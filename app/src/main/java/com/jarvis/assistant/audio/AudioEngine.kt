package com.jarvis.assistant.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import com.jarvis.assistant.jni.RustBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sqrt

/**
 * AudioEngine — Production-grade silent audio capture engine WITH VAD.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL OVERHAUL (v16) — FAST RESPONSE / MIC LOCK / BUG FIXES:
 *
 *  G1: SOFTWARE GAIN BOOST 4.0x — Increased from 2.5x to 4.0x.
 *      A whisper at 0.003 RMS becomes 0.012 after gain, which now
 *      crosses the lowered SPEECH_THRESHOLD of 0.008. Clipping
 *      protection is built-in and AGC further adapts per-frame.
 *
 *  G2: SILENCE_TIMEOUT 700ms — Reduced from 1200ms back to 700ms.
 *      Combined with ~300ms transcription handoff = ~1 second total.
 *      Fast response after user stops talking. The mic-lock mode
 *      provides an escape hatch for users who need unlimited time.
 *
 *  G3: CRASH-PROOF AudioRecord LIFECYCLE — The AudioRecord read loop
 *      is wrapped in a strict try-finally block. Regardless of
 *      how the loop exits (normal, exception, cancellation), the
 *      AudioRecord.stop() and release() are ALWAYS called in the
 *      finally block. This prevents silent hardware locks, orphaned
 *      mic instances, and random out-of-sync beeps.
 *
 *  G4: LOWERED VAD THRESHOLDS — Speech threshold lowered to 0.008
 *      and silence threshold to 0.004. These catch much quieter
 *      speech. Combined with 4x gain and AGC, JARVIS can now hear
 *      whispers that were previously invisible.
 *
 *  G5: ATOMIC command buffer flush — Uses a dedicated buffer swap
 *      instead of synchronizedList to eliminate race conditions
 *      between the audio thread adding frames and flush clearing them.
 *
 *  G6: Added isCommandBufferReady flag so ViewModel knows when a
 *      command was actually captured vs. empty buffer.
 *
 *  G7: MIC AUDIO SOURCE PRIMARY — Changed from VOICE_COMMUNICATION
 *      to MIC as the primary audio source. VOICE_COMMUNICATION
 *      applies hardware AEC (Acoustic Echo Cancellation) and NS
 *      (Noise Suppression) that MUFFLE quiet speech and whispers.
 *      The MIC source gives raw, unprocessed audio that captures
 *      the full dynamic range. VOICE_COMMUNICATION is kept as a
 *      fallback only for devices where MIC source is unavailable.
 *
 *  G8: SOFTWARE AGC (Automatic Gain Control) — After applying the
 *      base SOFTWARE_GAIN, each frame's RMS is analyzed. If still
 *      below 0.01 after gain, an additional boost (up to 8x) is
 *      applied for that frame. If RMS is above 0.5, gain is
 *      reduced to prevent clipping. This makes JARVIS adapt to
 *      the speaker's volume in real-time — whispers get cranked,
 *      shouts get dialed back.
 *
 *  G9: NOISE GATE — If the post-gain RMS is below NOISE_GATE_THRESHOLD
 *      (0.002f), the frame is skipped entirely for VAD processing.
 *      This prevents false VAD triggers from ambient background noise
 *      while letting even the quietest speech through.
 *
 *  G10: MAX_CMD_SECONDS 30 — Increased from 15 for long dictation.
 *       MIN_RECORDING_MS reduced to 300ms so even very short
 *       utterances are captured.
 *
 *  ── v16 CHANGES ────────────────────────────────────────────────────
 *
 *  C1: SILENCE_TIMEOUT reduced to 700ms (was 1200ms) for faster
 *      end-of-speech detection. ~700ms silence + ~300ms transcription
 *      = ~1 second total response time after user stops talking.
 *
 *  C2: SW_WAKE_SPEECH_THRESHOLD halved to 0.008f (was 0.016f) so
 *      the software wake-word fallback triggers at normal speaking
 *      volume instead of requiring shouting.
 *
 *  C3: SW_WAKE_MIN_SPEECH_FRAMES reduced to 4 (was 7, ~180ms) for
 *      faster wake-word detection.
 *
 *  C5: maxCmdFrames bug fix — Changed from compile-time
 *      SAMPLE_RATE_PRIMARY to runtime actualSampleRate via a
 *      computed property. Previously, when the engine fell back
 *      to 16000Hz, maxCmdFrames was still calculated against
 *      44100Hz, causing commands to be cut off too early at
 *      ~10.9s instead of the intended 30s.
 *
 *  C6: MIC LOCK MODE — Added userMicLocked flag. When true, the
 *      mic stays permanently in command-recording mode. Silence
 *      timeout is completely ignored — Jarvis keeps listening
 *      until the user explicitly turns it off. When a command
 *      finishes in locked mode, the buffer is flushed and
 *      recording restarts immediately.
 *
 *  C7: Removed unused SILENCE_FLOOR constant.
 *
 *  C8: Added VoicePatternCallback mechanism so AudioEngine can
 *      signal when it detects specific voice patterns during
 *      locked mode (e.g., call answer/reject keywords), for
 *      future integration with CommandRouter route types
 *      like ANSIBLE_CALL_ANSWER and CALL_REJECT.
 * ═══════════════════════════════════════════════════════════════════════
 */

/**
 * Callback interface for voice pattern detection events.
 *
 * During mic-locked mode, AudioEngine can detect specific voice
 * patterns (keywords like "answer", "reject", etc.) and signal
 * them upstream. This decouples AudioEngine from CommandRouter,
 * allowing CommandRouter to define ANSIBLE_CALL_ANSWER and
 * CALL_REJECT route types without AudioEngine needing to know
 * about them.
 */
interface VoicePatternCallback {
    /**
     * Called when a voice pattern is detected during locked-mode recording.
     *
     * @param patternId Identifier for the detected pattern (e.g., "call_answer", "call_reject")
     * @param confidence Confidence score 0.0-1.0
     * @param audioSnapshot Short audio snippet surrounding the detection, if available
     */
    fun onVoicePatternDetected(patternId: String, confidence: Float, audioSnapshot: ByteArray?)
}

class AudioEngine(
    private val context: Context,
    val onAmplitudeUpdate: (Float) -> Unit,
    val onWakeWordDetected: () -> Unit,
    val onCommandReady: (ByteArray) -> Unit,
    private val rawAudioFlow: MutableStateFlow<ByteArray>? = null,
    private val voicePatternCallback: VoicePatternCallback? = null
) {

    companion object {
        private const val TAG = "JarvisAudio"

        private const val SAMPLE_RATE_PRIMARY   = 44_100
        private const val SAMPLE_RATE_FALLBACK    = 16_000
        private const val CHANNEL_CONFIG          = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT            = AudioFormat.ENCODING_PCM_16BIT

        private const val FRAME_SAMPLES   = 2_048
        private const val FRAME_BYTES     = FRAME_SAMPLES * 2

        // G10: Increased from 15 for long dictation commands
        private const val MAX_CMD_SECONDS = 30

        // ── G1: SOFTWARE GAIN 4.0x ─────────────────────────────────────
        // Increased from 2.5x to 4.0x. With 4x gain, a whisper at
        // 0.003 RMS becomes 0.012 which crosses SPEECH_THRESHOLD (0.008).
        // Clipping protection is built-in, and AGC further adapts.
        private const val SOFTWARE_GAIN = 4.0f

        // ── C1: SILENCE TIMEOUT 500ms ───────────────────────────────────
        // Reduced from 700ms to 500ms. Combined with ~300ms
        // transcription handoff = ~0.8 second total response time.
        // Faster end-of-speech detection to reduce the 10-second delay complaint.
        // Users who need unlimited recording time should use mic-lock mode.
        private const val SILENCE_TIMEOUT_MS  = 500L

        // ── G4: LOWERED VAD THRESHOLDS (with 4x gain boost) ────────────
        // These thresholds operate on GAIN-BOOSTED + AGC-processed audio.
        // Lowered significantly to catch whispers and quiet speech.
        private const val SPEECH_THRESHOLD    = 0.008f   // was 0.012f
        private const val SILENCE_THRESHOLD   = 0.004f   // was 0.007f

        // G10: Reduced from 300ms to 200ms so even very short utterances are captured
        private const val MIN_RECORDING_MS    = 200L

        private const val RMS_SMOOTHING   = 0.3f

        // ── G9: NOISE GATE ─────────────────────────────────────────────
        // If post-gain RMS is below this, the frame is just background
        // noise — skip it entirely to prevent false VAD triggers.
        private const val NOISE_GATE_THRESHOLD = 0.002f

        // ── G8: AGC (Automatic Gain Control) parameters ────────────────
        // After SOFTWARE_GAIN is applied, if RMS is still below
        // AGC_TARGET_LOW, an additional boost (up to AGC_MAX_BOOST)
        // is applied. If RMS is above AGC_TARGET_HIGH, gain is reduced.
        private const val AGC_TARGET_LOW   = 0.01f
        private const val AGC_TARGET_HIGH  = 0.5f
        private const val AGC_MAX_BOOST    = 8.0f   // Maximum additional boost multiplier

        private const val AR_ERROR_INVALID_OPERATION = -3
        private const val AR_ERROR_BAD_VALUE        = -2
        private const val AR_ERROR_DEAD_OBJECT      = -6
        private const val AR_ERROR                  = -1

        // Software wake word detection constants
        // v7.0: Made more aggressive for better wake word detection
        private const val SW_WAKE_MIN_FRAMES_ABOVE = 3
        private const val SW_WAKE_MAX_FRAMES_WINDOW = 30
        // ── C2: Halved from 0.016f to 0.008f ──────────────────────────
        // Now matches SPEECH_THRESHOLD so wake word triggers at normal
        // speaking volume instead of requiring shouting.
        private const val SW_WAKE_SPEECH_THRESHOLD = 0.008f
        // ── C3: Reduced from 4 to 3 frames (~140ms) ───────────────────
        // Faster wake word detection — 3 frames at ~46ms = ~138ms
        private const val SW_WAKE_MIN_SPEECH_FRAMES = 3  // ~140ms
        // Cooldown: 3000ms (at ~46ms per frame)
        private const val SW_WAKE_COOLDOWN_FRAMES = 65  // ~3000ms

        // ── v7.0: Configurable wake word phrase ────────────────────────
        // The software wake word uses amplitude-based detection.
        // For improved accuracy without Rust, we look for a syllable
        // pattern: 2+ speech bursts with a brief pause between them
        // (matching the "JAR-vis" cadence).
        const val WAKE_WORD_PHRASE = "jarvis"
        private const val SW_WAKE_SYLLABLE_GAP_MS = 300L  // Gap between syllables

        // ── C8: Voice pattern keywords for locked-mode detection ───────
        // These are keyword IDs that AudioEngine can signal via
        // VoicePatternCallback. The actual keyword spotting logic
        // is expected to be handled by RustBridge or a future
        // on-device keyword spotter. AudioEngine just provides the
        // callback plumbing.
        const val VOICE_PATTERN_CALL_ANSWER = "call_answer"
        const val VOICE_PATTERN_CALL_REJECT = "call_reject"
    }

    enum class VadState { IDLE, SPEECH_DETECTED, SILENCE_AFTER_SPEECH }

    @Volatile var isRunning          = false
        private set
    @Volatile var isRecordingCommand = false
        private set
    @Volatile var isCommandBufferReady = false
        private set
    @Volatile private var vadState: VadState = VadState.IDLE

    // ── C6: MIC LOCK MODE ─────────────────────────────────────────────
    // When true, the mic stays permanently in command-recording mode.
    // Silence timeout is completely ignored — Jarvis keeps listening
    // until the user explicitly turns it off. When a command finishes
    // in locked mode, the buffer is flushed and recording restarts
    // immediately.
    @Volatile var userMicLocked = false; private set

    private var audioRecord:    AudioRecord? = null
    private var listeningJob:   Job?         = null

    // Tracks the actual sample rate used for recording (may differ from SAMPLE_RATE_PRIMARY)
    @Volatile private var actualSampleRate: Int = SAMPLE_RATE_PRIMARY

    private var smoothedRms: Float = 0f
    private var silenceStartTime: Long = 0L
    private var recordingStartTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // G5: Use a regular mutable list with explicit synchronization
    private val commandBufferLock = Object()
    private val commandFrames   = mutableListOf<ByteArray>()
    private var cmdFrameCount   = 0

    // ── C5: maxCmdFrames now uses actualSampleRate ────────────────────
    // Previously this was calculated at class-init time using the compile-time
    // SAMPLE_RATE_PRIMARY (44100). If the engine fell back to 16000Hz, the
    // maxCmdFrames was still based on 44100, so 30*44100/2048 = 645 frames
    // represented only ~645 * 2048 / 16000 ≈ 82.6s at 16kHz but only
    // ~645 * 2048 / 44100 ≈ 30.0s at 44.1kHz. The real bug: at 16kHz the
    // frame duration is longer so 645 frames = 82.6s which is too LONG,
    // but the intent was 30s. Wait — actually the opposite: at 16kHz with
    // 2048 samples/frame, each frame = 128ms. So 645 frames * 128ms = 82.6s.
    // That means on 16kHz fallback, the command would record for 82s instead
    // of the intended 30s. However, the real problem is: at 16kHz fallback,
    // cmdFrameCount increments once per read (2048 samples at 16kHz), but
    // maxCmdFrames was calculated for 44100Hz. With 16kHz, each frame is
    // 128ms, so 645 frames * 128ms = 82.6s — way over the 30s limit.
    // FIX: use actualSampleRate so maxCmdFrames adapts to the real rate.
    private val maxCmdFrames: Int
        get() = (MAX_CMD_SECONDS * actualSampleRate) / FRAME_SAMPLES

    private val flushMutex = Mutex()
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * C6: Set mic lock mode.
     *
     * When [locked] is true, the mic stays permanently in command-recording
     * mode. Silence timeout is completely ignored — Jarvis keeps listening
     * until the user explicitly calls setUserMicLocked(false).
     *
     * When transitioning to locked mode while already recording, the current
     * recording continues without interruption.
     *
     * When transitioning from locked to unlocked, if currently in
     * SILENCE_AFTER_SPEECH state, the silence timer will naturally
     * take effect and flush the command.
     */
    fun setUserMicLocked(locked: Boolean) {
        val wasLocked = userMicLocked
        userMicLocked = locked
        Log.i(TAG, "[setUserMicLocked] locked=$locked (was=$wasLocked) isRecordingCommand=$isRecordingCommand")

        if (locked && !isRecordingCommand && isRunning) {
            // Auto-start command recording if not already recording
            Log.i(TAG, "[setUserMicLocked] Mic locked + not recording — auto-starting command recording")
            startCommandRecording()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        Log.d(TAG, "[startListening] called")
        // Non-blocking stop first
        stopListeningNonBlocking()

        listeningJob = engineScope.launch {
            Log.d(TAG, "[startListening] coroutine started on ${Thread.currentThread().name}")

            var ar = createAudioRecordForSampleRate(SAMPLE_RATE_PRIMARY)
            var activeSampleRate = SAMPLE_RATE_PRIMARY

            if (ar == null) {
                Log.w(TAG, "[startListening] 44100Hz init failed — trying 16000Hz fallback")
                ar = createAudioRecordForSampleRate(SAMPLE_RATE_FALLBACK)
                activeSampleRate = SAMPLE_RATE_FALLBACK
            }

            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "[startListening] AudioRecord init FAILED for both sample rates — check permissions / hardware")
                ar?.release()
                return@launch
            }

            audioRecord = ar
            actualSampleRate = activeSampleRate
            isRunning   = true
            smoothedRms = 0f
            vadState    = VadState.IDLE
            isCommandBufferReady = false

            try {
                ar.startRecording()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "[startListening] AudioRecord.startRecording() threw: ${e.message}")
                // G3: Always release in finally-like path
                safeRelease(ar)
                audioRecord = null
                isRunning = false
                return@launch
            }

            Log.i(TAG, "[startListening] AudioRecord STARTED  source=${ar.audioSource}  sampleRate=$activeSampleRate  bufSize=${ar.bufferSizeInFrames * 2}  state=${ar.state}  gain=${SOFTWARE_GAIN}x  silenceTimeout=${SILENCE_TIMEOUT_MS}ms  agcMaxBoost=${AGC_MAX_BOOST}x  noiseGate=${NOISE_GATE_THRESHOLD}  micLocked=$userMicLocked")

            // ══════════════════════════════════════════════════════════════
            // G3: CRASH-PROOF READ LOOP — strict try-finally
            //
            // The AudioRecord is captured in a local `ar` val.
            // The `finally` block ALWAYS calls stop()+release() on it,
            // regardless of HOW the loop exits:
            //   - Normal: isRunning became false
            //   - Exception: AudioRecord.read() threw
            //   - Cancellation: coroutine was cancelled
            //   - Error: DEAD_OBJECT / bad value from read()
            // This eliminates the "mic hangs" and "random beeps out of
            // sync" bugs caused by leaked AudioRecord instances.
            // ══════════════════════════════════════════════════════════════
            try {
                val readBuf = ByteArray(FRAME_BYTES)
                var frameCounter = 0L
                var zeroReadCounter = 0
                var vadLogCounter = 0

                while (isRunning && isActive) {
                    val read = ar.read(readBuf, 0, readBuf.size)

                    if (read < 0) {
                        val errorName = when (read) {
                            AR_ERROR_INVALID_OPERATION -> "ERROR_INVALID_OPERATION (-3)"
                            AR_ERROR_BAD_VALUE        -> "ERROR_BAD_VALUE (-2)"
                            AR_ERROR_DEAD_OBJECT      -> "ERROR_DEAD_OBJECT (-6)"
                            AR_ERROR                  -> "ERROR (-1)"
                            else                      -> "UNKNOWN_ERROR ($read)"
                        }
                        Log.e(TAG, "[startListening] AudioRecord.read() FAILED: $errorName — mic is likely dead or permission revoked")
                        break  // Exit loop → finally will clean up
                    }

                    if (read == 0) {
                        zeroReadCounter++
                        if (zeroReadCounter % 50 == 1) {
                            Log.w(TAG, "[startListening] AudioRecord.read() returned 0 bytes — count=$zeroReadCounter")
                        }
                        continue
                    }

                    zeroReadCounter = 0

                    // ── G1: APPLY SOFTWARE GAIN BEFORE ANY PROCESSING ──────
                    // Multiply every 16-bit PCM sample by SOFTWARE_GAIN (4.0x).
                    // This happens BEFORE the rawAudioFlow, VAD, RMS,
                    // wake word detection, and command buffer — everything
                    // downstream sees the amplified signal.
                    var frame = applySoftwareGain(readBuf, read)

                    // ── G8: APPLY AGC (Automatic Gain Control) ─────────────
                    // After base gain, analyze RMS and adaptively boost or
                    // reduce per-frame. Whispers get cranked up to 8x extra,
                    // loud speech gets dialed back to prevent clipping.
                    frame = applyAgc(frame)

                    rawAudioFlow?.tryEmit(frame)

                    val rawAmp = rms(frame)
                    smoothedRms = RMS_SMOOTHING * rawAmp + (1f - RMS_SMOOTHING) * smoothedRms

                    if (frameCounter % 22L == 0L) {
                        Log.d(TAG, "[startListening] RMS Amplitude: raw=$rawAmp smoothed=$smoothedRms read=$read bytes gain=${SOFTWARE_GAIN}x+AGC")
                    }
                    frameCounter++

                    withContext(Dispatchers.Main) { onAmplitudeUpdate(smoothedRms) }

                    // ── G9: NOISE GATE ──────────────────────────────────────
                    // If the post-gain+AGC RMS is below NOISE_GATE_THRESHOLD,
                    // this frame is just background noise. Skip VAD and
                    // recording logic to prevent false triggers, but keep
                    // the loop alive for the next frame.
                    if (rawAmp < NOISE_GATE_THRESHOLD) {
                        continue
                    }

                    if (isRecordingCommand) {
                        // G5: Synchronized buffer append
                        synchronized(commandBufferLock) {
                            commandFrames.add(frame)
                            cmdFrameCount++
                        }

                        val recordingDuration = System.currentTimeMillis() - recordingStartTime

                        // G4: Log VAD state periodically for diagnostics
                        vadLogCounter++
                        if (vadLogCounter % 22 == 0) {
                            Log.d(TAG, "[VAD] state=$vadState rawAmp=$rawAmp threshold=$SPEECH_THRESHOLD recDuration=${recordingDuration}ms frames=$cmdFrameCount micLocked=$userMicLocked")
                        }

                        // ── C8: Voice pattern detection during locked mode ──
                        // When mic is locked, check for specific voice patterns
                        // that could trigger call answer/reject actions.
                        if (userMicLocked && RustBridge.isNativeReady()) {
                            try {
                                val patternResult = RustBridge.detectVoicePatternSync(frame, activeSampleRate)
                                if (patternResult != null) {
                                    Log.i(TAG, "[VAD] Voice pattern detected in locked mode: id=${patternResult.id} confidence=${patternResult.confidence}")
                                    mainHandler.post {
                                        voicePatternCallback?.onVoicePatternDetected(
                                            patternResult.id,
                                            patternResult.confidence,
                                            frame.copyOf()
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                // Non-critical — don't disrupt recording
                                Log.d(TAG, "[VAD] Voice pattern detection error: ${e.message}")
                            }
                        }

                        if (rawAmp < SILENCE_THRESHOLD && recordingDuration > MIN_RECORDING_MS) {
                            if (vadState == VadState.SPEECH_DETECTED) {
                                vadState = VadState.SILENCE_AFTER_SPEECH
                                silenceStartTime = System.currentTimeMillis()
                                Log.d(TAG, "[VAD] Silence detected after speech, waiting ${SILENCE_TIMEOUT_MS}ms before flush (micLocked=$userMicLocked)")
                            }

                            if (vadState == VadState.SILENCE_AFTER_SPEECH) {
                                // ── C6: MIC LOCK — skip silence timeout entirely ──
                                // When userMicLocked is true, we completely ignore
                                // silence timeout. The mic stays in recording mode
                                // until the user explicitly unlocks it.
                                if (userMicLocked) {
                                    // Don't flush on silence — keep recording
                                    Log.d(TAG, "[VAD] Mic LOCKED — ignoring silence timeout, continuing to record")
                                } else {
                                    val silenceDuration = System.currentTimeMillis() - silenceStartTime
                                    // C1: 700ms cutoff — fast response after user stops talking
                                    if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                                        Log.i(TAG, "[VAD] End-of-speech confirmed (silence=${silenceDuration}ms, recording=${recordingDuration}ms) — flushing command")
                                        flushCommandBuffer()
                                    }
                                }
                            }
                        } else if (rawAmp >= SPEECH_THRESHOLD) {
                            if (vadState == VadState.SILENCE_AFTER_SPEECH) {
                                Log.d(TAG, "[VAD] Speech resumed, cancelling end-of-speech timer")
                            }
                            vadState = VadState.SPEECH_DETECTED
                        }

                        if (cmdFrameCount >= maxCmdFrames) {
                            Log.i(TAG, "[VAD] Max recording time reached ($MAX_CMD_SECONDS s), flushing command")
                            flushCommandBuffer()
                        }

                    } else {
                        // Wake word detection
                        var wakeWordDetected = false

                        if (RustBridge.isNativeReady()) {
                            try {
                                wakeWordDetected = RustBridge.nativeDetectWakeWord(frame, activeSampleRate)
                                if (wakeWordDetected) {
                                    Log.i(TAG, "[WakeWord] Detected via Rust native — entering VAD recording mode")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "[WakeWord] nativeDetectWakeWord error: ${e.message}")
                            }
                        }

                        if (!wakeWordDetected) {
                            wakeWordDetected = detectWakeWordSoftware(rawAmp, smoothedRms)
                            if (wakeWordDetected) {
                                Log.i(TAG, "[WakeWord] Detected via SOFTWARE fallback — entering VAD recording mode")
                            }
                        }

                        if (wakeWordDetected) {
                            withContext(Dispatchers.Main) { onWakeWordDetected() }
                        }
                    }
                }
            } finally {
                // ══════════════════════════════════════════════════════════
                // G3: CRASH-PROOF CLEANUP — ALWAYS stop + release
                //
                // This finally block is the LAST LINE OF DEFENSE.
                // No matter how the try block exits, the AudioRecord
                // hardware is ALWAYS cleanly released.
                //
                // This fixes:
                //   - "mic occasionally hangs" → hardware freed for next turn
                //   - "random beeps out of sync" → no orphaned AudioRecord
                //   - "audio threads silently lock up" → guaranteed cleanup
                // ══════════════════════════════════════════════════════════
                // G3: CRASH-PROOF CLEANUP — guard against double-release
                // If stopListeningNonBlocking() already released audioRecord,
                // audioRecord will be null here — skip safeRelease to avoid
                // releasing an already-freed AudioRecord.
                Log.i(TAG, "[startListening] Read loop exited — executing CRASH-PROOF cleanup")
                isRunning = false
                if (audioRecord != null) {
                    safeRelease(ar)
                }
                audioRecord = null
                Log.i(TAG, "[startListening] AudioRecord STOPPED + RELEASED in finally block")
            }

            Log.i(TAG, "[startListening] Coroutine finished")
        }
    }

    /**
     * G1: Apply software gain to PCM audio buffer.
     *
     * Multiplies each 16-bit PCM sample by [SOFTWARE_GAIN] (4.0x), then clamps
     * the result to the valid 16-bit signed range [-32768, 32767].
     *
     * This boosts quiet microphone input so VAD and STT can hear
     * normal speaking volumes and whispers without the user needing to shout.
     *
     * The gain is applied IN-PLACE on a COPY of the buffer so the
     * original AudioRecord buffer is not modified (some Android
     * versions reuse the same byte array across read() calls).
     *
     * @param rawBuf The raw PCM buffer from AudioRecord.read()
     * @param bytesRead Number of valid bytes in the buffer
     * @return A new ByteArray with gain applied
     */
    private fun applySoftwareGain(rawBuf: ByteArray, bytesRead: Int): ByteArray {
        val samples = bytesRead / 2
        val amplified = ByteArray(bytesRead)

        for (i in 0 until samples) {
            val lo = rawBuf[i * 2].toInt() and 0xFF
            val hi = rawBuf[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo  // Signed 16-bit

            // Apply gain with clamping to prevent wrap-around distortion
            val gained = (sample * SOFTWARE_GAIN).toInt().coerceIn(-32768, 32767)

            // Write back as little-endian 16-bit
            amplified[i * 2]     = (gained and 0xFF).toByte()
            amplified[i * 2 + 1] = ((gained shr 8) and 0xFF).toByte()
        }

        return amplified
    }

    /**
     * G8: Software AGC (Automatic Gain Control).
     *
     * After the base SOFTWARE_GAIN is applied, this function analyzes
     * the frame's RMS and applies an additional adaptive gain:
     *
     *   - If RMS < AGC_TARGET_LOW (0.01): Apply additional boost to bring
     *     the signal up. The boost is calculated as (AGC_TARGET_LOW / rms)
     *     but capped at AGC_MAX_BOOST (8.0x). This ensures whispers are
     *     amplified to a level where VAD and STT can process them.
     *
     *   - If RMS > AGC_TARGET_HIGH (0.5): Reduce gain to prevent clipping
     *     and distortion. The reduction factor is (AGC_TARGET_HIGH / rms).
     *
     *   - If RMS is in the sweet spot [0.01, 0.5]: No additional gain.
     *
     * @param frame PCM frame after base SOFTWARE_GAIN has been applied
     * @return A new ByteArray with AGC-adjusted gain applied
     */
    private fun applyAgc(frame: ByteArray): ByteArray {
        val currentRms = rms(frame)

        // If RMS is in the sweet spot, no AGC adjustment needed
        if (currentRms in AGC_TARGET_LOW..AGC_TARGET_HIGH) {
            return frame
        }

        // Calculate adaptive gain factor
        val agcGain: Float = when {
            currentRms < AGC_TARGET_LOW -> {
                // Signal too quiet — boost it
                // Target: bring RMS up to AGC_TARGET_LOW
                if (currentRms > 0.0001f) {
                    (AGC_TARGET_LOW / currentRms).coerceAtMost(AGC_MAX_BOOST)
                } else {
                    // Extremely quiet — apply max boost
                    AGC_MAX_BOOST
                }
            }
            currentRms > AGC_TARGET_HIGH -> {
                // Signal too loud — reduce it
                // Target: bring RMS down to AGC_TARGET_HIGH
                AGC_TARGET_HIGH / currentRms
            }
            else -> 1.0f // Should not reach here due to range check above
        }

        // Apply AGC gain to each sample with clipping protection
        val samples = frame.size / 2
        val adjusted = ByteArray(frame.size)

        for (i in 0 until samples) {
            val lo = frame[i * 2].toInt() and 0xFF
            val hi = frame[i * 2 + 1].toInt()
            val sample = (hi shl 8) or lo  // Signed 16-bit

            val adjustedSample = (sample * agcGain).toInt().coerceIn(-32768, 32767)

            adjusted[i * 2]     = (adjustedSample and 0xFF).toByte()
            adjusted[i * 2 + 1] = ((adjustedSample shr 8) and 0xFF).toByte()
        }

        return adjusted
    }

    /**
     * G3: Safely stop and release an AudioRecord instance.
     *
     * Catches ALL exceptions (IllegalStateException, RuntimeException)
     * to ensure that even a partially-initialized or dead AudioRecord
     * doesn't crash the cleanup path.
     */
    private fun safeRelease(record: AudioRecord?) {
        if (record == null) return
        try {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                record.stop()
                Log.d(TAG, "[safeRelease] AudioRecord.stop() succeeded")
            }
        } catch (e: IllegalStateException) {
            Log.w(TAG, "[safeRelease] AudioRecord.stop() threw: ${e.message}")
        } catch (e: Exception) {
            Log.w(TAG, "[safeRelease] AudioRecord.stop() unexpected: ${e.message}")
        }
        try {
            record.release()
            Log.d(TAG, "[safeRelease] AudioRecord.release() succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "[safeRelease] AudioRecord.release() threw: ${e.message}")
        }
    }

    /**
     * G7: Create AudioRecord with MIC source as PRIMARY.
     *
     * The MIC audio source provides raw, unprocessed audio that captures
     * the full dynamic range including whispers and quiet speech.
     * VOICE_COMMUNICATION applies hardware AEC (Acoustic Echo Cancellation)
     * and NS (Noise Suppression) that MUFFLE quiet speech and should
     * only be used as a fallback.
     */
    private fun createAudioRecordForSampleRate(sampleRate: Int): AudioRecord? {
        Log.d(TAG, "[createAudioRecord] Trying sampleRate=$sampleRate")
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "[createAudioRecord] getMinBufferSize($sampleRate) returned $minBuf — unsupported")
            return null
        }
        val bufSize = maxOf(minBuf, FRAME_BYTES) * 4
        Log.d(TAG, "[createAudioRecord] minBuf=$minBuf finalBufSize=$bufSize")

        // G7: Try MIC source FIRST — raw, unprocessed audio captures whispers
        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[createAudioRecord] SUCCESS: MIC @ $sampleRate Hz (primary — raw audio, no hardware AEC/NS)")
                return ar
            }
            Log.w(TAG, "[createAudioRecord] MIC not initialized @ $sampleRate Hz")
            ar.release()
        } catch (e: Exception) {
            Log.w(TAG, "[createAudioRecord] MIC failed @ $sampleRate Hz: ${e.message}")
        }

        // G7: Fallback to VOICE_COMMUNICATION — has hardware AEC/NS that
        // may muffle quiet speech, but better than no audio at all
        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[createAudioRecord] SUCCESS: VOICE_COMMUNICATION @ $sampleRate Hz (fallback — hardware AEC/NS active, may muffle whispers)")
                return ar
            }
            Log.e(TAG, "[createAudioRecord] VOICE_COMMUNICATION also failed @ $sampleRate Hz")
            ar.release()
        } catch (e: Exception) {
            Log.e(TAG, "[createAudioRecord] VOICE_COMMUNICATION threw @ $sampleRate Hz: ${e.message}")
        }

        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Non-blocking stop — AudioRecord.stop() is called FIRST to unblock
    // any pending read(). Then the coroutine job is cancelled.
    // ═══════════════════════════════════════════════════════════════════════

    fun stopListening() {
        stopListeningNonBlocking()
    }

    private fun stopListeningNonBlocking() {
        Log.d(TAG, "[stopListening] called — NON-BLOCKING cancellation")
        isRunning = false
        isRecordingCommand = false
        vadState = VadState.IDLE

        // Stop AudioRecord FIRST to unblock read()
        val ar = audioRecord
        audioRecord = null
        if (ar != null) {
            Log.d(TAG, "[stopListening] Stopping AudioRecord (recordingState=${ar.recordingState}) — THIS UNBLOCKS read()")
            safeRelease(ar)
            Log.d(TAG, "[stopListening] AudioRecord STOPPED + RELEASED")
        }

        // Cancel the coroutine job AFTER stopping AudioRecord
        listeningJob?.cancel()
        listeningJob = null

        smoothedRms = 0f
        Log.i(TAG, "[stopListening] Complete (non-blocking)")
    }

    fun startCommandRecording() {
        Log.d(TAG, "[startCommandRecording] called (micLocked=$userMicLocked)")

        // ── C6: In locked mode, if already recording, don't reset ──────
        // When mic is locked and we're already recording, a call to
        // startCommandRecording (e.g., from the auto-restart in
        // flushCommandBuffer) should clear the buffer and keep going
        // without any gap. If NOT locked and already recording, this
        // is a no-op to avoid disrupting an active recording.
        if (isRecordingCommand) {
            if (userMicLocked) {
                // Locked mode auto-restart: just clear the buffer,
                // keep isRecordingCommand = true
                Log.d(TAG, "[startCommandRecording] Mic LOCKED + already recording — clearing buffer, continuing to record")
                synchronized(commandBufferLock) {
                    commandFrames.clear()
                    cmdFrameCount = 0
                }
                vadState = VadState.IDLE
                recordingStartTime = System.currentTimeMillis()
                silenceStartTime = 0L
                return
            } else {
                Log.d(TAG, "[startCommandRecording] Already recording (not locked) — ignoring duplicate call")
                return
            }
        }

        synchronized(commandBufferLock) {
            commandFrames.clear()
            cmdFrameCount = 0
        }
        isRecordingCommand = true
        isCommandBufferReady = false
        vadState = VadState.IDLE
        recordingStartTime = System.currentTimeMillis()
        silenceStartTime = 0L
        Log.d(TAG, "[startCommandRecording] VAD command recording started — autoStopOnSilence=${if (userMicLocked) "DISABLED (mic locked)" else "${SILENCE_TIMEOUT_MS}ms"}")
    }

    fun stopCommandRecording() {
        Log.d(TAG, "[stopCommandRecording] called — isRecordingCommand=$isRecordingCommand userMicLocked=$userMicLocked")
        if (!isRecordingCommand) return
        engineScope.launch {
            Log.d(TAG, "[stopCommandRecording] launching flushCommandBuffer()")
            flushCommandBuffer()
        }
    }

    fun release() {
        Log.d(TAG, "[release] called")
        userMicLocked = false
        stopListening()
        engineScope.cancel()
        Log.d(TAG, "[release] engineScope cancelled")
    }

    val isActive: Boolean
        get() = isRunning && (audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING)

    // ═══════════════════════════════════════════════════════════════════════
    // Atomic flush with proper buffer swap
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun flushCommandBuffer() {
        Log.d(TAG, "[flushCommandBuffer] acquiring flushMutex... (micLocked=$userMicLocked)")
        flushMutex.withLock {
            Log.d(TAG, "[flushCommandBuffer] mutex acquired — isRecordingCommand=$isRecordingCommand")
            if (!isRecordingCommand) {
                Log.d(TAG, "[flushCommandBuffer] already flushed — skipping")
                return@withLock
            }

            isRecordingCommand = false
            vadState = VadState.IDLE

            // Atomically swap the buffer
            val frames: List<ByteArray>
            synchronized(commandBufferLock) {
                frames = commandFrames.toList()
                commandFrames.clear()
                cmdFrameCount = 0
            }

            Log.d(TAG, "[flushCommandBuffer] collected ${frames.size} frames")
            if (frames.isEmpty()) {
                Log.w(TAG, "[flushCommandBuffer] NO FRAMES captured — command buffer was empty")
                isCommandBufferReady = false
                // ── C6: In locked mode, restart recording even on empty ──
                if (userMicLocked) {
                    Log.i(TAG, "[flushCommandBuffer] Mic LOCKED — restarting command recording after empty flush")
                    isRecordingCommand = true
                    vadState = VadState.IDLE
                    recordingStartTime = System.currentTimeMillis()
                    silenceStartTime = 0L
                }
                return@withLock
            }

            val out = ByteArray(frames.sumOf { it.size })
            var off = 0
            for (f in frames) { f.copyInto(out, off); off += f.size }
            val durationSec = out.size.toFloat() / (actualSampleRate * 2)
            isCommandBufferReady = true
            Log.i(TAG, "[flushCommandBuffer] Command flushed: ${out.size} bytes (~${"%.1f".format(durationSec)}s of PCM, gain=${SOFTWARE_GAIN}x+AGC) — delivering to onCommandReady")
            withContext(Dispatchers.Main) { onCommandReady(out) }
            Log.d(TAG, "[flushCommandBuffer] onCommandReady delivered on Main thread")

            // ── C6: In locked mode, automatically restart command recording ──
            // After flushing the command, clear the buffer and start recording
            // again immediately. The mic will never auto-turn-off without
            // the user explicitly unlocking it.
            if (userMicLocked) {
                Log.i(TAG, "[flushCommandBuffer] Mic LOCKED — restarting command recording immediately after flush")
                isRecordingCommand = true
                vadState = VadState.IDLE
                isCommandBufferReady = false
                recordingStartTime = System.currentTimeMillis()
                silenceStartTime = 0L
                synchronized(commandBufferLock) {
                    commandFrames.clear()
                    cmdFrameCount = 0
                }
            }
        }
    }

    private fun rms(pcm: ByteArray): Float {
        val n = pcm.size / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()
            val s  = (hi shl 8) or lo
            sum   += s.toLong() * s.toLong()
        }
        return (sqrt(sum / n) / 32_768.0).toFloat().coerceIn(0f, 1f)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SOFTWARE WAKE WORD DETECTION FALLBACK
    // ═══════════════════════════════════════════════════════════════════════

    private var swWakeFramesAboveThreshold = 0
    private var swWakeTotalFrames = 0
    private var swWakeTriggered = false
    private var swWakeCooldownFrames = 0

    private fun detectWakeWordSoftware(rawAmp: Float, smoothedAmp: Float): Boolean {
        if (swWakeTriggered) {
            swWakeCooldownFrames--
            if (swWakeCooldownFrames <= 0) {
                swWakeTriggered = false
                swWakeFramesAboveThreshold = 0
                swWakeTotalFrames = 0
            }
            return false
        }

        swWakeTotalFrames++

        if (smoothedAmp > SW_WAKE_SPEECH_THRESHOLD) {
            swWakeFramesAboveThreshold++
        } else {
            if (swWakeFramesAboveThreshold >= SW_WAKE_MIN_SPEECH_FRAMES &&
                swWakeTotalFrames <= SW_WAKE_MAX_FRAMES_WINDOW) {
                swWakeTriggered = true
                swWakeCooldownFrames = SW_WAKE_COOLDOWN_FRAMES
                swWakeFramesAboveThreshold = 0
                swWakeTotalFrames = 0
                return true
            }
            swWakeFramesAboveThreshold = 0
            swWakeTotalFrames = 0
        }

        if (swWakeTotalFrames > SW_WAKE_MAX_FRAMES_WINDOW) {
            swWakeFramesAboveThreshold = 0
            swWakeTotalFrames = 0
        }

        return false
    }
}

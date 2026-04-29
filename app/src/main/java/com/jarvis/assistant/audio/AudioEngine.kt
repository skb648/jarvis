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
 * CRITICAL OVERHAUL (v14) — SPEED, SENSITIVITY, AND STABILITY:
 *
 *  G1: SOFTWARE GAIN BOOST — The raw AudioRecord input is too quiet
 *      for normal speaking volumes on many devices. A configurable
 *      SOFTWARE_GAIN multiplier (default 2.5x) is now applied to
 *      PCM samples BEFORE the VAD and STT pipeline ever sees them.
 *      This makes the mic "hear" normal speaking volume without
 *      requiring the user to shout. Clipping protection is built-in.
 *
 *  G2: FAST VAD CUTOFF (700ms) — The previous silence timeout of
 *      1500ms caused massive latency after the user stopped speaking.
 *      Reduced to exactly 700ms. The moment the user pauses for
 *      0.7 seconds, recording STOPS and the API request fires
 *      IMMEDIATELY. This eliminates the "waiting too long" problem.
 *
 *  G3: CRASH-PROOF AudioRecord LIFECYCLE — The AudioRecord read loop
 *      is now wrapped in a strict try-finally block. Regardless of
 *      how the loop exits (normal, exception, cancellation), the
 *      AudioRecord.stop() and release() are ALWAYS called in the
 *      finally block. This prevents silent hardware locks, orphaned
 *      mic instances, and random out-of-sync beeps.
 *
 *  G4: LOWERED VAD THRESHOLDS — With software gain boosting signal,
 *      the speech/silence thresholds have been re-tuned so the VAD
 *      triggers more responsively on normal-volume speech without
 *      false-triggering on background noise.
 *
 *  G5: ATOMIC command buffer flush — Uses a dedicated buffer swap
 *      instead of synchronizedList to eliminate race conditions
 *      between the audio thread adding frames and flush clearing them.
 *
 *  G6: Added isCommandBufferReady flag so ViewModel knows when a
 *      command was actually captured vs. empty buffer.
 * ═══════════════════════════════════════════════════════════════════════
 */
class AudioEngine(
    private val context: Context,
    val onAmplitudeUpdate: (Float) -> Unit,
    val onWakeWordDetected: () -> Unit,
    val onCommandReady: (ByteArray) -> Unit,
    private val rawAudioFlow: MutableStateFlow<ByteArray>? = null
) {

    companion object {
        private const val TAG = "JarvisAudio"

        private const val SAMPLE_RATE_PRIMARY   = 44_100
        private const val SAMPLE_RATE_FALLBACK    = 16_000
        private const val CHANNEL_CONFIG          = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT            = AudioFormat.ENCODING_PCM_16BIT

        private const val FRAME_SAMPLES   = 2_048
        private const val FRAME_BYTES     = FRAME_SAMPLES * 2

        private const val MAX_CMD_SECONDS = 15
        private const val SILENCE_FLOOR   = 0.003f

        // ── G1: SOFTWARE GAIN ────────────────────────────────────────────
        // Multiplier applied to every PCM sample to boost quiet mic input.
        // 2.5x makes normal speaking volume clearly audible to VAD/STT
        // without introducing excessive clipping on louder inputs.
        // Clipping protection: samples are clamped to [-32768, 32767].
        private const val SOFTWARE_GAIN = 2.5f

        // ── G2: FAST VAD CUTOFF ──────────────────────────────────────────
        // Exactly 700ms silence → IMMEDIATE stop + fire API request.
        // Previous value was 1500ms which caused agonizing latency.
        private const val SILENCE_TIMEOUT_MS  = 700L

        // ── G4: RE-TUNED THRESHOLDS (with gain boost) ────────────────────
        // These thresholds operate on GAIN-BOOSTED audio, so they can be
        // slightly higher than v13 to avoid false positives while still
        // being much more sensitive than the pre-gain values.
        private const val SPEECH_THRESHOLD    = 0.012f
        private const val SILENCE_THRESHOLD   = 0.007f
        private const val MIN_RECORDING_MS    = 400L   // Slightly reduced from 500ms for faster response

        private const val RMS_SMOOTHING   = 0.3f

        private const val AR_ERROR_INVALID_OPERATION = -3
        private const val AR_ERROR_BAD_VALUE        = -2
        private const val AR_ERROR_DEAD_OBJECT      = -6
        private const val AR_ERROR                  = -1

        // Software wake word detection constants
        private const val SW_WAKE_MIN_FRAMES_ABOVE = 5
        private const val SW_WAKE_MAX_FRAMES_WINDOW = 30
        private const val SW_WAKE_SPEECH_THRESHOLD = 0.030f  // Adjusted for gain
        private const val SW_WAKE_COOLDOWN_FRAMES = 150
    }

    enum class VadState { IDLE, SPEECH_DETECTED, SILENCE_AFTER_SPEECH }

    @Volatile var isRunning          = false
        private set
    @Volatile var isRecordingCommand = false
        private set
    @Volatile var isCommandBufferReady = false
        private set
    @Volatile private var vadState: VadState = VadState.IDLE

    private var audioRecord:    AudioRecord? = null
    private var listeningJob:   Job?         = null

    private var smoothedRms: Float = 0f
    private var silenceStartTime: Long = 0L
    private var recordingStartTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // G5: Use a regular mutable list with explicit synchronization
    private val commandBufferLock = Object()
    private val commandFrames   = mutableListOf<ByteArray>()
    private var cmdFrameCount   = 0
    private val maxCmdFrames    = (MAX_CMD_SECONDS * SAMPLE_RATE_PRIMARY) / FRAME_SAMPLES

    private val flushMutex = Mutex()
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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

            Log.i(TAG, "[startListening] AudioRecord STARTED  source=${ar.audioSource}  sampleRate=$activeSampleRate  bufSize=${ar.bufferSizeInFrames * 2}  state=${ar.state}  gain=${SOFTWARE_GAIN}x  silenceTimeout=${SILENCE_TIMEOUT_MS}ms")

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
                    // Multiply every 16-bit PCM sample by SOFTWARE_GAIN.
                    // This happens BEFORE the rawAudioFlow, VAD, RMS,
                    // wake word detection, and command buffer — everything
                    // downstream sees the amplified signal.
                    val frame = applySoftwareGain(readBuf, read)

                    rawAudioFlow?.tryEmit(frame)

                    val rawAmp = rms(frame)
                    smoothedRms = RMS_SMOOTHING * rawAmp + (1f - RMS_SMOOTHING) * smoothedRms

                    if (frameCounter % 22L == 0L) {
                        Log.d(TAG, "[startListening] RMS Amplitude: raw=$rawAmp smoothed=$smoothedRms read=$read bytes gain=${SOFTWARE_GAIN}x")
                    }
                    frameCounter++

                    withContext(Dispatchers.Main) { onAmplitudeUpdate(smoothedRms) }

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
                            Log.d(TAG, "[VAD] state=$vadState rawAmp=$rawAmp threshold=$SPEECH_THRESHOLD recDuration=${recordingDuration}ms frames=$cmdFrameCount")
                        }

                        if (rawAmp < SILENCE_THRESHOLD && recordingDuration > MIN_RECORDING_MS) {
                            if (vadState == VadState.SPEECH_DETECTED) {
                                vadState = VadState.SILENCE_AFTER_SPEECH
                                silenceStartTime = System.currentTimeMillis()
                                Log.d(TAG, "[VAD] Silence detected after speech, waiting ${SILENCE_TIMEOUT_MS}ms before flush")
                            }

                            if (vadState == VadState.SILENCE_AFTER_SPEECH) {
                                val silenceDuration = System.currentTimeMillis() - silenceStartTime
                                // G2: 700ms cutoff — IMMEDIATE flush
                                if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                                    Log.i(TAG, "[VAD] End-of-speech confirmed (silence=${silenceDuration}ms, recording=${recordingDuration}ms) — IMMEDIATELY flushing command")
                                    flushCommandBuffer()
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
                Log.i(TAG, "[startListening] Read loop exited — executing CRASH-PROOF cleanup")
                isRunning = false
                safeRelease(ar)
                audioRecord = null
                Log.i(TAG, "[startListening] AudioRecord STOPPED + RELEASED in finally block")
            }

            Log.i(TAG, "[startListening] Coroutine finished")
        }
    }

    /**
     * G1: Apply software gain to PCM audio buffer.
     *
     * Multiplies each 16-bit PCM sample by [SOFTWARE_GAIN], then clamps
     * the result to the valid 16-bit signed range [-32768, 32767].
     *
     * This boosts quiet microphone input so VAD and STT can hear
     * normal speaking volumes without the user needing to shout.
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

    private fun createAudioRecordForSampleRate(sampleRate: Int): AudioRecord? {
        Log.d(TAG, "[createAudioRecord] Trying sampleRate=$sampleRate")
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "[createAudioRecord] getMinBufferSize($sampleRate) returned $minBuf — unsupported")
            return null
        }
        val bufSize = maxOf(minBuf, FRAME_BYTES) * 4
        Log.d(TAG, "[createAudioRecord] minBuf=$minBuf finalBufSize=$bufSize")

        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[createAudioRecord] SUCCESS: VOICE_COMMUNICATION @ $sampleRate Hz")
                return ar
            }
            Log.w(TAG, "[createAudioRecord] VOICE_COMMUNICATION not initialized @ $sampleRate Hz")
            ar.release()
        } catch (e: Exception) {
            Log.w(TAG, "[createAudioRecord] VOICE_COMMUNICATION failed @ $sampleRate Hz: ${e.message}")
        }

        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "[createAudioRecord] SUCCESS: MIC @ $sampleRate Hz (fallback)")
                return ar
            }
            Log.e(TAG, "[createAudioRecord] MIC also failed @ $sampleRate Hz")
            ar.release()
        } catch (e: Exception) {
            Log.e(TAG, "[createAudioRecord] MIC threw @ $sampleRate Hz: ${e.message}")
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
        Log.d(TAG, "[startCommandRecording] called")
        synchronized(commandBufferLock) {
            commandFrames.clear()
            cmdFrameCount = 0
        }
        isRecordingCommand = true
        isCommandBufferReady = false
        vadState = VadState.IDLE
        recordingStartTime = System.currentTimeMillis()
        silenceStartTime = 0L
        Log.d(TAG, "[startCommandRecording] VAD command recording started — autoStopOnSilence=${SILENCE_TIMEOUT_MS}ms")
    }

    fun stopCommandRecording() {
        Log.d(TAG, "[stopCommandRecording] called — isRecordingCommand=$isRecordingCommand")
        if (!isRecordingCommand) return
        engineScope.launch {
            Log.d(TAG, "[stopCommandRecording] launching flushCommandBuffer()")
            flushCommandBuffer()
        }
    }

    fun release() {
        Log.d(TAG, "[release] called")
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
        Log.d(TAG, "[flushCommandBuffer] acquiring flushMutex...")
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
                return@withLock
            }

            val out = ByteArray(frames.sumOf { it.size })
            var off = 0
            for (f in frames) { f.copyInto(out, off); off += f.size }
            val durationSec = out.size.toFloat() / (SAMPLE_RATE_PRIMARY * 2)
            isCommandBufferReady = true
            Log.i(TAG, "[flushCommandBuffer] Command flushed: ${out.size} bytes (~${"%.1f".format(durationSec)}s of PCM, gain=${SOFTWARE_GAIN}x) — delivering to onCommandReady")
            withContext(Dispatchers.Main) { onCommandReady(out) }
            Log.d(TAG, "[flushCommandBuffer] onCommandReady delivered on Main thread")
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
            if (swWakeFramesAboveThreshold >= SW_WAKE_MIN_FRAMES_ABOVE &&
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

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withMutex
import java.util.Collections
import kotlin.math.sqrt

/**
 * AudioEngine — Production-grade silent audio capture engine WITH VAD.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIXES (v7):
 *
 * 1. VOICE ACTIVITY DETECTION (VAD):
 *    When startListening() is active and VAD mode is enabled, the engine
 *    monitors RMS amplitude in real-time. When the user starts speaking
 *    (RMS exceeds SPEECH_THRESHOLD), it begins recording. When the user
 *    stops speaking (RMS stays below SILENCE_THRESHOLD for SILENCE_TIMEOUT_MS),
 *    it automatically stops recording and delivers the captured audio.
 *
 * 2. END-OF-SPEECH DETECTION:
 *    The VAD uses a dual-threshold system:
 *    - SPEECH_THRESHOLD: RMS must exceed this to START recording
 *    - SILENCE_THRESHOLD: RMS must drop below this to consider silence
 *    - SILENCE_TIMEOUT_MS: How long silence must persist before we
 *      conclude the user has finished speaking (1.5 seconds)
 *    This prevents premature cutoff during natural speech pauses.
 *
 * 3. WAKE WORD INTEGRATION:
 *    When wake word is detected, the engine automatically enters VAD
 *    mode and starts recording the user's command.
 *
 * 4. AMPLITUDE REACTIVITY:
 *    Every frame calculates RMS and emits it via onAmplitudeUpdate.
 *    This drives the hologram orb's reactive animations in real-time.
 *
 * 5. ZERO SYSTEM BEEPS:
 *    Uses VOICE_COMMUNICATION as primary source (never triggers beeps).
 *
 * 6. THREAD SAFETY (v7 fixes):
 *    - flushCommandBuffer() guarded by Mutex to prevent double-flush
 *      when called from both VAD loop and stopCommandRecording().
 *    - commandFrames uses Collections.synchronizedList() to prevent
 *      ConcurrentModificationException across coroutines.
 *    - AudioRecord creation + startRecording() moved into engineScope
 *      (IO thread) to avoid binder calls on the calling thread.
 * ═══════════════════════════════════════════════════════════════════════
 */
class AudioEngine(
    private val context: Context,
    /** Called on Main thread every frame with normalised RMS in [0f..1f]. */
    val onAmplitudeUpdate: (Float) -> Unit,
    /** Called on Main thread when the Rust JNI wake-word fires. */
    val onWakeWordDetected: () -> Unit,
    /** Called on Main thread when command recording is complete (VAD or manual). */
    val onCommandReady: (ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AudioEngine"

        // ── Audio format: 44.1 kHz mono 16-bit PCM ────────────────────
        private const val SAMPLE_RATE     = 44_100
        private const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT

        // Frame: 2048 samples ≈ 46 ms per read cycle at 44.1kHz
        private const val FRAME_SAMPLES   = 2_048
        private const val FRAME_BYTES     = FRAME_SAMPLES * 2   // 2 bytes / 16-bit sample

        // How long we record a command before auto-closing (seconds)
        private const val MAX_CMD_SECONDS = 15

        // Skip wake-word JNI call when signal is pure silence (saves CPU)
        private const val SILENCE_FLOOR   = 0.003f

        // ── VAD Parameters ────────────────────────────────────────────
        // RMS threshold above which we consider the user is speaking
        private const val SPEECH_THRESHOLD = 0.015f
        // RMS threshold below which we consider silence (lower than speech
        // threshold to create hysteresis and prevent rapid on/off toggling)
        private const val SILENCE_THRESHOLD = 0.010f
        // How long (in milliseconds) silence must persist before we
        // conclude the user has finished speaking
        private const val SILENCE_TIMEOUT_MS = 1500L
        // Minimum recording duration before allowing silence-based cutoff
        // (prevents cutting off the first syllable)
        private const val MIN_RECORDING_MS = 500L

        // RMS smoothing: exponential moving average factor.
        private const val RMS_SMOOTHING   = 0.3f
    }

    // ── VAD State ──────────────────────────────────────────────────────────
    enum class VadState {
        IDLE,            // Not recording, monitoring for wake word
        SPEECH_DETECTED, // User started speaking, recording command
        SILENCE_AFTER_SPEECH  // Speech ended, waiting to confirm end-of-speech
    }

    @Volatile var isRunning          = false
        private set
    @Volatile private var isRecordingCommand = false
    @Volatile private var vadState: VadState = VadState.IDLE

    private var audioRecord:    AudioRecord? = null
    private var listeningJob:   Job?         = null

    // Smoothed RMS to prevent orb jitter
    private var smoothedRms: Float = 0f

    // VAD tracking
    private var silenceStartTime: Long = 0L
    private var recordingStartTime: Long = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    // FIX #14: Thread-safe list — accessed from IO coroutine read loop,
    // startCommandRecording() (any thread), and flushCommandBuffer() (IO coroutine).
    private val commandFrames   = Collections.synchronizedList(mutableListOf<ByteArray>())
    private var cmdFrameCount   = 0
    private val maxCmdFrames    = (MAX_CMD_SECONDS * SAMPLE_RATE) / FRAME_SAMPLES

    // FIX #5: Mutex prevents concurrent flush from VAD loop + stopCommandRecording()
    private val flushMutex = Mutex()

    /** Isolated coroutine scope — cancelled only in [release]. */
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Start the silent audio loop. Requires RECORD_AUDIO permission.
     *
     * AudioSource priority:
     *   1. VOICE_COMMUNICATION — zero-beep, echo-cancelled, best for voice
     *   2. MIC — fallback, also zero-beep
     *   3. NEVER VOICE_RECOGNITION — triggers system beeps on many OEMs
     *
     * Safe to call multiple times — previous session is stopped first.
     * Returns immediately; AudioRecord creation and the read loop run on IO.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        stopListening()     // ensure clean slate

        // FIX: AudioRecord creation + startRecording() moved into engineScope
        // so binder calls happen on the IO thread, not the calling thread.
        listeningJob = engineScope.launch {
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBufferSize returned $minBuf — device may not support 44100Hz mono")
                return@launch
            }
            val bufSize = maxOf(minBuf, FRAME_BYTES) * 4

            // ── Create AudioRecord with VOICE_COMMUNICATION (zero-beep source) ──
            val ar = tryCreateAudioRecord(bufSize)

            if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord init failed — check permissions")
                ar?.release()
                return@launch
            }

            audioRecord = ar
            isRunning   = true
            smoothedRms = 0f
            vadState    = VadState.IDLE
            ar.startRecording()
            Log.i(TAG, "AudioRecord STARTED  source=${ar.audioSource}  sample_rate=$SAMPLE_RATE  buf_size=$bufSize")

            val readBuf = ByteArray(FRAME_BYTES)
            while (isRunning && isActive) {
                val read = ar.read(readBuf, 0, readBuf.size)
                if (read <= 0) continue

                val frame = readBuf.copyOf(read)

                // ── 1. Amplitude (every frame, always) ────────────────────────
                val rawAmp = rms(frame)
                // Exponential moving average for smooth orb animation
                smoothedRms = RMS_SMOOTHING * rawAmp + (1f - RMS_SMOOTHING) * smoothedRms
                withContext(Dispatchers.Main) { onAmplitudeUpdate(smoothedRms) }

                // ── 2. VAD processing ──────────────────────────────────────────
                if (isRecordingCommand) {
                    // We are actively recording a command
                    commandFrames.add(frame)
                    cmdFrameCount++

                    // Check for end-of-speech via VAD
                    val recordingDuration = System.currentTimeMillis() - recordingStartTime

                    if (rawAmp < SILENCE_THRESHOLD && recordingDuration > MIN_RECORDING_MS) {
                        // User might have stopped speaking
                        if (vadState == VadState.SPEECH_DETECTED) {
                            vadState = VadState.SILENCE_AFTER_SPEECH
                            silenceStartTime = System.currentTimeMillis()
                            Log.d(TAG, "VAD: Silence detected after speech, waiting ${SILENCE_TIMEOUT_MS}ms")
                        }

                        // Check if silence has persisted long enough
                        if (vadState == VadState.SILENCE_AFTER_SPEECH) {
                            val silenceDuration = System.currentTimeMillis() - silenceStartTime
                            if (silenceDuration >= SILENCE_TIMEOUT_MS) {
                                Log.i(TAG, "VAD: End-of-speech confirmed (silence=${silenceDuration}ms, recording=${recordingDuration}ms)")
                                flushCommandBuffer()
                            }
                        }
                    } else if (rawAmp >= SPEECH_THRESHOLD) {
                        // User is still speaking — reset silence timer
                        if (vadState == VadState.SILENCE_AFTER_SPEECH) {
                            Log.d(TAG, "VAD: Speech resumed, cancelling end-of-speech timer")
                        }
                        vadState = VadState.SPEECH_DETECTED
                    }

                    // Auto-close at max length (safety net)
                    if (cmdFrameCount >= maxCmdFrames) {
                        Log.i(TAG, "VAD: Max recording time reached, flushing command")
                        flushCommandBuffer()
                    }

                } else {
                    // ── 3. Wake word probe (when not recording) ─────────────
                    if (rawAmp > SILENCE_FLOOR && RustBridge.isNativeReady()) {
                        try {
                            if (RustBridge.nativeDetectWakeWord(frame, SAMPLE_RATE)) {
                                Log.i(TAG, "Wake word detected — entering VAD recording mode")
                                withContext(Dispatchers.Main) { onWakeWordDetected() }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "nativeDetectWakeWord error: ${e.message}")
                        }
                    }
                }
            }
            Log.i(TAG, "Audio read loop exited cleanly")
        }
    }

    /**
     * Attempt to create an AudioRecord with the best zero-beep audio source.
     */
    private fun tryCreateAudioRecord(bufSize: Int): AudioRecord? {
        // Try VOICE_COMMUNICATION first (best for voice, zero-beep)
        try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Using AudioSource.VOICE_COMMUNICATION (preferred)")
                return ar
            }
            Log.w(TAG, "VOICE_COMMUNICATION not initialized, trying MIC fallback")
            ar.release()
        } catch (e: Exception) {
            Log.w(TAG, "VOICE_COMMUNICATION failed: ${e.message}, trying MIC fallback")
        }

        // Fallback: raw MIC (also zero-beep)
        return try {
            val ar = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufSize
            )
            if (ar.state == AudioRecord.STATE_INITIALIZED) {
                Log.i(TAG, "Using AudioSource.MIC (fallback)")
                ar
            } else {
                Log.e(TAG, "MIC source also failed to initialize")
                ar.release()
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "MIC source threw: ${e.message}")
            null
        }
    }

    /**
     * Stop the audio loop and IMMEDIATELY release AudioRecord.
     */
    fun stopListening() {
        isRunning = false
        isRecordingCommand = false
        vadState = VadState.IDLE

        listeningJob?.cancel()
        listeningJob = null

        audioRecord?.let {
            try { it.stop()   } catch (_: Exception) {}
            try { it.release() } catch (_: Exception) {}
        }
        audioRecord = null

        // Reset smoothed amplitude so orb collapses gracefully
        smoothedRms = 0f
        Log.i(TAG, "AudioRecord STOPPED + released")
    }

    /**
     * Begin recording a command with VAD (Voice Activity Detection).
     *
     * Called when:
     * 1. User presses the mic button → manual trigger
     * 2. Wake word detected → automatic trigger
     *
     * VAD will auto-stop recording when silence is detected after speech.
     */
    fun startCommandRecording() {
        commandFrames.clear()
        cmdFrameCount = 0
        isRecordingCommand = true
        vadState = VadState.IDLE
        recordingStartTime = System.currentTimeMillis()
        silenceStartTime = 0L
        Log.d(TAG, "Command recording started with VAD — will auto-stop on ${SILENCE_TIMEOUT_MS}ms silence")
    }

    /** Stop command recording and deliver accumulated PCM bytes. */
    fun stopCommandRecording() {
        if (!isRecordingCommand) return
        engineScope.launch { flushCommandBuffer() }
    }

    /** Release all resources. Call from ViewModel.onCleared(). */
    fun release() {
        stopListening()
        engineScope.cancel()
    }

    val isActive: Boolean
        get() = isRunning && audioRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING

    // ── Private ────────────────────────────────────────────────────────────────

    /**
     * Flush the command buffer and deliver PCM bytes via [onCommandReady].
     *
     * FIX #5: Guarded by [flushMutex] to prevent double-delivery when
     * called concurrently from the VAD IO loop and stopCommandRecording().
     * The early-return on `!isRecordingCommand` ensures the second caller
     * is a no-op after the first has already flushed.
     */
    private suspend fun flushCommandBuffer() {
        flushMutex.withMutex {
            // If another invocation already flushed, skip — prevents double delivery
            if (!isRecordingCommand) return@withMutex

            isRecordingCommand = false
            vadState = VadState.IDLE
            val frames = commandFrames.toList()
            commandFrames.clear()
            cmdFrameCount = 0
            if (frames.isEmpty()) return@withMutex

            val out = ByteArray(frames.sumOf { it.size })
            var off = 0
            for (f in frames) { f.copyInto(out, off); off += f.size }
            val durationSec = out.size.toFloat() / (SAMPLE_RATE * 2)
            Log.i(TAG, "Command flushed: ${out.size} bytes (~${"%.1f".format(durationSec)}s)")
            withContext(Dispatchers.Main) { onCommandReady(out) }
        }
    }

    /**
     * Root-mean-square of raw little-endian signed 16-bit PCM.
     * Returns [0f..1f] normalised to Int16 full-scale.
     */
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
}

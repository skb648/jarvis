package com.jarvis.assistant.audio

import android.Manifest
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.jarvis.assistant.jni.RustBridge
import kotlinx.coroutines.*
import kotlin.math.sqrt

/**
 * AudioEngine — Production-grade silent audio capture engine.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL DESIGN DECISIONS:
 *
 * 1. ZERO SYSTEM BEEPS:
 *    Uses [MediaRecorder.AudioSource.VOICE_COMMUNICATION] as primary,
 *    falls back to [MediaRecorder.AudioSource.MIC].
 *    NEVER uses AudioSource.VOICE_RECOGNITION (triggers system beeps
 *    on Samsung, Xiaomi, and other OEM skins).
 *    NEVER uses SpeechRecognizer (causes recurring audio focus beeps).
 *
 * 2. PURE PCM LOOP:
 *    The while(isRunning) loop reads raw 16-bit PCM bytes from
 *    AudioRecord. No intent, no SpeechRecognizer, no MediaCodec.
 *    This is the ONLY way to guarantee silence.
 *
 * 3. REAL-TIME RMS AMPLITUDE:
 *    Every frame calculates the Root Mean Square of the PCM buffer.
 *    The result is emitted via [onAmplitudeUpdate] callback, which
 *    the ViewModel exposes as a `MutableStateFlow<Float>`.
 *    This drives the hologram orb's reactive animations.
 *
 * 4. MIC TOGGLE — INSTANT:
 *    [stopListening] sets `isRunning = false`, calls `.stop()` and
 *    `.release()` on AudioRecord IMMEDIATELY. No pending callbacks,
 *    no delayed disposal. The UI toggle is responsive.
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Lifecycle:
 *   startListening() → reads PCM frames in a background coroutine forever
 *   stopListening()  → cancels the coroutine, stops + releases AudioRecord immediately
 */
class AudioEngine(
    private val context: Context,
    /** Called on Main thread every frame with normalised RMS in [0f..1f]. */
    val onAmplitudeUpdate: (Float) -> Unit,
    /** Called on Main thread when the Rust JNI wake-word fires. */
    val onWakeWordDetected: () -> Unit,
    /** Called on Main thread when command recording is complete. */
    val onCommandReady: (ByteArray) -> Unit
) {

    companion object {
        private const val TAG = "AudioEngine"

        // ── Audio format: 44.1 kHz mono 16-bit PCM ────────────────────────
        // Per the spec, we use 44100Hz for maximum compatibility.
        // The Rust backend can resample if needed.
        private const val SAMPLE_RATE     = 44_100
        private const val CHANNEL_CONFIG  = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT    = AudioFormat.ENCODING_PCM_16BIT

        // Frame: 2048 samples ≈ 46 ms per read cycle at 44.1kHz
        // Small enough for responsive amplitude, large enough for efficiency
        private const val FRAME_SAMPLES   = 2_048
        private const val FRAME_BYTES     = FRAME_SAMPLES * 2   // 2 bytes / 16-bit sample

        // How long we record a command before auto-closing (seconds)
        private const val MAX_CMD_SECONDS = 15

        // Skip wake-word JNI call when signal is pure silence (saves CPU)
        private const val SILENCE_FLOOR   = 0.003f

        // RMS smoothing: exponential moving average factor.
        // Higher = more responsive, Lower = smoother.
        // 0.3 provides a good balance for orb animation.
        private const val RMS_SMOOTHING   = 0.3f
    }

    // ── State ──────────────────────────────────────────────────────────────────
    @Volatile var isRunning          = false
        private set
    @Volatile private var isRecordingCommand = false

    private var audioRecord:    AudioRecord? = null
    private var listeningJob:   Job?         = null

    // Smoothed RMS to prevent orb jitter
    private var smoothedRms: Float = 0f

    private val commandFrames   = mutableListOf<ByteArray>()
    private var cmdFrameCount   = 0
    private val maxCmdFrames    = (MAX_CMD_SECONDS * SAMPLE_RATE) / FRAME_SAMPLES

    /** Isolated coroutine scope — cancelled only in [release]. */
    private val engineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Start the silent audio loop. Requires RECORD_AUDIO permission.
     *
     * AudioSource priority:
     *   1. VOICE_COMMUNICATION — zero-beep, echo-cancelled, best for voice
     *   2. MIC — fallback, also zero-beep
     *   3. NEVER VOICE_RECOGNITION — triggers system beeps on many OEMs
     *
     * Safe to call multiple times — previous session is stopped first.
     * Returns immediately; the read loop runs in a background coroutine.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        stopListening()     // ensure clean slate

        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBuf <= 0) {
            Log.e(TAG, "getMinBufferSize returned $minBuf — device may not support 44100Hz mono")
            return
        }
        val bufSize = maxOf(minBuf, FRAME_BYTES) * 4

        // ── Create AudioRecord with VOICE_COMMUNICATION (zero-beep source) ──
        val ar = tryCreateAudioRecord(bufSize)

        if (ar == null || ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed — check permissions")
            ar?.release()
            return
        }

        audioRecord = ar
        isRunning   = true
        smoothedRms = 0f
        ar.startRecording()
        Log.i(TAG, "AudioRecord STARTED  source=${ar.audioSource}  sample_rate=$SAMPLE_RATE  buf_size=$bufSize")

        listeningJob = engineScope.launch {
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

                // ── 2. Command buffer OR wake-word probe ──────────────────────
                if (isRecordingCommand) {
                    commandFrames.add(frame)
                    cmdFrameCount++
                    if (cmdFrameCount >= maxCmdFrames) {
                        flushCommandBuffer()    // auto-close at max length
                    }
                } else if (rawAmp > SILENCE_FLOOR && RustBridge.isNativeReady()) {
                    try {
                        if (RustBridge.nativeDetectWakeWord(frame, SAMPLE_RATE)) {
                            Log.i(TAG, "Wake word detected")
                            withContext(Dispatchers.Main) { onWakeWordDetected() }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "nativeDetectWakeWord error: ${e.message}")
                    }
                }
            }
            Log.i(TAG, "Audio read loop exited cleanly")
        }
    }

    /**
     * Attempt to create an AudioRecord with the best zero-beep audio source.
     *
     * Priority:
     *   1. VOICE_COMMUNICATION — echo-cancelled, noise-suppressed, NO system beeps
     *   2. MIC — raw microphone, also NO system beeps
     *
     * We NEVER use VOICE_RECOGNITION because it triggers recurring
     * audio-focus change notifications on many OEM Android skins,
     * which produce audible "beep" sounds every second.
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

        // Fallback: raw MIC (also zero-beep, no SpeechRecognizer involvement)
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
     * Stop the audio loop and **immediately** release the AudioRecord resource.
     *
     * This is called from the Mic toggle — must be fast (UI is waiting).
     * Sets isRunning = false to break the while loop, cancels the coroutine,
     * then stop() + release() the AudioRecord hardware.
     *
     * NO SYSTEM BEEPS: We never used SpeechRecognizer or VOICE_RECOGNITION,
     * so there are no system sounds to clean up.
     */
    fun stopListening() {
        isRunning = false
        isRecordingCommand = false

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

    /** Begin accumulating frames as a user voice command. */
    fun startCommandRecording() {
        commandFrames.clear()
        cmdFrameCount = 0
        isRecordingCommand = true
        Log.d(TAG, "Command recording started")
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

    private suspend fun flushCommandBuffer() {
        isRecordingCommand = false
        val frames = commandFrames.toList()
        commandFrames.clear()
        cmdFrameCount = 0
        if (frames.isEmpty()) return

        val out = ByteArray(frames.sumOf { it.size })
        var off = 0
        for (f in frames) { f.copyInto(out, off); off += f.size }
        Log.i(TAG, "Command flushed: ${out.size} bytes (~${out.size / (SAMPLE_RATE * 2)} s)")
        withContext(Dispatchers.Main) { onCommandReady(out) }
    }

    /**
     * Root-mean-square of raw little-endian signed 16-bit PCM.
     * Returns [0f..1f] normalised to Int16 full-scale.
     *
     * This is the core amplitude calculation that feeds the orb animation.
     * Each sample is converted from two bytes (little-endian) to a signed
     * 16-bit integer, squared, summed, then we take the square root of
     * the mean and normalise by dividing by 32768 (max Int16 value).
     */
    private fun rms(pcm: ByteArray): Float {
        val n = pcm.size / 2
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val lo = pcm[i * 2].toInt() and 0xFF
            val hi = pcm[i * 2 + 1].toInt()        // sign-extended by Kotlin
            val s  = (hi shl 8) or lo
            sum   += s.toLong() * s.toLong()
        }
        return (sqrt(sum / n) / 32_768.0).toFloat().coerceIn(0f, 1f)
    }
}

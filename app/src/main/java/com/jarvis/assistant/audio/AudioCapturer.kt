package com.jarvis.assistant.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Real-time microphone capture at 16 kHz / 16-bit mono.
 * - Analyzes every ~100 ms chunk and pushes [AudioFeatures] to listeners
 * - Can record an utterance to a WAV file (used for optional Gemini audio-in)
 * - Pushes raw ShortArray chunks to raw listeners (used by the wake-word detector)
 *
 * IMPORTANT: This feeds the raw waveform (not text) into the emotion engine,
 * which is exactly how JARVIS "feels" your voice — tone, pitch, energy, speed.
 */
class AudioCapturer {

    fun interface Listener {
        fun onChunk(features: AudioFeatures)
    }

    fun interface RawListener {
        fun onRawChunk(samples: ShortArray)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val rawListeners = CopyOnWriteArrayList<RawListener>()
    private var record: AudioRecord? = null
    private var running = false
    private var threadRef: Thread? = null

    private val recordingLock = Any()
    private var utteranceBytes: ByteArrayOutputStream? = null

    val isRecording get() = synchronized(recordingLock) { utteranceBytes != null }

    fun addListener(l: Listener) = listeners.add(l)
    fun removeListener(l: Listener) = listeners.remove(l)
    fun addRawListener(l: RawListener) = rawListeners.add(l)
    fun removeRawListener(l: RawListener) = rawListeners.remove(l)

    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioRecord.getMinBufferSize(
            AudioAnalyzer.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return

        val rec = try {
            AudioRecord.Builder()
                .setAudioSource(pickSource())
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(AudioAnalyzer.SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            null
        } ?: return

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return
        }

        record = rec
        running = true
        threadRef = thread(name = "jarvis-audio") {
            rec.startRecording()
            val chunk = ShortArray(1600) // 100 ms
            while (running) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) continue
                val effective = if (read < chunk.size) chunk.copyOf(read) else chunk
                val features = AudioAnalyzer.analyze(effective)
                for (l in listeners) l.onChunk(features)
                for (l in rawListeners) l.onRawChunk(effective)
                synchronized(recordingLock) {
                    utteranceBytes?.let { out ->
                        val bytes = ByteArray(read * 2)
                        for (i in 0 until read) {
                            bytes[i * 2] = (chunk[i].toInt() and 0xFF).toByte()
                            bytes[i * 2 + 1] = (chunk[i].toInt() shr 8 and 0xFF).toByte()
                        }
                        out.write(bytes)
                    }
                }
            }
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        threadRef?.join(500)
        threadRef = null
        try { record?.release() } catch (_: Exception) {}
        record = null
        synchronized(recordingLock) { utteranceBytes = null }
    }

    /** Start capturing the current utterance into memory. */
    fun beginUtterance() {
        synchronized(recordingLock) {
            utteranceBytes = ByteArrayOutputStream()
        }
    }

    /** Stop utterance capture and write it to [file] as a WAV. Returns true on success. */
    fun endUtterance(file: File): Boolean {
        val data = synchronized(recordingLock) {
            val out = utteranceBytes ?: return false
            val bytes = out.toByteArray()
            utteranceBytes = null
            bytes
        }
        if (data.size < 100) return false
        return try {
            WavUtil.writeWav(file, data, AudioAnalyzer.SAMPLE_RATE)
        } catch (e: Exception) {
            false
        }
    }

    /** UNPROCESSED when available (clean emotion data), else plain MIC. */
    private fun pickSource(): Int {
        val sources = listOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.MIC
        )
        for (s in sources) {
            try {
                val minBuf = AudioRecord.getMinBufferSize(
                    AudioAnalyzer.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                if (minBuf <= 0) continue
                val test = AudioRecord.Builder()
                    .setAudioSource(s)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(AudioAnalyzer.SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBuf)
                    .build()
                val ok = test.state == AudioRecord.STATE_INITIALIZED
                test.release()
                if (ok) return s
            } catch (_: Exception) {}
        }
        return MediaRecorder.AudioSource.MIC
    }
}

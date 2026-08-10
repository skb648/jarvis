package com.jarvis.assistant.audio

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.ArrayDeque
import kotlin.math.sqrt

/**
 * On-device wake-word detector — 100% offline, no Google ASR involved.
 *
 * HOW IT WORKS:
 *  1. TRAIN: aap "Hey Jarvis" record karte ho (Settings -> Wake word -> Train).
 *     JARVIS us recording se MFCC frames nikaal ke ek template file me save
 *     karta hai (aapki awaaz ka signature).
 *  2. DETECT: live mic ke har chunk se MFCC frames bante hain; sliding window
 *     par template se DTW (Dynamic Time Warping) distance measure hota hai.
 *     Distance kam = same word bola. Threshold ke neeche = WAKE!
 *
 * This is an experimental but genuinely on-device alternative to cloud hotword
 * engines — instant (<200 ms) and battery friendly.
 */
class WakeWordDetector {

    interface Listener {
        fun onWakeWordDetected()
    }

    private var template: List<FloatArray> = emptyList()
    private val frameBuffer = ArrayDeque<FloatArray>()
    private var pendingSamples = ShortArray(0)
    private var energySum = 0.0
    private var energyCount = 0L

    @Volatile
    var listener: Listener? = null

    /** DTW cost threshold — lower = stricter, higher = more sensitive. */
    var threshold: Float = 1.45f

    val isTrained get() = template.isNotEmpty()

    // ------------------------------------------------------------ training

    /** Train from a recorded utterance. Returns true if a template was built. */
    fun train(samples: ShortArray): Boolean {
        val frames = Mfcc.computeFrames(samples)
        val speech = trimSilence(frames)
        if (speech.size < 25) return false
        template = speech
        return true
    }

    fun saveTemplate(file: File): Boolean {
        if (template.isEmpty()) return false
        return try {
            DataOutputStream(file.outputStream()).use { out ->
                out.writeInt(template.size)
                for (frame in template) {
                    for (v in frame) out.writeFloat(v)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun loadTemplate(file: File): Boolean {
        return try {
            DataInputStream(file.inputStream()).use { inp ->
                val count = inp.readInt()
                if (count < 25) return false
                val loaded = ArrayList<FloatArray>(count)
                repeat(count) {
                    val frame = FloatArray(Mfcc.COEFS)
                    for (i in frame.indices) frame[i] = inp.readFloat()
                    loaded.add(frame)
                }
                template = loaded
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    fun reset() {
        template = emptyList()
    }

    private fun trimSilence(frames: List<FloatArray>): List<FloatArray> {
        if (frames.size < 40) return frames
        // crude energy proxy: first coefficient magnitude
        val energies = frames.map { kotlin.math.abs(it[0]) }
        val maxE = energies.maxOrNull() ?: return frames
        if (maxE < 1e-5f) return frames
        val gate = maxE * 0.15f
        var start = energies.indexOfFirst { it > gate }
        var end = energies.indexOfLast { it > gate }
        if (start < 0) start = 0
        if (end <= start) end = (start + 40).coerceAtMost(frames.size - 1)
        val pad = 6
        val from = (start - pad).coerceAtLeast(0)
        val to = (end + pad).coerceAtMost(frames.size)
        return frames.subList(from, to)
    }

    // ------------------------------------------------------------ runtime

    /** Feed a raw 16 kHz chunk. Cheap when no template is loaded. */
    fun feedChunk(samples: ShortArray) {
        if (template.isEmpty()) return

        // accumulate
        val combined = ShortArray(pendingSamples.size + samples.size)
        System.arraycopy(pendingSamples, 0, combined, 0, pendingSamples.size)
        System.arraycopy(samples, 0, combined, pendingSamples.size, samples.size)

        // energy gate: skip silence quickly
        var e = 0.0
        for (s in samples) e += (s.toLong() * s)
        energySum += e / samples.size
        energyCount++
        val avgEnergy = energySum / energyCount
        if (avgEnergy < 8_000_000.0) { // ~ -52 dBFS RMS
            // keep only the tail (in case speech starts) but skip DTW
            pendingSamples = combined.copyOfRange((combined.size - Mfcc.FRAME).coerceAtLeast(0), combined.size)
            return
        }

        // extract new frames
        var consumed = 0
        while (consumed + Mfcc.FRAME <= combined.size - Mfcc.HOP) {
            val frame = extractFrame(combined, consumed)
            frameBuffer.addLast(frame)
            consumed += Mfcc.HOP
            if (frameBuffer.size > 900) frameBuffer.removeFirst()
        }
        pendingSamples = combined.copyOfRange(consumed, combined.size)

        // sliding DTW check
        if (frameBuffer.size >= template.size) {
            val cost = dtwDistance(template, frameBuffer.toList().takeLast(template.size))
            if (cost < threshold) {
                resetBuffers()
                listener?.onWakeWordDetected()
            }
        }
    }

    private fun extractFrame(samples: ShortArray, start: Int): FloatArray {
        var mean = 0.0
        for (i in 0 until Mfcc.FRAME) mean += samples[start + i]
        mean /= Mfcc.FRAME
        var norm = 0.0
        val v = FloatArray(Mfcc.COEFS)
        // Use a lightweight proxy: windowed energy + first differences of MFCCs
        // would need a full FFT per frame; instead reuse Mfcc on a copied window.
        val win = ShortArray(Mfcc.FRAME)
        for (i in 0 until Mfcc.FRAME) win[i] = (samples[start + i] - mean).toInt().toShort()
        val frame = Mfcc.computeFrames(win).firstOrNull() ?: FloatArray(Mfcc.COEFS)
        for (x in frame) norm += x * x
        norm = sqrt(norm)
        if (norm > 1e-6f) for (i in v.indices) v[i] = frame[i] / norm.toFloat()
        return v
    }

    private fun resetBuffers() {
        frameBuffer.clear()
        pendingSamples = ShortArray(0)
        energySum = 0.0
        energyCount = 0
    }

    // ---------------------------------------------------------------- DTW

    /**
     * Classic DTW with Sakoe-Chiba band. Returns average per-frame cost.
     * O(T²) with T ≈ 100–150 frames — fast enough on any modern phone.
     */
    private fun dtwDistance(template: List<FloatArray>, live: List<FloatArray>): Float {
        val n = template.size
        val m = live.size
        val band = (n / 4).coerceAtLeast(4)

        // rolling two rows to keep memory tiny
        var prev = FloatArray(m) { Float.MAX_VALUE }
        var cur = FloatArray(m)
        prev[0] = frameDist(template[0], live[0])

        for (i in 1 until n) {
            val t = template[i]
            for (j in 0 until m) {
                if (kotlin.math.abs(i - j) > band) {
                    cur[j] = Float.MAX_VALUE
                    continue
                }
                val cost = frameDist(t, live[j])
                val best = minOf(
                    prev[j],                                  // up
                    if (j > 0) cur[j - 1] else Float.MAX_VALUE, // left
                    if (j > 0) prev[j - 1] else Float.MAX_VALUE  // diagonal
                )
                cur[j] = cost + best
            }
            val tmp = prev
            prev = cur
            cur = tmp
            cur.fill(Float.MAX_VALUE)
        }
        return (prev[m - 1] / n)
    }

    private fun frameDist(a: FloatArray, b: FloatArray): Float {
        var d = 0.0f
        for (i in a.indices) {
            val diff = a[i] - b[i]
            d += diff * diff
        }
        return d
    }
}

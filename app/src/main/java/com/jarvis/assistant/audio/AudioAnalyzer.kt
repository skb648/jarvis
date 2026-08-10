package com.jarvis.assistant.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Physical features extracted from a raw audio chunk (16 kHz, 16-bit PCM mono).
 * These are the "body language" of the voice:
 *  - rms            : loudness (energy)
 *  - peak           : peak amplitude
 *  - pitchHz        : fundamental frequency (0 if unvoiced/silence)
 *  - zcr            : zero-crossing rate per sample (rough speech-speed proxy)
 *  - highFreqRatio  : share of energy in high frequencies (strain/brightness)
 *  - voiced         : 1.0 if a stable pitch was found
 */
data class AudioFeatures(
    val rms: Float,
    val peak: Float,
    val pitchHz: Float,
    val zcr: Float,
    val highFreqRatio: Float,
    val voiced: Float
)

object AudioAnalyzer {

    const val SAMPLE_RATE = 16000

    fun analyze(samples: ShortArray): AudioFeatures {
        val n = samples.size
        if (n == 0) return AudioFeatures(0f, 0f, 0f, 0f, 0f, 0f)

        var energy = 0.0
        var peak = 0
        var zc = 0
        var prev = 0
        for (i in 0 until n) {
            val s = samples[i].toInt()
            energy += (s.toDouble() * s) / (32768.0 * 32768.0)
            peak = max(peak, abs(s))
            if ((prev >= 0) != (s >= 0)) zc++
            prev = s
        }
        val meanEnergy = energy / n
        val rms = sqrt(meanEnergy).toFloat()

        // First-difference = crude high-pass -> energy in fast fluctuations
        var highEnergy = 0.0
        for (i in 1 until n) {
            val d = samples[i].toInt() - samples[i - 1].toInt()
            highEnergy += (d.toDouble() * d) / (4.0 * 32768.0 * 32768.0)
        }
        val highFreqRatio = if (meanEnergy > 1e-8) {
            ((highEnergy / n) / meanEnergy).toFloat().coerceIn(0f, 1f)
        } else 0f

        val zcrRate = zc.toFloat() / n
        val pitch = detectPitch(samples)

        return AudioFeatures(
            rms = rms,
            peak = peak / 32768f,
            pitchHz = pitch,
            zcr = zcrRate,
            highFreqRatio = highFreqRatio,
            voiced = if (pitch > 0f) 1f else 0f
        )
    }

    /** Autocorrelation pitch detection, search range 80–400 Hz (typical speech). */
    private fun detectPitch(samples: ShortArray): Float {
        val minLag = SAMPLE_RATE / 400
        val maxLag = SAMPLE_RATE / 80
        var bestLag = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            var score = 0.0
            var norm = 0.0
            var i = 0
            while (i < samples.size - lag) {
                val a = samples[i].toDouble()
                score += a * samples[i + lag]
                norm += a * a
                i += 2 // decimate for speed
            }
            if (norm < 1e-6) continue
            val s = score / norm
            if (s > bestScore) {
                bestScore = s
                bestLag = lag
            }
        }
        return if (bestLag > 0 && bestScore > 0.35) SAMPLE_RATE.toFloat() / bestLag else 0f
    }
}

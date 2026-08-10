package com.jarvis.assistant.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * MFCC (Mel-Frequency Cepstral Coefficients) — the same features speech
 * recognizers use. JARVIS uses them to build a *template* of YOUR voice
 * saying the wake word, then matches live audio against it with DTW.
 *
 * Pipeline per frame (32 ms @ 16 kHz):
 *   DC removal -> pre-emphasis -> Hamming window -> FFT(1024)
 *   -> 24 mel filters (300–5500 Hz) -> log -> DCT-II -> 12 coefficients
 */
object Mfcc {

    const val SAMPLE_RATE = 16000
    const val FRAME = 512          // 32 ms
    const val HOP = 256            // 16 ms
    const val NFFT = 1024
    const val MEL_FILTERS = 24
    const val COEFS = 12

    private val hamming = FloatArray(FRAME) { i ->
        0.54f - 0.46f * cos(2.0 * PI * i / (FRAME - 1)).toFloat()
    }

    private val melFilterbank: Array<FloatArray> by lazy { buildMelFilterbank() }

    /** Split audio into frames and return normalized MFCC vectors. */
    fun computeFrames(samples: ShortArray): List<FloatArray> {
        val frames = ArrayList<FloatArray>()
        var start = 0
        while (start + FRAME <= samples.size) {
            val frame = extract(samples, start)
            frames.add(normalize(frame))
            start += HOP
        }
        return frames
    }

    private fun extract(samples: ShortArray, start: Int): FloatArray {
        // DC removal
        var mean = 0.0
        for (i in 0 until FRAME) mean += samples[start + i]
        mean /= FRAME

        // Pre-emphasis + hamming window
        val win = FloatArray(FRAME)
        var prev = (samples[start] - mean).toFloat()
        for (i in 0 until FRAME) {
            val cur = (samples[start + i] - mean).toFloat()
            win[i] = (cur - 0.97f * prev) * hamming[i]
            prev = cur
        }

        // FFT (real input -> complex spectrum)
        val real = FloatArray(NFFT)
        val imag = FloatArray(NFFT)
        for (i in 0 until FRAME) real[i] = win[i]
        fft(real, imag)

        // Power spectrum (positive half)
        val power = FloatArray(NFFT / 2)
        for (i in 0 until NFFT / 2) {
            power[i] = real[i] * real[i] + imag[i] * imag[i]
        }

        // Mel filterbank energies
        val melEnergy = FloatArray(MEL_FILTERS)
        for (m in 0 until MEL_FILTERS) {
            var e = 0.0f
            val filter = melFilterbank[m]
            for (k in filter.indices) {
                e += filter[k] * power[k]
            }
            melEnergy[m] = ln(e + 1e-10f)
        }

        // DCT-II -> cepstral coefficients
        val coefs = FloatArray(COEFS)
        for (i in 0 until COEFS) {
            var sum = 0.0f
            for (m in 0 until MEL_FILTERS) {
                sum += melEnergy[m] * cos(PI * i * (m + 0.5) / MEL_FILTERS).toFloat()
            }
            coefs[i] = sum
        }
        return coefs
    }

    /** L2-normalize a frame so DTW distance is amplitude-independent. */
    private fun normalize(v: FloatArray): FloatArray {
        var norm = 0.0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm < 1e-6f) return v
        for (i in v.indices) v[i] /= norm
        return v
    }

    /** Iterative radix-2 Cooley-Tukey FFT in place. */
    private fun fft(real: FloatArray, imag: FloatArray) {
        val n = real.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wRe = cos(ang).toFloat()
            val wIm = sin(ang).toFloat()
            for (i in 0 until n step len) {
                var curRe = 1f
                var curIm = 0f
                for (k in 0 until len / 2) {
                    val uRe = real[i + k]
                    val uIm = imag[i + k]
                    val vRe = real[i + k + len / 2] * curRe - imag[i + k + len / 2] * curIm
                    val vIm = real[i + k + len / 2] * curIm + imag[i + k + len / 2] * curRe
                    real[i + k] = uRe + vRe
                    imag[i + k] = uIm + vIm
                    real[i + k + len / 2] = uRe - vRe
                    imag[i + k + len / 2] = uIm - vIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
            }
            len = len shl 1
        }
    }

    private fun buildMelFilterbank(): Array<FloatArray> {
        val fMin = 300.0
        val fMax = 5500.0
        fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
        fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

        val melMin = hzToMel(fMin)
        val melMax = hzToMel(fMax)
        val melPoints = DoubleArray(MEL_FILTERS + 2) { i ->
            melToHz(melMin + (melMax - melMin) * i / (MEL_FILTERS + 1))
        }
        val binFreq = SAMPLE_RATE.toDouble() / NFFT
        val bins = melPoints.map { (it / binFreq).toInt() }

        val filters = Array(MEL_FILTERS) { m ->
            val f = FloatArray(NFFT / 2)
            for (k in bins[m] until bins[m + 1]) {
                f[k] = (k - bins[m]).toFloat() / (bins[m + 1] - bins[m]).toFloat()
            }
            for (k in bins[m + 1] until bins[m + 2]) {
                f[k] = (bins[m + 2] - k).toFloat() / (bins[m + 2] - bins[m + 1]).toFloat()
            }
            f
        }
        return filters
    }
}

package com.jarvis.assistant.mood

import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * MoodDetector — Detects user mood from voice audio features.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * ⚠️  DISCLAIMER — ACCURACY LIMITATIONS:
 *
 * Rule-based mood detection from simple audio features (pitch, volume,
 * speech rate, energy variation) is inherently unreliable. These features
 * are noisy proxies for emotional state and can produce essentially random
 * classifications, especially in short or quiet utterances.
 *
 * Confidence scores are NOT calibrated and should NOT be used to make
 * significant UX decisions. They are intentionally conservative (capped
 * low) to prevent over-reliance on unreliable classifications.
 *
 * Results below the MIN_CONFIDENCE_THRESHOLD are reported as "neutral"
 * to reduce misclassification. For production-grade emotion detection,
 * consider using a trained ML model (e.g., SER deep learning models).
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Features analyzed:
 *   - Pitch (F0): High = excited/stressed, Low = calm/sad
 *   - Speech rate: Fast = urgent, Slow = calm
 *   - Volume (RMS energy): Loud = angry/excited, Quiet = sad/calm
 *   - Energy variation: High variation = emotional, Low = monotone
 *
 * Mood values: "happy", "sad", "angry", "calm", "stressed", "neutral", "excited"
 */
object MoodDetector {

    private const val TAG = "MoodDetector"

    /**
     * Minimum confidence threshold. Results below this are reported as "neutral"
     * to avoid misclassification from unreliable rule-based scoring.
     * Keep this relatively high (0.35+) since the rule-based scores are noisy.
     */
    private const val MIN_CONFIDENCE_THRESHOLD = 0.35f

    /**
     * Maximum confidence cap. Even when the rule-based scorer produces a high
     * gap between scores, we cap confidence to signal that this method is
     * fundamentally imprecise. Users of this API should treat anything above
     * this cap with skepticism.
     */
    private const val MAX_CONFIDENCE_CAP = 0.7f

    /** Valid mood values */
    val VALID_MOODS = setOf(
        "happy", "sad", "angry", "calm", "stressed", "neutral", "excited"
    )

    /**
     * Result of mood detection analysis.
     *
     * @param mood Detected mood string (one of VALID_MOODS)
     * @param confidence Confidence score 0.0–1.0
     * @param suggestedAction Optional action JARVIS should take (e.g., "play_calm_music")
     */
    data class MoodResult(
        val mood: String,
        val confidence: Float,
        val suggestedAction: String?
    )

    /**
     * Detect mood from raw PCM audio bytes.
     *
     * @param pcmBytes 16-bit PCM audio data (mono)
     * @param sampleRate Sample rate of the audio (e.g., 16000, 44100)
     * @return MoodResult with detected mood, confidence, and suggested action
     */
    fun detectMoodFromAudio(pcmBytes: ByteArray, sampleRate: Int): MoodResult {
        if (pcmBytes.size < 3200) {
            // Less than 0.1s of audio at 16kHz — not enough data
            return MoodResult("neutral", 0.3f, null)
        }

        try {
            // Convert PCM bytes to sample array
            val samples = pcmBytesToSamples(pcmBytes)
            if (samples.isEmpty()) {
                return MoodResult("neutral", 0.3f, null)
            }

            // ─── Feature Extraction ─────────────────────────────────────

            // 1. Volume (RMS Energy)
            val rmsEnergy = computeRMS(samples)
            val volume = normalizeVolume(rmsEnergy)

            // 2. Pitch estimation via zero-crossing rate (simplified)
            val zeroCrossingRate = computeZeroCrossingRate(samples, sampleRate)
            val pitch = estimatePitch(zeroCrossingRate, sampleRate)

            // 3. Speech rate — estimated from energy envelope segmentation
            val speechRate = estimateSpeechRate(samples, sampleRate)

            // 4. Energy variation (standard deviation of frame energies)
            val energyVariation = computeEnergyVariation(samples)

            Log.d(TAG, "[detectMoodFromAudio] Features: volume=$volume, pitch=$pitch, " +
                    "speechRate=$speechRate, energyVar=$energyVariation")

            // ─── Mood Classification (rule-based) ───────────────────────

            return classifyMood(volume, pitch, speechRate, energyVariation)

        } catch (e: Exception) {
            Log.e(TAG, "[detectMoodFromAudio] Error: ${e.message}")
            return MoodResult("neutral", 0.2f, null)
        }
    }

    // ─── PCM Conversion ────────────────────────────────────────────────

    private fun pcmBytesToSamples(pcmBytes: ByteArray): ShortArray {
        val numSamples = pcmBytes.size / 2
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val lo = pcmBytes[i * 2].toInt() and 0xFF
            val hi = pcmBytes[i * 2 + 1].toInt()
            samples[i] = ((hi shl 8) or lo).toShort()
        }
        return samples
    }

    // ─── Feature Extraction ────────────────────────────────────────────

    /** Compute RMS (Root Mean Square) energy of the signal */
    private fun computeRMS(samples: ShortArray): Double {
        var sumSquares = 0.0
        for (s in samples) {
            val normalized = s.toDouble() / Short.MAX_VALUE
            sumSquares += normalized * normalized
        }
        return sqrt(sumSquares / samples.size)
    }

    /** Normalize volume to 0.0–1.0 range */
    private fun normalizeVolume(rms: Double): Float {
        // Typical speech RMS is 0.01–0.3; map that to 0–1
        return (rms * 5.0).coerceIn(0.0, 1.0).toFloat()
    }

    /** Compute zero-crossing rate (crossings per second) */
    private fun computeZeroCrossingRate(samples: ShortArray, sampleRate: Int): Float {
        var crossings = 0
        for (i in 1 until samples.size) {
            if ((samples[i] >= 0 && samples[i - 1] < 0) ||
                (samples[i] < 0 && samples[i - 1] >= 0)) {
                crossings++
            }
        }
        val durationSeconds = samples.size.toFloat() / sampleRate
        return if (durationSeconds > 0) crossings / durationSeconds else 0f
    }

    /** Estimate pitch from zero-crossing rate */
    private fun estimatePitch(zcr: Float, sampleRate: Int): Float {
        // Approximate fundamental frequency: F0 ≈ ZCR / 2
        // Typical speech: 80–400 Hz for adults
        return (zcr / 2f).coerceIn(50f, 600f)
    }

    /** Estimate speech rate (syllables per second) from energy envelope */
    private fun estimateSpeechRate(samples: ShortArray, sampleRate: Int): Float {
        val frameSize = (sampleRate * 0.025).toInt()  // 25ms frames
        val hopSize = (sampleRate * 0.010).toInt()     // 10ms hop
        if (samples.size < frameSize) return 3f  // default

        val energies = mutableListOf<Double>()
        var pos = 0
        while (pos + frameSize <= samples.size) {
            var energy = 0.0
            for (i in pos until pos + frameSize) {
                val normalized = samples[i].toDouble() / Short.MAX_VALUE
                energy += normalized * normalized
            }
            energies.add(energy / frameSize)
            pos += hopSize
        }

        if (energies.isEmpty()) return 3f

        // Count syllable-like peaks: segments where energy rises above threshold
        val threshold = energies.average() * 0.5
        var peaks = 0
        var aboveThreshold = false
        for (e in energies) {
            if (e > threshold && !aboveThreshold) {
                peaks++
                aboveThreshold = true
            } else if (e <= threshold) {
                aboveThreshold = false
            }
        }

        val durationSeconds = samples.size.toFloat() / sampleRate
        return if (durationSeconds > 0) peaks / durationSeconds else 3f
    }

    /** Compute variation in frame energies (normalized standard deviation) */
    private fun computeEnergyVariation(samples: ShortArray): Float {
        val frameSize = (samples.size / 10).coerceIn(160, 8192)
        val energies = mutableListOf<Double>()
        var pos = 0
        while (pos + frameSize <= samples.size) {
            var energy = 0.0
            for (i in pos until pos + frameSize) {
                val normalized = samples[i].toDouble() / Short.MAX_VALUE
                energy += normalized * normalized
            }
            energies.add(energy / frameSize)
            pos += frameSize
        }

        if (energies.size < 2) return 0.3f

        val mean = energies.average()
        val variance = energies.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        // Normalized variation coefficient
        return if (mean > 0) {
            (stdDev / mean).coerceIn(0.0, 2.0).toFloat()
        } else {
            0.3f
        }
    }

    // ─── Mood Classification ───────────────────────────────────────────

    /**
     * Rule-based mood classifier using extracted audio features.
     *
     * Thresholds are tuned for typical voice assistant usage:
     *   - Volume: 0–1 (quiet to loud)
     *   - Pitch: 50–600 Hz
     *   - Speech rate: 1–8 syllables/sec
     *   - Energy variation: 0–2 (low to high variation)
     */
    private fun classifyMood(
        volume: Float,
        pitch: Float,
        speechRate: Float,
        energyVariation: Float
    ): MoodResult {
        // Score each mood based on feature alignment
        val scores = mutableMapOf<String, Float>()

        // HAPPY: moderate-high volume, high pitch, moderate-fast rate, moderate variation
        scores["happy"] = scoreHappy(volume, pitch, speechRate, energyVariation)

        // EXCITED: high volume, very high pitch, fast rate, high variation
        scores["excited"] = scoreExcited(volume, pitch, speechRate, energyVariation)

        // SAD: low volume, low pitch, slow rate, low variation
        scores["sad"] = scoreSad(volume, pitch, speechRate, energyVariation)

        // ANGRY: very high volume, moderate-high pitch, fast rate, high variation
        scores["angry"] = scoreAngry(volume, pitch, speechRate, energyVariation)

        // CALM: moderate volume, moderate-low pitch, slow rate, low variation
        scores["calm"] = scoreCalm(volume, pitch, speechRate, energyVariation)

        // STRESSED: moderate-high volume, high pitch, fast rate, high variation
        scores["stressed"] = scoreStressed(volume, pitch, speechRate, energyVariation)

        // NEUTRAL: baseline (always has some score)
        scores["neutral"] = 0.4f

        // Find the mood with highest score
        val bestMood = scores.maxByOrNull { it.value }?.key ?: "neutral"
        val bestScore = scores[bestMood] ?: 0.4f

        // Confidence is the gap between best and second-best score.
        // Apply conservative cap — rule-based scoring is inherently imprecise.
        val sortedScores = scores.values.sortedDescending()
        val rawConfidence = if (sortedScores.size >= 2) {
            (sortedScores[0] - sortedScores[1]).coerceIn(0.05f, 1.0f)
        } else {
            0.1f
        }

        // Cap confidence to signal that rule-based detection is unreliable
        val confidence = rawConfidence.coerceAtMost(MAX_CONFIDENCE_CAP)

        // If confidence is below the minimum threshold, report "neutral" instead
        // of a potentially misclassified mood. This prevents actions like
        // "play_calm_music" from firing on essentially random classifications.
        val finalMood = if (confidence < MIN_CONFIDENCE_THRESHOLD) "neutral" else bestMood
        val finalConfidence = if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            // Report a very low confidence for neutral when we fell back
            confidence * 0.5f
        } else {
            confidence
        }

        val suggestedAction = getSuggestedAction(finalMood)

        Log.i(TAG, "[classifyMood] mood=$finalMood confidence=$finalConfidence " +
                "(rawConfidence=$rawConfidence, bestMood=$bestMood) scores=$scores")
        return MoodResult(
            mood = finalMood,
            confidence = finalConfidence,
            suggestedAction = suggestedAction
        )
    }

    // ─── Scoring Functions ─────────────────────────────────────────────

    private fun scoreHappy(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v in 0.4f..0.7f) score += 0.25f
        if (p in 180f..350f) score += 0.3f
        if (r in 4f..6f) score += 0.25f
        if (e in 0.3f..0.8f) score += 0.2f
        return score
    }

    private fun scoreExcited(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v > 0.6f) score += 0.3f
        if (p > 300f) score += 0.3f
        if (r > 5f) score += 0.25f
        if (e > 0.6f) score += 0.15f
        return score
    }

    private fun scoreSad(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v < 0.3f) score += 0.3f
        if (p < 150f) score += 0.3f
        if (r < 3f) score += 0.25f
        if (e < 0.3f) score += 0.15f
        return score
    }

    private fun scoreAngry(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v > 0.7f) score += 0.35f
        if (p in 150f..300f) score += 0.2f
        if (r > 5.5f) score += 0.25f
        if (e > 0.7f) score += 0.2f
        return score
    }

    private fun scoreCalm(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v in 0.2f..0.5f) score += 0.3f
        if (p in 100f..200f) score += 0.3f
        if (r < 3.5f) score += 0.25f
        if (e < 0.4f) score += 0.15f
        return score
    }

    private fun scoreStressed(v: Float, p: Float, r: Float, e: Float): Float {
        var score = 0f
        if (v in 0.4f..0.7f) score += 0.2f
        if (p > 250f) score += 0.3f
        if (r > 5f) score += 0.3f
        if (e > 0.5f) score += 0.2f
        return score
    }

    // ─── Suggested Actions ─────────────────────────────────────────────

    /**
     * Get a suggested action based on the detected mood.
     * These actions can be triggered by JarvisViewModel to respond empathetically.
     */
    private fun getSuggestedAction(mood: String): String? {
        return when (mood) {
            "stressed" -> "play_calm_music"
            "sad" -> "play_uplifting_music"
            "angry" -> "suggest_break"
            "excited" -> "match_energy"
            else -> null
        }
    }

    /**
     * Get mood context string for inclusion in AI system prompts.
     * Returns a natural language description of the detected mood.
     */
    fun getMoodContextString(moodResult: MoodResult): String {
        return when (moodResult.mood) {
            "happy" -> "The user sounds happy and cheerful. Match their positive energy."
            "sad" -> "The user sounds sad or down. Be gentle and supportive. Consider suggesting something uplifting."
            "angry" -> "The user sounds frustrated or angry. Be calm and patient. Avoid being overly casual."
            "calm" -> "The user sounds calm and relaxed. Maintain a composed, helpful tone."
            "stressed" -> "The user sounds stressed or under pressure. Be supportive and offer to help simplify things."
            "excited" -> "The user sounds excited and enthusiastic. Share their enthusiasm."
            else -> ""
        }
    }
}

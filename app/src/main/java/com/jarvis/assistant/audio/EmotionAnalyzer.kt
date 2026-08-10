package com.jarvis.assistant.audio

import com.jarvis.assistant.model.Emotion
import kotlin.math.abs

/**
 * Emotion recognition from raw audio features.
 *
 * This is a lightweight on-device "feel" engine — no internet, no API key.
 * It scores each of the 7 emotions using the vocal body-language cues:
 *
 *   ANGRY     loud + tense (high-freq energy) + faster
 *   EXCITED   high pitch + loud + fast
 *   HAPPY     high-ish pitch + lively + bright but not strained
 *   SAD       low pitch + soft + slow
 *   CALM      soft + slow + steady
 *   STRESSED  fast + strained + unstable pitch
 *   NEUTRAL   everything in the middle
 */
object EmotionAnalyzer {

    data class Result(
        val emotion: Emotion,
        val confidence: Float,   // 0..1
        val scores: Map<Emotion, Float>
    )

    private val SILENCE_RMS = 0.004f

    fun classify(features: AudioFeatures): Result {
        if (features.rms < SILENCE_RMS) {
            return Result(Emotion.NEUTRAL, 0f, emptyMap())
        }

        val en = features.rms.coerceIn(0f, 1f)
        val pitchN = normalizePitch(features.pitchHz)
        val spd = (features.zcr * 14f).coerceIn(0f, 1f)          // zero-crossing -> speed
        val hfr = features.highFreqRatio.coerceIn(0f, 1f)
        val strain = ((features.highFreqRatio - 0.25f) * 3f).coerceIn(0f, 1f)

        fun s(vararg v: Float) = v.sum()

        val scores = linkedMapOf(
            Emotion.ANGRY to s(en * 0.45f, strain * 0.30f, pitchN * 0.10f, spd * 0.15f),
            Emotion.EXCITED to s(pitchN * 0.40f, en * 0.25f, spd * 0.25f, hfr * 0.10f),
            Emotion.HAPPY to s(pitchN * 0.35f, en * 0.25f, spd * 0.20f, (1f - strain) * 0.20f),
            Emotion.SAD to s((1f - pitchN) * 0.40f, (1f - en) * 0.30f, (1f - spd) * 0.30f),
            Emotion.CALM to s((1f - en) * 0.35f, (1f - spd) * 0.30f, (1f - pitchN) * 0.20f, (1f - hfr) * 0.15f),
            Emotion.STRESSED to s(spd * 0.35f, strain * 0.30f, pitchN * 0.20f, en * 0.15f),
            Emotion.NEUTRAL to s(
                (1f - abs(pitchN - 0.45f) * 2f).coerceIn(0f, 1f) * 0.30f,
                (1f - abs(en - 0.12f) * 6f).coerceIn(0f, 1f) * 0.30f,
                (1f - spd) * 0.20f,
                (1f - hfr) * 0.20f
            )
        )

        val top = scores.maxByOrNull { it.value } ?: return Result(Emotion.NEUTRAL, 0f, scores)
        val second = scores.filterKeys { it != top.key }.maxOfOrNull { it.value } ?: 0f
        val confidence = (top.value - second + top.value * 0.25f).coerceIn(0f, 1f)

        // Slightly raise the bar for non-neutral emotions so the model doesn't
        // flicker between emotions on weak signals (emotion stability tuning).
        val finalEmotion = if (top.value > 0.30f && top.key != Emotion.NEUTRAL) top.key
        else if (top.key == Emotion.NEUTRAL && top.value > 0.34f) Emotion.NEUTRAL
        else Emotion.NEUTRAL
        return Result(finalEmotion, if (finalEmotion == Emotion.NEUTRAL) confidence * 0.5f else confidence, scores)
    }

    /** Aggregate results from many chunks (one full utterance). */
    fun aggregate(results: List<Result>): Result {
        if (results.isEmpty()) return Result(Emotion.NEUTRAL, 0f, emptyMap())
        val sums = mutableMapOf<Emotion, Float>()
        for (r in results) {
            if (r.scores.isEmpty()) continue
            val top = r.scores.maxByOrNull { it.value } ?: continue
            sums[top.key] = (sums[top.key] ?: 0f) + top.value
        }
        val best = sums.maxByOrNull { it.value }
            ?: return Result(Emotion.NEUTRAL, 0f, emptyMap())
        val total = sums.values.sum().coerceAtLeast(1e-6f)
        val confidence = (best.value / total).coerceIn(0f, 1f)
        return Result(best.key, confidence, sums)
    }

    private fun normalizePitch(pitchHz: Float): Float =
        if (pitchHz <= 0f) 0.45f
        else ((pitchHz - 80f) / (400f - 80f)).coerceIn(0f, 1f)
}

package com.jarvis.assistant.model

import androidx.compose.ui.graphics.Color

/**
 * The 7 emotions JARVIS can feel from your voice.
 * Each emotion also carries the audio-synthesis parameters used to make
 * JARVIS *sound* that emotion when speaking (pitch / speed / volume / pause).
 */
enum class Emotion(
    val label: String,
    val emoji: String,
    val colorValue: Long,
    val ttsPitch: Float,
    val ttsRate: Float,
    val ttsVolume: Float,
    val prePauseMs: Long,
    val description: String
) {
    NEUTRAL(
        "Neutral", "😐", 0xFF4FC3F7,
        1.00f, 1.00f, 0.90f, 350,
        "balanced, professional, calm tone"
    ),
    HAPPY(
        "Happy", "😄", 0xFF66BB6A,
        1.18f, 1.12f, 0.95f, 220,
        "brighter pitch, lively speed, warm"
    ),
    SAD(
        "Sad", "😢", 0xFF5C6BC0,
        0.82f, 0.84f, 0.72f, 650,
        "lower pitch, slower, softer, gentle pauses"
    ),
    ANGRY(
        "Angry", "😠", 0xFFEF5350,
        0.92f, 1.22f, 1.00f, 180,
        "tense, faster, firmer and louder"
    ),
    EXCITED(
        "Excited", "🤩", 0xFFFFA726,
        1.30f, 1.30f, 0.98f, 140,
        "high pitch, quick, energetic"
    ),
    CALM(
        "Calm", "😌", 0xFF26A69A,
        0.95f, 0.88f, 0.68f, 550,
        "slow, soft, soothing rhythm"
    ),
    STRESSED(
        "Stressed", "😰", 0xFFAB47BC,
        1.08f, 1.06f, 0.85f, 380,
        "slightly tense, careful, reassuring"
    );

    val color: Color get() = Color(colorValue)

    companion object {
        fun fromName(name: String): Emotion =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NEUTRAL
    }
}

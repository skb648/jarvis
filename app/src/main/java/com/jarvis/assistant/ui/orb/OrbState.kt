package com.jarvis.assistant.ui.orb

import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.ui.theme.*

/**
 * Represents the current cognitive state of the JARVIS brain.
 * Each state maps to a specific color used by the HolographicOrb.
 */
enum class BrainState(val label: String, val color: Color, val description: String) {
    IDLE(
        label = "IDLE",
        color = OrbIdleColor,
        description = "Standing by"
    ),
    LISTENING(
        label = "LISTENING",
        color = OrbListeningColor,
        description = "Listening to you"
    ),
    THINKING(
        label = "THINKING",
        color = OrbThinkingColor,
        description = "Processing request"
    ),
    SPEAKING(
        label = "SPEAKING",
        color = OrbSpeakingColor,
        description = "Speaking response"
    ),
    ERROR(
        label = "ERROR",
        color = OrbErrorColor,
        description = "Something went wrong"
    );

    /** Whether the ripple rings should be visible for this state. */
    val showRipples: Boolean
        get() = this == LISTENING || this == SPEAKING

    /** Whether the orb should pulse more intensely. */
    val intensePulse: Boolean
        get() = this == THINKING || this == LISTENING

    companion object {
        fun fromString(name: String): BrainState {
            return entries.find { it.name.equals(name, ignoreCase = true) } ?: IDLE
        }
    }
}

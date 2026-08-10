package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Iron-Man HUD palette
val JarvisBg = Color(0xFF04060D)
val JarvisPanel = Color(0xFF0A1120)
val JarvisPanelHi = Color(0xFF101B30)
val JarvisCyan = Color(0xFF00E5FF)
val JarvisCyanDim = Color(0xFF0D3B4D)
val JarvisGold = Color(0xFFFFC400)
val JarvisText = Color(0xFFE8F1FF)
val JarvisTextDim = Color(0xFF8CA3C7)
val JarvisRed = Color(0xFFFF5C5C)

val AccentColors = mapOf(
    "cyan" to Color(0xFF00E5FF),
    "gold" to Color(0xFFFFC400),
    "green" to Color(0xFF69F0AE),
    "purple" to Color(0xFFB388FF),
    "red" to Color(0xFFFF5C5C)
)

fun accentColor(name: String): Color = AccentColors[name] ?: JarvisCyan

@Composable
fun JarvisTheme(accent: Color = JarvisCyan, content: @Composable () -> Unit) {
    val colors = darkColorScheme(
        primary = accent,
        secondary = JarvisGold,
        background = JarvisBg,
        surface = JarvisPanel,
        onPrimary = Color(0xFF00202A),
        onSecondary = Color(0xFF332800),
        onBackground = JarvisText,
        onSurface = JarvisText,
        surfaceVariant = JarvisPanelHi,
        onSurfaceVariant = JarvisTextDim,
        error = JarvisRed
    )
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        content = content
    )
}

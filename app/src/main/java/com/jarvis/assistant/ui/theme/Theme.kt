package com.jarvis.assistant.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val JarvisDarkColorScheme = darkColorScheme(
    primary            = JarvisCyan,
    onPrimary          = DeepNavy,
    primaryContainer   = CyanGradientEnd,
    onPrimaryContainer = TextPrimary,

    secondary          = JarvisPurple,
    onSecondary        = TextPrimary,
    secondaryContainer = PurpleGradientEnd,
    onSecondaryContainer = TextPrimary,

    tertiary           = JarvisGreen,
    onTertiary         = DeepNavy,
    tertiaryContainer  = GreenGradientEnd,
    onTertiaryContainer = TextPrimary,

    error              = JarvisRedPink,
    onError            = TextPrimary,
    errorContainer     = Color(0xFF4D0019),
    onErrorContainer   = JarvisRedPink,

    background         = DeepNavy,
    onBackground       = TextPrimary,
    surface            = SurfaceNavy,
    onSurface          = TextPrimary,
    surfaceVariant     = SurfaceNavyLight,
    onSurfaceVariant   = TextSecondary,

    outline            = GlassBorder,
    outlineVariant     = GlassHighlight,

    inverseSurface     = TextPrimary,
    inverseOnSurface   = DeepNavy,
    inversePrimary     = CyanGradientEnd,

    scrim              = Color.Black,
)

@Composable
fun JarvisTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            dynamicDarkColorScheme(LocalContext.current)
        }
        else -> JarvisDarkColorScheme
    }

    // Force dark status/navigation bars
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = DeepNavy.toArgb()
            window.navigationBarColor = DeepNavy.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = JarvisTypography,
        content = content
    )
}

// ─── Glassmorphism Helpers ──────────────────────────────────────────

/**
 * Returns a glassmorphic background color for cards and overlays.
 * Blends the surface color with a white tint at the given [alpha].
 */
fun glassmorphicBackgroundColor(alpha: Float = 0.10f): Color {
    return Color.White.copy(alpha = alpha)
}

/**
 * Returns a glassmorphic border color with configurable [alpha].
 */
fun glassmorphicBorderColor(alpha: Float = 0.20f): Color {
    return Color.White.copy(alpha = alpha)
}

/**
 * Returns a glassmorphic highlight shimmer color.
 */
fun glassmorphicHighlightColor(alpha: Float = 0.05f): Color {
    return Color.White.copy(alpha = alpha)
}

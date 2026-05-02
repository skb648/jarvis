package com.jarvis.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─── Monospace family (orb labels, headlines, titles) ───────────────
val MonospaceFamily = FontFamily.Monospace

// ─── Sans family (body, labels, captions) ───────────────────────────
val SansFamily = FontFamily.SansSerif

val JarvisTypography = Typography(

    // ── Display: Large monospace — orb state labels, hero text ──────
    displayLarge = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ── Headline: Medium monospace — section headers ────────────────
    headlineLarge = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ── Title: Small monospace — card titles, list headers ──────────
    titleLarge = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ── Body: Regular sans — paragraphs, messages ───────────────────
    bodyLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ── Label: Small sans — buttons, chips, badges ──────────────────
    labelLarge = TextStyle(
        fontFamily = SansFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = MonospaceFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    )
)

// ─── Extra Typography Styles ─────────────────────────────────────────
// Additional text styles for premium/polish beyond Material3 defaults.

/** Monospace label with tight letter spacing — used for status tags, version numbers */
val LabelMono = TextStyle(
    fontFamily = MonospaceFamily,
    fontWeight = FontWeight.Medium,
    fontSize = 10.sp,
    lineHeight = 14.sp,
    letterSpacing = 1.5.sp
)

/** Monospace display for large metrics (e.g. battery %, CPU %) */
val MetricDisplay = TextStyle(
    fontFamily = MonospaceFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 40.sp,
    lineHeight = 48.sp,
    letterSpacing = (-0.5).sp
)

/** Section header with wide letter spacing — used for "BATTERY STATUS", "NETWORK", etc. */
val SectionHeader = TextStyle(
    fontFamily = MonospaceFamily,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    lineHeight = 16.sp,
    letterSpacing = 3.sp
)

/** Caption style for tiny annotations and footnotes */
val CaptionMono = TextStyle(
    fontFamily = MonospaceFamily,
    fontWeight = FontWeight.Normal,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 0.5.sp
)

/** Badge/tag style with bold weight and wide tracking */
val BadgeStyle = TextStyle(
    fontFamily = MonospaceFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 9.sp,
    lineHeight = 12.sp,
    letterSpacing = 2.sp
)

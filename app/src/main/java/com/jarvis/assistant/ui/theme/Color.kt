package com.jarvis.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Primary Palette ────────────────────────────────────────────────
val JarvisCyan       = Color(0xFF00D4FF)
val JarvisPurple     = Color(0xFF7B2FFF)
val JarvisGreen      = Color(0xFF00FFB2)
val JarvisRedPink    = Color(0xFFFF4D6A)

// ─── Background / Surface ───────────────────────────────────────────
val DeepNavy         = Color(0xFF0A0E21)
val SurfaceNavy      = Color(0xFF111633)
val SurfaceNavyLight = Color(0xFF1A2040)

// ─── Orb State Colors ───────────────────────────────────────────────
val OrbIdleColor     = JarvisCyan
val OrbListeningColor = JarvisGreen
val OrbThinkingColor = JarvisPurple
val OrbSpeakingColor = Color(0xFF00FFD4)   // green-cyan blend
val OrbErrorColor    = JarvisRedPink

// ─── Glassmorphism Overlay Colors (with alpha) ──────────────────────
val GlassBackground  = Color(0x1AFFFFFF)   // 10 % white
val GlassBorder      = Color(0x33FFFFFF)   // 20 % white
val GlassHighlight   = Color(0x0DFFFFFF)   //  5 % white
val GlassOverlay     = Color(0x26111133)   // 15 % surface navy

// ─── Text ───────────────────────────────────────────────────────────
val TextPrimary      = Color(0xFFF0F0FF)
val TextSecondary    = Color(0xFF8888AA)
val TextTertiary     = Color(0xFF555577)

// ─── Semantic ───────────────────────────────────────────────────────
val SuccessGreen     = Color(0xFF00E676)
val WarningAmber     = Color(0xFFFFAB00)
val ErrorRed         = JarvisRedPink

// ─── Gradient endpoints ────────────────────────────────────────────
val CyanGradientEnd  = Color(0xFF0088CC)
val PurpleGradientEnd = Color(0xFF5500CC)
val GreenGradientEnd = Color(0xFF009966)

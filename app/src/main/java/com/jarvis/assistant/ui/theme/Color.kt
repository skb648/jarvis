package com.jarvis.assistant.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Primary Palette ────────────────────────────────────────────────
val JarvisCyan       = Color(0xFF00D4FF)
val JarvisPurple     = Color(0xFF7B2FFF)
val JarvisGreen      = Color(0xFF00FFB2)
val JarvisRedPink    = Color(0xFFFF4D6A)

// ─── Premium Accent Colors ──────────────────────────────────────────
val JarvisGold       = Color(0xFFFFD700)      // Premium accents, featured highlights
val JarvisOrange     = Color(0xFFFF6B35)      // Warnings/highlights, energy indicators

// ─── Background / Surface ───────────────────────────────────────────
val DeepNavy         = Color(0xFF0A0E21)
val SurfaceNavy      = Color(0xFF111633)
val SurfaceNavyLight = Color(0xFF1A2040)
val SurfaceNavyElevated = Color(0xFF222850)   // Elevated surface for modals/popups
val SurfaceNavyDeep  = Color(0xFF080C1A)       // Deeper than DeepNavy for contrast

// ─── Gradient Endpoints ─────────────────────────────────────────────
val GradientStart    = Color(0xFF0A0E21)       // Background gradient start (top)
val GradientEnd      = Color(0xFF1A1060)       // Background gradient end (bottom)
val GradientMid      = Color(0xFF0F1840)       // Mid-point for multi-stop gradients

// ─── Glassmorphism Surface Colors ────────────────────────────────────
val SurfaceGlass     = Color(0x0DFFFFFF)       // 5% white — ultra-subtle glass overlay
val GlassBackground  = Color(0x1AFFFFFF)       // 10% white
val GlassBorder      = Color(0x33FFFFFF)       // 20% white
val GlassHighlight   = Color(0x0DFFFFFF)       // 5% white
val GlassOverlay     = Color(0x26111133)       // 15% surface navy

// ─── Orb State Colors ───────────────────────────────────────────────
val OrbIdleColor     = JarvisCyan
val OrbListeningColor = JarvisGreen
val OrbThinkingColor = JarvisPurple
val OrbSpeakingColor = Color(0xFF00FFD4)       // green-cyan blend
val OrbErrorColor    = JarvisRedPink

// ─── Text ───────────────────────────────────────────────────────────
val TextPrimary      = Color(0xFFF0F0FF)
val TextSecondary    = Color(0xFF8888AA)
val TextTertiary     = Color(0xFF555577)
val TextDisabled     = Color(0xFF333355)       // Disabled/hint text

// ─── Semantic ───────────────────────────────────────────────────────
val SuccessGreen     = Color(0xFF00E676)
val WarningAmber     = Color(0xFFFFAB00)
val ErrorRed         = JarvisRedPink
val InfoBlue         = Color(0xFF4488FF)        // Informational accent

// ─── Gradient Endpoints (branded) ──────────────────────────────────
val CyanGradientEnd  = Color(0xFF0088CC)
val PurpleGradientEnd = Color(0xFF5500CC)
val GreenGradientEnd = Color(0xFF009966)
val GoldGradientEnd  = Color(0xFFCC9900)        // Darker gold for gradient endpoints
val OrangeGradientEnd = Color(0xFFCC4400)        // Darker orange for gradient endpoints

// ─── Chart / Data Visualization Colors ──────────────────────────────
val ChartCyan        = Color(0xFF00D4FF)
val ChartPurple      = Color(0xFF9B5FFF)
val ChartGreen       = Color(0xFF00E676)
val ChartGold        = Color(0xFFFFD700)
val ChartRed         = Color(0xFFFF4D6A)

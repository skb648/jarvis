package com.jarvis.assistant.ui.orb

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.*
import kotlin.math.sin

/**
 * HolographicOrb — The star visual component of JARVIS.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIX (v6): AMPLITUDE-REACTIVE SCALING
 *
 * The orb now PROPERLY reacts to audio amplitude:
 * - Orb RADIUS scales with amplitude (1.0x idle → 1.3x loud)
 * - Glow RADIUS expands with amplitude
 * - Glow ALPHA increases with amplitude (brighter when speaking)
 * - Pulse SPEED increases with amplitude (faster breathing when active)
 * - Ripple rings appear during LISTENING and SPEAKING states
 *
 * Previously, amplitude only affected alpha slightly. Now it drives
 * the full visual response, just like the JarvisMainScreen orb.
 * ═══════════════════════════════════════════════════════════════════════
 */
@Composable
fun HolographicOrb(
    brainState: BrainState,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    amplitude: Float = 0f        // 0..1 from audio level — scales glow intensity
) {
    val orbColor = brainState.color

    // ── Smoothed amplitude ──────────────────────────────────────────
    val amp by animateFloatAsState(
        targetValue = amplitude.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "orb-amplitude"
    )

    // ── Infinite transitions ────────────────────────────────────────
    val infinite = rememberInfiniteTransition(label = "orb-infinite")

    // Layer 1: outer glow pulse — speed increases with amplitude
    val glowDuration = (2000 + (1f - amp) * 1000).toInt().coerceIn(800, 3000)
    val glowPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = glowDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow-pulse"
    )

    // Layer 2: ripple expansion (1.5 s)
    val rippleProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ripple-progress"
    )

    // Layer 3: main orb rotation (8 s)
    val orbRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orb-rotation"
    )

    // Layer 4: orbital ring rotation (8 s, opposite)
    val ringRotation by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ring-rotation"
    )

    // Core pulse — faster when speaking
    val corePulseDuration = (600 + (1f - amp) * 800).toInt().coerceIn(300, 1400)
    val corePulse by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = corePulseDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core-pulse"
    )

    // Intensity multiplier driven by amplitude (calm → energised)
    val intensity = 0.7f + amp * 0.3f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ── Canvas: all layers ────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f

            // ═══ AMPLITUDE-REACTIVE ORB RADIUS ═══════════════════════════
            // The orb GROWS when the user speaks or when JARVIS speaks.
            // Scale factor: 0.55 (idle) to 0.72 (loud voice)
            val orbRadius = baseRadius * (0.55f + amp * 0.17f)
            val safeOrbRadius = orbRadius.coerceAtLeast(1f)

            // ═══ LAYER 1 — Outer Glow (AMPLITUDE REACTIVE) ═══════════════
            // Glow expands and brightens with voice amplitude
            val glowRadius = (orbRadius * (1.3f + amp * 0.5f + glowPulse * 0.3f) * intensity).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = (0.25f + amp * 0.15f) * glowPulse * intensity),
                        orbColor.copy(alpha = (0.08f + amp * 0.07f) * glowPulse * intensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // Secondary soft bloom
            val bloomRadius = (baseRadius * (0.85f + amp * 0.15f)).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.12f * glowPulse + amp * 0.1f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = bloomRadius
                ),
                radius = bloomRadius,
                center = center
            )

            // ═══ LAYER 2 — Ripple Rings ═══════════════════════════════════
            if (brainState.showRipples) {
                for (i in 0..2) {
                    val phase = (rippleProgress + i * 0.33f) % 1f
                    val rippleRadius = orbRadius * (1f + phase * 0.9f)
                    val rippleAlpha = (1f - phase) * 0.5f * intensity * (0.5f + amp)
                    drawCircle(
                        color = orbColor.copy(alpha = rippleAlpha),
                        radius = rippleRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * (1f - phase * 0.6f))
                    )
                }
            }

            // ═══ LAYER 3 — Main Orb ═══════════════════════════════════════
            // Rotating sweep gradient
            rotate(orbRotation, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.9f + amp * 0.1f),
                            orbColor.copy(alpha = 0.15f),
                            orbColor.copy(alpha = 0.1f),
                            orbColor.copy(alpha = 0.5f + amp * 0.2f),
                            orbColor.copy(alpha = 0.9f + amp * 0.1f)
                        ),
                        center = center
                    ),
                    radius = safeOrbRadius,
                    center = center
                )
            }

            // Inner radial for 3-D depth
            val innerHighlight = Offset(
                center.x - orbRadius * 0.25f,
                center.y - orbRadius * 0.25f
            )
            val depthRadius = (orbRadius * 0.8f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.25f + amp * 0.1f),
                        orbColor.copy(alpha = 0.4f),
                        Color.Transparent
                    ),
                    center = innerHighlight,
                    radius = depthRadius
                ),
                radius = safeOrbRadius,
                center = center
            )

            // Specular highlight — small bright spot
            val specCenter = Offset(center.x - orbRadius * 0.3f, center.y - orbRadius * 0.3f)
            val specRadius = (orbRadius * 0.35f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.45f + amp * 0.15f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    center = specCenter,
                    radius = specRadius
                ),
                radius = specRadius,
                center = specCenter
            )

            // ═══ INNER PULSING CORE ═══════════════════════════════════════
            val coreR = orbRadius * 0.20f * (0.8f + corePulse * 0.35f + amp * 0.45f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f),
                        orbColor.copy(alpha = 0.75f + amp * 0.25f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = coreR * 2.2f
                ),
                radius = coreR,
                center = center
            )

            // ═══ LAYER 4 — Orbital Ring ═══════════════════════════════════
            val ringRadius = orbRadius * 1.22f
            val arcCount = 24
            val arcSweep = 8f
            val gapSweep = 360f / arcCount - arcSweep

            rotate(ringRotation, pivot = center) {
                for (i in 0 until arcCount) {
                    val startAngle = i * (arcSweep + gapSweep)
                    val arcAlpha = (0.3f + 0.3f * sin(Math.toRadians((startAngle + ringRotation) * 2.0)))
                        .toFloat() * intensity
                    drawArc(
                        color = orbColor.copy(alpha = arcAlpha.coerceIn(0.15f, 0.7f)),
                        startAngle = startAngle,
                        sweepAngle = arcSweep,
                        useCenter = false,
                        topLeft = Offset(center.x - ringRadius, center.y - ringRadius),
                        size = Size(ringRadius * 2, ringRadius * 2),
                        style = Stroke(width = (1.5f + amp).dp.toPx())
                    )
                }
            }

            // Thin orbit circle track
            drawCircle(
                color = orbColor.copy(alpha = 0.08f + amp * 0.05f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = (0.5f + amp * 0.5f).dp.toPx())
            )
        }

        // ── Centre Icon ─────────────────────────────────────────────
        val iconTint = orbColor.copy(alpha = 0.9f)
        when (brainState) {
            BrainState.IDLE      -> Icon(Icons.Filled.AutoAwesome, contentDescription = "Idle", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.LISTENING -> Icon(Icons.Filled.Mic, contentDescription = "Listening", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.THINKING  -> Icon(Icons.Filled.Psychology, contentDescription = "Thinking", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.SPEAKING  -> Icon(Icons.Filled.VolumeUp, contentDescription = "Speaking", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.ERROR     -> Icon(Icons.Filled.Error, contentDescription = "Error", tint = iconTint, modifier = Modifier.size(size * 0.18f))
        }
    }
}

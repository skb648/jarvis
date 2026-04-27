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
import androidx.compose.material3.MaterialTheme
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
 * Four animation layers running via [rememberInfiniteTransition]:
 *  1. Outer glow   — pulsing radial gradient (2 s cycle, reverse)
 *  2. Ripple rings  — 3 expanding circles (LISTENING / SPEAKING only, 1.5 s cycle)
 *  3. Main orb      — rotating sweep gradient (8 s) + inner radial for 3-D depth
 *  4. Orbital ring  — 24 dashed arc segments rotating (8 s, opposite dir)
 *
 * A centre icon changes based on [BrainState].
 */
@Composable
fun HolographicOrb(
    brainState: BrainState,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    amplitude: Float = 0f        // 0‥1 from audio level — scales glow intensity
) {
    val orbColor = brainState.color

    // ── Infinite transitions ────────────────────────────────────────
    val infinite = rememberInfiniteTransition(label = "orb-infinite")

    // Layer 1: outer glow pulse (2 s)
    val glowPulse by infinite.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = EaseInOutSine),
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

    // Intensity multiplier driven by amplitude (calm → energised)
    val intensity = 0.7f + amplitude * 0.3f

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ── Canvas: all four layers ─────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f
            val orbRadius = baseRadius * 0.55f
            val safeOrbRadius = orbRadius.coerceAtLeast(1f)

            // ═══ LAYER 1 — Outer Glow ═══════════════════════════════
            val glowRadius = (orbRadius * (1.2f + glowPulse * 0.35f) * intensity).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.25f * glowPulse * intensity),
                        orbColor.copy(alpha = 0.08f * glowPulse * intensity),
                        Color.Transparent
                    ),
                    center = center,
                    radius = glowRadius
                ),
                radius = glowRadius,
                center = center
            )

            // Secondary soft bloom
            val bloomRadius = (baseRadius * 0.9f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = 0.12f * glowPulse),
                        Color.Transparent
                    ),
                    center = center,
                    radius = bloomRadius
                ),
                radius = bloomRadius,
                center = center
            )

            // ═══ LAYER 2 — Ripple Rings ═════════════════════════════
            if (brainState.showRipples) {
                for (i in 0..2) {
                    val phase = (rippleProgress + i * 0.33f) % 1f
                    val rippleRadius = orbRadius * (1f + phase * 0.9f)
                    val rippleAlpha = (1f - phase) * 0.5f * intensity
                    drawCircle(
                        color = orbColor.copy(alpha = rippleAlpha),
                        radius = rippleRadius,
                        center = center,
                        style = Stroke(width = 2.dp.toPx() * (1f - phase * 0.6f))
                    )
                }
            }

            // ═══ LAYER 3 — Main Orb ══════════════════════════════════
            // Rotating sweep gradient
            rotate(orbRotation, pivot = center) {
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            orbColor.copy(alpha = 0.9f),
                            orbColor.copy(alpha = 0.15f),
                            orbColor.copy(alpha = 0.1f),
                            orbColor.copy(alpha = 0.5f),
                            orbColor.copy(alpha = 0.9f)
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
                        Color.White.copy(alpha = 0.25f),
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
                        Color.White.copy(alpha = 0.45f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    center = specCenter,
                    radius = specRadius
                ),
                radius = specRadius,
                center = specCenter
            )

            // ═══ LAYER 4 — Orbital Ring ══════════════════════════════
            val ringRadius = orbRadius * 1.22f
            val arcCount = 24
            val arcSweep = 8f      // degrees per segment
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
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }
            }

            // Thin orbit circle track
            drawCircle(
                color = orbColor.copy(alpha = 0.08f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = 0.5.dp.toPx())
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

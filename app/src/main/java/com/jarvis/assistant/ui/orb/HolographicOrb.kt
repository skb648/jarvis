package com.jarvis.assistant.ui.orb

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.NotificationsActive
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
import kotlin.math.cos
import kotlin.math.PI

/**
 * HolographicOrb — The star visual component of JARVIS.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * v8: 3D BUBBLE / JELLY SPHERE EFFECT
 *
 * Complete visual rewrite to create a Siri/Bixby-like 3D glass orb:
 *
 *  1. 3D Sphere Shading    — Offset radial gradients (bright top-left,
 *     dark bottom-right) simulate a lit 3D sphere
 *  2. Fresnel Edge Glow    — Ring gradient (dark center, bright edge)
 *     makes the orb look like glass
 *  3. Internal Caustics    — 3 animated bright spots orbiting inside,
 *     simulating light refracting through glass
 *  4. Specular Highlight   — Elongated white oval at top-left for
 *     realistic light-source reflection
 *  5. Bottom Shadow        — Dark gradient at bottom (ambient occlusion)
 *     creates the illusion of the orb sitting on a surface
 *  6. Breathing Animation  — Sine-wave radius modulation (3-4s idle,
 *     faster when listening/processing)
 *  7. WAKE Animation       — Quick 30% inflate, bright ring pulse
 *     outward, caustics speed up dramatically
 *  8. Particle Trail       — 6-8 glowing dots orbiting at different
 *     distances and speeds; more visible when active
 *
 * Preserves all v7 functionality: brainState, amplitude, wakeFlash,
 * center icon, WAKE flash animations.
 * ═══════════════════════════════════════════════════════════════════════
 */
@Composable
fun HolographicOrb(
    brainState: BrainState,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    amplitude: Float = 0f,        // 0..1 from audio level — scales glow intensity
    wakeFlash: Boolean = false     // true when wake word just detected
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

    // ── Wake flash animation: expanding ring progress (0→1 over ~800ms) ──
    val wakeRingProgress by animateFloatAsState(
        targetValue = if (wakeFlash) 1f else 0f,
        animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing),
        label = "wake-ring-progress"
    )

    // ── Wake flash overlay alpha (bright white flash that fades over 500ms) ──
    val wakeFlashAlpha by animateFloatAsState(
        targetValue = if (wakeFlash) 0f else 0f,
        animationSpec = tween(durationMillis = 500, easing = EaseOutCubic),
        label = "wake-flash-alpha"
    )

    // Track the flash alpha separately — starts at 0.85 when wakeFlash becomes true
    var flashAlphaTarget by remember { mutableFloatStateOf(0f) }
    val flashAlpha by animateFloatAsState(
        targetValue = flashAlphaTarget,
        animationSpec = tween(durationMillis = 500, easing = EaseOutCubic),
        label = "wake-flash-overlay"
    )

    LaunchedEffect(wakeFlash) {
        if (wakeFlash) {
            flashAlphaTarget = 0.85f
            // Immediately start fading after a brief hold
            flashAlphaTarget = 0f
        }
    }

    // ── Wake scale animation: 1.0x → 1.4x on wake, settle to 1.15x for LISTENING ──
    val wakeScaleTarget = when {
        wakeFlash -> 1.4f
        brainState == BrainState.LISTENING -> 1.15f
        else -> 1.0f
    }
    val wakeScale by animateFloatAsState(
        targetValue = wakeScaleTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "wake-scale"
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

    // Layer 2: ripple expansion (1.5 s, or faster for WAKE)
    val rippleDuration = if (brainState == BrainState.WAKE) 600 else 1500
    val rippleProgress by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = rippleDuration, easing = LinearOutSlowInEasing),
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

    // Core pulse — faster when speaking or in WAKE state
    val corePulseDuration = if (brainState == BrainState.WAKE) {
        150
    } else {
        (600 + (1f - amp) * 800).toInt().coerceIn(300, 1400)
    }
    val corePulse by infinite.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = corePulseDuration, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "core-pulse"
    )

    // ═══════════════════════════════════════════════════════════════════
    // NEW v8 ANIMATIONS: Breathing, Caustics, Particles
    // ═══════════════════════════════════════════════════════════════════

    // ── Breathing animation: slow sine wave for idle, faster when active ──
    // 3-4 second cycle when idle, 1.5-2s when listening/processing
    val breathDuration = when (brainState) {
        BrainState.IDLE -> 3500
        BrainState.WAKE -> 800
        BrainState.LISTENING -> 1500
        BrainState.THINKING -> 1200
        BrainState.SPEAKING -> 1000
        BrainState.ERROR -> 2000
    }
    val breathPhase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = (2f * PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = breathDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "breath-phase"
    )
    // Breathing modulates radius ±3-5%
    val breathScale = 1f + 0.04f * sin(breathPhase)

    // ── Caustic rotation: 3 bright spots orbiting inside the sphere ──
    // Speed increases dramatically for WAKE state
    val causticDuration = when (brainState) {
        BrainState.WAKE -> 800
        BrainState.LISTENING -> 3000
        BrainState.THINKING -> 2000
        BrainState.SPEAKING -> 1500
        else -> 6000
    }
    val causticAngle1 by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = causticDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "caustic-angle-1"
    )
    val causticAngle2 by infinite.animateFloat(
        initialValue = 120f,
        targetValue = 480f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (causticDuration * 1.3f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "caustic-angle-2"
    )
    val causticAngle3 by infinite.animateFloat(
        initialValue = 240f,
        targetValue = 600f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = (causticDuration * 0.8f).toInt(), easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "caustic-angle-3"
    )

    // ── Particle trail: 8 orbiting energy particles ──
    val particleBaseDuration = when (brainState) {
        BrainState.WAKE -> 1200
        BrainState.LISTENING -> 3000
        BrainState.THINKING -> 2500
        BrainState.SPEAKING -> 1800
        else -> 5000
    }
    val particleAngles = remember {
        List(8) { it * 45f }  // Start evenly distributed
    }
    val particleAnglesAnimated = particleAngles.mapIndexed { index, startAngle ->
        val speed = 1f + index * 0.15f  // Each particle at slightly different speed
        infinite.animateFloat(
            initialValue = startAngle,
            targetValue = startAngle + 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (particleBaseDuration * speed).toInt(),
                    easing = LinearEasing
                ),
                repeatMode = RepeatMode.Restart
            ),
            label = "particle-angle-$index"
        )
    }
    val particleAngleValues = particleAnglesAnimated.map { it.value }

    // Intensity multiplier driven by amplitude (calm → energised)
    val intensity = if (brainState == BrainState.WAKE) 1.0f else (0.7f + amp * 0.3f)

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        // ── Canvas: all layers ────────────────────────────────────────
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(this.size.width / 2f, this.size.height / 2f)
            val baseRadius = this.size.minDimension / 2f

            // ═══ BREATHING + AMPLITUDE + WAKE REACTIVE ORB RADIUS ═══════════
            // Breathing adds ±4% oscillation
            // Amplitude adds 0-17% expansion
            // Wake scale multiplies on top (1.0x → 1.4x)
            val orbRadius = baseRadius * (0.55f + amp * 0.17f) * breathScale * wakeScale
            val safeOrbRadius = orbRadius.coerceAtLeast(1f)

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 5: BOTTOM SHADOW / AMBIENT OCCLUSION
            //
            // A subtle dark gradient at the bottom of the sphere where it
            // meets the "surface". Creates the illusion of the orb sitting
            // on something, adding grounding and 3D realism.
            // ═══════════════════════════════════════════════════════════════════
            val shadowOffset = orbRadius * 0.65f
            val shadowCenter = Offset(center.x, center.y + shadowOffset)
            val shadowRadius = orbRadius * 1.15f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.35f + amp * 0.1f),
                        Color.Black.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    center = shadowCenter,
                    radius = shadowRadius
                ),
                radius = shadowRadius,
                center = shadowCenter
            )

            // ═══ WAKE FLASH: Expanding ring burst ════════════════════════════
            // When wakeFlash is true, draw an expanding ring from core outward
            if (wakeFlash && wakeRingProgress > 0f) {
                val burstRadius = safeOrbRadius * (1f + wakeRingProgress * 2.5f)
                val burstAlpha = (1f - wakeRingProgress) * 0.8f
                val burstStroke = (4f * (1f - wakeRingProgress)).coerceAtLeast(0.5f)

                // Outer expanding ring
                drawCircle(
                    color = Color(0xFF00FFFF).copy(alpha = burstAlpha),
                    radius = burstRadius,
                    center = center,
                    style = Stroke(width = burstStroke.dp.toPx())
                )

                // Second ring slightly behind
                val burst2Progress = (wakeRingProgress - 0.15f).coerceIn(0f, 1f)
                if (burst2Progress > 0f) {
                    val burst2Radius = safeOrbRadius * (1f + burst2Progress * 2.0f)
                    val burst2Alpha = (1f - burst2Progress) * 0.5f
                    drawCircle(
                        color = Color.White.copy(alpha = burst2Alpha),
                        radius = burst2Radius,
                        center = center,
                        style = Stroke(width = (burstStroke * 0.6f).dp.toPx())
                    )
                }

                // Third ring — thin outer echo
                val burst3Progress = (wakeRingProgress - 0.3f).coerceIn(0f, 1f)
                if (burst3Progress > 0f) {
                    val burst3Radius = safeOrbRadius * (1f + burst3Progress * 3.0f)
                    val burst3Alpha = (1f - burst3Progress) * 0.3f
                    drawCircle(
                        color = Color(0xFF00FFFF).copy(alpha = burst3Alpha),
                        radius = burst3Radius,
                        center = center,
                        style = Stroke(width = (burstStroke * 0.3f).dp.toPx())
                    )
                }
            }

            // ═══ WAKE FLASH: Bright white overlay that fades over 500ms ════
            if (flashAlpha > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = flashAlpha),
                            Color.White.copy(alpha = flashAlpha * 0.5f),
                            Color(0xFF00FFFF).copy(alpha = flashAlpha * 0.3f),
                            Color.Transparent
                        ),
                        center = center,
                        radius = safeOrbRadius * 1.5f
                    ),
                    radius = safeOrbRadius * 1.5f,
                    center = center
                )
            }

            // ═══ LAYER 1 — Outer Glow (AMPLITUDE REACTIVE) ═══════════════
            // Glow expands and brightens with voice amplitude
            val glowRadius = (orbRadius * (1.3f + amp * 0.5f + glowPulse * 0.3f) * intensity).coerceAtLeast(1f)

            // Extra bright glow for WAKE state
            val wakeGlowBoost = if (brainState == BrainState.WAKE) 0.3f else 0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        orbColor.copy(alpha = (0.25f + amp * 0.15f + wakeGlowBoost) * glowPulse * intensity),
                        orbColor.copy(alpha = (0.08f + amp * 0.07f + wakeGlowBoost * 0.5f) * glowPulse * intensity),
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
                        orbColor.copy(alpha = 0.12f * glowPulse + amp * 0.1f + wakeGlowBoost),
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
                // More rapid/extra rings for WAKE state
                val ringCount = if (brainState == BrainState.WAKE) 5 else 2
                for (i in 0..ringCount) {
                    val phaseOffset = if (brainState == BrainState.WAKE) 0.2f else 0.33f
                    val phase = (rippleProgress + i * phaseOffset) % 1f
                    val rippleRadius = orbRadius * (1f + phase * 0.9f)
                    val baseAlpha = if (brainState == BrainState.WAKE) 0.7f else 0.5f
                    val rippleAlpha = (1f - phase) * baseAlpha * intensity * (0.5f + amp)
                    val strokeWidth = if (brainState == BrainState.WAKE) 3.dp else 2.dp
                    drawCircle(
                        color = orbColor.copy(alpha = rippleAlpha),
                        radius = rippleRadius,
                        center = center,
                        style = Stroke(width = strokeWidth.toPx() * (1f - phase * 0.6f))
                    )
                }
            }

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 1: 3D SPHERE SHADING — Main body with offset radial gradient
            //
            // The primary orb body uses a radial gradient whose center is
            // offset to the upper-left. This creates the "lit from above"
            // look that makes a flat circle appear as a 3D sphere.
            // Bright highlight at top-left, dark shadow at bottom-right.
            // ═══════════════════════════════════════════════════════════════════

            // Rotating sweep gradient for energy effect
            rotate(orbRotation, pivot = center) {
                val sweepAlpha = if (brainState == BrainState.WAKE) 1f else (0.9f + amp * 0.1f)
                val sweepMidAlpha = if (brainState == BrainState.WAKE) 0.4f else 0.15f
                drawCircle(
                    brush = Brush.sweepGradient(
                        colors = listOf(
                            orbColor.copy(alpha = sweepAlpha),
                            orbColor.copy(alpha = sweepMidAlpha),
                            orbColor.copy(alpha = 0.1f),
                            orbColor.copy(alpha = 0.5f + amp * 0.2f),
                            orbColor.copy(alpha = sweepAlpha)
                        ),
                        center = center
                    ),
                    radius = safeOrbRadius,
                    center = center
                )
            }

            // 3D depth: offset radial gradient — highlight upper-left, shadow lower-right
            // This is the KEY gradient that creates the 3D sphere illusion
            val hlCenter = Offset(
                center.x - orbRadius * 0.30f,  // Shift left for top-left highlight
                center.y - orbRadius * 0.30f    // Shift up for top-left highlight
            )
            val depthRadius = (orbRadius * 1.6f).coerceAtLeast(1f)
            val depthAlpha = if (brainState == BrainState.WAKE) 0.55f else (0.30f + amp * 0.15f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = depthAlpha * 1.2f),           // Bright highlight
                        0.15f to Color.White.copy(alpha = depthAlpha * 0.6f),           // Soft transition
                        0.35f to orbColor.copy(alpha = 0.7f),                           // Main color
                        0.65f to orbColor.copy(alpha = 0.4f),                           // Darker
                        0.85f to orbColor.copy(alpha = 0.15f),                          // Shadow area
                        1.00f to Color.Black.copy(alpha = 0.25f)                        // Deep shadow edge
                    ),
                    center = hlCenter,
                    radius = depthRadius
                ),
                radius = safeOrbRadius,
                center = center
            )

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 2: FRESNEL EDGE GLOW
            //
            // Real glass spheres have brighter edges (Fresnel effect).
            // A ring-shaped gradient: dark center, bright edge.
            // This is what makes glass orbs look like glass.
            // ═══════════════════════════════════════════════════════════════════
            val fresnelAlpha = if (brainState == BrainState.WAKE) 0.6f else (0.25f + amp * 0.2f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.Transparent,                                      // Center is transparent
                        0.50f to Color.Transparent,                                      // Still transparent halfway
                        0.72f to orbColor.copy(alpha = fresnelAlpha * 0.15f),            // Barely visible
                        0.85f to orbColor.copy(alpha = fresnelAlpha * 0.5f),             // Growing
                        0.93f to orbColor.copy(alpha = fresnelAlpha * 0.9f),             // Bright ring
                        0.98f to Color.White.copy(alpha = fresnelAlpha * 0.4f),          // White edge highlight
                        1.00f to orbColor.copy(alpha = fresnelAlpha * 0.3f)              // Slight falloff at edge
                    ),
                    center = center,
                    radius = safeOrbRadius
                ),
                radius = safeOrbRadius,
                center = center
            )

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 3: INTERNAL REFRACTION CAUSTICS
            //
            // 3 bright spots that orbit inside the sphere, simulating
            // light refracting through glass. They rotate at different
            // speeds and distances from center.
            // ═══════════════════════════════════════════════════════════════════
            val causticAlpha = when (brainState) {
                BrainState.WAKE -> 0.55f
                BrainState.LISTENING -> 0.35f
                BrainState.THINKING -> 0.4f
                BrainState.SPEAKING -> 0.45f
                else -> 0.2f
            } + amp * 0.15f

            // Caustic 1 — orbiting at 35% radius
            val c1AngleRad = Math.toRadians(causticAngle1.toDouble())
            val c1Dist = orbRadius * 0.35f
            val c1Center = Offset(
                center.x + (c1Dist * cos(c1AngleRad)).toFloat(),
                center.y + (c1Dist * sin(c1AngleRad)).toFloat()
            )
            val c1Radius = (orbRadius * 0.18f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = causticAlpha * 0.8f),
                        orbColor.copy(alpha = causticAlpha * 0.5f),
                        Color.Transparent
                    ),
                    center = c1Center,
                    radius = c1Radius * 2f
                ),
                radius = c1Radius,
                center = c1Center
            )

            // Caustic 2 — orbiting at 25% radius, different speed
            val c2AngleRad = Math.toRadians(causticAngle2.toDouble())
            val c2Dist = orbRadius * 0.25f
            val c2Center = Offset(
                center.x + (c2Dist * cos(c2AngleRad)).toFloat(),
                center.y + (c2Dist * sin(c2AngleRad)).toFloat()
            )
            val c2Radius = (orbRadius * 0.12f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = causticAlpha * 0.6f),
                        orbColor.copy(alpha = causticAlpha * 0.4f),
                        Color.Transparent
                    ),
                    center = c2Center,
                    radius = c2Radius * 2f
                ),
                radius = c2Radius,
                center = c2Center
            )

            // Caustic 3 — orbiting at 40% radius, fastest
            val c3AngleRad = Math.toRadians(causticAngle3.toDouble())
            val c3Dist = orbRadius * 0.40f
            val c3Center = Offset(
                center.x + (c3Dist * cos(c3AngleRad)).toFloat(),
                center.y + (c3Dist * sin(c3AngleRad)).toFloat()
            )
            val c3Radius = (orbRadius * 0.10f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = causticAlpha * 0.5f),
                        orbColor.copy(alpha = causticAlpha * 0.3f),
                        Color.Transparent
                    ),
                    center = c3Center,
                    radius = c3Radius * 2f
                ),
                radius = c3Radius,
                center = c3Center
            )

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 4: SURFACE SPECULAR HIGHLIGHT
            //
            // An elongated white oval at the top-left that represents
            // the light source reflection. NOT a circle — slightly
            // stretched horizontally to simulate a window/light reflection
            // on a curved surface. This is the most important element
            // for 3D realism.
            // ═══════════════════════════════════════════════════════════════════
            val specCenter = Offset(
                center.x - orbRadius * 0.32f,
                center.y - orbRadius * 0.32f
            )
            val specRadiusX = (orbRadius * 0.30f).coerceAtLeast(1f)  // Wider (horizontal stretch)
            val specRadiusY = (orbRadius * 0.20f).coerceAtLeast(1f)  // Narrower (vertical)
            val specAlpha = if (brainState == BrainState.WAKE) 0.85f else (0.55f + amp * 0.15f)

            // Draw elongated specular highlight using an oval (scaled circle)
            // We use drawOval via drawRoundRect with large corner radius
            drawOval(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = specAlpha),
                        Color.White.copy(alpha = specAlpha * 0.6f),
                        Color.White.copy(alpha = 0.0f)
                    ),
                    center = Offset(specRadiusX, specRadiusY),  // Relative to oval center
                    radius = specRadiusX
                ),
                topLeft = Offset(specCenter.x - specRadiusX, specCenter.y - specRadiusY),
                size = Size(specRadiusX * 2f, specRadiusY * 2f)
            )

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 5 (continued): BOTTOM AMBIENT OCCLUSION SHADOW
            //
            // A subtle dark gradient at the bottom of the sphere itself
            // (not just below it). This creates the illusion that the
            // bottom of the sphere is in shadow from the overhead light.
            // ═══════════════════════════════════════════════════════════════════
            val aoCenter = Offset(
                center.x + orbRadius * 0.15f,
                center.y + orbRadius * 0.35f
            )
            val aoRadius = (orbRadius * 0.7f).coerceAtLeast(1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.20f + amp * 0.05f),
                        Color.Black.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    center = aoCenter,
                    radius = aoRadius
                ),
                radius = safeOrbRadius,
                center = center
            )

            // ═══ INNER PULSING CORE ═══════════════════════════════════════
            // Extra bright core for WAKE state
            val coreBrightness = if (brainState == BrainState.WAKE) 1.0f else (0.75f + amp * 0.25f)
            val coreScale = if (brainState == BrainState.WAKE) 1.6f else 1f
            val coreR = orbRadius * 0.20f * (0.8f + corePulse * 0.35f + amp * 0.45f) * coreScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.95f * coreBrightness),
                        orbColor.copy(alpha = coreBrightness),
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
                    val arcBoost = if (brainState == BrainState.WAKE) 0.2f else 0f
                    drawArc(
                        color = orbColor.copy(alpha = (arcAlpha + arcBoost).coerceIn(0.15f, 0.9f)),
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
                color = orbColor.copy(alpha = 0.08f + amp * 0.05f + if (brainState == BrainState.WAKE) 0.1f else 0f),
                radius = ringRadius,
                center = center,
                style = Stroke(width = (0.5f + amp * 0.5f).dp.toPx())
            )

            // ═══════════════════════════════════════════════════════════════════
            // FEATURE 8: PARTICLE TRAIL SYSTEM
            //
            // 8 small glowing dots orbiting at different distances and
            // speeds. These represent energy particles. More visible
            // when active (LISTENING/THINKING/SPEAKING), dim when idle.
            // ═══════════════════════════════════════════════════════════════════
            val particleVisibility = when (brainState) {
                BrainState.WAKE -> 1.0f
                BrainState.LISTENING -> 0.7f
                BrainState.THINKING -> 0.8f
                BrainState.SPEAKING -> 0.85f
                BrainState.ERROR -> 0.3f
                else -> 0.15f + amp * 0.4f   // Dim when idle, brighten with voice
            }

            val particleDistances = listOf(
                orbRadius * 1.30f,
                orbRadius * 1.45f,
                orbRadius * 1.20f,
                orbRadius * 1.55f,
                orbRadius * 1.35f,
                orbRadius * 1.50f,
                orbRadius * 1.25f,
                orbRadius * 1.60f
            )
            val particleSizes = listOf(
                orbRadius * 0.035f,
                orbRadius * 0.028f,
                orbRadius * 0.032f,
                orbRadius * 0.025f,
                orbRadius * 0.030f,
                orbRadius * 0.022f,
                orbRadius * 0.027f,
                orbRadius * 0.020f
            )

            for (i in 0 until 8) {
                val angleRad = Math.toRadians(particleAngleValues[i].toDouble())
                val dist = particleDistances[i]
                val px = center.x + (dist * cos(angleRad)).toFloat()
                val py = center.y + (dist * sin(angleRad)).toFloat()
                val pSize = particleSizes[i].coerceAtLeast(1f)
                val pAlpha = particleVisibility * (0.6f + 0.4f * sin(breathPhase + i * 0.8f).toFloat())

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = pAlpha * 0.9f),
                            orbColor.copy(alpha = pAlpha * 0.6f),
                            Color.Transparent
                        ),
                        center = Offset(px, py),
                        radius = pSize * 3f
                    ),
                    radius = pSize,
                    center = Offset(px, py)
                )
            }
        }

        // ── Centre Icon ─────────────────────────────────────────────
        val iconTint = orbColor.copy(alpha = 0.9f)
        when (brainState) {
            BrainState.IDLE      -> Icon(Icons.Filled.AutoAwesome, contentDescription = "Idle", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.WAKE      -> Icon(Icons.Filled.NotificationsActive, contentDescription = "Wake Detected", tint = Color.White.copy(alpha = 0.95f), modifier = Modifier.size(size * 0.18f))
            BrainState.LISTENING -> Icon(Icons.Filled.Mic, contentDescription = "Listening", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.THINKING  -> Icon(Icons.Filled.Psychology, contentDescription = "Thinking", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.SPEAKING  -> Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Speaking", tint = iconTint, modifier = Modifier.size(size * 0.18f))
            BrainState.ERROR     -> Icon(Icons.Filled.Error, contentDescription = "Error", tint = iconTint, modifier = Modifier.size(size * 0.18f))
        }
    }
}

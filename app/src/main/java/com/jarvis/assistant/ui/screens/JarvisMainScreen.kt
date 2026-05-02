package com.jarvis.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.theme.*
import kotlin.math.*

// ─── AssistantScreen — backward-compat wrapper ───────────────────────────────
@Composable
fun AssistantScreen(
    brainState: BrainState,
    audioAmplitude: Float,
    currentTranscription: String,
    lastResponse: String,
    emotion: String,
    isListening: Boolean,
    onToggleListening: () -> Unit,
    userMicLocked: Boolean = false,
    onToggleMicLock: () -> Unit = {},
    wakeFlash: Boolean = false,
    modifier: Modifier = Modifier
) = JarvisMainScreen(
    brainState          = brainState,
    audioAmplitude      = audioAmplitude,
    currentTranscription = currentTranscription,
    lastResponse        = lastResponse,
    emotion             = emotion,
    isListening         = isListening,
    onToggleListening   = onToggleListening,
    userMicLocked       = userMicLocked,
    onToggleMicLock     = onToggleMicLock,
    wakeFlash           = wakeFlash,
    modifier            = modifier
)

// ─── JarvisMainScreen ─────────────────────────────────────────────────────────
/**
 * Iron Man / Siri-style reactive hologram assistant screen.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * REACTIVE 3D HOLOGRAM ORB — THE CORE VISUAL:
 *
 * The orb is built from 3-4 overlapping shapes using Compose Canvas
 * with `Brush.radialGradient` and `BlendMode.Screen` / `BlendMode.Plus`
 * to create a glowing, breathing energy core effect.
 *
 * AMPLITUDE REACTIVITY:
 * - [audioAmplitude] is observed via `collectAsState()` from the ViewModel
 * - `animateFloatAsState` smooths the raw amplitude to prevent jitter
 * - The interpolated amplitude drives:
 *   • Orb SCALE: 1.0x (idle) → 1.3x (loud voice)
 *   • Glow RADIUS: expands with voice
 *   • Alpha / opacity: brighter when active
 *   • Core PULSE: faster breathing when speaking
 *   • Layer BLEND: more intense color mixing when loud
 *
 * WAKE WORD REACTION (v7):
 * - [wakeFlash] triggers a full-screen cyan flash overlay that fades out
 * - "WAKE DETECTED" text appears briefly at the top
 * - wakeFlash is passed to HolographicOrb for expanding ring burst
 * - Orb scales up 1.0x → 1.4x on wake, settles to 1.15x for LISTENING
 *
 * LAYER ARCHITECTURE (bottom to top):
 *   0  Background scan-grid Canvas (fillMaxSize)
 *   1  Bottom content column — response cards + mic button (zIndex = 1)
 *   2  Hologram orb Canvas — always visible, centred (zIndex = 2)
 *   3  Centre icon over orb (zIndex = 3)
 *   4  Top HUD bar (zIndex = 4)
 *   5  Wake flash overlay + WAKE DETECTED text (zIndex = 5)
 * ═══════════════════════════════════════════════════════════════════════
 */
@Composable
fun JarvisMainScreen(
    brainState: BrainState,
    audioAmplitude: Float,
    currentTranscription: String,
    lastResponse: String,
    emotion: String,
    isListening: Boolean,
    onToggleListening: () -> Unit,
    userMicLocked: Boolean = false,
    onToggleMicLock: () -> Unit = {},
    wakeFlash: Boolean = false,
    modifier: Modifier = Modifier
) {
    // ═══════════════════════════════════════════════════════════════════════
    // AMPLITUDE INTERPOLATION — smooth raw RMS into orb-friendly values
    // ═══════════════════════════════════════════════════════════════════════
    // Raw amplitude from AudioEngine is ~22 fps and can be jumpy.
    // animateFloatAsState provides smooth spring-like interpolation so the
    // orb grows and shrinks fluidly rather than jittering.
    val smoothedAmplitude by animateFloatAsState(
        targetValue = audioAmplitude.coerceIn(0f, 1f),
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "smoothed-amplitude"
    )

    // ── State-driven colour ───────────────────────────────────────────────────
    val primaryColor by animateColorAsState(
        targetValue  = brainState.color,
        animationSpec = tween(600, easing = EaseInOutSine),
        label        = "primary-color"
    )
    val glowColor by animateColorAsState(
        targetValue = when (brainState) {
            BrainState.WAKE     -> Color(0xFF00FFFF)
            BrainState.ERROR    -> Color(0xFFFF2200)
            BrainState.SPEAKING -> Color(0xFF00FFD4)
            BrainState.THINKING -> JarvisPurple
            else                -> JarvisCyan
        },
        animationSpec = tween(400),
        label         = "glow-color"
    )

    // ── Active: any non-idle state ────────────────────────────────────────────
    val isAwake = brainState != BrainState.IDLE

    // ── Wake flash overlay animation ─────────────────────────────────────────
    // Full-screen cyan flash that fades out when wakeFlash is triggered
    var wakeOverlayAlpha by remember { mutableFloatStateOf(0f) }
    val wakeOverlayAnim by animateFloatAsState(
        targetValue = wakeOverlayAlpha,
        animationSpec = tween(durationMillis = 700, easing = EaseOutCubic),
        label = "wake-overlay"
    )

    // "WAKE DETECTED" text alpha
    var wakeTextAlpha by remember { mutableFloatStateOf(0f) }
    val wakeTextAnim by animateFloatAsState(
        targetValue = wakeTextAlpha,
        animationSpec = tween(durationMillis = 1200, easing = EaseOutCubic),
        label = "wake-text"
    )

    LaunchedEffect(wakeFlash) {
        if (wakeFlash) {
            wakeOverlayAlpha = 0.35f
            wakeTextAlpha = 1f
            // BUG-P2-15 FIX: Add delay before fading so the flash is actually visible.
            // Previously the "on" values were immediately overwritten to 0, making
            // the flash invisible.
            delay(300)
            wakeOverlayAlpha = 0f
            wakeTextAlpha = 0f
        }
    }

    // ── Infinite transitions ──────────────────────────────────────────────────
    val inf = rememberInfiniteTransition(label = "holo")

    val orbitalRotation by inf.animateFloat(
        0f, 360f,
        infiniteRepeatable(tween(10_000, easing = LinearEasing)),
        label = "orb-rot"
    )
    val innerRingRot by inf.animateFloat(
        360f, 0f,
        infiniteRepeatable(tween(7_000, easing = LinearEasing)),
        label = "inner-rot"
    )
    val glowPulse by inf.animateFloat(
        0.55f, 1f,
        infiniteRepeatable(tween(1_800, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "glow-pulse"
    )
    // Core pulse speed increases with amplitude — breathing faster when speaking
    val corePulseDuration = (700 + (1f - smoothedAmplitude) * 800).toInt().coerceIn(300, 1500)
    val corePulse by inf.animateFloat(
        0.6f, 1f,
        infiniteRepeatable(tween(corePulseDuration, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "core-pulse"
    )
    val scanLine by inf.animateFloat(
        -1.2f, 1.2f,
        infiniteRepeatable(tween(3_200, easing = LinearEasing)),
        label = "scan"
    )
    val emotionPulse by inf.animateFloat(
        0.5f, 1f,
        infiniteRepeatable(tween(900, easing = EaseInOutSine), RepeatMode.Reverse),
        label = "em-pulse"
    )
    // Ripple phases (offset start times)
    val r1 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing)), label = "r1")
    val r2 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), initialStartOffset = StartOffset(470)), label = "r2")
    val r3 by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = FastOutSlowInEasing), initialStartOffset = StartOffset(940)), label = "r3")

    // Ambient breathing for idle state — subtle expansion/contraction
    val breathPhase by inf.animateFloat(
        0f, (2f * PI).toFloat(),
        infiniteRepeatable(tween(4000, easing = LinearEasing)),
        label = "breath"
    )

    val emotionColor = when (emotion.lowercase()) {
        "happy", "joy"    -> JarvisGreen
        "sad"             -> Color(0xFF5588FF)
        "angry", "stressed" -> JarvisRedPink
        "surprised"       -> JarvisCyan
        "fearful"         -> Color(0xFFFF8800)
        else              -> TextSecondary
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Root Box: children stack by declaration order / zIndex
    // ─────────────────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavy)
    ) {

        // ── LAYER 0: Background scan-grid ─────────────────────────────────────
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(0f)
        ) {
            val lineSpacing = 36.dp.toPx()
            val lineAlpha   = 0.04f
            var y = 0f
            while (y < size.height) {
                drawLine(JarvisCyan.copy(alpha = lineAlpha), Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5f)
                y += lineSpacing
            }
            var x = 0f
            while (x < size.width) {
                drawLine(JarvisCyan.copy(alpha = lineAlpha), Offset(x, 0f), Offset(x, size.height), strokeWidth = 0.5f)
                x += lineSpacing
            }
        }

        // ── LAYER 1: Bottom content (response + mic) ──────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(1f)
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Push content to lower half — orb occupies top ~55%
            Spacer(Modifier.fillMaxHeight(0.52f))

            // Amplitude wave bar
            AmplitudeWave(
                amplitude = smoothedAmplitude,
                color     = primaryColor,
                modifier  = Modifier
                    .fillMaxWidth(0.65f)
                    .height(28.dp)
            )

            Spacer(Modifier.height(10.dp))

            // State label
            Text(
                text  = "${brainState.label}  •  ${brainState.description}",
                color = primaryColor,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(10.dp))

            // Mic button row with Lock toggle
            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic Lock Button
                OutlinedIconButton(
                    onClick = onToggleMicLock,
                    modifier = Modifier.size(44.dp),
                    border = BorderStroke(
                        1.dp,
                        if (userMicLocked) JarvisGreen else TextTertiary.copy(alpha = 0.4f)
                    ),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = if (userMicLocked) JarvisGreen.copy(alpha = 0.15f) else Color.Transparent,
                        contentColor = if (userMicLocked) JarvisGreen else TextTertiary
                    )
                ) {
                    Icon(
                        imageVector = if (userMicLocked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                        contentDescription = if (userMicLocked) "Mic locked" else "Mic lock",
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(Modifier.width(12.dp))

                // Main Mic Button
                MicButton(
                    isListening = isListening,
                    primaryColor = primaryColor,
                    onToggle = onToggleListening
                )
            }

            Spacer(Modifier.height(14.dp))

            // Scrollable cards
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (currentTranscription.isNotBlank()) {
                    InfoCard(label = "YOU SAID", content = currentTranscription, accentColor = JarvisCyan)
                }
                if (lastResponse.isNotBlank()) {
                    InfoCard(
                        label        = "JARVIS",
                        content      = lastResponse,
                        accentColor  = primaryColor,
                        emotion      = emotion,
                        emotionColor = emotionColor,
                        emotionPulse = emotionPulse
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // LAYER 2: THE REACTIVE 3D HOLOGRAM ORB
        //
        // This is the Siri/Iron-Man style energy core that violently spikes
        // when the user speaks or when JARVIS responds.
        //
        // Architecture:
        //   Shape 1: Outer ambient glow — large, diffuse radial gradient
        //   Shape 2: Middle energy sphere — radial gradient with 3D highlight
        //   Shape 3: Inner core — bright white-to-color gradient (pulsing)
        //   Shape 4: Secondary glow layer — BlendMode.Screen for color mixing
        //
        // All shapes are drawn with overlapping radial gradients using
        // BlendMode.Screen / BlendMode.Plus so colors ADD together,
        // creating the characteristic glowing energy effect.
        // ═══════════════════════════════════════════════════════════════════════
        Canvas(
            modifier = Modifier
                .size(300.dp)
                .align(Alignment.TopCenter)
                .offset(y = 48.dp)
                .zIndex(2f)
        ) {
            val cx  = size.width  / 2f
            val cy  = size.height / 2f
            val amp = smoothedAmplitude   // already animated by animateFloatAsState

            // ── Wake scale: 1.0x idle → 1.4x wake → 1.15x listening ──────
            val wakeScaleFactor = when {
                wakeFlash -> 1.4f
                brainState == BrainState.LISTENING -> 1.15f
                else -> 1f
            }

            // ── Idle breathing: subtle scale oscillation when no voice ──────
            val breathScale = if (brainState == BrainState.IDLE) {
                1f + 0.03f * sin(breathPhase)
            } else {
                1f
            }

            // Base radius: ~34% of canvas; expands with voice amplitude
            val base = size.minDimension * 0.34f
            val orbR = base * (1f + amp * 0.30f) * breathScale * wakeScaleFactor

            // ════════════════════════════════════════════════════════════════
            // WAKE FLASH: Expanding ring burst on wake word detection
            // ════════════════════════════════════════════════════════════════
            if (wakeFlash) {
                // Animated expanding rings are handled by HolographicOrb internally,
                // but we add a large bright ring burst here too for the main screen canvas
                val ringAlpha = 0.6f
                for (i in 0..3) {
                    val expansion = 1.2f + i * 0.6f
                    val alpha = ringAlpha / (i + 1)
                    drawCircle(
                        color = Color(0xFF00FFFF).copy(alpha = alpha),
                        radius = orbR * expansion,
                        center = Offset(cx, cy),
                        style = Stroke(width = (3f - i * 0.5f).coerceAtLeast(0.5f).dp.toPx())
                    )
                }
            }

            // ════════════════════════════════════════════════════════════════
            // SHAPE 1: Outer ambient glow (Cyan → Transparent)
            //
            // Large, diffuse halo that expands dramatically with voice.
            // Uses radialGradient with voice-reactive alpha.
            // ════════════════════════════════════════════════════════════════
            val glowR = orbR * (1.9f + amp * 0.7f) * glowPulse
            val wakeGlowBoost = if (wakeFlash || brainState == BrainState.WAKE) 0.15f else 0f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        glowColor.copy(alpha = (0.20f + amp * 0.15f + wakeGlowBoost) * glowPulse),
                        glowColor.copy(alpha = 0.05f + amp * 0.05f + wakeGlowBoost * 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = glowR
                ),
                radius = glowR,
                center = Offset(cx, cy)
            )

            // ── Ripple rings (WAKE / LISTENING / SPEAKING) ───────────────────
            if (brainState.showRipples) {
                val rippleMaxAlpha = if (brainState == BrainState.WAKE) 0.4f else 0.22f
                listOf(r1 to rippleMaxAlpha, r2 to rippleMaxAlpha * 0.77f, r3 to rippleMaxAlpha * 0.5f).forEach { (p, maxA) ->
                    drawCircle(
                        color  = primaryColor.copy(alpha = maxA * (1f - p) * (0.5f + amp)),
                        radius = orbR * (1.05f + p * 1.1f),
                        center = Offset(cx, cy),
                        style  = Stroke(width = if (brainState == BrainState.WAKE) 2.5.dp.toPx() else 1.8.dp.toPx())
                    )
                }
            }

            // ── Outer orbital ring (24 dashed arcs, clockwise) ──────────────
            rotate(orbitalRotation, Offset(cx, cy)) {
                val rr = orbR * 1.26f
                for (i in 0 until 24) {
                    drawArc(
                        color      = primaryColor.copy(alpha = if (i % 3 == 0) 0.9f else 0.3f),
                        startAngle = i * 15f,
                        sweepAngle = 9f,
                        useCenter  = false,
                        topLeft    = Offset(cx - rr, cy - rr),
                        size       = androidx.compose.ui.geometry.Size(rr * 2, rr * 2),
                        style      = Stroke(width = if (i % 3 == 0) 2.5.dp.toPx() else 1.dp.toPx())
                    )
                }
            }

            // ── Inner accent ring (counter-rotating) ──────────────────────
            rotate(innerRingRot, Offset(cx, cy)) {
                val ir = orbR * 1.09f
                for (i in 0 until 12) {
                    drawArc(
                        color      = glowColor.copy(alpha = 0.45f + amp * 0.2f),
                        startAngle = i * 30f,
                        sweepAngle = 18f,
                        useCenter  = false,
                        topLeft    = Offset(cx - ir, cy - ir),
                        size       = androidx.compose.ui.geometry.Size(ir * 2, ir * 2),
                        style      = Stroke(width = (1f + amp).dp.toPx())
                    )
                }
            }

            // ════════════════════════════════════════════════════════════════
            // SHAPE 2: Main sphere — 3D RadialGradient + SweepGradient sheen
            //
            // The primary orb body. Uses an offset radial gradient center
            // to simulate 3D lighting (highlight top-left, shadow bottom-right).
            // ════════════════════════════════════════════════════════════════
            val hlOff = Offset(cx - orbR * 0.28f, cy - orbR * 0.28f)
            val sphereAlpha = if (brainState == BrainState.WAKE) 1f else (0.9f + amp * 0.1f)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color.White.copy(alpha = 0.25f + amp * 0.1f + if (brainState == BrainState.WAKE) 0.2f else 0f),
                        0.22f to primaryColor.copy(alpha = sphereAlpha),
                        0.60f to primaryColor.copy(alpha = 0.55f),
                        1.00f to primaryColor.copy(alpha = 0.15f)
                    ),
                    center = hlOff,
                    radius = orbR * 1.6f
                ),
                radius = orbR,
                center = Offset(cx, cy)
            )

            // ════════════════════════════════════════════════════════════════
            // SHAPE 3: Secondary glow layer — BlendMode.Screen
            //
            // Overlaps the main sphere with a complementary color gradient
            // using BlendMode.Screen so the colors ADD together.
            // This creates the characteristic Cyan + Magenta + Deep Blue
            // energy mixing effect that makes the orb look like a glowing
            // energy core rather than a flat circle.
            //
            // When amplitude is high (user speaking), the secondary layer
            // becomes more prominent and shifts color toward Magenta.
            // ════════════════════════════════════════════════════════════════
            val secondaryAlpha = 0.15f + amp * 0.35f + if (brainState == BrainState.WAKE) 0.2f else 0f
            val secondaryColor = when {
                brainState == BrainState.WAKE -> Color(0xFF00FFFF)  // Cyan for wake
                amp > 0.4f -> Color(0xFFFF00FF)  // Magenta spike when loud
                amp > 0.15f -> Color(0xFF4400FF)  // Deep Blue for moderate
                else -> Color(0xFF0066FF)          // Cool Blue for idle
            }
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        secondaryColor.copy(alpha = secondaryAlpha),
                        Color(0xFF0000FF).copy(alpha = secondaryAlpha * 0.5f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = orbR * 1.2f
                ),
                radius = orbR,
                center = Offset(cx, cy),
                blendMode = BlendMode.Screen
            )

            // ════════════════════════════════════════════════════════════════
            // SHAPE 4: Tertiary energy layer — BlendMode.Plus
            //
            // A third overlapping circle using BlendMode.Plus (additive
            // blending). This layer is most visible when the orb is
            // actively reacting to voice, creating intense bright spots
            // where the Cyan and Magenta layers overlap.
            // ════════════════════════════════════════════════════════════════
            if (amp > 0.05f || brainState == BrainState.SPEAKING || brainState == BrainState.WAKE) {
                val tertiaryAlpha = (0.08f + amp * 0.25f + if (brainState == BrainState.WAKE) 0.3f else 0f).coerceIn(0f, 0.6f)
                val tertiaryColor = Color(0xFF00FFFF)  // Pure Cyan for additive glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            tertiaryColor.copy(alpha = tertiaryAlpha),
                            Color(0xFF0088FF).copy(alpha = tertiaryAlpha * 0.4f),
                            Color.Transparent
                        ),
                        center = Offset(cx + orbR * 0.1f, cy + orbR * 0.1f),  // Slightly offset for 3D
                        radius = orbR * 1.1f
                    ),
                    radius = orbR * 0.9f,
                    center = Offset(cx, cy),
                    blendMode = BlendMode.Plus
                )
            }

            // Surface sheen (sweep gradient for metallic look)
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.07f + amp * 0.08f),
                        Color.Transparent,
                        Color.Transparent
                    ),
                    center = Offset(cx, cy)
                ),
                radius = orbR,
                center = Offset(cx, cy)
            )
            // Rim outline
            val rimAlpha = if (brainState == BrainState.WAKE) 0.9f else (0.6f + amp * 0.2f)
            drawCircle(
                color  = primaryColor.copy(alpha = rimAlpha),
                radius = orbR,
                center = Offset(cx, cy),
                style  = Stroke(width = if (brainState == BrainState.WAKE) 3.dp.toPx() else (1.5f + amp * 1f).dp.toPx())
            )

            // ── Scan line ─────────────────────────────────────────────────
            val sy = cy + scanLine * orbR
            if (sy in (cy - orbR)..(cy + orbR)) {
                val halfChord = sqrt(max(0f, orbR * orbR - (sy - cy).pow(2)))
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            primaryColor.copy(alpha = 0.55f + amp * 0.2f),
                            primaryColor.copy(alpha = 0.75f + amp * 0.25f),
                            primaryColor.copy(alpha = 0.55f + amp * 0.2f),
                            Color.Transparent
                        ),
                        startX = cx - halfChord,
                        endX   = cx + halfChord
                    ),
                    start       = Offset(cx - halfChord, sy),
                    end         = Offset(cx + halfChord, sy),
                    strokeWidth = 1.5.dp.toPx()
                )
            }

            // ════════════════════════════════════════════════════════════════
            // INNER PULSING CORE — The "heart" of the orb
            //
            // This bright white-to-color gradient circle at the center
            // is what makes the orb feel ALIVE. It pulses faster when
            // the user speaks and swells with amplitude.
            // ════════════════════════════════════════════════════════════════
            val coreScale = if (brainState == BrainState.WAKE) 1.5f else 1f
            val coreR = orbR * 0.20f * (0.8f + corePulse * 0.35f + amp * 0.45f) * coreScale
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = if (brainState == BrainState.WAKE) 1f else 0.95f),
                        primaryColor.copy(alpha = 0.75f + amp * 0.25f + if (brainState == BrainState.WAKE) 0.25f else 0f),
                        Color.Transparent
                    ),
                    center = Offset(cx, cy),
                    radius = coreR * 2.2f
                ),
                radius = coreR,
                center = Offset(cx, cy)
            )

            // ════════════════════════════════════════════════════════════════
            // AMPLITUDE SPIKE PARTICLES — appear when voice is loud
            //
            // When amplitude exceeds 0.3, small bright dots appear around
            // the orb to simulate energy discharge. Their positions are
            // deterministic (based on frame calculation) for consistency.
            // ════════════════════════════════════════════════════════════════
            val showParticles = amp > 0.3f || brainState == BrainState.WAKE
            if (showParticles) {
                val particleBase = if (brainState == BrainState.WAKE) 8 else ((amp - 0.3f) * 12).toInt().coerceIn(1, 6)
                val particleCount = particleBase.coerceIn(1, 10)
                val particleAlpha = if (brainState == BrainState.WAKE) 0.9f else (amp - 0.3f) * 1.4f
                for (i in 0 until particleCount) {
                    val angle = (orbitalRotation * 2f + i * 36f) * (PI / 180f)
                    val dist = orbR * (1.15f + amp * 0.3f + i * 0.05f)
                    val px = cx + (dist * cos(angle)).toFloat()
                    val py = cy + (dist * sin(angle)).toFloat()
                    val particleR = (2f + amp * 3f).dp.toPx()
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = particleAlpha),
                                primaryColor.copy(alpha = particleAlpha * 0.5f),
                                Color.Transparent
                            ),
                            center = Offset(px, py),
                            radius = particleR * 3f
                        ),
                        radius = particleR,
                        center = Offset(px, py)
                    )
                }
            }
        }

        // ── LAYER 3: Centre icon over the orb ────────────────────────────────
        Icon(
            imageVector = when (brainState) {
                BrainState.IDLE      -> Icons.Filled.Mic
                BrainState.WAKE      -> Icons.Filled.NotificationsActive
                BrainState.LISTENING -> Icons.Filled.Mic
                BrainState.THINKING  -> Icons.Filled.Psychology
                BrainState.SPEAKING  -> Icons.AutoMirrored.Filled.VolumeUp
                BrainState.ERROR     -> Icons.Filled.Error
            },
            contentDescription = brainState.label,
            tint = Color.White.copy(alpha = if (brainState == BrainState.WAKE) 1f else 0.88f),
            modifier = Modifier
                .size(if (brainState == BrainState.WAKE) 38.dp else 34.dp)
                .align(Alignment.TopCenter)
                .offset(y = (48 + 133).dp)
                .zIndex(3f)
        )

        // ── LAYER 4: Top HUD bar ──────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(horizontal = 16.dp, vertical = 10.dp)
                .zIndex(4f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = "J.A.R.V.I.S  v5",
                color      = TextTertiary,
                fontSize   = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Canvas(Modifier.size(6.dp)) { drawCircle(primaryColor) }
                Text(
                    text       = brainState.label,
                    color      = primaryColor,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // LAYER 5: WAKE FLASH OVERLAY + WAKE DETECTED TEXT
        //
        // When wakeFlash is true, a full-screen cyan overlay flashes and
        // fades out, and "WAKE DETECTED" text appears briefly at the top.
        // ═══════════════════════════════════════════════════════════════════════

        // Full-screen cyan flash overlay
        if (wakeOverlayAnim > 0.01f) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(5f)
            ) {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF00FFFF).copy(alpha = wakeOverlayAnim),
                            Color(0xFF00FFFF).copy(alpha = wakeOverlayAnim * 0.4f),
                            Color.Transparent
                        ),
                        center = Offset(size.width / 2f, size.height / 3f),
                        radius = size.maxDimension * 0.8f
                    )
                )
            }
        }

        // "WAKE DETECTED" text at the top
        if (wakeTextAnim > 0.01f) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .padding(top = 50.dp)
                    .zIndex(6f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "WAKE DETECTED",
                    color = Color(0xFF00FFFF).copy(alpha = wakeTextAnim),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "\"JARVIS\" ACTIVATED",
                    color = Color.White.copy(alpha = wakeTextAnim * 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// ─── Amplitude visualiser (21 bars) ──────────────────────────────────────────

@Composable
private fun AmplitudeWave(
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier
) {
    val inf   = rememberInfiniteTransition(label = "wave")
    val phase by inf.animateFloat(
        0f, (2 * PI).toFloat(),
        infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "wave-phase"
    )
    Canvas(modifier) {
        val bars   = 21
        val gap    = size.width / (bars * 2f - 1f)
        val maxH   = size.height
        for (i in 0 until bars) {
            val wave = sin(phase + i * 0.6f).toFloat()
            val barH = maxH * (0.14f + (0.5f + wave * 0.5f) * amplitude * 0.86f).coerceIn(0.10f, 1f)
            val x    = i * gap * 2f
            val top  = (size.height - barH) / 2f
            drawRoundRect(
                color        = color.copy(alpha = 0.65f + wave * 0.3f),
                topLeft      = Offset(x, top),
                size         = androidx.compose.ui.geometry.Size(gap, barH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(gap / 2f)
            )
        }
    }
}

// ─── Mic button ───────────────────────────────────────────────────────────────

@Composable
private fun MicButton(
    isListening: Boolean,
    primaryColor: Color,
    onToggle: () -> Unit
) {
    Box(contentAlignment = Alignment.Center) {
        if (isListening) {
            Canvas(Modifier.size(84.dp)) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(primaryColor.copy(alpha = 0.30f), Color.Transparent)
                    )
                )
            }
        }
        FilledIconButton(
            onClick = onToggle,
            modifier = Modifier.size(60.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = if (isListening) JarvisRedPink else JarvisCyan.copy(alpha = 0.9f),
                contentColor   = DeepNavy
            )
        ) {
            Icon(
                imageVector        = if (isListening) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Start listening",
                modifier           = Modifier.size(26.dp)
            )
        }
    }
    Text(
        text      = if (isListening) "TAP TO STOP" else "TAP TO SPEAK",
        color     = if (isListening) JarvisRedPink else TextSecondary,
        fontSize  = 10.sp,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 2.sp
    )
}

// ─── Info card ────────────────────────────────────────────────────────────────

@Composable
private fun InfoCard(
    label: String,
    content: String,
    accentColor: Color,
    emotion: String = "",
    emotionColor: Color = Color.Transparent,
    emotionPulse: Float = 1f
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(10.dp),
        color    = SurfaceNavy.copy(alpha = 0.88f)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(13.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )
                Text(label, color = accentColor, fontSize = 9.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
                if (emotion.isNotBlank() && emotion != "neutral") {
                    Spacer(Modifier.weight(1f))
                    Canvas(Modifier.size(6.dp)) {
                        drawCircle(emotionColor, size.minDimension / 2f * emotionPulse)
                    }
                    Text(emotion.uppercase(), color = emotionColor, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(7.dp))
            Text(content, color = TextPrimary, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Start)
        }
    }
}

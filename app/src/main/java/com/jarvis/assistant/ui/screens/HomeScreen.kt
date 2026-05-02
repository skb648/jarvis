package com.jarvis.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NavigateNext
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicButtonCard
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.components.GlassmorphicFeaturedCard
import com.jarvis.assistant.ui.orb.BrainState
import com.jarvis.assistant.ui.orb.HolographicOrb
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisGold
import com.jarvis.assistant.ui.theme.JarvisGreen
import com.jarvis.assistant.ui.theme.JarvisOrange
import com.jarvis.assistant.ui.theme.JarvisPurple
import com.jarvis.assistant.ui.theme.SuccessGreen
import com.jarvis.assistant.ui.theme.TextPrimary
import com.jarvis.assistant.ui.theme.TextSecondary
import com.jarvis.assistant.ui.theme.TextTertiary
import com.jarvis.assistant.ui.theme.WarningAmber
import com.jarvis.assistant.ui.theme.SurfaceNavyLight
import com.jarvis.assistant.ui.theme.SurfaceGlass
import com.jarvis.assistant.ui.theme.GradientStart
import com.jarvis.assistant.ui.theme.GradientMid
import com.jarvis.assistant.ui.theme.GradientEnd
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.sin
import kotlin.random.Random

/**
 * Quick action definition.
 */
data class QuickAction(
    val label: String,
    val icon: ImageVector,
    val description: String,
    val accentColor: Color
)

/**
 * Data for a recent conversation preview bubble.
 */
data class RecentConversation(
    val title: String,
    val preview: String,
    val timestamp: String,
    val accentColor: Color
)

@Composable
fun HomeScreen(
    brainState: BrainState,
    audioAmplitude: Float,
    deviceCount: Int,
    activeDeviceCount: Int,
    onQuickAction: (String) -> Unit,
    isRustReady: Boolean = false,
    engineStatusText: String = "AI engine starting...",
    locationContext: String = "",
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val greeting = remember { getGreeting() }
    val orbColor by animateColorAsState(
        targetValue = brainState.color,
        animationSpec = tween(600),
        label = "orb-color"
    )

    // ── Animated gradient transition for greeting text ──────────────
    val infiniteTransition = rememberInfiniteTransition(label = "greeting-gradient")
    val gradientOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gradient-offset"
    )

    // Build the animated gradient brush for greeting text
    val greetingGradientBrush = Brush.horizontalGradient(
        colors = listOf(JarvisCyan, JarvisPurple, JarvisCyan),
        startX = gradientOffset * 600f,
        endX = gradientOffset * 600f + 400f,
        tileMode = TileMode.Mirror
    )

    // ── Formatted date/time ────────────────────────────────────────
    val currentDateTime = remember {
        val date = LocalDate.now()
        val time = LocalTime.now()
        val dayFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d")
        val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        "${dayFormatter.format(date).uppercase()} • ${timeFormatter.format(time)}"
    }

    // ── Generate floating particles data ───────────────────────────
    val particles = remember {
        List(20) { index ->
            ParticleData(
                x = Random.nextFloat(),
                y = Random.nextFloat(),
                size = Random.nextFloat() * 3f + 1f,
                speed = Random.nextFloat() * 0.3f + 0.1f,
                alpha = Random.nextFloat() * 0.3f + 0.1f,
                phaseOffset = Random.nextFloat() * 6.28f
            )
        }
    }

    // ── Particle animation time ────────────────────────────────────
    val particleTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 30000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "particle-time"
    )

    Box(
        modifier = modifier.fillMaxSize()
    ) {
        // ══════════════════════════════════════════════════════════════
        // LAYER 1: Background gradient + floating particles
        // ══════════════════════════════════════════════════════════════
        Canvas(modifier = Modifier.fillMaxSize()) {
            // Background gradient
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(GradientStart, GradientMid, GradientEnd)
                )
            )

            // Floating particles
            particles.forEach { p ->
                val time = particleTime * p.speed
                val x = (p.x + sin(time + p.phaseOffset) * 0.05f) * size.width
                val y = ((p.y - time * 0.01f) % 1.2f - 0.1f) * size.height
                val alpha = p.alpha * (0.5f + 0.5f * sin(time * 2f + p.phaseOffset))

                drawCircle(
                    color = JarvisCyan.copy(alpha = alpha.coerceIn(0f, 0.4f)),
                    radius = p.size.dp.toPx(),
                    center = Offset(x, y)
                )
            }
        }

        // ══════════════════════════════════════════════════════════════
        // LAYER 2: Scrollable content
        // ══════════════════════════════════════════════════════════════
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // ── Animated Gradient Greeting ────────────────────────────────
            Text(
                text = greeting,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    brush = greetingGradientBrush
                )
            )
            Text(
                text = "How can I help you today?",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextSecondary
                )
            )

            Spacer(modifier = Modifier.height(6.dp))

            // ── Current Date/Time Display ────────────────────────────────
            Text(
                text = currentDateTime,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiary,
                    letterSpacing = 1.5.sp
                )
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ══════════════════════════════════════════════════════════════
            // AI ENGINE STATUS BAR — Pulsing indicator with live status
            // ══════════════════════════════════════════════════════════════
            AiEngineStatusBar(
                isRustReady = isRustReady,
                engineStatusText = engineStatusText
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ── Holographic Orb ─────────────────────────────────────────
            HolographicOrb(
                brainState = brainState,
                size = 220.dp,
                amplitude = audioAmplitude
            )

            Spacer(modifier = Modifier.height(12.dp))

            // ── State Label ─────────────────────────────────────────────
            Text(
                text = brainState.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = orbColor
                )
            )
            Text(
                text = brainState.description,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextSecondary
                )
            )

            Spacer(modifier = Modifier.height(28.dp))

            // ── Enhanced Quick Action Row with Shimmer ──────────────────
            val quickActions = listOf(
                QuickAction("Voice", Icons.Filled.Mic, "Start voice command", JarvisCyan),
                QuickAction("Capture", Icons.Filled.PhotoCamera, "Capture screen", JarvisPurple),
                QuickAction("Chat", Icons.AutoMirrored.Filled.Chat, "Open conversation", JarvisGreen),
                QuickAction("Devices", Icons.Filled.Devices, "Smart home", WarningAmber)
            )

            // Shimmer animation offset
            val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
            val shimmerOffset by shimmerTransition.animateFloat(
                initialValue = -1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2500, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "shimmer-offset"
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                quickActions.forEach { action ->
                    Box {
                        GlassmorphicButtonCard(
                            onClick = { onQuickAction(action.label.lowercase()) },
                            modifier = Modifier.size(80.dp)
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                // Content
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(bottom = 4.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Icon(
                                        imageVector = action.icon,
                                        contentDescription = action.label,
                                        tint = action.accentColor,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = action.label,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = TextSecondary,
                                            fontSize = 10.sp
                                        )
                                    )
                                }

                                // 2.dp bottom highlight bar
                                Canvas(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(2.dp)
                                        .align(Alignment.BottomCenter)
                                ) {
                                    drawRect(
                                        brush = Brush.horizontalGradient(
                                            colors = listOf(
                                                action.accentColor.copy(alpha = 0.0f),
                                                action.accentColor.copy(alpha = 0.8f),
                                                action.accentColor.copy(alpha = 0.0f)
                                            )
                                        )
                                    )
                                }
                            }
                        }

                        // Animated shimmer / scan line overlay
                        Canvas(
                            modifier = Modifier.size(80.dp)
                        ) {
                            val sweepWidth = size.width * 0.4f
                            val sweepX = shimmerOffset * size.width
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        Color.White.copy(alpha = 0f),
                                        Color.White.copy(alpha = 0.08f),
                                        Color.White.copy(alpha = 0f)
                                    ),
                                    startX = sweepX - sweepWidth / 2f,
                                    endX = sweepX + sweepWidth / 2f
                                ),
                                size = size
                            )
                        }

                        // Subtle gradient overlay based on accent color
                        Canvas(
                            modifier = Modifier.size(80.dp)
                        ) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    colors = listOf(
                                        action.accentColor.copy(alpha = 0.08f),
                                        Color.Transparent,
                                        action.accentColor.copy(alpha = 0.04f)
                                    )
                                ),
                                size = size
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(28.dp))

            // ══════════════════════════════════════════════════════════════
            // WEATHER CARD — Animated icon with temperature display
            // ══════════════════════════════════════════════════════════════
            WeatherCard()

            Spacer(modifier = Modifier.height(12.dp))

            // ── Daily Brief Card (Featured with golden border) ───────────
            GlassmorphicFeaturedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "Daily Brief",
                        tint = JarvisGold,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Daily Brief",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "Tap for your morning briefing",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary
                            )
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = "Briefing",
                        tint = JarvisGold.copy(alpha = 0.7f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Location Context Card ────────────────────────────────────
            GlassmorphicCardSimple(
                modifier = Modifier.fillMaxWidth(),
                showHighlight = true
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = "Location",
                        tint = JarvisCyan,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Location",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = if (locationContext.isNotBlank()) locationContext else "Location not available",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary
                            )
                        )
                    }
                    Icon(
                        imageVector = Icons.Filled.NavigateNext,
                        contentDescription = "Navigate",
                        tint = TextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ══════════════════════════════════════════════════════════════
            // RECENT CONVERSATIONS — Bubble-style preview entries
            // ══════════════════════════════════════════════════════════════
            RecentConversationsCard()

            Spacer(modifier = Modifier.height(12.dp))

            // ── Device Status Summary Card ──────────────────────────────
            GlassmorphicCardSimple(
                modifier = Modifier.fillMaxWidth(),
                showHighlight = true
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Home",
                        tint = JarvisGreen,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Smart Home",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = "$activeDeviceCount active / $deviceCount total devices",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = TextSecondary
                            )
                        )
                    }
                    Icon(
                        imageVector = if (activeDeviceCount > 0) Icons.Filled.CheckCircle else Icons.Filled.OfflineBolt,
                        contentDescription = "Status",
                        tint = if (activeDeviceCount > 0) SuccessGreen else TextTertiary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── System Status Card with animated gradient border ─────────
            GlassmorphicCardSimple(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // Animated gradient border glow
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    JarvisCyan.copy(alpha = 0.0f),
                                    JarvisPurple.copy(alpha = 0.15f + gradientOffset * 0.1f),
                                    JarvisCyan.copy(alpha = 0.0f)
                                ),
                                startX = gradientOffset * size.width * 0.5f,
                                endX = gradientOffset * size.width * 0.5f + size.width * 0.5f
                            ),
                            size = size.copy(height = 2.dp.toPx())
                        )
                    },
                showHighlight = true
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "AI",
                        tint = JarvisPurple,
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "JARVIS Core",
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary
                            )
                        )
                        Text(
                            text = engineStatusText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isRustReady) TextSecondary else WarningAmber
                            )
                        )
                    }
                    Icon(
                        imageVector = if (isRustReady) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = if (isRustReady) "Online" else "Limited",
                        tint = if (isRustReady) SuccessGreen else WarningAmber,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// AI ENGINE STATUS BAR — Shows engine status with pulsing indicator
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AiEngineStatusBar(
    isRustReady: Boolean,
    engineStatusText: String
) {
    val infiniteTransition = rememberInfiniteTransition(label = "ai-status-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status-pulse"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status-scale"
    )

    GlassmorphicCardSimple(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = SurfaceGlass,
        cornerRadius = 12.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Pulsing indicator dot
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawCircle(
                        color = if (isRustReady) JarvisGreen else JarvisOrange,
                        alpha = pulseAlpha * 0.3f,
                        radius = size.minDimension / 2f * pulseScale
                    )
                }
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(
                        color = if (isRustReady) JarvisGreen else JarvisOrange,
                        alpha = pulseAlpha,
                        radius = size.minDimension / 2f
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isRustReady) "AI ENGINE ONLINE" else "AI ENGINE STARTING",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (isRustReady) JarvisGreen else JarvisOrange,
                        letterSpacing = 1.5.sp,
                        fontSize = 10.sp
                    )
                )
                Text(
                    text = engineStatusText,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary,
                        fontSize = 9.sp
                    )
                )
            }

            // Status badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .drawBehind {
                        drawRect(
                            color = if (isRustReady) JarvisGreen.copy(alpha = 0.15f) else JarvisOrange.copy(alpha = 0.15f)
                        )
                    }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    text = if (isRustReady) "READY" else "INIT",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (isRustReady) JarvisGreen else JarvisOrange,
                        fontSize = 8.sp,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// WEATHER CARD — Animated sun icon with temperature display
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun WeatherCard() {
    val infiniteTransition = rememberInfiniteTransition(label = "weather-anim")

    // Rotating sun rays animation
    val sunRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "sun-rotation"
    )

    // Pulsing glow
    val sunPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sun-pulse"
    )

    GlassmorphicCardSimple(
        modifier = Modifier.fillMaxWidth(),
        showHighlight = true,
        highlightColor = JarvisGold.copy(alpha = 0.04f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Animated sun icon
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(48.dp)
            ) {
                // Sun glow
                Canvas(modifier = Modifier.size(48.dp)) {
                    drawCircle(
                        color = JarvisGold.copy(alpha = sunPulse * 0.15f),
                        radius = size.minDimension / 2f
                    )
                }
                // Sun rays
                Canvas(modifier = Modifier.size(40.dp)) {
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val rayLength = 6.dp.toPx()
                    val innerRadius = 10.dp.toPx()
                    for (i in 0..7) {
                        val angle = Math.toRadians(sunRotation + i * 45.0)
                        val startX = center.x + innerRadius * kotlin.math.cos(angle).toFloat()
                        val startY = center.y + innerRadius * kotlin.math.sin(angle).toFloat()
                        val endX = center.x + (innerRadius + rayLength) * kotlin.math.cos(angle).toFloat()
                        val endY = center.y + (innerRadius + rayLength) * kotlin.math.sin(angle).toFloat()
                        drawLine(
                            color = JarvisGold.copy(alpha = sunPulse),
                            start = Offset(startX, startY),
                            end = Offset(endX, endY),
                            strokeWidth = 2.dp.toPx()
                        )
                    }
                }
                // Core sun
                Icon(
                    imageVector = Icons.Filled.WbSunny,
                    contentDescription = "Weather",
                    tint = JarvisGold,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Weather",
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    )
                )
                Text(
                    text = "Tap to check weather",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary
                    )
                )
            }
            Text(
                text = "—°",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = JarvisGold.copy(alpha = 0.6f)
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RECENT CONVERSATIONS — Bubble-style preview entries
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun RecentConversationsCard() {
    val recentChats = remember {
        listOf(
            RecentConversation("Weather query", "What's the weather today?", "2m ago", JarvisCyan),
            RecentConversation("Smart home", "Turn off the living room lights", "15m ago", JarvisGreen),
            RecentConversation("Joke time", "Tell me something funny", "1h ago", JarvisPurple)
        )
    }

    GlassmorphicCardSimple(
        modifier = Modifier.fillMaxWidth(),
        showHighlight = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = "RECENT CONVERSATIONS",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiary,
                    letterSpacing = 2.sp,
                    fontSize = 9.sp
                )
            )
            Spacer(modifier = Modifier.height(10.dp))

            recentChats.forEach { chat ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Colored dot indicator
                    Canvas(modifier = Modifier.size(6.dp)) {
                        drawCircle(
                            color = chat.accentColor,
                            radius = size.minDimension / 2f
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chat.title,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextPrimary,
                                fontSize = 12.sp
                            )
                        )
                        Text(
                            text = chat.preview,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextTertiary,
                                fontSize = 10.sp
                            ),
                            maxLines = 1
                        )
                    }
                    Text(
                        text = chat.timestamp,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary,
                            fontSize = 9.sp
                        )
                    )
                }
            }
        }
    }
}

// ─── Particle data class ────────────────────────────────────────────────────
private data class ParticleData(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val alpha: Float,
    val phaseOffset: Float
)

private fun getGreeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour < 6   -> "Good night,"
        hour < 12  -> "Good morning,"
        hour < 17  -> "Good afternoon,"
        hour < 21  -> "Good evening,"
        else       -> "Good night,"
    }
}

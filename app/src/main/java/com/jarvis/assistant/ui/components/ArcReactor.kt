package com.jarvis.assistant.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.service.JarvisEvents.JarvisState
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Arc Reactor — JARVIS's face.
 * - Glow color = the emotion currently felt from your voice
 * - Rotating arcs = "thinking"
 * - Pulsing ring = listening
 * - 48 waveform bars around the ring = your live voice
 */
@Composable
fun ArcReactor(
    emotionColor: Color,
    emotionEmoji: String,
    emotionLabel: String,
    confidence: Float,
    waveform: List<Float>,
    state: JarvisState,
    accent: Color = JarvisCyan,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(emotionColor, tween(450), label = "emotionColor")

    val infinite = rememberInfiniteTransition(label = "reactor")
    val arcRotation by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "arc"
    )
    val arcRotationBack by infinite.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(13000, easing = LinearEasing), RepeatMode.Restart),
        label = "arcBack"
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = if (state == JarvisState.LISTENING) 1.08f else 1.02f,
        animationSpec = infiniteRepeatable(
            tween(if (state == JarvisState.LISTENING) 650 else 1600),
            RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
        ) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // outer glow
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(animatedColor.copy(alpha = 0.30f), Color.Transparent),
                    center = center,
                    radius = r
                )
            )

            // static rings
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = r * 0.94f,
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
            drawCircle(
                color = accent.copy(alpha = 0.45f),
                radius = r * 0.80f,
                center = center,
                style = Stroke(width = 2.2.dp.toPx())
            )

            // rotating HUD arcs
            rotate(arcRotation, pivot = center) {
                drawArc(
                    color = animatedColor,
                    startAngle = -35f,
                    sweepAngle = 130f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.80f, center.y - r * 0.80f),
                    size = Size(r * 1.6f, r * 1.6f),
                    style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            rotate(arcRotationBack, pivot = center) {
                drawArc(
                    color = JarvisGold,
                    startAngle = 160f,
                    sweepAngle = 55f,
                    useCenter = false,
                    topLeft = Offset(center.x - r * 0.66f, center.y - r * 0.66f),
                    size = Size(r * 1.32f, r * 1.32f),
                    style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round)
                )
            }

            // live waveform bars around the ring
            val n = waveform.size
            for (i in 0 until n) {
                val v = (waveform[i] * 6f).coerceIn(0f, 1f)
                val angle = (i * 360f / n) * PI.toFloat() / 180f
                val dir = Offset(cos(angle), sin(angle))
                val start = center + dir * (r * 0.52f)
                val end = center + dir * (r * 0.52f + v * r * 0.24f)
                drawLine(
                    color = if (v > 0.03f) animatedColor.copy(alpha = 0.35f + v * 0.6f)
                    else Color.White.copy(alpha = 0.08f),
                    start = start,
                    end = end,
                    strokeWidth = 2.2.dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // inner core
            drawCircle(
                color = animatedColor.copy(alpha = 0.12f),
                radius = r * 0.38f,
                center = center
            )
            drawCircle(
                color = animatedColor.copy(alpha = 0.9f),
                radius = r * 0.055f,
                center = center
            )
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emotionEmoji, fontSize = 34.sp)
            Text(
                emotionLabel,
                color = animatedColor,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            if (confidence > 0.02f) {
                Text(
                    "${(confidence * 100).toInt()}% confidence",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                when (state) {
                    JarvisState.LISTENING -> "● sun raha hoon..."
                    JarvisState.THINKING -> "◌ soch raha hoon..."
                    JarvisState.SPEAKING -> "► bol raha hoon..."
                    JarvisState.WAKE -> "◉ wake word active"
                    JarvisState.IDLE -> "○ inactive"
                },
                fontSize = 11.sp,
                color = if (state == JarvisState.LISTENING) JarvisCyan
                else MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.5.sp
            )
        }
    }
}

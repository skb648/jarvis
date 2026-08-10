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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.jarvis.assistant.ui.theme.JarvisGold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D premium orb — Siri/Bixby style, sirf Compose Canvas se.
 * Specular sphere (light source up-left), rotating HUD arcs,
 * live waveform ring, emotion glow, center status.
 */
@Composable
fun Orb3D(
    emotionColor: Color,
    state: JarvisState,
    waveform: List<Float>,
    emotionEmoji: String,
    emotionLabel: String,
    modifier: Modifier = Modifier
) {
    val animatedColor by animateColorAsState(emotionColor, tween(450), label = "orbColor")
    val infinite = rememberInfiniteTransition(label = "orb")
    val arc1 by infinite.animateFloat(
        0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing), RepeatMode.Restart), label = "a1"
    )
    val arc2 by infinite.animateFloat(
        360f, 0f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart), label = "a2"
    )
    val pulse by infinite.animateFloat(
        0.96f, if (state == JarvisState.LISTENING) 1.06f else 1.02f,
        infiniteRepeatable(tween(if (state == JarvisState.LISTENING) 600 else 1500), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = pulse
                    scaleY = pulse
                }
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)

            // outer glow
            drawCircle(
                Brush.radialGradient(
                    listOf(animatedColor.copy(alpha = 0.28f), Color.Transparent),
                    center = c, radius = r
                )
            )
            // sphere body — 3D specular look
            drawCircle(
                Brush.radialGradient(
                    listOf(
                        Color.White.copy(alpha = 0.35f),           // highlight
                        animatedColor.copy(alpha = 0.22f),
                        Color(0xFF050A18).copy(alpha = 0.92f)      // dark edge
                    ),
                    center = Offset(c.x - r * 0.35f, c.y - r * 0.35f),
                    radius = r * 1.25f
                )
            )
            // rim
            drawCircle(
                animatedColor.copy(alpha = 0.55f),
                radius = r * 0.92f, center = c,
                style = Stroke(width = 2.dp.toPx())
            )
            // rotating arcs
            rotate(arc1, pivot = c) {
                drawArc(
                    animatedColor,
                    startAngle = -40f, sweepAngle = 120f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.78f, c.y - r * 0.78f),
                    size = Size(r * 1.56f, r * 1.56f),
                    style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            rotate(arc2, pivot = c) {
                drawArc(
                    JarvisGold,
                    startAngle = 150f, sweepAngle = 70f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.62f, c.y - r * 0.62f),
                    size = Size(r * 1.24f, r * 1.24f),
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                )
            }
            // waveform ring
            val n = waveform.size
            for (i in 0 until n) {
                val v = (waveform[i] * 5f).coerceIn(0f, 1f)
                val angle = (i * 360f / n) * PI.toFloat() / 180f
                val dir = Offset(cos(angle), sin(angle))
                val start = c + dir * (r * 0.50f)
                val end = c + dir * (r * 0.50f + v * r * 0.22f)
                drawLine(
                    color = if (v > 0.04f) animatedColor.copy(alpha = 0.3f + v * 0.6f)
                    else Color.White.copy(alpha = 0.07f),
                    start = start, end = end,
                    strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                )
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emotionEmoji, fontSize = 16.sp)
            Text(
                emotionLabel,
                color = animatedColor,
                fontSize = 7.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Text(
                when (state) {
                    JarvisState.LISTENING -> "●"
                    JarvisState.THINKING -> "◌"
                    JarvisState.SPEAKING -> "►"
                    else -> ""
                },
                color = animatedColor, fontSize = 8.sp
            )
        }
    }
}

package com.jarvis.assistant.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.ui.components.Orb3D
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisPanel
import com.jarvis.assistant.ui.theme.JarvisTheme
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

/**
 * 3D premium floating bubble — Siri/Bixby style.
 * Tap = talk • Drag = move (edge snap) • Long-press = menu (close/pin).
 * Compose overlay me live emotion + waveform + state.
 */
class FloatingBubbleService : Service() {

    companion object {
        @Volatile
        var active = false
            private set
    }

    private var wm: WindowManager? = null
    private var bubbleView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    private fun showBubble() {
        val size = (92 * resources.displayMetrics.density).toInt()
        params = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 260
        }

        val view = ComposeView(this).apply {
            setContent {
                JarvisTheme {
                    BubbleContent(
                        onTalk = {
                            startForegroundService(
                                Intent(this@FloatingBubbleService, JarvisService::class.java)
                                    .setAction(NotificationHelper.ACTION_TALK)
                            )
                        },
                        onClose = { stopSelf() },
                        onDrag = { dx, dy ->
                            params?.let { p ->
                                p.x = (p.x + dx).coerceIn(0, screenWidthPx() - p.width)
                                p.y = (p.y + dy).coerceIn(0, screenHeightPx() - p.height)
                                runCatching { wm?.updateViewLayout(bubbleView, p) }
                            }
                        },
                        onSnap = {
                            params?.let { p ->
                                val screenW = screenWidthPx()
                                p.x = if (p.x + p.width / 2 < screenW / 2) 8 else screenW - p.width - 8
                                runCatching { wm?.updateViewLayout(bubbleView, p) }
                            }
                        }
                    )
                }
            }
        }
        bubbleView = view
        wm?.addView(view, params)
        active = true
    }

    private fun screenWidthPx() = resources.displayMetrics.widthPixels
    private fun screenHeightPx() = resources.displayMetrics.heightPixels

    override fun onDestroy() {
        runCatching { bubbleView?.let { wm?.removeView(it) } }
        bubbleView = null
        active = false
        super.onDestroy()
    }
}

// ------------------------------------------------------------------ UI

@Composable
private fun BubbleContent(
    onTalk: () -> Unit,
    onClose: () -> Unit,
    onDrag: (Int, Int) -> Unit,
    onSnap: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var emotion by remember { mutableStateOf(Emotion.NEUTRAL) }
    var state by remember { mutableStateOf(JarvisEvents.JarvisState.IDLE) }
    var waveform by remember { mutableStateOf(List(36) { 0f }) }
    var partial by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        JarvisEvents.events.collectLatest { e ->
            when (e) {
                is JarvisEvents.Event.EmotionTick -> {
                    emotion = e.emotion
                    waveform = waveform.drop(1) + e.rms
                }
                is JarvisEvents.Event.State -> state = e.state
                is JarvisEvents.Event.Partial -> partial = e.text
                else -> {}
            }
        }
    }

    if (expanded) {
        // mini HUD panel
        Dialog(onDismissRequest = { expanded = false }) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(JarvisPanel)
                    .padding(16.dp)
            ) {
                androidx.compose.foundation.layout.Column {
                    Text("J.A.R.V.I.S.", color = JarvisCyan, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${emotion.emoji} ${emotion.label}  •  ${
                            when (state) {
                                JarvisEvents.JarvisState.LISTENING -> "sun raha hoon"
                                JarvisEvents.JarvisState.THINKING -> "soch raha hoon"
                                JarvisEvents.JarvisState.SPEAKING -> "bol raha hoon"
                                else -> "ready"
                            }
                        }",
                        color = emotion.color, fontSize = 12.sp
                    )
                    if (partial.isNotBlank()) {
                        Text("\u201C$partial\u201D", color = Color(0xFFFFC400), fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                    Row {
                        Text("🎤 Bolo", color = Color.White, fontSize = 14.sp, modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(JarvisCyan.copy(alpha = 0.2f))
                            .clickable { expanded = false; onTalk() }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("✕ Band", color = Color(0xFFFF5C5C), fontSize = 14.sp, modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFFFF5C5C).copy(alpha = 0.12f))
                            .clickable { onClose() }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var startX = 0f
                var startY = 0f
                var dragging = false
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: continue
                        when (event.type) {
                            androidx.compose.ui.input.pointer.PointerEventType.Press -> {
                                startX = change.position.x
                                startY = change.position.y
                                dragging = false
                            }
                            androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                                val dx = change.position.x - startX
                                val dy = change.position.y - startY
                                if (abs(dx) > 12 || abs(dy) > 12) dragging = true
                                if (dragging) onDrag(dx.toInt(), dy.toInt())
                            }
                            androidx.compose.ui.input.pointer.PointerEventType.Release -> {
                                if (!dragging) expanded = true else onSnap()
                            }
                        }
                    }
                }
            }
    ) {
        Orb3D(
            emotionColor = emotion.color,
            state = state,
            waveform = waveform,
            emotionEmoji = emotion.emoji,
            emotionLabel = emotion.label,
            modifier = Modifier
                .align(Alignment.Center)
                .size(80.dp)
        )
    }
}

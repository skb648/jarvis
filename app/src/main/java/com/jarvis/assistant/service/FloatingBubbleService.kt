package com.jarvis.assistant.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.jarvis.assistant.core.NotificationHelper
import kotlin.math.abs

/**
 * Floating bubble — Messenger-style JARVIS head over any app.
 * Tap = baat karo. Drag = move. Swipe up = dismiss.
 */
class FloatingBubbleService : Service() {

    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var moved = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
    }

    private fun showBubble() {
        if (bubbleView != null) return
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }

        val bubble = TextView(this).apply {
            text = "🦾"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC0A1120.toInt())
                setStroke(3, 0xFF00E5FF.toInt())
            }
        }

        val size = (56 * resources.displayMetrics.density).toInt()
        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - initialTouchX).toInt()
                    val dy = (event.rawY - initialTouchY).toInt()
                    if (abs(dx) > 8 || abs(dy) > 8) moved = true
                    params.x = initialX + dx
                    params.y = initialY + dy
                    runCatching { windowManager?.updateViewLayout(bubble, params) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        // tap -> talk
                        val i = Intent(this, JarvisService::class.java)
                            .setAction(NotificationHelper.ACTION_TALK)
                        startForegroundService(i)
                    } else if (params.y < 60) {
                        // swiped to top -> dismiss
                        stopSelf()
                    }
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(bubble, params)
        bubbleView = bubble
    }

    override fun onDestroy() {
        runCatching { bubbleView?.let { windowManager?.removeView(it) } }
        bubbleView = null
        super.onDestroy()
    }
}

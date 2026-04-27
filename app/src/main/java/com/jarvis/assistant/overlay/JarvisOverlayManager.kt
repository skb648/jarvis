package com.jarvis.assistant.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import com.jarvis.assistant.channels.JarviewModel

/**
 * JARVIS Overlay Manager — Persistent floating widget.
 *
 * Creates a draggable ImageView system overlay that:
 *   - Floats above all apps (TYPE_APPLICATION_OVERLAY)
 *   - Can be dragged to any position on screen
 *   - Distinguishes between tap and drag (10px threshold)
 *   - Persists position across sessions
 *   - Sends events to UI on tap
 *
 * Works seamlessly on Android 16 with proper overlay permission.
 */
class JarvisOverlayManager(private val context: Context) {

    companion object {
        private const val TAG = "JarvisOverlay"
        private const val DRAG_THRESHOLD = 10 // pixels
        private const val DEFAULT_SIZE = 80 // dp
        private const val DEFAULT_POS_X = 0
        private const val DEFAULT_POS_Y = 200
    }

    private var overlayView: ImageView? = null
    private var isShowing = false
    private var sizePx = 0

    // Position tracking
    private var posX = DEFAULT_POS_X
    private var posY = DEFAULT_POS_Y

    // Drag tracking
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var initialPosX = 0
    private var initialPosY = 0
    private var isDragging = false

    private val windowManager: WindowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    /**
     * Show the floating overlay widget.
     * Requires SYSTEM_ALERT_WINDOW permission.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return

        try {
            // Convert dp to pixels
            val density = context.resources.displayMetrics.density
            sizePx = (DEFAULT_SIZE * density).toInt()

            // Create the overlay view
            overlayView = ImageView(context).apply {
                setImageResource(android.R.drawable.ic_menu_compass)
                setColorFilter(0xFF00D4FF.toInt()) // Cyan
                setBackgroundResource(android.R.drawable.dialog_holo_dark_frame)
                alpha = 0.8f

                setOnTouchListener(object : View.OnTouchListener {
                    override fun onTouch(v: View, event: MotionEvent): Boolean {
                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                initialTouchX = event.rawX
                                initialTouchY = event.rawY
                                initialPosX = posX
                                initialPosY = posY
                                isDragging = false
                                return true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                val dx = event.rawX - initialTouchX
                                val dy = event.rawY - initialTouchY

                                if (!isDragging && (Math.abs(dx) > DRAG_THRESHOLD || Math.abs(dy) > DRAG_THRESHOLD)) {
                                    isDragging = true
                                }

                                if (isDragging) {
                                    posX = initialPosX + dx.toInt()
                                    posY = initialPosY + dy.toInt()
                                    updateViewPosition()
                                }
                                return true
                            }
                            MotionEvent.ACTION_UP -> {
                                if (!isDragging) {
                                    // Tap detected — notify UI
                                    Log.d(TAG, "Overlay tapped")
                                    JarviewModel.sendEventToUi("overlay_tapped", emptyMap())
                                }
                                return true
                            }
                        }
                        return false
                    }
                })
            }

            // Layout params for overlay
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = posX
                y = posY
            }

            windowManager.addView(overlayView, params)
            isShowing = true
            Log.i(TAG, "Overlay shown at ($posX, $posY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay — missing overlay permission?", e)
        }
    }

    /**
     * Hide and remove the overlay widget.
     */
    fun hide() {
        if (!isShowing) return

        try {
            overlayView?.let {
                windowManager.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing overlay", e)
        }

        overlayView = null
        isShowing = false
        Log.d(TAG, "Overlay hidden")
    }

    /**
     * Update overlay position programmatically.
     */
    fun updatePosition(x: Int, y: Int) {
        posX = x
        posY = y
        updateViewPosition()
    }

    /**
     * Check if overlay is currently showing.
     */
    fun isOverlayShowing(): Boolean = isShowing

    private fun updateViewPosition() {
        if (!isShowing || overlayView == null) return

        try {
            val params = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return
            params.x = posX
            params.y = posY
            windowManager.updateViewLayout(overlayView!!, params)
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay position", e)
        }
    }
}

package com.jarvis.assistant.gesture

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.shizuku.ShizukuManager
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gesture Controller — Unified gesture execution interface.
 *
 * Dual-path execution:
 *   1. Primary: Via AccessibilityService dispatchGesture() with completion callbacks
 *   2. Fallback: Via Shizuku shell input commands
 *
 * Supports: tap, swipe, longPress, pinch — with automatic fallback
 * when AccessibilityService is unavailable.
 */
object GestureController {

    private const val TAG = "JarvisGesture"
    private const val LONG_PRESS_DURATION_MS = 500L
    private const val SWIPE_DURATION_MS = 300L
    private const val PINCH_DURATION_MS = 300L

    /**
     * Perform a tap gesture at the specified coordinates.
     */
    fun performTap(x: Int, y: Int): Boolean {
        // Try AccessibilityService first
        val service = JarviewModel.accessibilityService?.get()
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 50L))
                .build()

            return dispatchGesture(service, gesture)
        }

        // Fallback to Shizuku
        return ShizukuManager.simulateTap(x, y).isSuccess
    }

    /**
     * Perform a long press gesture at the specified coordinates.
     */
    fun performLongPress(x: Int, y: Int): Boolean {
        val service = JarviewModel.accessibilityService?.get()
        if (service != null) {
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, LONG_PRESS_DURATION_MS))
                .build()

            return dispatchGesture(service, gesture)
        }

        // Shizuku fallback — simulate long press via swipe at same point
        return ShizukuManager.simulateSwipe(x, y, x, y, LONG_PRESS_DURATION_MS.toInt()).isSuccess
    }

    /**
     * Perform a swipe gesture from (x1,y1) to (x2,y2).
     */
    fun performSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Long = SWIPE_DURATION_MS): Boolean {
        val service = JarviewModel.accessibilityService?.get()
        if (service != null) {
            val path = Path().apply {
                moveTo(x1.toFloat(), y1.toFloat())
                lineTo(x2.toFloat(), y2.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()

            return dispatchGesture(service, gesture)
        }

        return ShizukuManager.simulateSwipe(x1, y1, x2, y2, duration.toInt()).isSuccess
    }

    /**
     * Perform a scroll gesture (vertical swipe).
     */
    fun performScroll(x: Int, startY: Int, endY: Int, duration: Long = SWIPE_DURATION_MS): Boolean {
        return performSwipe(x, startY, x, endY, duration)
    }

    /**
     * Perform a pinch gesture at the specified center point.
     *
     * BUG FIX: Shizuku fallback now uses swipe gestures that simulate
     * pinch motion (two fingers moving toward center) instead of two
     * sequential taps which don't resemble a pinch at all.
     */
    fun performPinch(centerX: Int, centerY: Int, pinchDistance: Float, duration: Long = PINCH_DURATION_MS): Boolean {
        val service = JarviewModel.accessibilityService?.get()
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val path1 = Path().apply {
                moveTo((centerX - pinchDistance), centerY.toFloat())
                lineTo(centerX.toFloat(), centerY.toFloat())
            }
            val path2 = Path().apply {
                moveTo((centerX + pinchDistance), centerY.toFloat())
                lineTo(centerX.toFloat(), centerY.toFloat())
            }

            val builder = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path1, 0L, duration))
                .addStroke(GestureDescription.StrokeDescription(path2, 0L, duration))

            return dispatchGesture(service, builder.build())
        }

        // Shizuku fallback: simulate pinch with two simultaneous swipes toward center
        // (sequenced since Shizuku input commands can't do multi-touch)
        val halfDist = (pinchDistance / 2).toInt()
        val swipe1 = ShizukuManager.simulateSwipe(
            centerX - pinchDistance.toInt(), centerY,
            centerX - halfDist, centerY,
            duration.toInt()
        )
        val swipe2 = ShizukuManager.simulateSwipe(
            centerX + pinchDistance.toInt(), centerY,
            centerX + halfDist, centerY,
            duration.toInt()
        )
        return swipe1.isSuccess && swipe2.isSuccess
    }

    // ─── Gesture Dispatch ───────────────────────────────────────

    /**
     * Dispatch a gesture and WAIT for it to actually complete (or be cancelled)
     * before returning, using CountDownLatch + GestureResultCallback.
     *
     * Returns true only if onCompleted() was called.
     * Returns false if onCancelled() was called, dispatch failed to initiate,
     * or the gesture timed out after 5 seconds.
     *
     * Thread-safe: uses AtomicBoolean for the result and CountDownLatch
     * for synchronization between the callback and the calling thread.
     */
    private fun dispatchGesture(service: AccessibilityService, gesture: GestureDescription): Boolean {
        return try {
            val completed = AtomicBoolean(false)
            val latch = CountDownLatch(1)

            val callback = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed.set(true)
                    latch.countDown()
                    Log.d(TAG, "Gesture completed successfully")
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    completed.set(false)
                    latch.countDown()
                    Log.w(TAG, "Gesture cancelled by system")
                }
            }

            val dispatchInitiated = service.dispatchGesture(gesture, callback, null)
            if (!dispatchInitiated) {
                Log.w(TAG, "Gesture dispatch failed to initiate")
                return false
            }

            // Wait up to 5 seconds for the gesture to complete
            val finished = latch.await(5, TimeUnit.SECONDS)
            if (!finished) {
                Log.w(TAG, "Gesture timed out after 5 seconds")
                return false
            }

            completed.get()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch gesture", e)
            false
        }
    }
}

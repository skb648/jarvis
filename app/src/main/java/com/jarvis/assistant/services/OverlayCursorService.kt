package com.jarvis.assistant.services

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.gesture.GestureController
import java.lang.ref.WeakReference

/**
 * OverlayCursorService — REAL system overlay cursor for JARVIS AI assistant.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * SYSTEM OVERLAY CURSOR — AI-CONTROLLED MOUSE POINTER:
 *
 * Draws a movable mouse cursor ON TOP OF ALL APPS using Android's
 * SYSTEM_ALERT_WINDOW permission. The cursor can be controlled by:
 *   1. AI agent — programmatic movement via moveCursorTo()/clickAt()
 *   2. User drag — manual positioning in manual mode
 *
 * Key features:
 *   - Custom Canvas-drawn arrow cursor with pulsing cyan glow
 *   - AI mode: cyan circle + crosshair + animated rotating arc
 *   - Smooth ValueAnimator movement (300ms EaseInOut)
 *   - Foreground service with persistent notification
 *   - Thread-safe: all WindowManager ops on main thread via Handler
 *   - Touch pass-through in AI mode, draggable in manual mode
 *   - Integrates with JarviewModel for cross-service state sync
 * ═══════════════════════════════════════════════════════════════════════
 */
class OverlayCursorService : Service() {

    companion object {
        private const val TAG = "OverlayCursor"
        private const val CHANNEL_ID = "jarvis_cursor_overlay"
        private const val NOTIFICATION_ID = 1002

        // Intent actions
        const val ACTION_START = "com.jarvis.assistant.CURSOR_START"
        const val ACTION_STOP = "com.jarvis.assistant.CURSOR_STOP"
        const val ACTION_SHOW = "com.jarvis.assistant.CURSOR_SHOW"
        const val ACTION_HIDE = "com.jarvis.assistant.CURSOR_HIDE"
        const val ACTION_MOVE = "com.jarvis.assistant.CURSOR_MOVE"
        const val ACTION_AI_MODE = "com.jarvis.assistant.CURSOR_AI_MODE"
        const val ACTION_CLICK = "com.jarvis.assistant.CURSOR_CLICK"

        // Intent extras
        const val EXTRA_X = "cursor_x"
        const val EXTRA_Y = "cursor_y"
        const val EXTRA_AI_ACTIVE = "ai_active"

        // Animation
        private const val MOVE_ANIMATION_DURATION_MS = 300L
        private const val CLICK_DELAY_MS = 350L

        // Cursor size
        private const val CURSOR_SIZE_DP = 48

        // JARVIS cyan
        private const val JARVIS_CYAN = 0xFF00E5FF.toInt()
        private const val JARVIS_PURPLE = 0xFF7C4DFF.toInt()

        @Volatile
        var isRunning = false
            private set
    }

    // ─── Core Components ───────────────────────────────────────────────
    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var cursorView: CursorOverlayView? = null
    private var layoutParams: WindowManager.LayoutParams? = null

    // ─── Cursor State ──────────────────────────────────────────────────
    @Volatile private var cursorX: Int = 0
    @Volatile private var cursorY: Int = 0
    @Volatile private var cursorVisible: Boolean = false
    @Volatile private var aiControlMode: Boolean = false

    // ─── Animation ─────────────────────────────────────────────────────
    private var moveAnimator: android.animation.ValueAnimator? = null

    // ─── Screen Metrics ────────────────────────────────────────────────
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0

    // ─── Drag State (manual mode) ──────────────────────────────────────
    private var isDragging = false
    private var dragInitialTouchX = 0f
    private var dragInitialTouchY = 0f
    private var dragInitialPosX = 0
    private var dragInitialPosY = 0

    // ────────────────────────────────────────────────────────────────────
    // Service Lifecycle
    // ────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels

        // Default position: screen center
        cursorX = screenWidth / 2
        cursorY = screenHeight / 2

        // Register in JarviewModel
        JarviewModel.overlayCursorService = WeakReference(this)
        JarviewModel.cursorX = cursorX
        JarviewModel.cursorY = cursorY

        Log.i(TAG, "OverlayCursorService created — screen=${screenWidth}x${screenHeight}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_SHOW -> showCursor()
            ACTION_HIDE -> hideCursor()
            ACTION_MOVE -> {
                val x = intent.getIntExtra(EXTRA_X, cursorX)
                val y = intent.getIntExtra(EXTRA_Y, cursorY)
                moveCursorTo(x, y)
            }
            ACTION_AI_MODE -> {
                val active = intent.getBooleanExtra(EXTRA_AI_ACTIVE, true)
                setAiControlMode(active)
            }
            ACTION_CLICK -> {
                val x = intent.getIntExtra(EXTRA_X, cursorX)
                val y = intent.getIntExtra(EXTRA_Y, cursorY)
                clickAt(x, y)
            }
        }

        // Start as foreground service on first invocation
        if (!isRunning) {
            val notification = buildNotification("JARVIS Cursor — Active")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start foreground: ${e.message}", e)
                // Fallback: try without foreground service type
                try {
                    startForeground(NOTIFICATION_ID, notification)
                } catch (e2: Exception) {
                    Log.e(TAG, "Foreground start completely failed: ${e2.message}", e2)
                }
            }

            isRunning = true
            JarviewModel.cursorServiceRunning = true

            // Auto-show cursor on start
            showCursor()

            Log.i(TAG, "OverlayCursorService STARTED as foreground service")
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // Cancel ongoing animations
        moveAnimator?.cancel()
        moveAnimator = null

        // Remove overlay
        removeOverlay()

        // Unregister from JarviewModel
        if (JarviewModel.overlayCursorService?.get() == this) {
            JarviewModel.overlayCursorService = null
        }
        JarviewModel.cursorServiceRunning = false

        isRunning = false
        Log.i(TAG, "OverlayCursorService destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart service when app is swiped from recents
        val restartIntent = Intent(this, OverlayCursorService::class.java).apply {
            action = ACTION_START
        }
        val pendingIntent = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + 1500,
            pendingIntent
        )
        Log.i(TAG, "Service restart scheduled after task removal")
        super.onTaskRemoved(rootIntent)
    }

    // ────────────────────────────────────────────────────────────────────
    // Public Cursor Control API
    // ────────────────────────────────────────────────────────────────────

    /**
     * Animate cursor to the specified screen coordinates.
     * Thread-safe: posts to main handler.
     */
    fun moveCursorTo(x: Int, y: Int) {
        mainHandler.post {
            moveCursorToInternal(x, y)
        }
    }

    /**
     * Move cursor then perform a click gesture at the target position.
     * Uses GestureController to dispatch the tap via AccessibilityService.
     */
    fun clickAt(x: Int, y: Int) {
        mainHandler.post {
            moveCursorToInternal(x, y)
            // Schedule the click after the animation completes
            mainHandler.postDelayed({
                performClickAtCursor()
            }, MOVE_ANIMATION_DURATION_MS + CLICK_DELAY_MS)
        }
    }

    /**
     * Show the cursor overlay.
     */
    fun showCursor() {
        mainHandler.post {
            if (cursorVisible) return@post
            addOverlay()
            cursorVisible = true
            JarviewModel.cursorVisible = true
            JarviewModel.sendEventToUi("cursor_shown", mapOf(
                "x" to cursorX, "y" to cursorY
            ))
            Log.d(TAG, "Cursor shown at ($cursorX, $cursorY)")
        }
    }

    /**
     * Hide the cursor overlay.
     */
    fun hideCursor() {
        mainHandler.post {
            if (!cursorVisible) return@post
            removeOverlay()
            cursorVisible = false
            JarviewModel.cursorVisible = false
            JarviewModel.sendEventToUi("cursor_hidden", emptyMap())
            Log.d(TAG, "Cursor hidden")
        }
    }

    /**
     * Set AI control mode.
     * When active: cursor shows crosshair + rotating arc, touch events pass through.
     * When inactive: cursor shows arrow only, cursor is draggable.
     */
    fun setAiControlMode(active: Boolean) {
        mainHandler.post {
            aiControlMode = active
            JarviewModel.computerUseActive = active

            // Update overlay flags for touch pass-through
            updateOverlayTouchMode()

            // Redraw cursor with new mode
            cursorView?.invalidate()

            JarviewModel.sendEventToUi("cursor_mode_changed", mapOf(
                "ai_control" to active
            ))
            Log.i(TAG, "AI control mode: $active")
        }
    }

    /**
     * Get current cursor position.
     */
    fun getCursorPosition(): Pair<Int, Int> = Pair(cursorX, cursorY)

    // ────────────────────────────────────────────────────────────────────
    // Internal Movement
    // ────────────────────────────────────────────────────────────────────

    /**
     * Internal: Animate cursor to position using ValueAnimator.
     * MUST be called on the main thread.
     */
    private fun moveCursorToInternal(targetX: Int, targetY: Int) {
        // Clamp to screen bounds
        val clampedX = targetX.coerceIn(0, screenWidth)
        val clampedY = targetY.coerceIn(0, screenHeight)

        // Cancel any ongoing animation
        moveAnimator?.cancel()

        val startX = cursorX
        val startY = cursorY

        // Skip animation if already at target
        if (startX == clampedX && startY == clampedY) return

        moveAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = MOVE_ANIMATION_DURATION_MS
            interpolator = AccelerateDecelerateInterpolator()

            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val currentX = (startX + (clampedX - startX) * fraction).toInt()
                val currentY = (startY + (clampedY - startY) * fraction).toInt()
                updateCursorPosition(currentX, currentY)
            }

            start()
        }
    }

    /**
     * Update cursor position immediately (no animation).
     * MUST be called on the main thread.
     */
    private fun updateCursorPosition(x: Int, y: Int) {
        cursorX = x
        cursorY = y

        // Sync to JarviewModel
        JarviewModel.cursorX = x
        JarviewModel.cursorY = y

        // Update overlay window position
        try {
            layoutParams?.let { params ->
                // Position the overlay so the cursor hotspot (tip) is at (x, y)
                params.x = x
                params.y = y
                windowManager?.updateViewLayout(cursorView, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating cursor position: ${e.message}")
        }

        // Notify cursor view for redraw
        cursorView?.invalidate()
    }

    /**
     * Perform a click at the current cursor position using GestureController.
     */
    private fun performClickAtCursor() {
        val clickX = cursorX
        val clickY = cursorY

        JarviewModel.sendEventToUi("cursor_click", mapOf(
            "x" to clickX, "y" to clickY
        ))

        val success = GestureController.performTap(clickX, clickY)
        Log.d(TAG, "Click at ($clickX, $clickY) — success=$success")
    }

    // ────────────────────────────────────────────────────────────────────
    // Overlay Window Management
    // ────────────────────────────────────────────────────────────────────

    /**
     * Add the cursor overlay to the WindowManager.
     * MUST be called on the main thread.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        if (cursorView != null) return // Already added

        try {
            val density = resources.displayMetrics.density
            val cursorSizePx = (CURSOR_SIZE_DP * density).toInt()

            // Create custom cursor view
            cursorView = CursorOverlayView(this, cursorSizePx).apply {
                setAiMode(aiControlMode)
            }

            // Layout params for system overlay
            val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

            val flags = if (aiControlMode) {
                // AI mode: pass through all touch events
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            } else {
                // Manual mode: cursor is draggable, but doesn't steal focus
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            }

            layoutParams = WindowManager.LayoutParams(
                cursorSizePx,
                cursorSizePx,
                layoutType,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = cursorX
                y = cursorY
            }

            // Set up touch listener for manual drag mode
            cursorView?.setOnTouchListener { _, event ->
                handleDragTouchEvent(event)
            }

            windowManager?.addView(cursorView, layoutParams)
            Log.i(TAG, "Cursor overlay added at ($cursorX, $cursorY)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add cursor overlay — overlay permission?", e)
        }
    }

    /**
     * Remove the cursor overlay from the WindowManager.
     * MUST be called on the main thread.
     */
    private fun removeOverlay() {
        try {
            cursorView?.let {
                windowManager?.removeView(it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error removing cursor overlay: ${e.message}")
        }
        cursorView = null
        layoutParams = null
    }

    /**
     * Update overlay flags when switching between AI and manual modes.
     * MUST be called on the main thread.
     */
    private fun updateOverlayTouchMode() {
        if (cursorView == null || layoutParams == null) return

        try {
            layoutParams?.let { params ->
                if (aiControlMode) {
                    // AI mode: touch events pass through to apps underneath
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    // Manual mode: cursor intercepts touch for dragging
                    params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                }
                windowManager?.updateViewLayout(cursorView, params)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating overlay touch mode: ${e.message}")
        }
    }

    /**
     * Handle touch events for cursor dragging in manual mode.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun handleDragTouchEvent(event: MotionEvent): Boolean {
        if (aiControlMode) return false // Don't intercept in AI mode

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isDragging = false
                dragInitialTouchX = event.rawX
                dragInitialTouchY = event.rawY
                dragInitialPosX = cursorX
                dragInitialPosY = cursorY
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - dragInitialTouchX
                val dy = event.rawY - dragInitialTouchY

                if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                    isDragging = true
                }

                if (isDragging) {
                    val newX = (dragInitialPosX + dx).toInt().coerceIn(0, screenWidth)
                    val newY = (dragInitialPosY + dy).toInt().coerceIn(0, screenHeight)
                    updateCursorPosition(newX, newY)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    JarviewModel.sendEventToUi("cursor_moved", mapOf(
                        "x" to cursorX, "y" to cursorY, "source" to "manual"
                    ))
                }
                isDragging = false
                return true
            }
        }
        return false
    }

    // ────────────────────────────────────────────────────────────────────
    // Foreground Notification
    // ────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS Cursor Overlay",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "JARVIS AI cursor overlay is active"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 2,
            Intent(this, OverlayCursorService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Cursor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Cursor", stopIntent)
            .build()
    }

    // ────────────────────────────────────────────────────────────────────
    // Custom Cursor View — Raw Canvas Drawing
    // ────────────────────────────────────────────────────────────────────

    /**
     * Custom View that draws the JARVIS cursor overlay.
     *
     * Drawing modes:
     *   - Idle: White/cyan arrow cursor with pulsing glow
     *   - AI Active: Cyan circle + crosshair + animated rotating arc
     */
    private inner class CursorOverlayView(
        context: Context,
        private val sizePx: Int
    ) : View(context) {

        private var isAiMode = false
        private var animationAngle = 0f
        private var pulsePhase = 0f
        private var glowAlpha = 0.4f

        // ── Paints ──────────────────────────────────────────────────────

        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val cursorStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JARVIS_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * resources.displayMetrics.density
            isAntiAlias = true
        }

        private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val aiCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JARVIS_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            isAntiAlias = true
        }

        private val aiCircleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JARVIS_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * resources.displayMetrics.density
            isAntiAlias = true
        }

        private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JARVIS_CYAN
            style = Paint.Style.STROKE
            strokeWidth = 2.5f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val arcSecondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = JARVIS_PURPLE
            style = Paint.Style.STROKE
            strokeWidth = 2f * resources.displayMetrics.density
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val coreDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            isAntiAlias = true
        }

        // ── Arrow cursor path (desktop-style pointer) ──────────────────
        private val arrowPath = Path()
        private val arrowOutlinePath = Path()

        private val density = resources.displayMetrics.density

        init {
            buildArrowPath()
            startAnimationLoop()
        }

        fun setAiMode(active: Boolean) {
            isAiMode = active
        }

        /**
         * Build the arrow cursor path — classic desktop mouse pointer shape.
         * The hotspot (tip) is at (0, 0).
         */
        private fun buildArrowPath() {
            val s = sizePx * 0.75f // Arrow size (slightly smaller than view)
            arrowPath.reset()
            arrowPath.moveTo(0f, 0f)                          // Tip (hotspot)
            arrowPath.lineTo(0f, s)                            // Left edge down
            arrowPath.lineTo(s * 0.28f, s * 0.72f)            // Inner notch
            arrowPath.lineTo(s * 0.48f, s)                    // Tail right
            arrowPath.lineTo(s * 0.58f, s * 0.88f)            // Inner notch right
            arrowPath.lineTo(s * 0.36f, s * 0.6f)             // Inner notch back
            arrowPath.lineTo(s * 0.75f, s * 0.6f)             // Right point
            arrowPath.close()

            // Outline path (slightly larger for stroke)
            arrowOutlinePath.set(arrowPath)
        }

        /**
         * Start the animation loop for pulse and rotation.
         * Uses postOnAnimation for 60fps rendering without ObjectAnimator overhead.
         */
        private fun startAnimationLoop() {
            val frameCallback = object : Runnable {
                override fun run() {
                    if (!isAttachedToWindow) return

                    // Update pulse phase (0..2PI cycle)
                    pulsePhase += 0.06f
                    if (pulsePhase > 2f * Math.PI.toFloat()) {
                        pulsePhase -= 2f * Math.PI.toFloat()
                    }
                    glowAlpha = 0.35f + 0.35f * Math.sin(pulsePhase.toDouble()).toFloat()

                    // Update rotation angle for AI arc
                    if (isAiMode) {
                        animationAngle += 4f
                        if (animationAngle >= 360f) animationAngle -= 360f
                    }

                    invalidate()
                    postOnAnimation(this)
                }
            }
            postOnAnimation(frameCallback)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            if (isAiMode) {
                drawAiCursor(canvas)
            } else {
                drawArrowCursor(canvas)
            }
        }

        /**
         * Draw the idle arrow cursor with pulsing glow.
         */
        private fun drawArrowCursor(canvas: Canvas) {
            val cx = sizePx * 0.375f  // Center X of the arrow
            val cy = sizePx * 0.5f    // Center Y of the arrow

            // ── Pulsing glow ──
            val glowRadius = sizePx * 0.6f * (0.85f + glowAlpha * 0.3f)
            glowPaint.shader = RadialGradient(
                cx, cy, glowRadius,
                intArrayOf(
                    JARVIS_CYAN and 0x00FFFFFF or ((glowAlpha * 0.3f * 255).toInt() shl 24),
                    JARVIS_CYAN and 0x00FFFFFF or ((glowAlpha * 0.1f * 255).toInt() shl 24),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, glowRadius, glowPaint)

            // ── Arrow body fill ──
            cursorPaint.color = Color.WHITE
            canvas.drawPath(arrowPath, cursorPaint)

            // ── Arrow outline ──
            cursorStrokePaint.alpha = 200
            canvas.drawPath(arrowOutlinePath, cursorStrokePaint)
        }

        /**
         * Draw the AI-active cursor: circle + crosshair + rotating arc.
         */
        private fun drawAiCursor(canvas: Canvas) {
            val cx = sizePx / 2f
            val cy = sizePx / 2f
            val densityScale = density

            // ── Outer glow ring ──
            val outerR = sizePx * 0.48f * (0.85f + glowAlpha * 0.3f)
            glowPaint.shader = RadialGradient(
                cx, cy, outerR,
                intArrayOf(
                    JARVIS_CYAN and 0x00FFFFFF or ((glowAlpha * 0.2f * 255).toInt() shl 24),
                    JARVIS_CYAN and 0x00FFFFFF or ((glowAlpha * 0.08f * 255).toInt() shl 24),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, outerR, glowPaint)

            // ── Middle glow ring ──
            val midR = sizePx * 0.25f
            glowPaint.shader = RadialGradient(
                cx, cy, midR,
                intArrayOf(
                    JARVIS_CYAN and 0x00FFFFFF or ((glowAlpha * 0.35f * 255).toInt() shl 24),
                    JARVIS_CYAN and 0x00FFFFFF or ((0.1f * 255).toInt() shl 24),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, midR, glowPaint)

            // ── Crosshair lines ──
            val crossLen = sizePx * 0.4f
            val coreR = 4f * densityScale
            val crossAlpha = (0.35f * glowAlpha * 2.5f).coerceAtMost(1f)

            crosshairPaint.alpha = (crossAlpha * 255).toInt()
            // Horizontal
            canvas.drawLine(cx - crossLen, cy, cx - coreR - 2f * densityScale, cy, crosshairPaint)
            canvas.drawLine(cx + coreR + 2f * densityScale, cy, cx + crossLen, cy, crosshairPaint)
            // Vertical
            canvas.drawLine(cx, cy - crossLen, cx, cy - coreR - 2f * densityScale, crosshairPaint)
            canvas.drawLine(cx, cy + coreR + 2f * densityScale, cx, cy + crossLen, crosshairPaint)

            // ── Rotating arc (primary) ──
            val arcR = sizePx * 0.32f
            arcPaint.alpha = (glowAlpha * 1.8f * 255).toInt().coerceAtMost(230)
            canvas.drawArc(
                cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                animationAngle,
                90f,
                false,
                arcPaint
            )

            // ── Rotating arc (secondary, purple) ──
            arcSecondaryPaint.alpha = (glowAlpha * 1.4f * 255).toInt().coerceAtMost(180)
            canvas.drawArc(
                cx - arcR, cy - arcR, cx + arcR, cy + arcR,
                animationAngle + 180f,
                60f,
                false,
                arcSecondaryPaint
            )

            // ── Core circle outline ──
            aiCirclePaint.alpha = (glowAlpha * 2.0f * 255).toInt().coerceAtMost(220)
            canvas.drawCircle(cx, cy, coreR + 2f * densityScale, aiCirclePaint)

            // ── Core dot (white center) ──
            coreDotPaint.alpha = 240
            canvas.drawCircle(cx, cy, coreR, coreDotPaint)

            // ── Small coordinate indicator tick ──
            crosshairPaint.alpha = (0.4f * 255).toInt()
            canvas.drawLine(
                cx + 8f * densityScale, cy - 8f * densityScale,
                cx + 8f * densityScale, cy - 4f * densityScale,
                crosshairPaint
            )
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(sizePx, sizePx)
        }
    }
}

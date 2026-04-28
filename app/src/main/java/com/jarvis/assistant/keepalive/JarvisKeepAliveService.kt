package com.jarvis.assistant.keepalive

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.content.pm.ServiceInfo
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel
import java.util.Timer
import java.util.TimerTask

/**
 * JARVIS Keep-Alive Service — Ensures JARVIS stays running.
 *
 * Three-layer persistence:
 *   1. Foreground service with partial wake lock + 30-second health checks
 *   2. START_STICKY — system restarts if killed
 *   3. onTaskRemoved() auto-restart + BootCompletedReceiver for boot persistence
 */
class JarvisKeepAliveService : Service() {

    companion object {
        private const val TAG = "JarvisKeepAlive"
        private const val CHANNEL_ID = "jarvis_keepalive_channel"
        private const val NOTIFICATION_ID = 2000
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L // 30 seconds
        private const val WAKELOCK_TIMEOUT_MS = 30 * 60 * 1000L // 30 minutes
        const val ACTION_START = "com.jarvis.assistant.KEEPALIVE_START"
        const val ACTION_STOP = "com.jarvis.assistant.KEEPALIVE_STOP"
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var healthCheckTimer: Timer? = null
    private var healthCheckCount = 0
    private var isRunning = false

    // ─── Service Lifecycle ──────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "Keep-alive service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) return START_STICKY

        startForegroundNotification()
        acquireWakeLock()
        startHealthChecks()

        isRunning = true
        JarviewModel.keepAliveRunning = true

        Log.i(TAG, "Keep-alive service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopHealthChecks()
        releaseWakeLock()
        isRunning = false
        JarviewModel.keepAliveRunning = false
        Log.i(TAG, "Keep-alive service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Auto-restart when task is swiped away
        Log.w(TAG, "Task removed — scheduling restart")
        val restartIntent = Intent(this, JarvisKeepAliveService::class.java).apply {
            action = ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent)
        } else {
            startService(restartIntent)
        }
        super.onTaskRemoved(rootIntent)
    }

    // ─── Wake Lock ──────────────────────────────────────────────

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Jarvis::KeepAlive"
            ).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
            Log.d(TAG, "Partial wake lock acquired (30 min timeout)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing wake lock", e)
        }
        wakeLock = null
    }

    // ─── Health Checks ──────────────────────────────────────────

    private fun startHealthChecks() {
        healthCheckTimer = Timer("JarvisHealthCheck", true).apply {
            scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    performHealthCheck()
                }
            }, HEALTH_CHECK_INTERVAL_MS, HEALTH_CHECK_INTERVAL_MS)
        }
        Log.d(TAG, "Health checks started (every ${HEALTH_CHECK_INTERVAL_MS / 1000}s)")
    }

    private fun stopHealthChecks() {
        healthCheckTimer?.cancel()
        healthCheckTimer = null
    }

    private fun performHealthCheck() {
        healthCheckCount++
        JarviewModel.healthCheckCount = healthCheckCount

        var activeServices = mutableListOf<String>()

        // NOTE: SpeechService and SensoryService are no longer auto-restarted here.
        // These legacy services created their own AudioRecord instances that
        // conflicted with the AudioEngine used by the ViewModel for voice commands.
        // AudioEngine now handles all audio I/O through the ViewModel directly.

        // Check Accessibility service
        if (JarviewModel.accessibilityService?.get() != null) {
            activeServices.add("Accessibility")
        }

        // Check MQTT connection
        if (JarviewModel.mqttConnected) {
            activeServices.add("Smart Home")
        }

        // Re-acquire wake lock if expired
        wakeLock?.let {
            if (!it.isHeld) {
                Log.w(TAG, "Wake lock expired — re-acquiring")
                acquireWakeLock()
            }
        }

        // Update notification with active services
        val status = if (activeServices.isNotEmpty()) {
            "Active: ${activeServices.joinToString(", ")}"
        } else {
            "Monitoring..."
        }
        updateNotification(status)

        JarviewModel.keepAliveStatus = status
        Log.d(TAG, "Health check #$healthCheckCount — $status")
    }

    // ─── Foreground Notification ────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS Keep Alive",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Background service keep-alive"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForegroundNotification() {
        val notification = buildNotification("JARVIS is active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: must use FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // API 29-33: FOREGROUND_SERVICE_TYPE_MANIFEST is implicit, just use 0
            startForeground(NOTIFICATION_ID, notification, 0)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}

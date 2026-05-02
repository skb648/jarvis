package com.jarvis.assistant.services

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.permissions.PermissionManager

/**
 * JarvisForegroundService — The bulletproof always-listening mic protector.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v7.0) — DEFEATING ANDROID BATTERY KILLS:
 *
 * This service is the LAST LINE OF DEFENSE against Android's aggressive
 * battery optimization. Without it, Android will kill the microphone
 * AudioRecord the moment the app goes to background or the screen turns off.
 *
 * Key features:
 *   1. FOREGROUND_SERVICE_TYPE_MICROPHONE — Tells Android this is a
 *      microphone service that MUST NOT be killed (Android 14+ requirement)
 *   2. Persistent notification — Required by Android for all foreground
 *      services. Shows "JARVIS is active — Always listening"
 *   3. START_STICKY — If the service is killed, Android restarts it
 *   4. onTaskRemoved() — When the user swipes the app from recents,
 *      schedules a restart via AlarmManager
 *   5. Battery optimization check — Logs a warning if the user hasn't
 *      disabled battery optimization for JARVIS
 * ═══════════════════════════════════════════════════════════════════════
 */
class JarvisForegroundService : Service() {

    companion object {
        private const val TAG = "JarvisForeground"
        private const val CHANNEL_ID = JarvisNotificationChannels.CHANNEL_JARVIS_SERVICES
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.assistant.FOREGROUND_START"
        const val ACTION_STOP = "com.jarvis.assistant.FOREGROUND_STOP"
        const val ACTION_UPDATE_STATUS = "com.jarvis.assistant.FOREGROUND_UPDATE_STATUS"
        
        @Volatile
        var isRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "JARVIS Foreground Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATUS -> {
                // Update the notification text with current status
                val statusText = intent.getStringExtra("status") ?: "JARVIS is active — Always listening"
                updateNotification(statusText)
                return START_STICKY
            }
        }

        if (isRunning) return START_STICKY

        // Check battery optimization and log warning if not bypassed
        if (!PermissionManager.isIgnoringBatteryOptimizations(this)) {
            Log.w(TAG, "⚠️ Battery optimization NOT bypassed — Android may kill this service! " +
                "Call ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS to fix this.")
        }

        val notification = buildNotification("JARVIS is active — Always listening")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        JarviewModel.foregroundServiceRunning = true

        Log.i(TAG, "JARVIS Foreground Service STARTED — mic stays alive in background " +
            "(batteryOptBypassed=${PermissionManager.isIgnoringBatteryOptimizations(this)})")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        JarviewModel.foregroundServiceRunning = false
        Log.i(TAG, "JARVIS Foreground Service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Restart the service when the app is swiped away from recents
        // This is the KEY to "always listening"
        val restartIntent = Intent(this, JarvisForegroundService::class.java).apply {
            action = ACTION_START
        }
        val pendingIntent = PendingIntent.getService(
            this, 1, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            android.os.SystemClock.elapsedRealtime() + 1000,
            pendingIntent
        )
        Log.i(TAG, "Service restart scheduled after task removal")
        super.onTaskRemoved(rootIntent)
    }

    private fun createNotificationChannel() {
        JarvisNotificationChannels.ensureChannels(this)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, JarvisForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopIntent)
            .build()
    }

    /**
     * Update the foreground notification text.
     * Useful for showing current state (e.g., "Listening for wake word...", "Processing...")
     */
    private fun updateNotification(text: String) {
        try {
            val notification = buildNotification(text)
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }
}

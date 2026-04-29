package com.jarvis.assistant.services

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel

class JarvisForegroundService : Service() {

    companion object {
        private const val TAG = "JarvisForeground"
        private const val CHANNEL_ID = "jarvis_always_listening"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.assistant.FOREGROUND_START"
        const val ACTION_STOP = "com.jarvis.assistant.FOREGROUND_STOP"
        
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
        }

        if (isRunning) return START_STICKY

        val notification = buildNotification("JARVIS is active — Always listening")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        JarviewModel.foregroundServiceRunning = true

        Log.i(TAG, "JARVIS Foreground Service STARTED — mic stays alive in background")
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
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS Always Listening",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "JARVIS is always listening for your voice commands"
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
}

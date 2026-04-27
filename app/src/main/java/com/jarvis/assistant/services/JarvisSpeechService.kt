package com.jarvis.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel

/**
 * JARVIS Speech Service — Silent foreground service for AudioEngine mic access.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * REWRITE NOTES:
 *
 * This service NO LONGER uses SpeechRecognizer (which causes system beeps).
 * Instead, it provides a foreground service context so that AudioEngine
 * (which uses pure AudioRecord with VOICE_COMMUNICATION source) can
 * capture microphone audio in the background without being killed.
 *
 * The actual audio processing is done by:
 *   - AudioEngine.kt (silent PCM loop + RMS calculation)
 *   - JarvisViewModel.kt (wires AudioEngine to UI and AI pipeline)
 *   - Rust wake_word::detect() (JNI wake word detection)
 *
 * This service only:
 *   1. Creates the foreground notification (required for background mic)
 *   2. Updates JarviewModel state so the UI knows the service is running
 *   3. Provides a lifecycle boundary for background audio
 * ═══════════════════════════════════════════════════════════════════════
 */
class JarvisSpeechService : Service() {

    companion object {
        private const val TAG = "JarvisSpeech"
        private const val CHANNEL_ID = "jarvis_speech_channel"
        private const val NOTIFICATION_ID = 2001
        const val ACTION_START = "com.jarvis.assistant.SPEECH_START"
        const val ACTION_STOP = "com.jarvis.assistant.SPEECH_STOP"
    }

    private var isRunning = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var bgHandlerThread: HandlerThread
    private lateinit var bgHandler: Handler

    // ─── Service Lifecycle ──────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        bgHandlerThread = HandlerThread("JarvisSpeechBg").also { it.start() }
        bgHandler = Handler(bgHandlerThread.looper)
        createNotificationChannel()
        Log.d(TAG, "Speech service created (AudioEngine-based, zero-beep)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) return START_STICKY

        val notification = buildNotification("JARVIS listening (silent mode)")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        JarviewModel.speechServiceRunning = true
        JarviewModel.speechState = "wake_word"

        Log.i(TAG, "Speech service started — AudioEngine-based silent mode (NO SpeechRecognizer)")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        JarviewModel.speechServiceRunning = false
        JarviewModel.speechState = "stopped"
        bgHandlerThread.quitSafely()
        Log.i(TAG, "Speech service destroyed")
    }

    // ─── Foreground Notification ────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS Voice Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Silent background voice monitoring"
            setShowBadge(false)
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

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

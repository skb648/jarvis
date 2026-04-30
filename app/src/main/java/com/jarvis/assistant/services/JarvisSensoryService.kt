package com.jarvis.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel
import kotlinx.coroutines.*

/**
 * JARVIS Sensory Service — Foreground service for audio amplitude monitoring
 * and screen context sensing.
 *
 * Provides real-time audio amplitude data at 20fps for the holographic orb
 * animation, and reads on-screen text via the AccessibilityService.
 *
 * Individual sensors can be dynamically enabled/disabled via broadcast intents.
 */
class JarvisSensoryService : Service() {

    companion object {
        private const val TAG = "JarvisSensory"
        private const val CHANNEL_ID = "jarvis_sensory_channel"
        private const val NOTIFICATION_ID = 2002
        const val ACTION_START = "com.jarvis.assistant.SENSORY_START"
        const val ACTION_STOP = "com.jarvis.assistant.SENSORY_STOP"
        const val ACTION_ENABLE_AUDIO = "com.jarvis.assistant.ENABLE_AUDIO"
        const val ACTION_DISABLE_AUDIO = "com.jarvis.assistant.DISABLE_AUDIO"
        const val ACTION_ENABLE_SCREEN = "com.jarvis.assistant.ENABLE_SCREEN"
        const val ACTION_DISABLE_SCREEN = "com.jarvis.assistant.DISABLE_SCREEN"

        // Audio configuration
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L // 20fps
    }

    private var isRunning = false
    private var audioEnabled = true
    private var screenTextEnabled = true

    // Audio amplitude polling — reads from JarviewModel instead of creating a competing AudioRecord
    private var audioPollingJob: Job? = null

    // Screen text polling
    private var screenTextJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private lateinit var bgHandlerThread: HandlerThread
    private lateinit var bgHandler: Handler

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_ENABLE_AUDIO -> {
                    audioEnabled = true
                    startAudioAmplitude()
                    Log.d(TAG, "Audio amplitude enabled")
                }
                ACTION_DISABLE_AUDIO -> {
                    audioEnabled = false
                    stopAudioAmplitude()
                    Log.d(TAG, "Audio amplitude disabled")
                }
                ACTION_ENABLE_SCREEN -> {
                    screenTextEnabled = true
                    startScreenTextPolling()
                    Log.d(TAG, "Screen text sensing enabled")
                }
                ACTION_DISABLE_SCREEN -> {
                    screenTextEnabled = false
                    stopScreenTextPolling()
                    Log.d(TAG, "Screen text sensing disabled")
                }
            }
        }
    }

    // ─── Service Lifecycle ──────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        bgHandlerThread = HandlerThread("JarvisSensoryBg").also { it.start() }
        bgHandler = Handler(bgHandlerThread.looper)
        // Register command receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_ENABLE_AUDIO)
            addAction(ACTION_DISABLE_AUDIO)
            addAction(ACTION_ENABLE_SCREEN)
            addAction(ACTION_DISABLE_SCREEN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }

        createNotificationChannel()
        Log.d(TAG, "Sensory service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }

        if (isRunning) return START_STICKY

        val notification = buildNotification("Sensors active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isRunning = true
        JarviewModel.sensoryServiceRunning = true

        if (audioEnabled) startAudioAmplitude()
        if (screenTextEnabled) startScreenTextPolling()

        Log.i(TAG, "Sensory service started")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopAudioAmplitude()
        stopScreenTextPolling()
        unregisterReceiver(commandReceiver)
        isRunning = false
        JarviewModel.sensoryServiceRunning = false
        serviceScope.cancel()
        bgHandlerThread.quitSafely()
        Log.i(TAG, "Sensory service destroyed")
    }

    // ─── Audio Amplitude Monitoring ─────────────────────────────
    // Instead of creating our own AudioRecord (which conflicts with AudioEngine),
    // we read the amplitude from JarviewModel.audioAmplitude which is updated
    // by AudioEngine's callback. This avoids the dual-AudioRecord conflict.

    private fun startAudioAmplitude() {
        if (audioPollingJob?.isActive == true || !audioEnabled) return

        audioPollingJob = serviceScope.launch {
            Log.d(TAG, "Audio amplitude polling started (reading from JarviewModel)")
            while (isActive && audioEnabled) {
                // Amplitude is already being set by AudioEngine via onAmplitudeUpdate.
                // We just forward it to the UI at 20fps.
                val amplitude = JarviewModel.audioAmplitude.toFloat()
                JarviewModel.audioRms = amplitude.toDouble()
                delay(AMPLITUDE_UPDATE_INTERVAL_MS)
            }
        }
        Log.d(TAG, "Audio amplitude monitoring started via JarviewModel at ${1000.0 / AMPLITUDE_UPDATE_INTERVAL_MS}fps")
    }

    private fun stopAudioAmplitude() {
        audioPollingJob?.cancel()
        audioPollingJob = null
    }

    // ─── Screen Text Polling ────────────────────────────────────

    private fun startScreenTextPolling() {
        if (screenTextJob?.isActive == true || !screenTextEnabled) return

        screenTextJob = serviceScope.launch {
            Log.d(TAG, "Screen text polling started (coroutine-based)")
            while (isActive && screenTextEnabled) {
                try {
                    val screenText = withContext(Dispatchers.Main) {
                        val accessibilityService = JarviewModel.accessibilityService?.get()
                        if (accessibilityService != null) {
                            try {
                                accessibilityService.extractScreenText() ?: ""
                            } catch (e: Exception) {
                                Log.w(TAG, "extractScreenText on main thread failed: ${e.message}")
                                ""
                            }
                        } else {
                            ""
                        }
                    }
                    val currentApp = JarviewModel.foregroundApp

                    JarviewModel.screenTextData = screenText
                    JarviewModel.sendEventToUi("screen_text", mapOf(
                        "text" to screenText,
                        "app" to currentApp
                    ))

                    delay(2000) // Poll every 2 seconds
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Screen text polling error", e)
                    delay(2000)
                }
            }
        }
        Log.d(TAG, "Screen text polling started")
    }

    private fun stopScreenTextPolling() {
        screenTextJob?.cancel()
        screenTextJob = null
    }

    // ─── Foreground Notification ────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "JARVIS Sensory Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Sensor monitoring service"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("JARVIS Sensors")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}

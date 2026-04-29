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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel

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
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 50L // 20fps
    }

    private var isRunning = false
    private var audioEnabled = true
    private var screenTextEnabled = true

    // Audio recording
    private var audioRecord: AudioRecord? = null
    private var audioThread: Thread? = null
    private var isAudioRecording = false
    private var bufferSize = 0

    // Screen text polling
    private var screenTextThread: Thread? = null
    private var isScreenTextPolling = false

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
        bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        // BUG FIX (BUG-26): Validate buffer size before creating AudioRecord
        if (bufferSize <= 0) {
            Log.e(TAG, "AudioRecord.getMinBufferSize returned $bufferSize — audio recording not available")
            bufferSize = 0
        }

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
        bgHandlerThread.quitSafely()
        Log.i(TAG, "Sensory service destroyed")
    }

    // ─── Audio Amplitude Monitoring ─────────────────────────────

    private fun startAudioAmplitude() {
        if (isAudioRecording || !audioEnabled) return

        try {
            // BUG FIX: Use VOICE_COMMUNICATION instead of VOICE_RECOGNITION.
            // VOICE_RECOGNITION triggers system beeps on Samsung, Xiaomi, and
            // other OEM skins. VOICE_COMMUNICATION is zero-beep and includes
            // echo cancellation / noise suppression — better for voice.
            // Falls back to MIC if VOICE_COMMUNICATION fails.
            audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            } catch (e: Exception) {
                Log.w(TAG, "VOICE_COMMUNICATION failed, falling back to MIC: ${e.message}")
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord not initialized")
                return
            }

            audioRecord?.startRecording()
            isAudioRecording = true

            audioThread = Thread({
                val buffer = ShortArray(bufferSize / 2)
                while (isAudioRecording && !Thread.interrupted()) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (read > 0) {
                        // Calculate RMS
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += buffer[i].toDouble() * buffer[i].toDouble()
                        }
                        val rms = Math.sqrt(sum / read.toDouble())
                        val peak = buffer.copyOf(read).maxOf { Math.abs(it.toInt()) }

                        // Convert to dB
                        val db = 20.0 * Math.log10(rms / 32767.0 + 0.0001)

                        // Update JarviewModel for UI
                        JarviewModel.audioAmplitude = db
                        JarviewModel.audioRms = rms
                        JarviewModel.audioPeak = peak.toDouble()
                    }

                    try {
                        Thread.sleep(AMPLITUDE_UPDATE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }, "JarvisAudioAmplitude").also { it.start() }

            Log.d(TAG, "Audio amplitude monitoring started at ${1000.0 / AMPLITUDE_UPDATE_INTERVAL_MS}fps")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing RECORD_AUDIO permission", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio amplitude", e)
        }
    }

    private fun stopAudioAmplitude() {
        isAudioRecording = false
        audioThread?.interrupt()
        audioThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
    }

    // ─── Screen Text Polling ────────────────────────────────────

    private fun startScreenTextPolling() {
        if (isScreenTextPolling || !screenTextEnabled) return

        isScreenTextPolling = true
        screenTextThread = Thread({
            while (isScreenTextPolling && !Thread.interrupted()) {
                try {
                    // Read screen text from AccessibilityService via JarviewModel
                    val accessibilityService = JarviewModel.accessibilityService?.get()
                    if (accessibilityService != null) {
                        // BUG FIX (BUG-2): Accessibility tree MUST be accessed on the main thread.
                        // rootInActiveWindow throws IllegalStateException if called from a background thread.
                        // We use a blocking queue to get the result from the main thread.
                        val screenTextResult = java.util.concurrent.LinkedBlockingQueue<String>(1)
                        Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                val text = accessibilityService.extractScreenText()
                                screenTextResult.offer(text ?: "")
                            } catch (e: Exception) {
                                Log.w(TAG, "extractScreenText on main thread failed: ${e.message}")
                                screenTextResult.offer("")
                            }
                        }
                        // Wait up to 3 seconds for the main thread to produce a result
                        val screenText = screenTextResult.poll(3, java.util.concurrent.TimeUnit.SECONDS) ?: ""
                        val currentApp = JarviewModel.foregroundApp

                        JarviewModel.screenTextData = screenText
                        JarviewModel.sendEventToUi("screen_text", mapOf(
                            "text" to screenText,
                            "app" to currentApp
                        ))
                    }

                    Thread.sleep(2000) // Poll every 2 seconds
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Screen text polling error", e)
                }
            }
        }, "JarvisScreenText").also { it.start() }

        Log.d(TAG, "Screen text polling started")
    }

    private fun stopScreenTextPolling() {
        isScreenTextPolling = false
        screenTextThread?.interrupt()
        screenTextThread = null
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

package com.jarvis.assistant.channels

import android.os.Handler
import android.os.Looper
import com.jarvis.assistant.services.JarvisAccessibilityService
import com.jarvis.assistant.services.JarvisForegroundService
import com.jarvis.assistant.services.JarvisSensoryService
import com.jarvis.assistant.services.JarvisSpeechService
import com.jarvis.assistant.keepalive.JarvisKeepAliveService
import java.lang.ref.WeakReference

/**
 * Global shared state singleton for cross-service communication.
 * All fields are @Volatile for thread-safe reads/writes across service threads.
 */
object JarviewModel {

    // ─── Event Sink ───────────────────────────────────────────────
    @Volatile
    var eventSink: ((String, Map<String, Any>) -> Unit)? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    /**
     * Send an event to the UI layer. Always posts to the main thread.
     */
    fun sendEventToUi(type: String, data: Map<String, Any> = emptyMap()) {
        uiHandler.post {
            eventSink?.invoke(type, data)
        }
    }

    // ─── Service References ───────────────────────────────────────
    // BUG FIX (BUG-6): ALL service references use WeakReference to prevent memory leaks.
    // Android system components have their own lifecycle. Strong references in a global
    // singleton prevent GC after the service is destroyed, causing memory leaks.
    // Use .get() to access the service instance.
    @Volatile var accessibilityService: WeakReference<JarvisAccessibilityService>? = null
    @Volatile var foregroundService: WeakReference<JarvisForegroundService>? = null
    @Volatile var speechService: WeakReference<JarvisSpeechService>? = null
    @Volatile var sensoryService: WeakReference<JarvisSensoryService>? = null
    @Volatile var keepAliveService: WeakReference<JarvisKeepAliveService>? = null

    // ─── Permission States ────────────────────────────────────────
    @Volatile var hasOverlayPermission: Boolean = false
    @Volatile var hasAudioPermission: Boolean = false
    @Volatile var hasCameraPermission: Boolean = false
    @Volatile var hasAccessibilityEnabled: Boolean = false
    @Volatile var hasNotificationListener: Boolean = false
    @Volatile var hasUsageStats: Boolean = false
    @Volatile var hasSystemWriteSettings: Boolean = false
    @Volatile var hasBatteryOptimizationBypass: Boolean = false
    @Volatile var runtimePermissionsGranted: Boolean = false

    // ─── Shizuku Status ──────────────────────────────────────────
    @Volatile var shizukuAvailable: Boolean = false
    @Volatile var shizukuPermissionGranted: Boolean = false

    // ─── Keep-Alive Status ────────────────────────────────────────
    @Volatile var keepAliveRunning: Boolean = false
    @Volatile var keepAliveActive: Boolean = false
    @Volatile var keepAliveStatus: String = ""
    @Volatile var healthCheckCount: Int = 0
    @Volatile var lastHealthCheckTimestamp: Long = 0L

    // ─── Screen Context ──────────────────────────────────────────
    @Volatile var foregroundApp: String = ""
    @Volatile var foregroundActivity: String = ""
    @Volatile var screenWidth: Int = 0
    @Volatile var screenHeight: Int = 0
    @Volatile var screenOrientation: Int = 0
    @Volatile var screenBrightness: Int = 0
    @Volatile var lastWindowChangeEvent: String = ""
    @Volatile var lastWindowChangeTimestamp: Long = 0L

    // ─── Sensory Data ────────────────────────────────────────────
    @Volatile var audioAmplitude: Double = 0.0
    @Volatile var audioRms: Double = 0.0
    @Volatile var audioPeak: Double = 0.0
    @Volatile var audioAmplitudeDb: Float = 0f
    @Volatile var audioPeakDb: Float = -100f
    @Volatile var cameraFrameAvailable: Boolean = false
    @Volatile var lastCameraFrameTimestamp: Long = 0L
    @Volatile var sensoryServiceRunning: Boolean = false
    @Volatile var screenTextData: String = ""

    // ─── Foreground Service State ─────────────────────────────────
    @Volatile var foregroundServiceRunning: Boolean = false

    // ─── Speech Service State ─────────────────────────────────────
    @Volatile var speechServiceRunning: Boolean = false
    @Volatile var speechMode: String = ""  // "wake_word" or "command"
    @Volatile var speechState: String = "idle"
    @Volatile var isListening: Boolean = false
    @Volatile var wakeWordDetected: Boolean = false
    @Volatile var voiceCommand: String = ""
    @Volatile var lastVoiceCommand: String = ""
    @Volatile var lastVoiceCommandTimestamp: Long = 0L
    @Volatile var speechErrorCount: Int = 0

    // ─── Brain State ─────────────────────────────────────────────
    @Volatile var brainState: String = "idle"  // idle, thinking, speaking, listening
    @Volatile var lastBrainResponse: String = ""
    @Volatile var lastBrainResponseTimestamp: Long = 0L

    // ─── Smart Home Data ─────────────────────────────────────────
    @Volatile var mqttConnected: Boolean = false
    @Volatile var mqttBroker: String = ""
    @Volatile var mqttClientId: String = ""
    @Volatile var homeAssistantConnected: Boolean = false
    @Volatile var homeAssistantUrl: String = ""
    @Volatile var deviceList: List<Map<String, Any>> = emptyList()
    @Volatile var lastMqttMessage: String = ""
    @Volatile var lastMqttTopic: String = ""

    // ─── Bypass State ────────────────────────────────────────────
    @Volatile var bypassActive: Boolean = false

    // ─── Overlay State ───────────────────────────────────────────
    @Volatile var overlayVisible: Boolean = false
    @Volatile var overlayX: Int = 0
    @Volatile var overlayY: Int = 0

    // ─── API Key (shared for TaskExecutorBridge image generation) ──
    @Volatile var groqApiKey: String = ""

    // ─── Automation State ─────────────────────────────────────────
    @Volatile var activeRoutines: Int = 0
    @Volatile var lastRoutineTriggered: String = ""
    @Volatile var lastRoutineTriggerTimestamp: Long = 0L

    // ─── Widget State ─────────────────────────────────────────────
    @Volatile var widgetBrainState: String = "idle"
    @Volatile var widgetLastCommand: String = ""

    // ─── Utility ──────────────────────────────────────────────────

    /**
     * Update a permission state by permission string.
     */
    fun updatePermissionState(permission: String, granted: Boolean) {
        when (permission) {
            "android.permission.RECORD_AUDIO" -> hasAudioPermission = granted
            "android.permission.CAMERA" -> hasCameraPermission = granted
            "android.permission.SYSTEM_ALERT_WINDOW" -> hasOverlayPermission = granted
        }
    }

    /**
     * Returns a snapshot of all state as a map, useful for diagnostics.
     */
    fun getFullState(): Map<String, Any> = mapOf(
        "hasOverlayPermission" to hasOverlayPermission,
        "hasAudioPermission" to hasAudioPermission,
        "hasCameraPermission" to hasCameraPermission,
        "hasAccessibilityEnabled" to hasAccessibilityEnabled,
        "hasNotificationListener" to hasNotificationListener,
        "hasUsageStats" to hasUsageStats,
        "hasSystemWriteSettings" to hasSystemWriteSettings,
        "hasBatteryOptimizationBypass" to hasBatteryOptimizationBypass,
        "runtimePermissionsGranted" to runtimePermissionsGranted,
        "shizukuAvailable" to shizukuAvailable,
        "shizukuPermissionGranted" to shizukuPermissionGranted,
        "keepAliveActive" to keepAliveActive,
        "healthCheckCount" to healthCheckCount,
        "lastHealthCheckTimestamp" to lastHealthCheckTimestamp,
        "foregroundApp" to foregroundApp,
        "foregroundActivity" to foregroundActivity,
        "screenWidth" to screenWidth,
        "screenHeight" to screenHeight,
        "screenOrientation" to screenOrientation,
        "audioAmplitude" to audioAmplitude,
        "audioAmplitudeDb" to audioAmplitudeDb,
        "audioPeakDb" to audioPeakDb,
        "foregroundServiceRunning" to foregroundServiceRunning,
        "speechServiceRunning" to speechServiceRunning,
        "speechMode" to speechMode,
        "isListening" to isListening,
        "wakeWordDetected" to wakeWordDetected,
        "lastVoiceCommand" to lastVoiceCommand,
        "speechErrorCount" to speechErrorCount,
        "brainState" to brainState,
        "mqttConnected" to mqttConnected,
        "mqttBroker" to mqttBroker,
        "homeAssistantConnected" to homeAssistantConnected,
        "homeAssistantUrl" to homeAssistantUrl,
        "deviceList" to deviceList,
        "overlayVisible" to overlayVisible,
        "activeRoutines" to activeRoutines,
        "lastRoutineTriggered" to lastRoutineTriggered,
        "widgetBrainState" to widgetBrainState,
        "widgetLastCommand" to widgetLastCommand
    )

    /**
     * Reset all volatile state to defaults.
     */
    fun reset() {
        eventSink = null
        accessibilityService = null  // WeakReference cleared; GC can reclaim the service
        foregroundService = null
        speechService = null
        sensoryService = null
        keepAliveService = null
        hasOverlayPermission = false
        hasAudioPermission = false
        hasCameraPermission = false
        hasAccessibilityEnabled = false
        hasNotificationListener = false
        hasUsageStats = false
        hasSystemWriteSettings = false
        hasBatteryOptimizationBypass = false
        runtimePermissionsGranted = false
        shizukuAvailable = false
        shizukuPermissionGranted = false
        keepAliveActive = false
        healthCheckCount = 0
        lastHealthCheckTimestamp = 0L
        foregroundApp = ""
        foregroundActivity = ""
        screenWidth = 0
        screenHeight = 0
        screenOrientation = 0
        audioAmplitude = 0.0
        audioRms = 0.0
        audioPeak = 0.0
        audioAmplitudeDb = 0f
        audioPeakDb = -100f
        cameraFrameAvailable = false
        sensoryServiceRunning = false
        screenTextData = ""
        speechServiceRunning = false
        speechMode = ""
        speechState = "idle"
        isListening = false
        wakeWordDetected = false
        voiceCommand = ""
        lastVoiceCommand = ""
        speechErrorCount = 0
        brainState = "idle"
        lastBrainResponse = ""
        keepAliveRunning = false
        keepAliveStatus = ""
        foregroundServiceRunning = false
        bypassActive = false
        mqttConnected = false
        mqttBroker = ""
        homeAssistantConnected = false
        homeAssistantUrl = ""
        deviceList = emptyList()
        overlayVisible = false
        overlayX = 0
        overlayY = 0
        activeRoutines = 0
        lastRoutineTriggered = ""
        widgetBrainState = "idle"
        widgetLastCommand = ""
    }
}

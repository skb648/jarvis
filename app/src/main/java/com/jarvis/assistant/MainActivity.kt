package com.jarvis.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.jarvis.assistant.data.SettingsRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.ui.navigation.JarvisNavGraph
import com.jarvis.assistant.ui.theme.JarvisTheme
import com.jarvis.assistant.viewmodel.JarvisViewModel

class MainActivity : ComponentActivity() {

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filter { !it.value }.keys
        if (denied.isNotEmpty()) {
            android.util.Log.w(TAG, "Denied permissions: $denied")
        }
        updatePermissionStates()
    }

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsRepository = (application as JarvisApp).settingsRepository

        enableEdgeToEdge()
        requestPermissionsIfNeeded()
        handleIntent(intent)

        setContent {
            JarvisTheme {
                MainContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Main composable content — wires the ViewModel state to the NavGraph.
     *
     * ═══════════════════════════════════════════════════════════════════════
     * CRITICAL FIX (v6): Chat screen voice button now ACTUALLY starts/stops
     * the microphone. Previously, onToggleVoice called toggleVoiceMode()
     * which only flipped a UI flag. Now it calls toggleVoiceMode(context)
     * which starts the AudioEngine.
     * ═══════════════════════════════════════════════════════════════════════
     */
    @Composable
    private fun MainContent() {
        val viewModel: JarvisViewModel = viewModel(
            factory = JarvisViewModel.Factory(settingsRepository)
        )

        // CRITICAL FIX: Pass application context to ViewModel for wake word monitoring
        val context = this
        LaunchedEffect(Unit) {
            viewModel.setApplicationContext(context)
            // Start wake word monitor if enabled
            if (viewModel.isWakeWordEnabled.value) {
                viewModel.startWakeWordMonitor(context)
            }
        }

        val brainState by viewModel.brainState.collectAsState()
        val audioAmplitude by viewModel.audioAmplitude.collectAsState()
        val currentTranscription by viewModel.currentTranscription.collectAsState()
        val lastResponse by viewModel.lastResponse.collectAsState()
        val emotion by viewModel.emotion.collectAsState()
        val isListening by viewModel.isListening.collectAsState()
        val messages by viewModel.messages.collectAsState()
        val isTyping by viewModel.isTyping.collectAsState()
        val isVoiceMode by viewModel.isVoiceMode.collectAsState()
        val devices by viewModel.devices.collectAsState()
        val isMqttConnected by viewModel.isMqttConnected.collectAsState()
        val mqttLabel by viewModel.mqttLabel.collectAsState()
        val geminiApiKey by viewModel.geminiApiKey.collectAsState()
        val elevenLabsApiKey by viewModel.elevenLabsApiKey.collectAsState()
        val ttsVoiceId by viewModel.ttsVoiceId.collectAsState()
        val isWakeWordEnabled by viewModel.isWakeWordEnabled.collectAsState()
        val mqttBrokerUrl by viewModel.mqttBrokerUrl.collectAsState()
        val mqttUsername by viewModel.mqttUsername.collectAsState()
        val mqttPassword by viewModel.mqttPassword.collectAsState()
        val homeAssistantUrl by viewModel.homeAssistantUrl.collectAsState()
        val homeAssistantToken by viewModel.homeAssistantToken.collectAsState()
        val isKeepAliveEnabled by viewModel.isKeepAliveEnabled.collectAsState()
        val isBatteryOptimized by viewModel.isBatteryOptimized.collectAsState()
        val isShizukuAvailable by viewModel.isShizukuAvailable.collectAsState()
        val isRustReady by viewModel.isRustReady.collectAsState()
        val engineStatusText by viewModel.engineStatusText.collectAsState()
        val apiKeySaveResult by viewModel.apiKeySaveResult.collectAsState()
        val apiKeyTestResult by viewModel.apiKeyTestResult.collectAsState()

        JarvisNavGraph(
            brainState = brainState,
            audioAmplitude = audioAmplitude,
            currentTranscription = currentTranscription,
            lastResponse = lastResponse,
            emotion = emotion,
            isListening = isListening,
            messages = messages,
            isTyping = isTyping,
            isVoiceMode = isVoiceMode,
            devices = devices,
            isMqttConnected = isMqttConnected,
            mqttLabel = mqttLabel,
            deviceCount = viewModel.deviceCount,
            activeDeviceCount = viewModel.activeDeviceCount,
            geminiApiKey = geminiApiKey,
            elevenLabsApiKey = elevenLabsApiKey,
            ttsVoiceId = ttsVoiceId,
            isWakeWordEnabled = isWakeWordEnabled,
            mqttBrokerUrl = mqttBrokerUrl,
            mqttUsername = mqttUsername,
            mqttPassword = mqttPassword,
            homeAssistantUrl = homeAssistantUrl,
            homeAssistantToken = homeAssistantToken,
            isKeepAliveEnabled = isKeepAliveEnabled,
            isBatteryOptimized = isBatteryOptimized,
            isShizukuAvailable = isShizukuAvailable,
            isRustReady = isRustReady,
            engineStatusText = engineStatusText,
            onToggleListening = { viewModel.toggleListening(context) },
            onSendMessage = { viewModel.sendMessage(it, context) },
            // ═══ CRITICAL FIX: Chat screen voice button now starts/stops mic ═══
            // Previously: viewModel.toggleVoiceMode() — just flipped a flag, mic stayed dead
            // Now: viewModel.toggleVoiceMode(context) — actually starts AudioEngine
            onToggleVoice = { viewModel.toggleVoiceMode(context) },
            onShowOverlay = { viewModel.showOverlay(context) },
            onToggleDevice = { id, state -> viewModel.toggleDevice(id, state) },
            onRefreshDevices = { viewModel.refreshDevices() },
            onQuickAction = { action ->
                when (action) {
                    "voice"    -> viewModel.startListening(context)
                    "capture"  -> viewModel.sendMessage("take a screenshot", context)
                    "chat"     -> { /* handled by NavGraph navigation */ }
                    "devices"  -> { /* handled by NavGraph navigation */ }
                }
            },
            onGeminiApiKeyChange = { viewModel.setGeminiApiKey(it) },
            onElevenLabsApiKeyChange = { viewModel.setElevenLabsApiKey(it) },
            onTtsVoiceChange = { viewModel.setTtsVoiceId(it) },
            onWakeWordToggle = { viewModel.setWakeWordEnabled(it) },
            onMqttBrokerChange = { viewModel.setMqttBrokerUrl(it) },
            onMqttUsernameChange = { viewModel.setMqttUsername(it) },
            onMqttPasswordChange = { viewModel.setMqttPassword(it) },
            onHomeAssistantUrlChange = { viewModel.setHomeAssistantUrl(it) },
            onHomeAssistantTokenChange = { viewModel.setHomeAssistantToken(it) },
            onKeepAliveToggle = { viewModel.setKeepAliveEnabled(it) },
            onBatteryOptimizationDisable = { openBatteryOptimizationSettings() },
            onPermissionsRequest = { requestPermissionsIfNeeded() },
            onHealthCheck = {
                lifecycleScope.launch {
                    val healthy = com.jarvis.assistant.jni.RustBridge.healthCheck()
                    viewModel.updateAmplitude(if (healthy) 1f else 0f)
                }
            },
            onSaveAndApplyKeys = { gemini, elevenlabs ->
                viewModel.saveAndApplyApiKeys(gemini, elevenlabs)
            },
            onShizukuRequestPermission = { viewModel.requestShizukuPermission(this@MainActivity) },
            apiKeySaveResult = apiKeySaveResult,
            onConsumeApiKeySaveResult = { viewModel.consumeApiKeySaveResult() },
            onTestApiKeys = { gemini, eleven -> viewModel.testApiKeys(gemini, eleven) },
            apiKeyTestResult = apiKeyTestResult,
            onClearApiKeyTestResult = { viewModel.clearApiKeyTestResult() }
        )
    }

    // ─── Permissions ────────────────────────────────────────────────

    private fun requestPermissionsIfNeeded() {
        val needed = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
        updatePermissionStates()
    }

    private fun updatePermissionStates() {
        try {
            // Use the correct factory that includes SettingsRepository
            val viewModel = androidx.lifecycle.ViewModelProvider(this, JarvisViewModel.Factory(settingsRepository))[JarvisViewModel::class.java]

            val isBatteryOpt = !PermissionManager.isIgnoringBatteryOptimizations(this)
            viewModel.updateBatteryOptimized(isBatteryOpt)

            val shizukuAvailable = com.jarvis.assistant.shizuku.ShizukuManager.isReady() &&
                    com.jarvis.assistant.shizuku.ShizukuManager.hasPermission()
            viewModel.updateShizukuAvailable(shizukuAvailable)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "updatePermissionStates: ${e.message}")
        }
    }

    // ─── Intent Handling ────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VOICE_COMMAND,
            "com.jarvis.assistant.LISTEN" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val viewModel = androidx.lifecycle.ViewModelProvider(this@MainActivity, JarvisViewModel.Factory(settingsRepository))[JarvisViewModel::class.java]
                        viewModel.startListening(this@MainActivity)
                        android.util.Log.i(TAG, "Listening triggered from intent shortcut")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to start listening from intent: ${e.message}")
                    }
                } else {
                    android.util.Log.w(TAG, "RECORD_AUDIO permission not granted")
                }
            }
            "com.jarvis.assistant.CHAT" -> {
                android.util.Log.i(TAG, "Chat shortcut triggered")
            }
        }
    }

    // ─── Battery Optimization ───────────────────────────────────────

    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
            ).apply {
                data = android.net.Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) { }
        }
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}

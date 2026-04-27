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
    }

    private lateinit var settingsRepository: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        settingsRepository = (application as JarvisApp).settingsRepository

        // Enable edge-to-edge rendering
        enableEdgeToEdge()

        // Request permissions on first launch
        requestPermissionsIfNeeded()

        // Handle intent shortcuts
        handleIntent(intent)

        // Set content
        setContent {
            JarvisTheme {
                MainContent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-check permissions when returning from settings screens
        checkAndUpdatePermissionStates()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /**
     * Main composable content — wires the ViewModel state to the NavGraph.
     */
    @Composable
    private fun MainContent() {
        val viewModel: JarvisViewModel = viewModel(
            factory = JarvisViewModel.Factory(settingsRepository)
        )
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
        val apiKeySaveResult by viewModel.apiKeySaveResult.collectAsState()

        val context = this

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
            onToggleListening = { viewModel.toggleListening(context) },
            onSendMessage = { viewModel.sendMessage(it, context) },
            onToggleVoice = { viewModel.toggleVoiceMode() },
            onToggleDevice = { id, state -> viewModel.toggleDevice(id, state) },
            onRefreshDevices = { viewModel.refreshDevices() },
            onQuickAction = { action ->
                when (action) {
                    "voice"    -> viewModel.toggleListening(context)
                    "capture"  -> viewModel.sendMessage("take a screenshot", context)
                    // chat and devices navigation handled inside JarvisNavGraph via onNavigateToRoute
                    "chat"     -> { /* navigation handled by nav */ }
                    "devices"  -> { /* navigation handled by nav */ }
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
            apiKeySaveResult = apiKeySaveResult,
            onConsumeApiKeySaveResult = { viewModel.consumeApiKeySaveResult() }
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
    }

    private fun checkAndUpdatePermissionStates() {
        val pm = PermissionManager
        viewModelScopeCheck {
            val isBatteryOpt = !pm.isIgnoringBatteryOptimizations(this)
        }
    }

    private fun viewModelScopeCheck(block: () -> Unit) {
        try { block() } catch (_: Exception) {}
    }

    // ─── Intent Handling ────────────────────────────────────────────

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VOICE_COMMAND,
            "com.jarvis.assistant.LISTEN" -> {
                // Trigger listening mode
            }
            "com.jarvis.assistant.CHAT" -> {
                // Navigate to chat
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

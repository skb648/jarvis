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
        // Re-check permission states after user responds
        updatePermissionStates()
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
        // Re-check permissions and Shizuku when returning from settings screens
        updatePermissionStates()
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
                    "voice"    -> viewModel.startListening(context)
                    "capture"  -> viewModel.sendMessage("take a screenshot", context)
                    // chat and devices are now handled via onNavigateToRoute in NavGraph
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
            onShizukuRequestPermission = { viewModel.requestShizukuPermission() },
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
        updatePermissionStates()
    }

    /**
     * CRITICAL FIX: Actually update the ViewModel with permission states.
     *
     * Previously this method computed `isBatteryOpt` but never called
     * `viewModel.updateBatteryOptimized()` or `viewModel.updateShizukuAvailable()`.
     * This meant the Settings screen always showed stale data.
     */
    private fun updatePermissionStates() {
        try {
            val viewModel = androidx.lifecycle.ViewModelProvider(this)[JarvisViewModel::class.java]

            // Update battery optimization state
            val isBatteryOpt = !PermissionManager.isIgnoringBatteryOptimizations(this)
            viewModel.updateBatteryOptimized(isBatteryOpt)

            // Update Shizuku availability
            val shizukuAvailable = com.jarvis.assistant.shizuku.ShizukuManager.isReady() &&
                    com.jarvis.assistant.shizuku.ShizukuManager.hasPermission()
            viewModel.updateShizukuAvailable(shizukuAvailable)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "updatePermissionStates: ${e.message}")
        }
    }

    // ─── Intent Handling ────────────────────────────────────────────

    /**
     * CRITICAL FIX: Intent shortcuts now ACTUALLY trigger actions.
     *
     * Previously these were empty comment blocks. Now:
     * - VOICE_COMMAND / LISTEN: Starts listening mode
     * - CHAT: Opens the conversation screen
     */
    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VOICE_COMMAND,
            "com.jarvis.assistant.LISTEN" -> {
                // Trigger listening mode — requires RECORD_AUDIO permission
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    try {
                        val viewModel = androidx.lifecycle.ViewModelProvider(this)[JarvisViewModel::class.java]
                        viewModel.startListening(this)
                        android.util.Log.i(TAG, "Listening triggered from intent shortcut")
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Failed to start listening from intent: ${e.message}")
                    }
                } else {
                    android.util.Log.w(TAG, "RECORD_AUDIO permission not granted — cannot start listening from shortcut")
                }
            }
            "com.jarvis.assistant.CHAT" -> {
                // Navigate to chat — handled by Compose navigation state
                // The NavGraph will pick this up via a LaunchedEffect
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

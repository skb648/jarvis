package com.jarvis.assistant.ui.navigation


import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.jarvis.assistant.ui.screens.*
import com.jarvis.assistant.ui.theme.*
import com.jarvis.assistant.viewmodel.ApiKeySaveResult

/**
 * Bottom navigation tab definition.
 */
data class BottomNavTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val tabs = listOf(
    BottomNavTab("home",        "Home",       Icons.Filled.Home),
    BottomNavTab("assistant",   "Assistant",  Icons.Filled.Mic),
    BottomNavTab("chat",        "Chat",       Icons.AutoMirrored.Filled.Chat),
    BottomNavTab("smarthome",   "Devices",    Icons.Filled.Devices),
    BottomNavTab("notes",       "Notes",      Icons.Filled.StickyNote2),
    BottomNavTab("computer",   "Computer",   Icons.Filled.Computer),
    BottomNavTab("diagnostics", "Diag",       Icons.Filled.MonitorHeart),
    BottomNavTab("settings",    "Settings",   Icons.Filled.Settings)
)



@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JarvisNavGraph(
    // ViewModel-provided state & callbacks — passed down from MainActivity
    brainState: com.jarvis.assistant.ui.orb.BrainState,
    audioAmplitude: Float,
    currentTranscription: String,
    lastResponse: String,
    emotion: String,
    isListening: Boolean,
    messages: List<ChatMessage>,
    isTyping: Boolean,
    isVoiceMode: Boolean,
    devices: List<SmartDevice>,
    isMqttConnected: Boolean,
    mqttLabel: String,
    deviceCount: Int,
    activeDeviceCount: Int,
    // Settings state
    geminiApiKey: String,
    elevenLabsApiKey: String,
    ttsVoiceId: String,
    isWakeWordEnabled: Boolean,
    mqttBrokerUrl: String,
    mqttUsername: String,
    mqttPassword: String,
    homeAssistantUrl: String,
    homeAssistantToken: String,
    isKeepAliveEnabled: Boolean,
    isBatteryOptimized: Boolean,
    isShizukuAvailable: Boolean,
    isRustReady: Boolean = false,
    engineStatusText: String = "AI engine starting...",
    // Callbacks
    onToggleListening: () -> Unit,
    onSendMessage: (String) -> Unit,
    onToggleVoice: () -> Unit,
    onToggleDevice: (String, Boolean) -> Unit,
    onRefreshDevices: () -> Unit,
    onQuickAction: (String) -> Unit,
    onShowOverlay: () -> Unit = {},
    onGeminiApiKeyChange: (String) -> Unit,
    onElevenLabsApiKeyChange: (String) -> Unit,
    onTtsVoiceChange: (String) -> Unit,
    onWakeWordToggle: (Boolean) -> Unit,
    onMqttBrokerChange: (String) -> Unit,
    onMqttUsernameChange: (String) -> Unit,
    onMqttPasswordChange: (String) -> Unit,
    onHomeAssistantUrlChange: (String) -> Unit,
    onHomeAssistantTokenChange: (String) -> Unit,
    onKeepAliveToggle: (Boolean) -> Unit,
    onBatteryOptimizationDisable: () -> Unit,
    onPermissionsRequest: () -> Unit,
    onHealthCheck: () -> Unit,
    onSaveAndApplyKeys: (String, String) -> Unit = { _, _ -> },
    onShizukuRequestPermission: () -> Unit = {},
    apiKeySaveResult: ApiKeySaveResult = ApiKeySaveResult.NONE,
    onConsumeApiKeySaveResult: () -> Unit = {},
    // A3: API key test
    onTestApiKeys: (String, String) -> Unit = { _, _ -> },
    apiKeyTestResult: String = "",
    onClearApiKeyTestResult: () -> Unit = {},
    // Chat session drawer state & callbacks
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: Long = -1L,
    onLoadSession: (ChatSession) -> Unit = {},
    onNewChat: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    // Mic Lock state
    userMicLocked: Boolean = false,
    onToggleMicLock: () -> Unit = {},
    // SmartHome quick action & settings navigation
    onSmartHomeQuickAction: (String) -> Unit = {},
    onGoToSettings: () -> Unit = {},
    // HomeScreen location context
    locationContext: String = "",
    // Wake flash effect
    wakeFlash: Boolean = false,
    // Voice direction (reserved for future use)
    voiceDirection: String = "",
    // Diagnostics state
    diagnosticsBatteryLevel: Int = 0,
    diagnosticsIsCharging: Boolean = false,
    diagnosticsIsRustReady: Boolean = false,
    diagnosticsCpuUsage: Float = 0f,
    onRefreshDiagnostics: () -> Unit = {},
    // Quick Notes state
    notes: List<QuickNote> = emptyList(),
    onAddNote: (String, String) -> Unit = { _, _ -> },
    onDeleteNote: (String) -> Unit = {},
    // Music Player state
    showMusicPlayer: Boolean = false,
    isMusicPlaying: Boolean = false,
    onToggleMusicPlayer: () -> Unit = {},
    onToggleMusicPlayback: () -> Unit = {},
    // Export chat callback
    onExportChat: () -> Unit = {},
    // Computer Use state
    computerUseActive: Boolean = false,
    cursorX: Float = 0.5f,
    cursorY: Float = 0.5f,
    computerAiStatus: String = "IDLE",
    computerActionLog: List<String> = emptyList(),
    onComputerCommand: (String) -> Unit = {},
    onActivateComputerUse: () -> Unit = {},
    onDeactivateComputerUse: () -> Unit = {}
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    /**
     * Quick action navigation callback.
     */
    val handleQuickAction: (String) -> Unit = { action ->
        when (action) {
            "chat" -> {
                navController.navigate("chat") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            "devices" -> {
                navController.navigate("smarthome") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            "notes" -> {
                navController.navigate("notes") {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }
            "music" -> {
                onToggleMusicPlayer()
            }
            else -> onQuickAction(action)
        }
    }

    /**
     * Navigate to settings — used by SmartHomeScreen's "Go to Settings" button.
     */
    val handleGoToSettings: () -> Unit = {
        navController.navigate("settings") {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
        onGoToSettings()
    }

    Scaffold(
        containerColor = DeepNavy,
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceNavy,
                contentColor = JarvisCyan,
                tonalElevation = 0.dp
            ) {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        icon = {
                            Icon(
                                tab.icon,
                                contentDescription = tab.label,
                                tint = if (currentDestination?.hierarchy?.any { it.route == tab.route } == true) {
                                    JarvisCyan
                                } else {
                                    TextTertiary
                                }
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = if (currentDestination?.hierarchy?.any { it.route == tab.route } == true) {
                                    JarvisCyan
                                } else {
                                    TextTertiary
                                }
                            )
                        },
                        selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = {
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = JarvisCyan.copy(alpha = 0.12f)
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = "home",
            modifier = Modifier.padding(innerPadding)
        ) {
            composable("home") {
                HomeScreen(
                    brainState = brainState,
                    audioAmplitude = audioAmplitude,
                    deviceCount = deviceCount,
                    activeDeviceCount = activeDeviceCount,
                    onQuickAction = handleQuickAction,
                    isRustReady = isRustReady,
                    engineStatusText = engineStatusText,
                    locationContext = locationContext,
                    batteryLevel = diagnosticsBatteryLevel,
                    isCharging = diagnosticsIsCharging
                )
            }
            composable("assistant") {
                AssistantScreen(
                    brainState = brainState,
                    audioAmplitude = audioAmplitude,
                    currentTranscription = currentTranscription,
                    lastResponse = lastResponse,
                    emotion = emotion,
                    isListening = isListening,
                    onToggleListening = onToggleListening,
                    userMicLocked = userMicLocked,
                    onToggleMicLock = onToggleMicLock,
                    wakeFlash = wakeFlash
                )
            }
            composable("chat") {
                ConversationScreen(
                    messages = messages,
                    isTyping = isTyping,
                    isVoiceMode = isVoiceMode,
                    onSendMessage = onSendMessage,
                    // CRITICAL FIX: Voice button in Chat screen now ACTUALLY
                    // starts/stops the microphone instead of just toggling a UI flag
                    onToggleVoice = onToggleVoice,
                    // Chat session drawer
                    chatSessions = chatSessions,
                    currentSessionId = currentSessionId,
                    onLoadSession = onLoadSession,
                    onNewChat = onNewChat,
                    onClearHistory = onClearHistory
                )
            }
            composable("smarthome") {
                SmartHomeScreen(
                    devices = devices,
                    isConnected = isMqttConnected,
                    connectionLabel = mqttLabel,
                    onToggleDevice = onToggleDevice,
                    onRefresh = onRefreshDevices,
                    onQuickAction = onSmartHomeQuickAction,
                    onGoToSettings = handleGoToSettings
                )
            }
            composable("notes") {
                QuickNotesScreen(
                    notes = notes,
                    onAddNote = onAddNote,
                    onDeleteNote = onDeleteNote
                )
            }
            composable("computer") {
                ComputerUseScreen(
                    isAiActive = computerUseActive,
                    cursorX = cursorX,
                    cursorY = cursorY,
                    aiStatus = computerAiStatus,
                    actionLog = computerActionLog,
                    onCommand = onComputerCommand,
                    onTakeControl = onActivateComputerUse,
                    onStop = onDeactivateComputerUse
                )
            }
            composable("diagnostics") {
                DeviceDiagnosticsScreen(
                    batteryLevel = diagnosticsBatteryLevel,
                    isCharging = diagnosticsIsCharging,
                    isRustReady = diagnosticsIsRustReady,
                    cpuUsagePercent = diagnosticsCpuUsage,
                    onRefresh = onRefreshDiagnostics
                )
            }
            composable("settings") {
                SettingsScreen(
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
                    onGeminiApiKeyChange = onGeminiApiKeyChange,
                    onElevenLabsApiKeyChange = onElevenLabsApiKeyChange,
                    onTtsVoiceChange = onTtsVoiceChange,
                    onWakeWordToggle = onWakeWordToggle,
                    onMqttBrokerChange = onMqttBrokerChange,
                    onMqttUsernameChange = onMqttUsernameChange,
                    onMqttPasswordChange = onMqttPasswordChange,
                    onHomeAssistantUrlChange = onHomeAssistantUrlChange,
                    onHomeAssistantTokenChange = onHomeAssistantTokenChange,
                    onKeepAliveToggle = onKeepAliveToggle,
                    onBatteryOptimizationDisable = onBatteryOptimizationDisable,
                    onPermissionsRequest = onPermissionsRequest,
                    onHealthCheck = onHealthCheck,
                    onSaveAndApplyKeys = onSaveAndApplyKeys,
                    onShizukuRequestPermission = onShizukuRequestPermission,
                    apiKeySaveResult = apiKeySaveResult,
                    onConsumeApiKeySaveResult = onConsumeApiKeySaveResult,
                    onTestApiKeys = onTestApiKeys,
                    apiKeyTestResult = apiKeyTestResult,
                    onClearApiKeyTestResult = onClearApiKeyTestResult,
                    onShowLicenses = {}
                )
            }
        }
    }
}

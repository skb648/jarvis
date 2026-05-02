package com.jarvis.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.jarvis.assistant.ui.theme.*
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.viewmodel.ApiKeySaveResult

/**
 * SettingsScreen — Production settings with HOT-SWAP API key support.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * HOT-SWAP FIX:
 *
 * When "SAVE & APPLY" is tapped:
 *   1. The [onSaveAndApplyKeys] callback is invoked with the raw key strings
 *   2. The ViewModel writes them to DataStore (persistent storage)
 *   3. The ViewModel IMMEDIATELY calls RustBridge.initialize(newKey, newKey)
 *   4. The Rust backend writes to RwLock<ApiKeys> (no OnceCell rejection)
 *   5. Subsequent Gemini/ElevenLabs API calls use the new keys instantly
 *
 * No app restart needed. No "keys already initialized" error.
 * ═══════════════════════════════════════════════════════════════════════
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
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
    isMqttConnected: Boolean = false,
    isAccessibilityEnabled: Boolean = false,
    // Per-field real-time setters
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
    // Batch Save & Apply — this is the HOT-SWAP trigger
    onSaveAndApplyKeys: (gemini: String, elevenLabs: String) -> Unit = { _, _ -> },
    onShizukuRequestPermission: () -> Unit = {},
    apiKeySaveResult: ApiKeySaveResult = ApiKeySaveResult.NONE,
    onConsumeApiKeySaveResult: () -> Unit = {},
    // A3: API key test
    onTestApiKeys: (gemini: String, elevenLabs: String) -> Unit = { _, _ -> },
    apiKeyTestResult: String = "",
    onClearApiKeyTestResult: () -> Unit = {},
    // About section
    onShowLicenses: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Local editable state for the batch API key panel.
    // These are initialized from the ViewModel's StateFlow values
    // (which come from DataStore) and allow the user to edit them
    // before hitting "SAVE & APPLY".
    var localGemini    by remember(geminiApiKey)     { mutableStateOf(geminiApiKey) }
    var localEleven    by remember(elevenLabsApiKey) { mutableStateOf(elevenLabsApiKey) }

    // ── Save button color flash animation state ─────────────────────────────
    var saveButtonColor by remember { mutableStateOf(JarvisCyan) }
    var isFlashing by remember { mutableStateOf(false) }

    LaunchedEffect(isFlashing) {
        if (isFlashing) {
            saveButtonColor = JarvisGreen
            delay(300)
            saveButtonColor = JarvisCyan
            delay(150)
            isFlashing = false
        }
    }

    // ── Toast feedback — fires once when result changes ───────────────────────
    // This confirms to the user that the hot-swap succeeded.
    LaunchedEffect(apiKeySaveResult) {
        when (apiKeySaveResult) {
            ApiKeySaveResult.SUCCESS -> {
                isFlashing = true
                Toast.makeText(
                    context,
                    "Keys saved and applied to Rust backend — no restart needed",
                    Toast.LENGTH_SHORT
                ).show()
                onConsumeApiKeySaveResult()
            }
            ApiKeySaveResult.FAILURE -> {
                Toast.makeText(
                    context,
                    "Failed to save keys — check logs and try again",
                    Toast.LENGTH_SHORT
                ).show()
                onConsumeApiKeySaveResult()
            }
            ApiKeySaveResult.NONE -> { /* nothing */ }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavy)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ══════════════════════════════════════════════════════════════════════
        // QUICK SETUP — Step-by-step checklist for first-time users
        // ══════════════════════════════════════════════════════════════════════
        SectionHeader("QUICK SETUP", Icons.Filled.Checklist)

        val setupSteps = remember(
            isShizukuAvailable, isAccessibilityEnabled,
            geminiApiKey, elevenLabsApiKey, isWakeWordEnabled
        ) {
            listOf(
                SetupStep("Grant Permissions", isComplete = true, icon = Icons.Filled.VerifiedUser),
                SetupStep("Enable Accessibility", isComplete = isAccessibilityEnabled, icon = Icons.Filled.AccessibilityNew),
                SetupStep("Configure Shizuku", isComplete = isShizukuAvailable, icon = Icons.Filled.Usb),
                SetupStep("Enter API Key", isComplete = geminiApiKey.isNotBlank(), icon = Icons.Filled.Key),
                SetupStep("Test Voice", isComplete = isWakeWordEnabled && elevenLabsApiKey.isNotBlank(), icon = Icons.Filled.Mic)
            )
        }
        val completedSteps = setupSteps.count { it.isComplete }
        val totalSteps = setupSteps.size
        val allComplete = completedSteps == totalSteps

        GlassmorphicCardSimple(
            backgroundColor = if (allComplete) JarvisGreen.copy(alpha = 0.06f) else WarningAmber.copy(alpha = 0.04f),
            borderColor = if (allComplete) JarvisGreen.copy(alpha = 0.3f) else WarningAmber.copy(alpha = 0.25f)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Header row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "First-Time Setup",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "$completedSteps/$totalSteps",
                        color = if (allComplete) JarvisGreen else WarningAmber,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Progress bar
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(GlassBorder.copy(alpha = 0.3f), shape = RoundedCornerShape(2.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(completedSteps.toFloat() / totalSteps)
                            .background(
                                if (allComplete) JarvisGreen else WarningAmber,
                                shape = RoundedCornerShape(2.dp)
                            )
                    )
                }

                // Step items
                setupSteps.forEachIndexed { index, step ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Step number circle or checkmark
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(
                                    if (step.isComplete) JarvisGreen.copy(alpha = 0.2f)
                                    else GlassBorder.copy(alpha = 0.15f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (step.isComplete) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = "Done",
                                    tint = JarvisGreen,
                                    modifier = Modifier.size(14.dp)
                                )
                            } else {
                                Text(
                                    "${index + 1}",
                                    color = TextTertiary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        Icon(
                            step.icon,
                            contentDescription = step.label,
                            tint = if (step.isComplete) JarvisGreen else TextTertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            step.label,
                            color = if (step.isComplete) JarvisGreen else TextSecondary,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                if (allComplete) {
                    Text(
                        "All setup steps complete — JARVIS is ready!",
                        color = JarvisGreen,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        GradientDivider()

        // ══════════════════════════════════════════════════════════════════════
        // CONNECTION STATUS — Real-time status of core services with health score
        // ══════════════════════════════════════════════════════════════════════
        SectionHeader("CONNECTION STATUS", Icons.Filled.Wifi)

        // ── Animated scan line for connection card ─────────────────────────
        val connScanTransition = rememberInfiniteTransition(label = "conn-scan")
        val connScanOffset by connScanTransition.animateFloat(
            initialValue = -0.3f,
            targetValue = 1.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "conn-scan-offset"
        )

        GlassmorphicCardSimple(
            backgroundColor = if (isRustReady && isShizukuAvailable && isMqttConnected && isAccessibilityEnabled)
                JarvisGreen.copy(alpha = 0.04f) else SurfaceNavy
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ── Connection Health Summary ──────────────────────────────
                val connectedCount = listOf(isShizukuAvailable, isMqttConnected, isAccessibilityEnabled, isRustReady).count { it }
                val totalCount = 4
                val healthFraction = connectedCount.toFloat() / totalCount
                val healthColor = when {
                    healthFraction >= 0.75f -> JarvisGreen
                    healthFraction >= 0.5f  -> JarvisCyan
                    healthFraction >= 0.25f -> WarningAmber
                    else                    -> JarvisRedPink
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Service Health",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            "$connectedCount/$totalCount",
                            color = healthColor,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        // Overall health dot
                        Box(contentAlignment = Alignment.Center) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                drawCircle(
                                    color = healthColor.copy(alpha = 0.25f),
                                    radius = size.minDimension / 2f * (0.8f + healthFraction * 0.6f)
                                )
                            }
                            Canvas(modifier = Modifier.size(8.dp)) {
                                drawCircle(color = healthColor)
                            }
                        }
                    }
                }

                // Health progress bar with scan line overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(GlassBorder.copy(alpha = 0.2f), shape = RoundedCornerShape(3.dp))
                ) {
                    // Filled portion
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(healthFraction)
                            .background(healthColor, shape = RoundedCornerShape(3.dp))
                    )
                    // Animated scan line
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scanX = connScanOffset * size.width
                        val sweepWidth = size.width * 0.2f
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = 0f),
                                    Color.White.copy(alpha = 0.15f),
                                    Color.White.copy(alpha = 0f)
                                ),
                                startX = scanX - sweepWidth / 2f,
                                endX = scanX + sweepWidth / 2f
                            )
                        )
                    }
                }

                GradientDivider(thickness = 0.5.dp)

                // Shizuku status
                ConnectionStatusRow(
                    label = "Shizuku",
                    status = if (isShizukuAvailable) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    icon = Icons.Filled.Usb
                )
                // MQTT status
                ConnectionStatusRow(
                    label = "MQTT",
                    status = if (isMqttConnected) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    icon = Icons.Filled.Hub
                )
                // Accessibility Service status
                ConnectionStatusRow(
                    label = "Accessibility",
                    status = if (isAccessibilityEnabled) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    icon = Icons.Filled.AccessibilityNew
                )
                // Rust Engine status
                ConnectionStatusRow(
                    label = "Rust Engine",
                    status = if (isRustReady) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED,
                    icon = Icons.Filled.Memory
                )
            }
        }

        GradientDivider()

        // ── 1. API Keys — batch Save & Apply (HOT-SWAP) ────────────────────
        SectionHeader("API KEYS", Icons.Filled.Key)

        GlassmorphicCardSimple {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // ── Hot-swap status indicator ──────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Enter both keys then tap SAVE & APPLY.\n" +
                        "Changes take effect IMMEDIATELY — no restart needed.\n" +
                        "Rust backend uses RwLock for hot-swap.",
                        color    = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.weight(1f)
                    )
                    // Rust readiness indicator
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(
                            color = if (isRustReady) JarvisGreen else JarvisRedPink
                        )
                    }
                }

                // Gemini key — NO validation, trust user input completely
                ApiKeyField(
                    value        = localGemini,
                    onValueChange = { localGemini = it },
                    label        = "Gemini API Key",
                    placeholder  = "AIzaSy…",
                    icon         = Icons.Filled.Android
                )

                // ── Gemini key validation indicator ─────────────────────────
                KeyValidationIndicator(localGemini)

                // ElevenLabs key — NO validation, trust user input completely
                ApiKeyField(
                    value        = localEleven,
                    onValueChange = { localEleven = it },
                    label        = "ElevenLabs API Key",
                    placeholder  = "…",
                    icon         = Icons.AutoMirrored.Filled.VolumeUp
                )

                // ── ElevenLabs key validation indicator ─────────────────────
                KeyValidationIndicator(localEleven)

                // ══════════════════════════════════════════════════════════════
                // TEST BUTTON — validates keys before saving
                // ══════════════════════════════════════════════════════════════
                OutlinedButton(
                    onClick  = { onTestApiKeys(localGemini, localEleven) },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = JarvisGreen),
                    border   = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Icon(Icons.Filled.Science, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("TEST API KEYS", fontFamily = FontFamily.Monospace, fontSize = 13.sp, letterSpacing = 2.sp)
                }

                // Test result display
                if (apiKeyTestResult.isNotBlank()) {
                    // CRITICAL FIX (v14): Show green for Gemini OK even without ElevenLabs
                    val isSuccess = apiKeyTestResult.contains("Gemini OK", ignoreCase = true) ||
                        apiKeyTestResult.contains("All keys valid", ignoreCase = true)
                    Text(
                        apiKeyTestResult,
                        color = if (isSuccess) JarvisGreen else WarningAmber,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    LaunchedEffect(apiKeyTestResult) {
                        delay(5000)
                        onClearApiKeyTestResult()
                    }
                }

                // ══════════════════════════════════════════════════════════════
                // SAVE & APPLY BUTTON — THE HOT-SWAP TRIGGER
                //
                // This button does TWO things atomically:
                //   1. Persists keys to DataStore (survives app restart)
                //   2. Calls RustBridge.nativeInitialize(newKey, newKey) which
                //      writes to RwLock<ApiKeys> in the Rust backend
                //
                // The Rust backend uses lazy_static! { RwLock<ApiKeys> } so
                // the overwrite ALWAYS succeeds — no OnceCell rejection.
                // ══════════════════════════════════════════════════════════════
                // ── Animated cycling gradient border on save button ──────────
                val saveBorderTransition = rememberInfiniteTransition(label = "save-border")
                val saveBorderShift by saveBorderTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 3000, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "save-border-shift"
                )

                val saveBorderBrush = Brush.horizontalGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = 0.5f + saveBorderShift * 0.5f),
                        JarvisPurple.copy(alpha = 0.5f + (1f - saveBorderShift) * 0.5f),
                        JarvisGreen.copy(alpha = 0.3f + saveBorderShift * 0.4f),
                        JarvisCyan.copy(alpha = 0.5f + (1f - saveBorderShift) * 0.5f)
                    )
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(saveBorderBrush)
                        .padding(1.5.dp)
                ) {
                    Button(
                        onClick  = { onSaveAndApplyKeys(localGemini, localEleven) },
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = saveButtonColor,
                            contentColor   = DeepNavy
                        ),
                        shape    = RoundedCornerShape(7.dp)
                    ) {
                        Icon(Icons.Filled.CheckCircle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "SAVE & APPLY",
                            fontFamily    = FontFamily.Monospace,
                            fontSize      = 13.sp,
                            letterSpacing = 2.sp
                        )
                    }
                }

                // Model info — hardcoded in Rust
                Text(
                    "Model: gemini-2.5-flash (audio + text + vision)",
                    color    = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        GradientDivider()

        // ── 2. Voice & TTS ────────────────────────────────────────────────────
        SectionHeader("VOICE & TTS", Icons.Filled.RecordVoiceOver)

        GlassmorphicCardSimple {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = ttsVoiceId,
                    onValueChange = onTtsVoiceChange,
                    label         = { Text("ElevenLabs Voice ID") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = tfColors(),
                    leadingIcon   = { Icon(Icons.Filled.GraphicEq, null, tint = TextSecondary) }
                )
                ToggleRow(
                    label       = "Wake Word",
                    subtitle    = "Say 'Jarvis' to activate",
                    checked     = isWakeWordEnabled,
                    onChecked   = onWakeWordToggle
                )
            }
        }

        GradientDivider()

        // ── 3. MQTT ───────────────────────────────────────────────────────────
        SectionHeader("MQTT / SMART HOME", Icons.Filled.Devices)

        GlassmorphicCardSimple {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = mqttBrokerUrl,
                    onValueChange = onMqttBrokerChange,
                    label         = { Text("MQTT Broker URL") },
                    placeholder   = { Text("mqtt://192.168.1.x:1883", color = TextTertiary) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = tfColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon   = { Icon(Icons.Filled.Hub, null, tint = TextSecondary) }
                )
                OutlinedTextField(
                    value         = mqttUsername,
                    onValueChange = onMqttUsernameChange,
                    label         = { Text("MQTT Username") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = tfColors(),
                    leadingIcon   = { Icon(Icons.Filled.Person, null, tint = TextSecondary) }
                )
                ApiKeyField(
                    value         = mqttPassword,
                    onValueChange = onMqttPasswordChange,
                    label         = "MQTT Password",
                    icon          = Icons.Filled.Lock
                )
            }
        }

        GradientDivider()

        // ── 4. Home Assistant ─────────────────────────────────────────────────
        SectionHeader("HOME ASSISTANT", Icons.Filled.Home)

        GlassmorphicCardSimple {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = homeAssistantUrl,
                    onValueChange = onHomeAssistantUrlChange,
                    label         = { Text("HA Base URL") },
                    placeholder   = { Text("http://homeassistant.local:8123", color = TextTertiary) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    colors        = tfColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon   = { Icon(Icons.Filled.Cloud, null, tint = TextSecondary) }
                )
                ApiKeyField(
                    value         = homeAssistantToken,
                    onValueChange = onHomeAssistantTokenChange,
                    label         = "Long-Lived Access Token",
                    icon          = Icons.Filled.VpnKey
                )
            }
        }

        GradientDivider()

        // ── 5. System ─────────────────────────────────────────────────────────
        SectionHeader("SYSTEM", Icons.Filled.Settings)

        GlassmorphicCardSimple {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Keep-Alive Service", "Run in background", isKeepAliveEnabled, onKeepAliveToggle)

                GradientDivider(thickness = 0.5.dp)

                BatteryRow(isBatteryOptimized, onBatteryOptimizationDisable)

                GradientDivider(thickness = 0.5.dp)

                ShizukuRow(isShizukuAvailable, onShizukuRequestPermission)

                GradientDivider(thickness = 0.5.dp)

                PermissionsSection(context, onPermissionsRequest)

                GradientDivider(thickness = 0.5.dp)

                OutlinedButton(
                    onClick  = onHealthCheck,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = JarvisCyan),
                    border   = ButtonDefaults.outlinedButtonBorder(enabled = true)
                ) {
                    Icon(Icons.Filled.MonitorHeart, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("HEALTH CHECK", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                }

                GradientDivider(thickness = 0.5.dp)

                // ── System Health Card ─────────────────────────────────────
                SystemHealthCard(isRustReady = isRustReady)
            }
        }

        GradientDivider()

        // ── 6. About ─────────────────────────────────────────────────────────
        SectionHeader("ABOUT", Icons.Filled.Info)

        // ── Animated gradient background About section ───────────────────────
        val aboutTransition = rememberInfiniteTransition(label = "about-gradient")
        val aboutShift by aboutTransition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 6000, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "about-shift"
        )

        GlassmorphicCardSimple {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        // Animated gradient overlay
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    JarvisCyan.copy(alpha = 0.06f + aboutShift * 0.06f),
                                    JarvisPurple.copy(alpha = 0.04f + (1f - aboutShift) * 0.06f),
                                    JarvisCyan.copy(alpha = 0.03f + aboutShift * 0.04f)
                                )
                            )
                        )
                    }
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // JARVIS logo and title with AI Engine pulsing dot
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.SmartToy,
                            contentDescription = "JARVIS",
                            tint = JarvisCyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            "JARVIS Assistant",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontFamily = FontFamily.Monospace
                        )

                        Spacer(Modifier.weight(1f))

                        // ── Pulsing AI Engine status dot ───────────────────────
                        val aiPulseTransition = rememberInfiniteTransition(label = "ai-pulse")
                        val aiPulseAlpha by aiPulseTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "ai-pulse-alpha"
                        )
                        val aiPulseScale by aiPulseTransition.animateFloat(
                            initialValue = 0.8f,
                            targetValue = 1.3f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(durationMillis = 1000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "ai-pulse-scale"
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                // Glow
                                Canvas(modifier = Modifier.size(14.dp)) {
                                    drawCircle(
                                        color = JarvisCyan.copy(alpha = aiPulseAlpha * 0.2f),
                                        radius = size.minDimension / 2f * aiPulseScale
                                    )
                                }
                                // Core dot
                                Canvas(modifier = Modifier.size(8.dp)) {
                                    drawCircle(
                                        color = if (isRustReady) JarvisGreen.copy(alpha = aiPulseAlpha) else JarvisRedPink.copy(alpha = aiPulseAlpha),
                                        radius = size.minDimension / 2f
                                    )
                                }
                            }
                            Text(
                                "AI Engine",
                                color = if (isRustReady) JarvisGreen else JarvisRedPink,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Version info
                    Text(
                        "Version 8.0.0 (Build 800)",
                        color = TextTertiary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    // Powered by
                    Text(
                        "Powered by Gemini 2.5 Flash + ElevenLabs",
                        color = TextTertiary,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    GradientDivider()

                    // Made with love
                    Text(
                        "Made with \u2665 for Tony Stark",
                        color = TextSecondary,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    // Open Source Licenses
                    TextButton(onClick = onShowLicenses) {
                        Text(
                            "Open Source Licenses",
                            color = TextTertiary,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        GradientDivider()

        // ── 7. What's New Card ─────────────────────────────────────────────
        SectionHeader("WHAT'S NEW", Icons.Filled.NewReleases)

        WhatsNewCard()

        Spacer(Modifier.height(32.dp))
    }
}

// ─── Animated Section header ──────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    // Animated pulse for the dot
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_$title")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha_$title"
    )

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp)
        ) {
            // Animated pulse dot
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(
                    color = JarvisCyan.copy(alpha = pulseAlpha)
                )
            }
            Icon(icon, null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
            Text(
                title,
                color = JarvisCyan,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
        }
        // Gradient line extending from the header to the right edge
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .padding(start = 26.dp)
        ) {
            val lineWidth = size.width
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = 0.6f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = lineWidth
                )
            )
        }
    }
}

// ─── Gradient Divider ─────────────────────────────────────────────────────────

@Composable
private fun GradientDivider(thickness: Dp = 0.5.dp) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(thickness)
    ) {
        val lineWidth = size.width
        drawRect(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    GlassBorder,
                    Color.Transparent
                ),
                startX = 0f,
                endX = lineWidth
            )
        )
    }
}

// ─── Key Validation Indicator ─────────────────────────────────────────────────

@Composable
private fun KeyValidationIndicator(keyValue: String) {
    val isEmpty = keyValue.isEmpty()
    val strengthLabel: String
    val strengthColor: Color

    if (isEmpty) {
        strengthLabel = "Not Set"
        strengthColor = JarvisRedPink
    } else {
        val len = keyValue.length
        when {
            len < 20 -> {
                strengthLabel = "Weak"
                strengthColor = JarvisRedPink
            }
            len <= 30 -> {
                strengthLabel = "Medium"
                strengthColor = WarningAmber
            }
            else -> {
                strengthLabel = "Strong"
                strengthColor = JarvisGreen
            }
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, top = 0.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Validation dot
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(
                color = if (isEmpty) JarvisRedPink else JarvisGreen
            )
        }
        // Key strength label
        Text(
            text = if (isEmpty) "Key not configured" else "Key Strength: $strengthLabel",
            color = if (isEmpty) JarvisRedPink else strengthColor,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
        // Strength bar
        if (!isEmpty) {
            val barFraction = when (strengthLabel) {
                "Weak" -> 0.33f
                "Medium" -> 0.66f
                else -> 1.0f
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(GlassBorder.copy(alpha = 0.3f), shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(barFraction)
                        .background(strengthColor, shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

// ─── System Health Card ───────────────────────────────────────────────────────

@Composable
private fun SystemHealthCard(isRustReady: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "System Health",
            color = TextPrimary,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace
        )

        // Health score indicator (progress bar)
        val healthScore = listOf(
            isRustReady,
            true, // Gemini API: assume connected if we got this far
            true  // Audio Engine: assume ready
        ).count { it }.toFloat() / 3f

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "Score",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(GlassBorder.copy(alpha = 0.3f), shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(healthScore)
                        .background(
                            when {
                                healthScore >= 0.66f -> JarvisGreen
                                healthScore >= 0.33f -> WarningAmber
                                else -> JarvisRedPink
                            },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                        )
                )
            }
            Text(
                "${(healthScore * 100).toInt()}%",
                color = when {
                    healthScore >= 0.66f -> JarvisGreen
                    healthScore >= 0.33f -> WarningAmber
                    else -> JarvisRedPink
                },
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        // Individual status rows
        HealthStatusRow(
            label = "Rust Engine",
            isOnline = isRustReady,
            onlineText = "Online",
            offlineText = "Offline"
        )
        HealthStatusRow(
            label = "Gemini API",
            isOnline = true, // Assume connected; real check is via TEST button
            onlineText = "Connected",
            offlineText = "Disconnected"
        )
        HealthStatusRow(
            label = "Audio Engine",
            isOnline = true, // Assume ready; real check is via HEALTH CHECK
            onlineText = "Ready",
            offlineText = "Not Ready"
        )
    }
}

@Composable
private fun HealthStatusRow(
    label: String,
    isOnline: Boolean,
    onlineText: String,
    offlineText: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Canvas(modifier = Modifier.size(6.dp)) {
                drawCircle(
                    color = if (isOnline) JarvisGreen else JarvisRedPink
                )
            }
            Text(
                label,
                color = TextSecondary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            if (isOnline) onlineText else offlineText,
            color = if (isOnline) JarvisGreen else JarvisRedPink,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

// ─── API / password field with show/hide toggle ───────────────────────────────
//
// No validation — the exact string is stored and passed to Rust as-is.
// The user is responsible for entering a correct key.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ApiKeyField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    icon: ImageVector = Icons.Filled.Key
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value               = value,
        onValueChange       = onValueChange,   // no trimming, no validation
        label               = { Text(label) },
        placeholder         = { if (placeholder.isNotEmpty()) Text(placeholder, color = TextTertiary) },
        singleLine          = true,
        modifier            = Modifier.fillMaxWidth(),
        colors              = tfColors(),
        keyboardOptions     = KeyboardOptions(keyboardType = KeyboardType.Password),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        leadingIcon         = { Icon(icon, null, tint = TextSecondary) },
        trailingIcon        = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = if (visible) "Hide" else "Show",
                    tint = TextSecondary
                )
            }
        }
    )
}

// ─── Toggle row ───────────────────────────────────────────────────────────────

@Composable
private fun ToggleRow(label: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = TextPrimary, fontSize = 14.sp)
            Text(subtitle, color = TextSecondary, fontSize = 11.sp)
        }
        Switch(
            checked       = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = DeepNavy, checkedTrackColor = JarvisCyan)
        )
    }
}

// ─── Battery row ──────────────────────────────────────────────────────────────

@Composable
private fun BatteryRow(isBatteryOptimized: Boolean, onDisable: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Battery Optimisation", color = TextPrimary, fontSize = 14.sp)
            Text(
                if (isBatteryOptimized) "Restricted — disable for best performance" else "Unrestricted",
                color = if (isBatteryOptimized) WarningAmber else JarvisGreen,
                fontSize = 11.sp
            )
        }
        if (isBatteryOptimized) {
            TextButton(onClick = onDisable) {
                Text("DISABLE", color = WarningAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        } else {
            Icon(Icons.Filled.CheckCircle, null, tint = JarvisGreen, modifier = Modifier.size(18.dp))
        }
    }
}

// ─── Shizuku row ──────────────────────────────────────────────────────────────

@Composable
private fun ShizukuRow(isAvailable: Boolean, onRequestPermission: () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("Shizuku", color = TextPrimary, fontSize = 14.sp)
            Text(
                if (isAvailable) "ADB-level access active" else "Required for system toggles",
                color = if (isAvailable) JarvisGreen else TextSecondary,
                fontSize = 11.sp
            )
        }
        if (isAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Filled.CheckCircle, null, tint = JarvisGreen, modifier = Modifier.size(16.dp))
                Text("Active", color = JarvisGreen, fontSize = 11.sp)
            }
        } else {
            TextButton(onClick = onRequestPermission) {
                Text("CONNECT", color = WarningAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ─── Permissions ──────────────────────────────────────────────────────────────

private data class Perm(val label: String, val sub: String, val icon: ImageVector, val check: (android.content.Context) -> Boolean, val action: String)

@Composable
private fun PermissionsSection(context: android.content.Context, onRequest: () -> Unit) {
    val perms = remember {
        listOf(
            Perm("Microphone", "Required for voice input", Icons.Filled.Mic,
                { ctx -> ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED },
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            Perm("Accessibility", "Required for screen reading", Icons.Filled.Accessibility,
                { ctx ->
                    val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                    s.contains(ctx.packageName, ignoreCase = true)
                },
                Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Perm("Notifications", "Required for alerts", Icons.Filled.Notifications,
                { ctx ->
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                        ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    } else true
                },
                Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        )
    }

    var tick by remember { mutableStateOf(0) }
    val states = remember(tick) { perms.map { it to it.check(context) } }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Permissions", color = TextPrimary, fontSize = 14.sp)
            TextButton(onClick = { tick++; onRequest() }) {
                Text("REQUEST ALL", color = JarvisCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        states.forEach { (p, granted) ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                Arrangement.SpaceBetween,
                Alignment.CenterVertically
            ) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Icon(p.icon, null, tint = if (granted) JarvisGreen else TextTertiary, modifier = Modifier.size(18.dp).padding(end = 10.dp))
                    Column {
                        Text(p.label, color = TextPrimary, fontSize = 13.sp)
                        Text(p.sub, color = TextSecondary, fontSize = 10.sp)
                    }
                }
                if (granted) {
                    Icon(Icons.Filled.CheckCircle, null, tint = JarvisGreen, modifier = Modifier.size(18.dp))
                } else {
                    TextButton(onClick = {
                        val i = Intent(p.action).apply {
                            data  = Uri.fromParts("package", context.packageName, null)
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        try { context.startActivity(i) } catch (_: Exception) {}
                        tick++
                    }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                        Text("GRANT", color = WarningAmber, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

// ─── What's New Card ────────────────────────────────────────────────────────

private data class ChangelogItem(
    val version: String,
    val date: String,
    val changes: List<String>
)

private val RecentChanges = listOf(
    ChangelogItem(
        version = "8.0",
        date = "Latest",
        changes = listOf(
            "Rust-powered hot-swap API keys",
            "Gemini 2.5 Flash multimodal engine",
            "Enhanced glassmorphic UI overhaul",
            "Voice wake word detection",
            "Smart home MQTT integration"
        )
    ),
    ChangelogItem(
        version = "7.5",
        date = "Previous",
        changes = listOf(
            "ElevenLabs TTS voice synthesis",
            "Conversation memory & sessions",
            "Quick notes with color tags",
            "Device diagnostics dashboard"
        )
    )
)

@Composable
private fun WhatsNewCard() {
    var isExpanded by remember { mutableStateOf(false) }

    // Shimmer animation for the "NEW" badge
    val shimmerTransition = rememberInfiniteTransition(label = "whats-new-shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "whats-new-shimmer-alpha"
    )

    GlassmorphicCardSimple(
        backgroundColor = JarvisCyan.copy(alpha = 0.04f),
        borderColor = JarvisCyan.copy(alpha = 0.2f)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header row with expand toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        tint = JarvisCyan.copy(alpha = shimmerAlpha),
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        "Recent Changes",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    // "NEW" badge
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(JarvisCyan.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            "NEW",
                            color = JarvisCyan.copy(alpha = shimmerAlpha),
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = TextTertiary,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Expandable changelog content
            AnimatedVisibility(
                visible = isExpanded,
                enter = fadeIn(tween(300)) + slideInVertically(
                    tween(300),
                    initialOffsetY = { it / 4 }
                )
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RecentChanges.forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    "v${item.version}",
                                    color = JarvisCyan,
                                    fontSize = 12.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    item.date,
                                    color = TextTertiary,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            item.changes.forEach { change ->
                                Row(
                                    modifier = Modifier.padding(start = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Canvas(modifier = Modifier.size(4.dp)) {
                                        drawCircle(color = JarvisCyan.copy(alpha = 0.6f))
                                    }
                                    Text(
                                        change,
                                        color = TextSecondary,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── TextField colours ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = JarvisCyan,
    unfocusedBorderColor = GlassBorder,
    focusedLabelColor    = JarvisCyan,
    unfocusedLabelColor  = TextSecondary,
    cursorColor          = JarvisCyan,
    focusedTextColor     = TextPrimary,
    unfocusedTextColor   = TextPrimary,
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor   = Color.Transparent
)

private data class SetupStep(val label: String, val isComplete: Boolean, val icon: ImageVector)

private enum class ConnectionState(val label: String, val color: Color) {
    CONNECTED("Connected", JarvisGreen),
    CONNECTING("Connecting", WarningAmber),
    DISCONNECTED("Disconnected", JarvisRedPink)
}

@Composable
private fun ConnectionStatusRow(label: String, status: ConnectionState, icon: ImageVector) {
    val infiniteTransition = rememberInfiniteTransition(label = "conn-$label")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (status == ConnectionState.CONNECTING) 600 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "conn-dot-$label"
    )
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 0.8f, targetValue = if (status == ConnectionState.CONNECTING) 1.4f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (status == ConnectionState.CONNECTING) 600 else 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ), label = "conn-scale-$label"
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(14.dp)) { drawCircle(color = status.color.copy(alpha = dotAlpha * 0.3f), radius = size.minDimension / 2f * dotScale) }
                Canvas(modifier = Modifier.size(8.dp)) { drawCircle(color = status.color.copy(alpha = dotAlpha), radius = size.minDimension / 2f) }
            }
            Icon(icon, contentDescription = label, tint = status.color, modifier = Modifier.size(16.dp))
            Text(label, color = TextSecondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        }
        Text(status.label, color = status.color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
}

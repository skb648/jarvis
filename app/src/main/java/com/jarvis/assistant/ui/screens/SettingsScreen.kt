package com.jarvis.assistant.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import com.jarvis.assistant.ui.theme.*
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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    // Local editable state for the batch API key panel.
    // These are initialized from the ViewModel's StateFlow values
    // (which come from DataStore) and allow the user to edit them
    // before hitting "SAVE & APPLY".
    var localGemini    by remember(geminiApiKey)     { mutableStateOf(geminiApiKey) }
    var localEleven    by remember(elevenLabsApiKey) { mutableStateOf(elevenLabsApiKey) }

    // ── Toast feedback — fires once when result changes ───────────────────────
    // This confirms to the user that the hot-swap succeeded.
    LaunchedEffect(apiKeySaveResult) {
        when (apiKeySaveResult) {
            ApiKeySaveResult.SUCCESS -> {
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

        // ── 1. API Keys — batch Save & Apply (HOT-SWAP) ────────────────────
        SectionHeader("API KEYS", Icons.Filled.Key)

        Surface(shape = MaterialTheme.shapes.medium, color = SurfaceNavy, tonalElevation = 2.dp) {
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

                // ElevenLabs key — NO validation, trust user input completely
                ApiKeyField(
                    value        = localEleven,
                    onValueChange = { localEleven = it },
                    label        = "ElevenLabs API Key",
                    placeholder  = "…",
                    icon         = Icons.AutoMirrored.Filled.VolumeUp
                )

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
                    val isSuccess = apiKeyTestResult.contains("valid", ignoreCase = true) && apiKeyTestResult.contains("All", ignoreCase = true)
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
                Button(
                    onClick  = { onSaveAndApplyKeys(localGemini, localEleven) },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan,
                        contentColor   = DeepNavy
                    ),
                    shape    = MaterialTheme.shapes.medium
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

                // Model info — hardcoded in Rust
                Text(
                    "Model: gemini-1.5-flash (audio + text + vision)",
                    color    = TextTertiary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        // ── 2. Voice & TTS ────────────────────────────────────────────────────
        SectionHeader("VOICE & TTS", Icons.Filled.RecordVoiceOver)

        Surface(shape = MaterialTheme.shapes.medium, color = SurfaceNavy) {
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

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        // ── 3. MQTT ───────────────────────────────────────────────────────────
        SectionHeader("MQTT / SMART HOME", Icons.Filled.Devices)

        Surface(shape = MaterialTheme.shapes.medium, color = SurfaceNavy) {
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

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        // ── 4. Home Assistant ─────────────────────────────────────────────────
        SectionHeader("HOME ASSISTANT", Icons.Filled.Home)

        Surface(shape = MaterialTheme.shapes.medium, color = SurfaceNavy) {
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

        HorizontalDivider(color = GlassBorder, thickness = 0.5.dp)

        // ── 5. System ─────────────────────────────────────────────────────────
        SectionHeader("SYSTEM", Icons.Filled.Settings)

        Surface(shape = MaterialTheme.shapes.medium, color = SurfaceNavy) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ToggleRow("Keep-Alive Service", "Run in background", isKeepAliveEnabled, onKeepAliveToggle)

                HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                BatteryRow(isBatteryOptimized, onBatteryOptimizationDisable)

                HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                ShizukuRow(isShizukuAvailable, onShizukuRequestPermission)

                HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

                PermissionsSection(context, onPermissionsRequest)

                HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

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
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

// ─── Section header ───────────────────────────────────────────────────────────

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(icon, null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
        Text(title, color = JarvisCyan, fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
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

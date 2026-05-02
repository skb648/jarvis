package com.jarvis.assistant.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.theme.*

/**
 * Smart home device data model.
 */
data class SmartDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val room: String,
    val isOn: Boolean,
    val value: String = ""     // e.g. "22°C", "60%", "locked"
)

enum class DeviceType(val label: String, val icon: ImageVector) {
    LIGHT("Light", Icons.Filled.Lightbulb),
    SWITCH("Switch", Icons.Filled.ToggleOn),
    SENSOR("Sensor", Icons.Filled.Sensors),
    THERMOSTAT("Thermostat", Icons.Filled.Thermostat),
    CAMERA("Camera", Icons.Filled.Videocam),
    LOCK("Lock", Icons.Filled.Lock),
    FAN("Fan", Icons.Filled.Toys),
    SPEAKER("Speaker", Icons.Filled.Speaker),
    TV("TV", Icons.Filled.Tv),
    CURTAIN("Curtain", Icons.Filled.ViewDay),
    UNKNOWN("Device", Icons.Filled.Devices)
}

// ── Scene Quick Actions ──────────────────────────────────────────────
private data class SceneAction(
    val label: String,
    val icon: ImageVector,
    val accentColor: Color
)

private val sceneActions = listOf(
    SceneAction("All Lights Off", Icons.Filled.Lightbulb, JarvisRedPink),
    SceneAction("Movie Mode", Icons.Filled.Movie, JarvisPurple),
    SceneAction("Good Night", Icons.Filled.Nightlight, Color(0xFF4A5599)),
    SceneAction("Welcome Home", Icons.Filled.Home, JarvisGreen)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartHomeScreen(
    devices: List<SmartDevice>,
    isConnected: Boolean,
    connectionLabel: String,
    onToggleDevice: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
    onQuickAction: (String) -> Unit = {},
    onGoToSettings: () -> Unit = {},
    // MQTT Setup parameters
    mqttBrokerUrl: String = "",
    mqttUsername: String = "",
    mqttPassword: String = "",
    onMqttBrokerChange: (String) -> Unit = {},
    onMqttUsernameChange: (String) -> Unit = {},
    onMqttPasswordChange: (String) -> Unit = {},
    onMqttConnect: () -> Unit = {},
    // Home Assistant parameters
    homeAssistantUrl: String = "",
    homeAssistantToken: String = "",
    onHomeAssistantUrlChange: (String) -> Unit = {},
    onHomeAssistantTokenChange: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var selectedRoom by remember { mutableStateOf("All") }
    val rooms = remember(devices) {
        val set = devices.map { it.room }.distinct().sorted()
        listOf("All") + set
    }
    val filteredDevices = remember(devices, selectedRoom) {
        if (selectedRoom == "All") devices else devices.filter { it.room == selectedRoom }
    }
    val activeCount = filteredDevices.count { it.isOn }
    val totalCount = filteredDevices.size

    Column(modifier = modifier.fillMaxSize()) {
        // ── Connection Status Bar ───────────────────────────────────
        Surface(
            color = if (isConnected) JarvisGreen.copy(alpha = 0.12f) else JarvisRedPink.copy(alpha = 0.12f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Pulsing dot when connected
                    if (isConnected) {
                        PulsingDot(color = JarvisGreen)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .padding(1.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = "Connection",
                        tint = if (isConnected) JarvisGreen else JarvisRedPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = connectionLabel,
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = if (isConnected) JarvisGreen else JarvisRedPink,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        if (isConnected) {
                            Text(
                                text = "Last synced: just now",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = JarvisGreen.copy(alpha = 0.6f),
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 9.sp
                                )
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isConnected) {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = JarvisRedPink,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Sync,
                                contentDescription = "Reconnect",
                                tint = JarvisRedPink.copy(alpha = 0.8f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    } else {
                        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = "Refresh",
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }

        // ── Room Filter Chips ───────────────────────────────────────
        ScrollableTabRow(
            selectedTabIndex = rooms.indexOf(selectedRoom).coerceAtLeast(0),
            containerColor = DeepNavy,
            contentColor = JarvisCyan,
            edgePadding = 12.dp,
            divider = {},
            modifier = Modifier.fillMaxWidth()
        ) {
            rooms.forEachIndexed { index, room ->
                Tab(
                    selected = selectedRoom == room,
                    onClick = { selectedRoom = room },
                    text = {
                        Text(
                            text = room,
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    },
                    selectedContentColor = JarvisCyan,
                    unselectedContentColor = TextSecondary
                )
            }
        }

        // ── Device Count Summary ────────────────────────────────────
        if (devices.isNotEmpty()) {
            Text(
                text = "$totalCount devices · $activeCount active",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp
                ),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        // ── Room Header Card (when specific room selected) ──────────
        if (selectedRoom != "All" && filteredDevices.isNotEmpty()) {
            val activeInRoom = filteredDevices.count { it.isOn }
            val totalInRoom = filteredDevices.size
            val ratio = if (totalInRoom > 0) activeInRoom.toFloat() / totalInRoom else 0f
            val barColor = when {
                ratio >= 0.6f -> JarvisGreen
                ratio >= 0.3f -> WarningAmber
                else -> JarvisRedPink
            }

            GlassmorphicCardSimple(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                cornerRadius = 16.dp
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = selectedRoom,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            fontSize = 20.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$totalInRoom devices · $activeInRoom active",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    // Active ratio bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                    ) {
                        // Background track
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .then(
                                    Modifier.drawBehind {
                                        drawRect(color = SurfaceNavyLight)
                                    }
                                )
                        )
                        // Filled portion
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .height(4.dp)
                                .then(
                                    Modifier.drawBehind {
                                        drawRect(color = barColor)
                                    }
                                )
                        )
                    }
                }
            }
        }

        // ── Scene / Automation Quick Actions ─────────────────────────
        if (devices.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                sceneActions.forEach { scene ->
                    OutlinedButton(
                        onClick = { onQuickAction(scene.label) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, scene.accentColor.copy(alpha = 0.5f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = scene.accentColor.copy(alpha = 0.08f),
                            contentColor = scene.accentColor
                        ),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp)
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = scene.icon,
                                contentDescription = scene.label,
                                tint = scene.accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = scene.label,
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 8.sp,
                                    lineHeight = 10.sp
                                ),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // ── Setup MQTT Connection Card (when MQTT not connected) ──────────────
        if (!isConnected) {
            // Animated pulse for the setup card header
            val setupPulseTransition = rememberInfiniteTransition(label = "mqtt-setup-pulse")
            val setupPulseAlpha by setupPulseTransition.animateFloat(
                initialValue = 0.4f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 2000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ), label = "setup-pulse-alpha"
            )

            GlassmorphicCardSimple(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                backgroundColor = WarningAmber.copy(alpha = 0.04f),
                borderColor = WarningAmber.copy(alpha = 0.25f),
                cornerRadius = 16.dp
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // ── Card Header with animated indicator ──────────────
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                // Glow ring
                                Canvas(modifier = Modifier.size(28.dp)) {
                                    drawCircle(
                                        color = WarningAmber.copy(alpha = setupPulseAlpha * 0.15f),
                                        radius = size.minDimension / 2f
                                    )
                                }
                                Icon(Icons.Filled.Hub, contentDescription = "MQTT", tint = WarningAmber, modifier = Modifier.size(20.dp))
                            }
                            Column {
                                Text(
                                    "Setup MQTT Connection",
                                    color = TextPrimary,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Not connected",
                                    color = WarningAmber.copy(alpha = setupPulseAlpha),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                        // Disconnected badge
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(JarvisRedPink.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "OFFLINE",
                                color = JarvisRedPink,
                                fontSize = 8.sp,
                                fontFamily = FontFamily.Monospace,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    Text(
                        "Enter your MQTT broker details to connect and discover smart home devices.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 15.sp
                    )

                    // ── Broker URL Input ────────────────────────────────
                    OutlinedTextField(
                        value = mqttBrokerUrl,
                        onValueChange = onMqttBrokerChange,
                        label = { Text("Broker URL") },
                        placeholder = { Text("mqtt://192.168.1.x:1883", color = TextTertiary) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = tfColorsSmartHome(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        leadingIcon = { Icon(Icons.Filled.Hub, null, tint = TextSecondary) }
                    )

                    // ── Username Input (optional) ──────────────────────
                    OutlinedTextField(
                        value = mqttUsername,
                        onValueChange = onMqttUsernameChange,
                        label = { Text("Username (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = tfColorsSmartHome(),
                        leadingIcon = { Icon(Icons.Filled.Person, null, tint = TextSecondary) }
                    )

                    // ── Password Input (optional) ──────────────────────
                    OutlinedTextField(
                        value = mqttPassword,
                        onValueChange = onMqttPasswordChange,
                        label = { Text("Password (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = tfColorsSmartHome(),
                        visualTransformation = PasswordVisualTransformation(),
                        leadingIcon = { Icon(Icons.Filled.Lock, null, tint = TextSecondary) }
                    )

                    // ── Connect Button with gradient border ────────────
                    val connectBorderTransition = rememberInfiniteTransition(label = "connect-border")
                    val connectBorderShift by connectBorderTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(durationMillis = 3000, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "connect-border-shift"
                    )

                    val connectBorderBrush = Brush.horizontalGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.5f + connectBorderShift * 0.5f),
                            JarvisGreen.copy(alpha = 0.3f + connectBorderShift * 0.4f),
                            JarvisCyan.copy(alpha = 0.5f + (1f - connectBorderShift) * 0.5f)
                        )
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(connectBorderBrush)
                            .padding(1.5.dp)
                    ) {
                        Button(
                            onClick = onMqttConnect,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = JarvisCyan,
                                contentColor = DeepNavy
                            ),
                            shape = RoundedCornerShape(7.dp)
                        ) {
                            Icon(Icons.Filled.CloudDone, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "CONNECT",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                }
            }
        }

        // ── Home Assistant Configuration Card ────────────────────────────────
        GlassmorphicCardSimple(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            cornerRadius = 16.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Home, contentDescription = "Home Assistant", tint = JarvisPurple, modifier = Modifier.size(20.dp))
                    Text("Home Assistant", color = TextPrimary, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
                Text("Connect to your Home Assistant instance for device control.", color = TextSecondary, fontSize = 11.sp, fontFamily = FontFamily.Monospace)

                OutlinedTextField(
                    value = homeAssistantUrl,
                    onValueChange = onHomeAssistantUrlChange,
                    label = { Text("HA Base URL") },
                    placeholder = { Text("http://homeassistant.local:8123", color = TextTertiary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColorsSmartHome(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    leadingIcon = { Icon(Icons.Filled.Cloud, null, tint = TextSecondary) }
                )
                OutlinedTextField(
                    value = homeAssistantToken,
                    onValueChange = onHomeAssistantTokenChange,
                    label = { Text("Long-Lived Access Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = tfColorsSmartHome(),
                    visualTransformation = PasswordVisualTransformation(),
                    leadingIcon = { Icon(Icons.Filled.VpnKey, null, tint = TextSecondary) }
                )
            }
        }

        // ── Empty State Illustration ─────────────────────────────────
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "No devices",
                        tint = TextTertiary.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "No Devices Found",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            color = TextSecondary
                        )
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Connect to MQTT or Home Assistant\nto discover devices",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    OutlinedButton(
                        onClick = onGoToSettings,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, JarvisCyan),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = JarvisCyan
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Go to Settings",
                            style = MaterialTheme.typography.labelMedium.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                }
            }
        } else {
            // ── Device Grid (2 columns) ──────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(items = filteredDevices, key = { it.id }) { device ->
                    DeviceCard(
                        device = device,
                        onToggle = { onToggleDevice(device.id, !device.isOn) }
                    )
                }
            }
        }
    }
}

// ── Pulsing Dot Composable ───────────────────────────────────────────
@Composable
private fun PulsingDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.4f at 0
                1f at 400
                0.4f at 800
                0.4f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1200
                0.8f at 0
                1.2f at 400
                0.8f at 800
                0.8f at 1200
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )

    Canvas(modifier = modifier.size(10.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = (size.minDimension / 2f) * pulseScale * 0.7f
        // Outer glow
        drawCircle(
            color = color.copy(alpha = pulseAlpha * 0.3f),
            radius = radius * 1.5f,
            center = center
        )
        // Core dot
        drawCircle(
            color = color.copy(alpha = pulseAlpha),
            radius = radius,
            center = center
        )
    }
}

// ── Icon Glow Ring ────────────────────────────────────────────────────
@Composable
private fun IconGlowRing(
    color: Color,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = 0.35f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Canvas(modifier = modifier.size(40.dp)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f
        // Outer soft glow
        drawCircle(
            color = color.copy(alpha = glowAlpha * 0.4f),
            radius = radius,
            center = center
        )
        // Inner ring
        drawCircle(
            color = color.copy(alpha = glowAlpha),
            radius = radius * 0.75f,
            center = center
        )
    }
}

// ── Status Dot ────────────────────────────────────────────────────────
@Composable
private fun StatusDot(
    isOn: Boolean,
    modifier: Modifier = Modifier
) {
    val color = if (isOn) JarvisGreen else TextTertiary.copy(alpha = 0.5f)
    Canvas(modifier = modifier.size(8.dp)) {
        drawCircle(
            color = color,
            radius = size.minDimension / 2f,
            center = Offset(size.width / 2f, size.height / 2f)
        )
    }
}

@Composable
private fun DeviceCard(
    device: SmartDevice,
    onToggle: () -> Unit
) {
    val accentColor = when (device.type) {
        DeviceType.LIGHT      -> JarvisCyan
        DeviceType.SWITCH     -> JarvisCyan
        DeviceType.SENSOR     -> JarvisGreen
        DeviceType.THERMOSTAT -> Color(0xFFFF8800)
        DeviceType.CAMERA     -> JarvisPurple
        DeviceType.LOCK       -> Color(0xFFFFAB00)
        DeviceType.FAN        -> Color(0xFF88CCFF)
        DeviceType.SPEAKER    -> Color(0xFFFF5588)
        DeviceType.TV         -> JarvisPurple
        DeviceType.CURTAIN    -> Color(0xFF88AA00)
        DeviceType.UNKNOWN    -> TextSecondary
    }

    // Animated card background color (flash effect on toggle)
    val animatedBgColor by animateColorAsState(
        targetValue = if (device.isOn) accentColor.copy(alpha = 0.08f) else GlassBackground,
        animationSpec = tween(durationMillis = 400),
        label = "bgColor"
    )

    // Animated border color
    val animatedBorderColor by animateColorAsState(
        targetValue = if (device.isOn) accentColor.copy(alpha = 0.3f) else GlassBorder,
        animationSpec = tween(durationMillis = 400),
        label = "borderColor"
    )

    // Animated elevation
    val animatedElevation by animateDpAsState(
        targetValue = if (device.isOn) 2.dp else 0.dp,
        animationSpec = tween(durationMillis = 300),
        label = "elevation"
    )

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = animatedBgColor),
        border = BorderStroke(width = 1.dp, color = animatedBorderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = animatedElevation),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Top row: glow + icon + switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(contentAlignment = Alignment.Center) {
                    // Glow ring behind icon when ON
                    if (device.isOn) {
                        IconGlowRing(color = accentColor)
                    }
                    Icon(
                        imageVector = device.type.icon,
                        contentDescription = device.type.label,
                        tint = if (device.isOn) accentColor else TextTertiary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                Switch(
                    checked = device.isOn,
                    onCheckedChange = { onToggle() },
                    modifier = Modifier.height(24.dp),
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = accentColor.copy(alpha = 0.5f),
                        checkedThumbColor = accentColor,
                        uncheckedTrackColor = SurfaceNavyLight,
                        uncheckedThumbColor = TextTertiary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Device name + status dot
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusDot(isOn = device.isOn)
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary,
                        fontSize = 13.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Value / status line
            if (device.value.isNotBlank()) {
                Text(
                    text = device.value,
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = accentColor,
                        fontSize = 11.sp
                    )
                )
            }

            // Room label
            Text(
                text = device.room,
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextTertiary,
                    fontSize = 10.sp
                )
            )
        }
    }
}

// ─── Text field colors for SmartHome screen ──────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
private fun tfColorsSmartHome() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = GlassBorder,
    focusedContainerColor = SurfaceNavy,
    unfocusedContainerColor = SurfaceNavy,
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedLabelColor = JarvisCyan,
    unfocusedLabelColor = TextTertiary,
    cursorColor = JarvisCyan
)

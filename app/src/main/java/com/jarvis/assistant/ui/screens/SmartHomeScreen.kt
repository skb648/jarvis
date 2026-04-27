package com.jarvis.assistant.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartHomeScreen(
    devices: List<SmartDevice>,
    isConnected: Boolean,
    connectionLabel: String,
    onToggleDevice: (String, Boolean) -> Unit,
    onRefresh: () -> Unit,
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isConnected) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                        contentDescription = "Connection",
                        tint = if (isConnected) JarvisGreen else JarvisRedPink,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = connectionLabel,
                        style = MaterialTheme.typography.labelMedium.copy(
                            color = if (isConnected) JarvisGreen else JarvisRedPink,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
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

        // ── Device Grid (2 columns) ─────────────────────────────────
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 12.dp)
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

    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isOn) accentColor.copy(alpha = 0.08f) else GlassBackground
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (device.isOn) accentColor.copy(alpha = 0.3f) else GlassBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            // Top row: icon + switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = device.type.icon,
                    contentDescription = device.type.label,
                    tint = if (device.isOn) accentColor else TextTertiary,
                    modifier = Modifier.size(28.dp)
                )
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

            // Device name
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

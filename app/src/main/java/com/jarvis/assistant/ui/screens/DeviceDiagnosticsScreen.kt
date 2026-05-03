package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.theme.*
import kotlin.math.sin
import kotlin.random.Random

// ═══════════════════════════════════════════════════════════════════════════════
// DeviceDiagnosticsScreen — JARVIS holographic device diagnostics panel
//
// Displays comprehensive device health information across six sections:
//  Battery, Storage, Memory, Network, CPU, and JARVIS System.
// Each section uses glassmorphic cards with color-coded indicators
// and animated progress bars.
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceDiagnosticsScreen(
    batteryLevel: Int = 0,
    isCharging: Boolean = false,
    batteryHealth: String = "Good",
    batteryTemp: String = "--",
    storageUsedGB: Float = 0f,
    storageTotalGB: Float = 64f,
    memoryUsedGB: Float = 0f,
    memoryTotalGB: Float = 8f,
    networkType: String = "Unknown",
    signalStrength: Int = 0,
    ssid: String = "--",
    ipAddress: String = "--",
    cpuUsagePercent: Float = 0f,
    cpuCores: Int = 8,
    cpuClockSpeed: String = "--",
    isRustReady: Boolean = false,
    isGroqConnected: Boolean = false,
    isAudioReady: Boolean = false,
    isWakeWordActive: Boolean = false,
    isServiceRunning: Boolean = false,
    uptimeHours: Int = 0,
    uptimeMinutes: Int = 0,
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavy)
    ) {
        // ── Top App Bar ────────────────────────────────────────────────────
        TopAppBar(
            title = {
                Text(
                    "DIAGNOSTICS",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 3.sp,
                    color = JarvisCyan
                )
            },
            navigationIcon = {
                IconButton(onClick = { /* Nav back */ }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = TextPrimary
                    )
                }
            },
            actions = {
                IconButton(onClick = onRefresh) {
                    Icon(
                        Icons.Filled.Refresh,
                        contentDescription = "Refresh",
                        tint = JarvisCyan
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = DeepNavy,
                titleContentColor = JarvisCyan
            )
        )

        // ── Scrollable Content ─────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // ── 1. Battery Status ───────────────────────────────────────────
            SectionHeader("BATTERY STATUS", Icons.Filled.BatteryStd)

            GlassmorphicCardSimple {
                BatteryCard(
                    batteryLevel = batteryLevel,
                    isCharging = isCharging,
                    batteryHealth = batteryHealth,
                    batteryTemp = batteryTemp
                )
            }

            // ── 2. Storage ─────────────────────────────────────────────────
            SectionHeader("STORAGE", Icons.Filled.Storage)

            GlassmorphicCardSimple {
                StorageCard(
                    storageUsedGB = storageUsedGB,
                    storageTotalGB = storageTotalGB
                )
            }

            // ── 3. Memory (RAM) ────────────────────────────────────────────
            SectionHeader("MEMORY", Icons.Filled.Memory)

            GlassmorphicCardSimple {
                MemoryCard(
                    memoryUsedGB = memoryUsedGB,
                    memoryTotalGB = memoryTotalGB
                )
            }

            // ── 4. Network ─────────────────────────────────────────────────
            SectionHeader("NETWORK", Icons.Filled.Wifi)

            GlassmorphicCardSimple {
                NetworkCard(
                    networkType = networkType,
                    signalStrength = signalStrength,
                    ssid = ssid,
                    ipAddress = ipAddress
                )
            }

            // ── 5. CPU / Performance ───────────────────────────────────────
            SectionHeader("CPU / PERFORMANCE", Icons.Filled.Speed)

            GlassmorphicCardSimple {
                CpuCard(
                    cpuUsagePercent = cpuUsagePercent,
                    cpuCores = cpuCores,
                    cpuClockSpeed = cpuClockSpeed
                )
            }

            // ── 6. JARVIS System ───────────────────────────────────────────
            SectionHeader("JARVIS SYSTEM", Icons.Filled.SmartToy)

            GlassmorphicCardSimple {
                JarvisSystemCard(
                    isRustReady = isRustReady,
                    isGroqConnected = isGroqConnected,
                    isAudioReady = isAudioReady,
                    isWakeWordActive = isWakeWordActive,
                    isServiceRunning = isServiceRunning,
                    uptimeHours = uptimeHours,
                    uptimeMinutes = uptimeMinutes
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section Header — matches SettingsScreen pattern
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionHeader(title: String, icon: ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Icon(icon, null, tint = JarvisCyan, modifier = Modifier.size(14.dp))
        Text(
            title,
            color = JarvisCyan,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 2.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 1. Battery Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun BatteryCard(
    batteryLevel: Int,
    isCharging: Boolean,
    batteryHealth: String,
    batteryTemp: String
) {
    val batteryColor = when {
        batteryLevel > 50 -> JarvisGreen
        batteryLevel in 20..50 -> WarningAmber
        else -> JarvisRedPink
    }

    val animatedProgress by animateFloatAsState(
        targetValue = batteryLevel / 100f,
        animationSpec = tween(durationMillis = 800),
        label = "batteryProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // ── Circular progress + percentage ─────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.size(140.dp)
        ) {
            // Background ring
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 8.dp.toPx()
                drawArc(
                    color = SurfaceNavyLight,
                    startAngle = -90f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            // Animated progress ring
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 8.dp.toPx()
                drawArc(
                    color = batteryColor,
                    startAngle = -90f,
                    sweepAngle = 360f * animatedProgress,
                    useCenter = false,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                )
            }
            // Center text
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.BatteryStd,
                    contentDescription = null,
                    tint = batteryColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "$batteryLevel%",
                    color = TextPrimary,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Battery details ────────────────────────────────────────────
        DiagnosticRow(
            label = "Charging",
            value = if (isCharging) "Yes" else "No",
            valueColor = if (isCharging) JarvisGreen else TextSecondary
        )
        DiagnosticRow(
            label = "Battery Health",
            value = batteryHealth,
            valueColor = when (batteryHealth) {
                "Good" -> JarvisGreen
                "Fair" -> WarningAmber
                else -> JarvisRedPink
            }
        )
        DiagnosticRow(
            label = "Temperature",
            value = "${batteryTemp}°C",
            valueColor = TextPrimary
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 2. Storage Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun StorageCard(
    storageUsedGB: Float,
    storageTotalGB: Float
) {
    val usagePercent = if (storageTotalGB > 0f) (storageUsedGB / storageTotalGB) * 100f else 0f
    val barColor = when {
        usagePercent < 60f -> JarvisGreen
        usagePercent in 60f..85f -> WarningAmber
        else -> JarvisRedPink
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (storageTotalGB > 0f) storageUsedGB / storageTotalGB else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "storageProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // ── Header row ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Storage, null, tint = barColor, modifier = Modifier.size(18.dp))
            Text(
                "%.1f / %.1f GB used (%.0f%% full)".format(storageUsedGB, storageTotalGB, usagePercent),
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── Usage bar ──────────────────────────────────────────────────
        UsageBar(progress = animatedProgress, color = barColor)

        Spacer(Modifier.height(12.dp))

        // ── Breakdown ──────────────────────────────────────────────────
        DiagnosticRow(label = "Apps", value = "-- GB", valueColor = TextSecondary)
        DiagnosticRow(label = "Media", value = "-- GB", valueColor = TextSecondary)
        DiagnosticRow(label = "Other", value = "-- GB", valueColor = TextSecondary)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 3. Memory Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MemoryCard(
    memoryUsedGB: Float,
    memoryTotalGB: Float
) {
    val usagePercent = if (memoryTotalGB > 0f) (memoryUsedGB / memoryTotalGB) * 100f else 0f
    val barColor = when {
        usagePercent < 60f -> JarvisGreen
        usagePercent in 60f..85f -> WarningAmber
        else -> JarvisRedPink
    }

    val animatedProgress by animateFloatAsState(
        targetValue = if (memoryTotalGB > 0f) memoryUsedGB / memoryTotalGB else 0f,
        animationSpec = tween(durationMillis = 800),
        label = "memoryProgress"
    )

    val availableGB = memoryTotalGB - memoryUsedGB

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // ── Header row ─────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Memory, null, tint = barColor, modifier = Modifier.size(18.dp))
            Text(
                "%.1f / %.1f GB used".format(memoryUsedGB, memoryTotalGB),
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(10.dp))

        // ── Usage bar ──────────────────────────────────────────────────
        UsageBar(progress = animatedProgress, color = barColor)

        Spacer(Modifier.height(12.dp))

        DiagnosticRow(
            label = "Available",
            value = "%.1f GB".format(availableGB),
            valueColor = when {
                availableGB > memoryTotalGB * 0.4f -> JarvisGreen
                availableGB > memoryTotalGB * 0.15f -> WarningAmber
                else -> JarvisRedPink
            }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 4. Network Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun NetworkCard(
    networkType: String,
    signalStrength: Int,
    ssid: String,
    ipAddress: String
) {
    val typeIcon = when {
        networkType.equals("WiFi", ignoreCase = true) -> Icons.Filled.Wifi
        networkType.equals("Mobile", ignoreCase = true) -> Icons.Filled.SignalCellularAlt
        else -> Icons.Filled.SignalCellularOff
    }

    val typeColor = when {
        networkType.equals("WiFi", ignoreCase = true) -> JarvisCyan
        networkType.equals("Mobile", ignoreCase = true) -> JarvisGreen
        else -> JarvisRedPink
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // ── Header ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(typeIcon, null, tint = typeColor, modifier = Modifier.size(18.dp))
                Text(
                    networkType,
                    color = typeColor,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ── Signal strength bars ────────────────────────────────────
            SignalBars(signalStrength = signalStrength.coerceIn(0, 4))
        }

        Spacer(Modifier.height(12.dp))

        DiagnosticRow(
            label = if (networkType.equals("WiFi", ignoreCase = true)) "SSID" else "Carrier",
            value = ssid,
            valueColor = if (ssid != "--") TextPrimary else TextTertiary
        )
        DiagnosticRow(
            label = "IP Address",
            value = ipAddress,
            valueColor = if (ipAddress != "--") TextPrimary else TextTertiary
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 5. CPU / Performance Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CpuCard(
    cpuUsagePercent: Float,
    cpuCores: Int,
    cpuClockSpeed: String
) {
    val cpuColor = when {
        cpuUsagePercent < 40f -> JarvisGreen
        cpuUsagePercent < 75f -> WarningAmber
        else -> JarvisRedPink
    }

    val animatedCpu by animateFloatAsState(
        targetValue = cpuUsagePercent / 100f,
        animationSpec = tween(durationMillis = 600),
        label = "cpuProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // ── CPU usage display ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Filled.Speed, null, tint = cpuColor, modifier = Modifier.size(18.dp))
                Text(
                    "CPU Usage",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                "%.1f%%".format(cpuUsagePercent),
                color = cpuColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        Spacer(Modifier.height(8.dp))

        // ── Usage bar ──────────────────────────────────────────────────
        UsageBar(progress = animatedCpu, color = cpuColor)

        Spacer(Modifier.height(10.dp))

        DiagnosticRow(label = "Cores", value = "$cpuCores", valueColor = TextPrimary)
        DiagnosticRow(label = "Clock Speed", value = "$cpuClockSpeed GHz", valueColor = TextPrimary)

        Spacer(Modifier.height(8.dp))

        // ── CPU history chart ──────────────────────────────────────────
        CpuHistoryChart(cpuUsagePercent = cpuUsagePercent, lineColor = cpuColor)
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// 6. JARVIS System Card
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun JarvisSystemCard(
    isRustReady: Boolean,
    isGroqConnected: Boolean,
    isAudioReady: Boolean,
    isWakeWordActive: Boolean,
    isServiceRunning: Boolean,
    uptimeHours: Int,
    uptimeMinutes: Int
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // ── System status rows ─────────────────────────────────────────
        SystemStatusRow(
            label = "Rust Engine",
            isOnline = isRustReady,
            onlineText = "Online",
            offlineText = "Offline"
        )
        SystemStatusRow(
            label = "Groq API",
            isOnline = isGroqConnected,
            onlineText = "Connected",
            offlineText = "Disconnected"
        )
        SystemStatusRow(
            label = "Audio Engine",
            isOnline = isAudioReady,
            onlineText = "Ready",
            offlineText = "Not Ready"
        )
        SystemStatusRow(
            label = "Wake Word",
            isOnline = isWakeWordActive,
            onlineText = "Active",
            offlineText = "Inactive"
        )
        SystemStatusRow(
            label = "Foreground Service",
            isOnline = isServiceRunning,
            onlineText = "Running",
            offlineText = "Stopped"
        )

        Spacer(Modifier.height(10.dp))

        HorizontalDivider(color = GlassBorder.copy(alpha = 0.4f), thickness = 0.5.dp)

        Spacer(Modifier.height(8.dp))

        // ── Uptime ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "UPTIME",
                color = TextSecondary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Text(
                "%02d hours %02d minutes".format(uptimeHours, uptimeMinutes),
                color = JarvisCyan,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Reusable Components
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * A label-value row for diagnostic entries.
 */
@Composable
private fun DiagnosticRow(
    label: String,
    value: String,
    valueColor: Color = TextPrimary
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = TextSecondary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
        Text(
            value,
            color = valueColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Animated usage bar with rounded corners.
 */
@Composable
private fun UsageBar(
    progress: Float,
    color: Color,
    height: Dp = 8.dp,
    modifier: Modifier = Modifier
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 800),
        label = "usageBar"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceNavyLight)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animatedProgress)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
    }
}

/**
 * Signal strength indicator with 4 vertical bars.
 */
@Composable
private fun SignalBars(signalStrength: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (i in 1..4) {
            val isFilled = i <= signalStrength
            val barHeight = (6 + i * 4).dp
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (isFilled) JarvisGreen else SurfaceNavyLight
                    )
            )
        }
    }
}

/**
 * System status row with a colored dot indicator.
 */
@Composable
private fun SystemStatusRow(
    label: String,
    isOnline: Boolean,
    onlineText: String,
    offlineText: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Status dot
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = if (isOnline) JarvisGreen else JarvisRedPink
                )
            }
            Text(
                label,
                color = TextPrimary,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(
            if (isOnline) onlineText else offlineText,
            color = if (isOnline) JarvisGreen else JarvisRedPink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
    }
}

/**
 * Simple CPU history chart drawn on Canvas.
 * Generates a polyline with values clustered around [cpuUsagePercent].
 */
@Composable
private fun CpuHistoryChart(
    cpuUsagePercent: Float,
    lineColor: Color,
    modifier: Modifier = Modifier
) {
    // Generate history data once; recompose only when cpuUsagePercent changes significantly
    val dataPoints = remember(cpuUsagePercent) {
        val base = cpuUsagePercent / 100f
        val rnd = Random(System.currentTimeMillis())
        List(30) { idx ->
            // Create a realistic-looking pattern: oscillating around base with noise
            val oscillation = 0.08f * sin(idx * 0.5f).toFloat()
            val noise = (rnd.nextFloat() - 0.5f) * 0.12f
            (base + oscillation + noise).coerceIn(0.02f, 0.98f)
        }
    }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(60.dp)
    ) {
        val w = size.width
        val h = size.height
        val padding = 4.dp.toPx()

        // Draw subtle grid lines
        for (i in 0..4) {
            val y = padding + (h - 2 * padding) * i / 4f
            drawLine(
                color = SurfaceNavyLight,
                start = Offset(padding, y),
                end = Offset(w - padding, y),
                strokeWidth = 0.5.dp.toPx()
            )
        }

        // Draw the polyline
        if (dataPoints.size > 1) {
            val stepX = (w - 2 * padding) / (dataPoints.size - 1)
            val points = dataPoints.mapIndexed { index, value ->
                Offset(
                    x = padding + index * stepX,
                    y = padding + (1f - value) * (h - 2 * padding)
                )
            }

            // Glow effect — wider translucent stroke
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                },
                color = lineColor.copy(alpha = 0.25f),
                style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
            )

            // Main line
            drawPath(
                path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(points.first().x, points.first().y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                },
                color = lineColor,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
            )

            // End dot
            drawCircle(
                color = lineColor,
                radius = 3.dp.toPx(),
                center = points.last()
            )
        }
    }
}

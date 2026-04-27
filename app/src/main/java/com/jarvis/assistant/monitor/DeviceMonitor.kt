package com.jarvis.assistant.monitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Device Monitor — Comprehensive device telemetry.
 *
 * Provides real-time monitoring of:
 *   - Battery: level, charging, temperature, voltage, health
 *   - Memory: total/available/used, low memory flag
 *   - Storage: internal + external total/available
 *   - Network: connection type, bandwidth
 *   - CPU: usage %, core count, max frequency
 */
object DeviceMonitor {

    private const val TAG = "JarvisDeviceMonitor"

    // ─── Battery ────────────────────────────────────────────────

    fun getBatteryInfo(context: Context): Map<String, Any> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10f) ?: 0f
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)?.div(1000f) ?: 0f
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
            else -> "Unknown"
        }
        val technology = intent?.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY) ?: "Unknown"

        return mapOf(
            "level" to level,
            "scale" to scale,
            "percent" to (level * 100 / scale),
            "isCharging" to isCharging,
            "temperature" to temp,
            "voltage" to voltage,
            "health" to health,
            "technology" to technology
        )
    }

    // ─── Memory ─────────────────────────────────────────────────

    fun getMemoryInfo(context: Context): Map<String, Any> {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)

        val totalMB = memInfo.totalMem / (1024 * 1024)
        val availableMB = memInfo.availMem / (1024 * 1024)
        val usedMB = totalMB - availableMB

        return mapOf(
            "totalMB" to totalMB,
            "availableMB" to availableMB,
            "usedMB" to usedMB,
            "usedPercent" to ((usedMB * 100.0) / totalMB).toInt(),
            "isLowMemory" to memInfo.lowMemory,
            "thresholdMB" to (memInfo.threshold / (1024 * 1024))
        )
    }

    // ─── Storage ────────────────────────────────────────────────

    fun getStorageInfo(): Map<String, Any?> {
        val internal = getStorageStats(Environment.getDataDirectory())
        val external = if (Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) {
            getStorageStats(Environment.getExternalStorageDirectory())
        } else null

        return mapOf(
            "internal" to internal,
            "external" to external
        )
    }

    private fun getStorageStats(path: File): Map<String, Any> {
        val stat = StatFs(path.path)
        val blockSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.blockSizeLong
        } else {
            stat.blockSize.toLong()
        }
        val totalBlocks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.blockCountLong
        } else {
            stat.blockCount.toLong()
        }
        val availableBlocks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            stat.availableBlocksLong
        } else {
            stat.availableBlocks.toLong()
        }

        val totalGB = (totalBlocks * blockSize) / (1024.0 * 1024 * 1024)
        val availableGB = (availableBlocks * blockSize) / (1024.0 * 1024 * 1024)
        val usedGB = totalGB - availableGB

        return mapOf(
            "totalGB" to String.format("%.1f", totalGB),
            "availableGB" to String.format("%.1f", availableGB),
            "usedGB" to String.format("%.1f", usedGB),
            "usedPercent" to ((usedGB * 100) / totalGB).toInt()
        )
    }

    // ─── Network ────────────────────────────────────────────────

    fun getNetworkInfo(context: Context): Map<String, Any> {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(network)

        val isConnected = caps != null
        val type = when {
            caps == null -> "none"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "unknown"
        }

        val downSpeed = caps?.linkDownstreamBandwidthKbps ?: 0
        val upSpeed = caps?.linkUpstreamBandwidthKbps ?: 0

        return mapOf(
            "isConnected" to isConnected,
            "type" to type,
            "downstreamKbps" to downSpeed,
            "upstreamKbps" to upSpeed
        )
    }

    // ─── CPU ────────────────────────────────────────────────────

    fun getCpuInfo(): Map<String, Any> {
        val cores = Runtime.getRuntime().availableProcessors()
        val usage = getCpuUsage()
        val maxFreq = getMaxCpuFrequency()

        return mapOf(
            "cores" to cores,
            "usagePercent" to usage,
            "maxFreqMHz" to maxFreq
        )
    }

    private fun getCpuUsage(): Float {
        return try {
            val reader = RandomAccessFile("/proc/stat", "r")
            val line = reader.readLine()
            reader.close()

            val parts = line.split("\\s+".toRegex())
            if (parts.size > 8) {
                val idle = parts[4].toLong()
                val total = parts.subList(1, 8).sumOf { it.toLong() }
                if (total > 0) {
                    ((total - idle) * 100f) / total
                } else 0f
            } else 0f
        } catch (e: Exception) {
            -1f
        }
    }

    private fun getMaxCpuFrequency(): Int {
        return try {
            val dir = File("/sys/devices/system/cpu/")
            val cpuDirs = dir.listFiles { f -> f.name.matches("cpu[0-9]+".toRegex()) } ?: return 0

            var maxFreq = 0
            for (cpuDir in cpuDirs) {
                val freqFile = File(cpuDir, "cpufreq/scaling_max_freq")
                if (freqFile.exists()) {
                    val freq = freqFile.readText().trim().toIntOrNull() ?: 0
                    if (freq > maxFreq) maxFreq = freq
                }
            }
            maxFreq / 1000 // Convert kHz to MHz
        } catch (e: Exception) {
            0
        }
    }

    // ─── Full Report ────────────────────────────────────────────

    fun getFullReport(context: Context): Map<String, Any> {
        return mapOf(
            "battery" to getBatteryInfo(context),
            "memory" to getMemoryInfo(context),
            "storage" to getStorageInfo(),
            "network" to getNetworkInfo(context),
            "cpu" to getCpuInfo()
        )
    }
}

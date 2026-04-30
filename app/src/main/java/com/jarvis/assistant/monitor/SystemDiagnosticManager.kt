package com.jarvis.assistant.monitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.permissions.PermissionManager

/**
 * SystemDiagnosticManager — JARVIS's Self-Knowledge Core.
 *
 * This class answers the fundamental question: "What can JARVIS do right now?"
 *
 * It checks the device's current state and produces a structured report
 * of all capabilities and permissions. This report is injected into the
 * Gemini System Prompt on EVERY request as a hidden "System Status Block",
 * so JARVIS always knows:
 *   - Is the AccessibilityService enabled? (Can it click/scroll/read the screen?)
 *   - Is SYSTEM_ALERT_WINDOW granted? (Can it draw overlays?)
 *   - Is the microphone permission granted? (Can it hear the user?)
 *   - What's the battery level? (Should it conserve resources?)
 *   - What's the network status? (Can it reach Gemini API?)
 *   - Is battery optimization bypassed? (Will Android kill it?)
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WHY THIS MATTERS:
 *
 * Without this, JARVIS might try to click a button when Accessibility
 * is disabled, or try to record audio when the mic permission is revoked.
 * By injecting the current state into the system prompt, Gemini can:
 *   - Refuse actions that require disabled permissions
 *   - Suggest the user enable Accessibility when needed
 *   - Adapt its behavior to low battery / offline situations
 *   - Diagnose its own failures ("Why isn't the mic working?" → check permission)
 * ═══════════════════════════════════════════════════════════════════════
 */
object SystemDiagnosticManager {

    private const val TAG = "JarvisSystemDiag"

    /**
     * Generate a complete system status report.
     * This is injected into the Gemini System Prompt on every request.
     *
     * @param context Application context
     * @return A formatted string with all system state information
     */
    fun getSystemStatusBlock(context: Context): String {
        val report = buildReport(context)
        return buildString {
            append("\n\n═══ SYSTEM STATUS ═══\n")
            append("Battery: ${report.batteryPercent}% ${if (report.isCharging) "(Charging)" else ""}\n")
            append("Network: ${report.networkType} ${if (report.isOnline) "(Connected)" else "(Offline)"}\n")
            append("Mic Permission: ${if (report.hasMicPermission) "GRANTED" else "DENIED"}\n")
            append("Camera Permission: ${if (report.hasCameraPermission) "GRANTED" else "DENIED"}\n")
            append("Overlay Permission: ${if (report.hasOverlayPermission) "GRANTED" else "DENIED"}\n")
            append("Accessibility Enabled: ${if (report.isAccessibilityEnabled) "YES" else "NO"}\n")
            append("Battery Optimization Bypassed: ${if (report.isBatteryOptBypassed) "YES" else "NO — may be killed by OS"}\n")
            append("Foreground Service Running: ${if (report.isForegroundServiceRunning) "YES" else "NO"}\n")
            append("Memory Available: ${report.availableMemoryMB}MB / ${report.totalMemoryMB}MB\n")
            append("Rust Native: ${if (report.isRustReady) "READY" else "NOT LOADED (using Kotlin HTTP)"}\n")
            append("═══ END STATUS ═══\n")
        }
    }

    /**
     * Generate a detailed diagnostic report (for the diagnose_system tool).
     * Returns a more verbose version with suggestions.
     */
    fun getDetailedDiagnosticReport(context: Context): String {
        val report = buildReport(context)
        return buildString {
            appendLine("JARVIS System Diagnostic Report")
            appendLine("=" .repeat(40))
            appendLine()
            appendLine("PERMISSIONS:")
            appendLine("  Microphone: ${statusIcon(report.hasMicPermission)} ${if (!report.hasMicPermission) "REQUIRED for voice input" else ""}")
            appendLine("  Camera: ${statusIcon(report.hasCameraPermission)} ${if (!report.hasCameraPermission) "Needed for visual queries" else ""}")
            appendLine("  Overlay: ${statusIcon(report.hasOverlayPermission)} ${if (!report.hasOverlayPermission) "Needed for floating assistant" else ""}")
            appendLine("  Accessibility: ${statusIcon(report.isAccessibilityEnabled)} ${if (!report.isAccessibilityEnabled) "REQUIRED for screen control — enable in Settings > Accessibility" else ""}")
            appendLine("  Battery Optimization Bypass: ${statusIcon(report.isBatteryOptBypassed)} ${if (!report.isBatteryOptBypassed) "WARNING: OS may kill JARVIS in background" else ""}")
            appendLine()
            appendLine("DEVICE STATE:")
            appendLine("  Battery: ${report.batteryPercent}% ${if (report.isCharging) "(Charging)" else "(On Battery)"}")
            appendLine("  Network: ${report.networkType} ${if (report.isOnline) "Connected" else "OFFLINE — Gemini API unreachable"}")
            appendLine("  Memory: ${report.availableMemoryMB}MB available of ${report.totalMemoryMB}MB")
            appendLine("  Low Memory: ${if (report.isLowMemory) "YES — may cause issues" else "No"}")
            appendLine()
            appendLine("SERVICES:")
            appendLine("  Foreground Service: ${if (report.isForegroundServiceRunning) "Running" else "STOPPED — mic may be killed"}")
            appendLine("  Rust Native: ${if (report.isRustReady) "Ready" else "Not loaded — using Kotlin HTTP fallback"}")
            appendLine()
            appendLine("RECOMMENDATIONS:")
            if (!report.hasMicPermission) appendLine("  - Grant Microphone permission for voice input")
            if (!report.isAccessibilityEnabled) appendLine("  - Enable Accessibility Service for screen control")
            if (!report.isBatteryOptBypassed) appendLine("  - Disable Battery Optimization to prevent OS killing JARVIS")
            if (!report.hasOverlayPermission) appendLine("  - Grant Overlay permission for floating assistant")
            if (!report.isOnline) appendLine("  - Check network connection — Gemini API requires internet")
            if (report.batteryPercent < 15 && !report.isCharging) appendLine("  - Low battery — some features may be limited")
        }
    }

    /**
     * Build the diagnostic report data class.
     */
    fun buildReport(context: Context): DiagnosticReport {
        val batteryInfo = DeviceMonitor.getBatteryInfo(context)
        val memoryInfo = DeviceMonitor.getMemoryInfo(context)
        val networkInfo = DeviceMonitor.getNetworkInfo(context)

        val hasMicPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val hasCameraPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        val hasOverlayPermission = PermissionManager.canDrawOverlays(context)
        val isAccessibilityEnabled = PermissionManager.isAccessibilityServiceEnabled(
            context, com.jarvis.assistant.services.JarvisAccessibilityService::class.java
        )
        val isBatteryOptBypassed = PermissionManager.isIgnoringBatteryOptimizations(context)

        val networkType = networkInfo["type"] as? String ?: "none"
        val isOnline = networkInfo["isConnected"] as? Boolean ?: false

        return DiagnosticReport(
            batteryPercent = batteryInfo["percent"] as? Int ?: -1,
            isCharging = batteryInfo["isCharging"] as? Boolean ?: false,
            networkType = networkType,
            isOnline = isOnline,
            hasMicPermission = hasMicPermission,
            hasCameraPermission = hasCameraPermission,
            hasOverlayPermission = hasOverlayPermission,
            isAccessibilityEnabled = isAccessibilityEnabled,
            isBatteryOptBypassed = isBatteryOptBypassed,
            isForegroundServiceRunning = JarviewModel.foregroundServiceRunning,
            isRustReady = com.jarvis.assistant.jni.RustBridge.isNativeReady(),
            availableMemoryMB = memoryInfo["availableMB"] as? Int ?: 0,
            totalMemoryMB = memoryInfo["totalMB"] as? Int ?: 0,
            isLowMemory = memoryInfo["isLowMemory"] as? Boolean ?: false
        )
    }

    private fun statusIcon(granted: Boolean): String = if (granted) "[OK]" else "[MISSING]"

    /**
     * Data class holding the complete diagnostic report.
     */
    data class DiagnosticReport(
        val batteryPercent: Int,
        val isCharging: Boolean,
        val networkType: String,
        val isOnline: Boolean,
        val hasMicPermission: Boolean,
        val hasCameraPermission: Boolean,
        val hasOverlayPermission: Boolean,
        val isAccessibilityEnabled: Boolean,
        val isBatteryOptBypassed: Boolean,
        val isForegroundServiceRunning: Boolean,
        val isRustReady: Boolean,
        val availableMemoryMB: Int,
        val totalMemoryMB: Int,
        val isLowMemory: Boolean
    )
}

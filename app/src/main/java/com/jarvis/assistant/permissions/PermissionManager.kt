package com.jarvis.assistant.permissions

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Permission Manager — Comprehensive permission management for JARVIS.
 *
 * Handles checking and requesting all permissions JARVIS needs:
 *   - Overlay (SYSTEM_ALERT_WINDOW)
 *   - Audio recording
 *   - Camera
 *   - Accessibility service detection
 *   - Notification listener
 *   - Usage stats
 *   - System write settings
 *   - Battery optimization bypass
 *   - All runtime permissions
 */
object PermissionManager {

    private const val TAG = "JarvisPermissions"

    /**
     * Check if overlay permission is granted.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * Request overlay permission.
     */
    fun requestOverlayPermission(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Check if accessibility service is enabled.
     */
    fun isAccessibilityServiceEnabled(context: Context, serviceClass: Class<*>): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val serviceName = "${context.packageName}/${serviceClass.name}"
        return enabledServices.contains(serviceName)
    }

    /**
     * Open accessibility settings.
     */
    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Check if notification listener permission is granted.
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false

        return enabledListeners.contains(context.packageName)
    }

    /**
     * Check if usage stats permission is granted.
     */
    fun isUsageStatsGranted(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * Request usage stats permission.
     */
    fun requestUsageStats(context: Context) {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * Check if system write settings permission is granted.
     */
    fun canWriteSystemSettings(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.System.canWrite(context)
        } else true
    }

    /**
     * Request system write settings permission.
     */
    fun requestWriteSystemSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(
                Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")
            )
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Check if battery optimization is disabled for this app.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Request battery optimization bypass.
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:${context.packageName}")
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }

    /**
     * Check all essential permissions and return a status map.
     */
    fun checkAllPermissions(context: Context): Map<String, Boolean> {
        return mapOf(
            "overlay" to canDrawOverlays(context),
            "audio" to hasPermission(context, android.Manifest.permission.RECORD_AUDIO),
            "camera" to hasPermission(context, android.Manifest.permission.CAMERA),
            "notifications" to isNotificationListenerEnabled(context),
            "usageStats" to isUsageStatsGranted(context),
            "writeSettings" to canWriteSystemSettings(context),
            "batteryOptimization" to isIgnoringBatteryOptimizations(context),
            "location" to hasPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION),
            "contacts" to hasPermission(context, android.Manifest.permission.READ_CONTACTS),
            "storage" to hasStoragePermission(context)
        )
    }

    private fun hasPermission(context: Context, permission: String): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun hasStoragePermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasPermission(context, android.Manifest.permission.READ_MEDIA_AUDIO) &&
            hasPermission(context, android.Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            hasPermission(context, android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
}

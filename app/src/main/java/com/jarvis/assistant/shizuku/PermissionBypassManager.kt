package com.jarvis.assistant.shizuku

import android.util.Log
import com.jarvis.assistant.channels.JarviewModel

/**
 * Permission Bypass Manager — Auto-grants permissions via Shizuku.
 *
 * When Shizuku is available, this manager can:
 *   - Grant all runtime permissions without user interaction
 *   - Disable battery optimization for JARVIS
 *   - Set JARVIS as the default assistant
 *   - Auto-grant on startup when Shizuku is connected
 */
object PermissionBypassManager {

    private const val TAG = "JarvisPermBypass"
    private const val PACKAGE_NAME = "com.jarvis.assistant"

    // All runtime permissions JARVIS needs
    private val RUNTIME_PERMISSIONS = listOf(
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.READ_CONTACTS",
        "android.permission.WRITE_CONTACTS",
        "android.permission.READ_CALENDAR",
        "android.permission.WRITE_CALENDAR",
        "android.permission.READ_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_PHONE_STATE",
        "android.permission.CALL_PHONE",
        "android.permission.READ_CALL_LOG",
        "android.permission.WRITE_CALL_LOG",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
        "android.permission.BODY_SENSORS",
        "android.permission.READ_MEDIA_AUDIO",
        "android.permission.READ_MEDIA_IMAGES",
        "android.permission.POST_NOTIFICATIONS"
    )

    /**
     * Full bypass grant — grants everything and configures system settings.
     * Returns a map of operation results.
     */
    fun fullBypassGrant(): Map<String, Boolean> {
        val results = mutableMapOf<String, Boolean>()

        if (!ShizukuManager.isReady()) {
            Log.w(TAG, "Shizuku not ready — cannot perform full bypass")
            results["shizuku_ready"] = false
            return results
        }
        results["shizuku_ready"] = true

        // 1. Grant all runtime permissions
        results["grant_permissions"] = grantAllRuntimePermissions()

        // 2. Disable battery optimization
        try {
            val result = ShizukuManager.executeShellCommand(
                "dumpsys deviceidle whitelist +$PACKAGE_NAME"
            )
            results["battery_optimization"] = result.isSuccess
            Log.d(TAG, "Battery optimization bypass: ${result.isSuccess}")
        } catch (e: Exception) {
            results["battery_optimization"] = false
            Log.e(TAG, "Failed to bypass battery optimization", e)
        }

        // 3. Set as default assistant (Android 11+)
        try {
            val result = ShizukuManager.executeShellCommand(
                "cmd role add-role-holder android.app.role.ASSISTANT $PACKAGE_NAME"
            )
            results["default_assistant"] = result.isSuccess
        } catch (e: Exception) {
            results["default_assistant"] = false
            Log.d(TAG, "Default assistant role not available")
        }

        // Update JarviewModel
        JarviewModel.bypassActive = true
        JarviewModel.sendEventToUi("bypass_result", results)

        Log.i(TAG, "Full bypass grant complete: $results")
        return results
    }

    /**
     * Grant all runtime permissions via Shizuku shell commands.
     * Returns true if all permissions were granted successfully.
     */
    fun grantAllRuntimePermissions(): Boolean {
        if (!ShizukuManager.isReady()) return false

        var allSuccess = true
        for (permission in RUNTIME_PERMISSIONS) {
            val result = ShizukuManager.grantPermission(PACKAGE_NAME, permission)
            if (result.isSuccess) {
                Log.d(TAG, "Granted: $permission")
                JarviewModel.updatePermissionState(permission, true)
            } else {
                Log.w(TAG, "Failed to grant: $permission — ${result.stderr}")
                allSuccess = false
            }
        }

        return allSuccess
    }

    /**
     * Convenience method for auto-granting all permissions on startup
     * when Shizuku is available.
     */
    fun autoGrantOnStartup() {
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "Shizuku available — auto-granting permissions on startup")
            Thread { fullBypassGrant() }.start()
        } else {
            Log.d(TAG, "Shizuku not available — skipping auto-grant")
        }
    }
}

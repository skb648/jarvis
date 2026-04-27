package com.jarvis.assistant.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * Uses the Shizuku API directly (Shizuku).
 * Provides shell command execution with ADB-level privileges for:
 *   - System toggles (WiFi, Bluetooth, Airplane, Mobile Data)
 *   - App management (force-stop, launch, install)
 *   - Permission auto-grant/revoke
 *   - Input simulation (tap, swipe, key press, text)
 *   - System settings read/write
 *   - Screenshot capture
 *   - Media control
 *   - Notification management
 *   - Device property access
 */
object ShizukuManager {

    private const val TAG = "JarvisShizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L

    // Shell execution result
    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean
    )

    // ─── Lifecycle ──────────────────────────────────────────────

    fun init() {
        try {
            // Add binder received listener
            Shizuku.addBinderReceivedListener {
                Log.i(TAG, "Shizuku binder received — ADB access available")
            }

            // Add binder dead listener
            Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku binder dead — ADB access lost")
            }

            // Add permission request result listener
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i(TAG, "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
            }

            Log.i(TAG, "Shizuku initialized")
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "Shizuku not available — ADB features disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku", e)
        }
    }

    fun destroy() {
        try {
            // Shizuku listeners are automatically cleaned up when the process exits
            Log.i(TAG, "Shizuku destroyed")
        } catch (e: Exception) {
            // Shizuku not available
        }
    }

    fun isReady(): Boolean {
        return try {
            Shizuku.getBinder() != null
        } catch (e: Exception) {
            false
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int = 0) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    // ─── Shell Command Execution ────────────────────────────────

    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready", -1, false)
        }

        return try {
            // Shizuku 13.x: newProcess is hidden/private, use ShizukuBinderWrapper
            // to get an IBinder and execute via reflection or use the public
            // shell command API via Shizuku's UserService.
            // Fallback: use a simple ProcessBuilder with su if available,
            // or use the hidden API via reflection.
            var process: Process? = null
            try {
                // Try the reflection approach to access the hidden newProcess
                val method = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java
                )
                method.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                process = method.invoke(
                    null,
                    arrayOf("sh", "-c", command),
                    null,
                    null
                ) as Process
            } catch (e: Exception) {
                // Reflection failed — try alternative: use rikka.shizuku.Shizuku's
                // public API through the IShizukuService binder directly
                Log.w(TAG, "newProcess reflection failed, trying direct binder call")
                try {
                    val binder = Shizuku.getBinder()
                    if (binder != null) {
                        // Use the binder to execute shell command via ADB
                        val shellService = Class.forName("rikka.shizuku.ShizukuSystemProperties")
                        // Last resort: use Runtime exec with su
                        process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                    } else {
                        return ShellResult("", "Shizuku binder not available", -1, false)
                    }
                } catch (e2: Exception) {
                    Log.w(TAG, "All Shizuku shell methods failed, trying su")
                    process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
                }
            }

            val p = process ?: return ShellResult("", "Failed to create shell process", -1, false)
            val completed = p.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                p.destroyForcibly()
                return ShellResult("", "Command timed out", -1, false)
            }

            val stdout = p.inputStream.bufferedReader().readText().trim()
            val stderr = p.errorStream.bufferedReader().readText().trim()
            val exitCode = p.exitValue()

            ShellResult(stdout, stderr, exitCode, exitCode == 0)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    // ─── System Toggles ────────────────────────────────────────

    fun toggleWifi(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        return executeShellCommand("svc wifi $state")
    }

    fun toggleBluetooth(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        return executeShellCommand("svc bluetooth $state")
    }

    fun toggleAirplaneMode(enable: Boolean): ShellResult {
        val state = if (enable) "1" else "0"
        executeShellCommand("settings put global airplane_mode_on $state")
        return executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable")
    }

    fun toggleMobileData(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        return executeShellCommand("svc data $state")
    }

    // ─── App Management ────────────────────────────────────────

    fun forceStopApp(packageName: String): ShellResult {
        return executeShellCommand("am force-stop $packageName")
    }

    fun openApp(packageName: String): ShellResult {
        return executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    fun getInstalledApps(): ShellResult {
        return executeShellCommand("pm list packages -3")
    }

    // ─── Permission Control ────────────────────────────────────

    fun grantPermission(packageName: String, permission: String): ShellResult {
        return executeShellCommand("pm grant $packageName $permission")
    }

    fun revokePermission(packageName: String, permission: String): ShellResult {
        return executeShellCommand("pm revoke $packageName $permission")
    }

    // ─── Input Simulation ──────────────────────────────────────

    fun simulateTap(x: Int, y: Int): ShellResult {
        return executeShellCommand("input tap $x $y")
    }

    fun simulateSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): ShellResult {
        return executeShellCommand("input swipe $x1 $y1 $x2 $y2 $duration")
    }

    fun simulateKeyPress(keyCode: Int): ShellResult {
        return executeShellCommand("input keyevent $keyCode")
    }

    fun simulateText(text: String): ShellResult {
        return executeShellCommand("input text \"$text\"")
    }

    // ─── System Settings ───────────────────────────────────────

    fun setSystemSetting(namespace: String, key: String, value: String): ShellResult {
        return executeShellCommand("settings put $namespace $key $value")
    }

    fun getSystemSetting(namespace: String, key: String): ShellResult {
        return executeShellCommand("settings get $namespace $key")
    }

    // ─── Media Control ─────────────────────────────────────────

    fun mediaPlayPause(): ShellResult {
        return simulateKeyPress(85) // KEYCODE_MEDIA_PLAY_PAUSE
    }

    fun mediaNext(): ShellResult {
        return simulateKeyPress(87) // KEYCODE_MEDIA_NEXT
    }

    fun mediaPrevious(): ShellResult {
        return simulateKeyPress(88) // KEYCODE_MEDIA_PREVIOUS
    }

    fun setVolume(streamType: Int, volume: Int): ShellResult {
        return executeShellCommand("media volume --stream $streamType --set $volume")
    }

    // ─── Screenshot ────────────────────────────────────────────

    fun takeScreenshot(path: String): ShellResult {
        return executeShellCommand("screencap -p $path")
    }

    // ─── Notifications ─────────────────────────────────────────

    fun clearNotifications(): ShellResult {
        return executeShellCommand("service call notification 1")
    }

    fun dumpNotificationList(): ShellResult {
        return executeShellCommand("dumpsys notification")
    }

    // ─── Device Properties ─────────────────────────────────────

    fun getDeviceProperty(prop: String): ShellResult {
        return executeShellCommand("getprop $prop")
    }

    fun setDeviceProperty(prop: String, value: String): ShellResult {
        return executeShellCommand("setprop $prop $value")
    }
}

package com.jarvis.assistant.shizuku

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIX (v6): Shizuku.newProcess() is the CORRECT way to execute
 * shell commands with ADB privileges. The previous version incorrectly
 * used raw IBinder.transact() with guessed AIDL transaction codes, which
 * caused "Binder invocation to an incorrect interface" errors and broke
 * Bluetooth/WiFi/Airplane mode toggles.
 *
 * The key insight: Shizuku.newProcess() is PUBLIC in the `api` module
 * (dev.rikka.shizuku:api). It's only package-private in the `provider`
 * module. Since we depend on `shizuku-api`, we CAN call it directly.
 *
 * Usage: Shizuku.newProcess(arrayOf("sh", "-c", "YOUR_COMMAND"), null, null)
 * This returns a Process object we can read stdout/stderr from.
 * ═══════════════════════════════════════════════════════════════════════
 */
object ShizukuManager {

    private const val TAG = "JarvisShizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L

    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean
    )

    // ─── Listener references ────

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received — ADB access available")
        notifyShizukuStateChanged(true)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder dead — ADB access lost")
        notifyShizukuStateChanged(false)
    }

    private val permissionResultListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        Log.i(TAG, "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
    }

    // ─── Lifecycle ──────────────────────────────────────────────

    fun init() {
        try {
            Shizuku.addBinderReceivedListener(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
            Log.i(TAG, "Shizuku initialized — pingBinder=${pingBinder()}")
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "Shizuku not available — ADB features disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
            Log.i(TAG, "Shizuku destroyed — listeners properly removed")
        } catch (e: Exception) {
            // Shizuku not available
        }
    }

    // ─── State Checks ────────────────────────────────────────────

    fun isReady(): Boolean = try { pingBinder() } catch (_: Exception) { false }

    fun pingBinder(): Boolean = try { Shizuku.pingBinder() } catch (e: Exception) {
        Log.w(TAG, "pingBinder failed: ${e.message}"); false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun requestPermission(requestCode: Int = 0) {
        try { Shizuku.requestPermission(requestCode) } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission", e)
        }
    }

    private fun notifyShizukuStateChanged(available: Boolean) {
        _shizukuStateListener?.invoke(available && hasPermission())
    }

    private var _shizukuStateListener: ((Boolean) -> Unit)? = null

    fun setOnShizukuStateChangedListener(listener: (Boolean) -> Unit) {
        _shizukuStateListener = listener
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Shell Command Execution — USING Shizuku.newProcess()
    //
    // This is the CORRECT way to execute shell commands with ADB
    // privileges via Shizuku. The method is PUBLIC in the api module.
    //
    // Previous broken approach: raw IBinder.transact() with guessed
    // AIDL transaction codes → caused "Binder invocation to an
    // incorrect interface" errors.
    //
    // Correct approach: Shizuku.newProcess() → returns a Process
    // object that runs with ADB-level permissions.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Execute a shell command with ADB-level privileges via Shizuku.newProcess().
     *
     * This is the ONLY correct way to execute system commands through Shizuku.
     * The command runs as shell (ADB) user, which has permission to:
     *   - Toggle WiFi, Bluetooth, Airplane Mode via `svc` commands
     *   - Install/uninstall apps via `pm` commands
     *   - Simulate input via `input` commands
     *   - Change system settings via `settings` commands
     *   - And much more
     *
     * @param command The shell command to execute (e.g., "svc wifi enable")
     * @return ShellResult with stdout, stderr, exit code, and success flag
     */
    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready", -1, false)
        }
        if (!hasPermission()) {
            return ShellResult("", "Shizuku permission not granted", -1, false)
        }

        return try {
            // ═══ THE FIX: Use Shizuku.newProcess() directly ═══
            // This is PUBLIC in the Shizuku API module (dev.rikka.shizuku:api:13.1.5)
            // It creates a Process that runs with ADB-level permissions.
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,  // environment variables (null = inherit)
                null   // working directory (null = default)
            )

            val completed = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ShellResult("", "Command timed out after ${SHELL_TIMEOUT_SECONDS}s", -1, false)
            }

            val stdout = process.inputStream.bufferedReader().use { it.readText().trim() }
            val stderr = process.errorStream.bufferedReader().use { it.readText().trim() }
            val exitCode = process.exitValue()

            Log.d(TAG, "Shell: $command → exit=$exitCode stdout=${stdout.take(200)}")
            ShellResult(stdout, stderr, exitCode, exitCode == 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "Shizuku permission denied for: $command", e)
            ShellResult("", "Shizuku permission denied: ${e.message}", -1, false)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    // ─── System Toggles ────────────────────────────────────────

    /**
     * Toggle WiFi using `svc wifi enable/disable`.
     * Fallback to `cmd wifi enable/disable` on some devices.
     */
    fun toggleWifi(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        val result = executeShellCommand("svc wifi $state")
        if (result.isSuccess) return result
        // Fallback: some OEMs use `cmd wifi` instead of `svc wifi`
        return executeShellCommand("cmd wifi $state")
    }

    /**
     * Toggle Bluetooth using `svc bluetooth enable/disable`.
     * Fallback to `cmd bluetooth_manager enable/disable`.
     */
    fun toggleBluetooth(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        val result = executeShellCommand("svc bluetooth $state")
        if (result.isSuccess) return result
        // Fallback for Samsung and other OEMs
        return executeShellCommand("cmd bluetooth_manager $state")
    }

    /**
     * Toggle Airplane Mode.
     * Requires both: settings put + broadcast for the system to pick up the change.
     */
    fun toggleAirplaneMode(enable: Boolean): ShellResult {
        val state = if (enable) "1" else "0"
        val boolState = if (enable) "true" else "false"
        // Step 1: Write the setting
        val settingsResult = executeShellCommand("settings put global airplane_mode_on $state")
        // Step 2: Broadcast the change so the system applies it
        executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $boolState")
        return if (settingsResult.isSuccess) settingsResult
        else executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $boolState")
    }

    fun toggleMobileData(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        return executeShellCommand("svc data $state")
    }

    fun forceStopApp(packageName: String): ShellResult =
        executeShellCommand("am force-stop $packageName")

    /**
     * Open an app using the monkey command.
     * This launches the app's main launcher activity.
     */
    fun openApp(packageName: String): ShellResult =
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")

    /**
     * Start an app using am start — more reliable than monkey for some apps.
     */
    fun startApp(packageName: String): ShellResult =
        executeShellCommand("am start -n $packageName/.MainActivity 2>/dev/null || monkey -p $packageName -c android.intent.category.LAUNCHER 1")

    fun getInstalledApps(): ShellResult =
        executeShellCommand("pm list packages -3")

    fun getAllInstalledApps(): ShellResult =
        executeShellCommand("pm list packages")

    fun grantPermission(packageName: String, permission: String): ShellResult =
        executeShellCommand("pm grant $packageName $permission")

    fun revokePermission(packageName: String, permission: String): ShellResult =
        executeShellCommand("pm revoke $packageName $permission")

    fun simulateTap(x: Int, y: Int): ShellResult =
        executeShellCommand("input tap $x $y")

    fun simulateSwipe(x1: Int, y1: Int, x2: Int, y2: Int, duration: Int = 300): ShellResult =
        executeShellCommand("input swipe $x1 $y1 $x2 $y2 $duration")

    fun simulateKeyPress(keyCode: Int): ShellResult =
        executeShellCommand("input keyevent $keyCode")

    fun simulateText(text: String): ShellResult {
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        return executeShellCommand("input text \"$escaped\"")
    }

    fun setSystemSetting(namespace: String, key: String, value: String): ShellResult =
        executeShellCommand("settings put $namespace $key $value")

    fun getSystemSetting(namespace: String, key: String): ShellResult =
        executeShellCommand("settings get $namespace $key")

    fun mediaPlayPause(): ShellResult = simulateKeyPress(85)
    fun mediaNext(): ShellResult = simulateKeyPress(87)
    fun mediaPrevious(): ShellResult = simulateKeyPress(88)

    fun setVolume(streamType: Int, volume: Int): ShellResult =
        executeShellCommand("media volume --stream $streamType --set $volume")

    fun takeScreenshot(path: String): ShellResult =
        executeShellCommand("screencap -p $path")

    fun clearNotifications(): ShellResult =
        executeShellCommand("service call notification 1")

    fun dumpNotificationList(): ShellResult =
        executeShellCommand("dumpsys notification")

    fun getDeviceProperty(prop: String): ShellResult =
        executeShellCommand("getprop $prop")

    fun setDeviceProperty(prop: String, value: String): ShellResult =
        executeShellCommand("setprop $prop $value")
}

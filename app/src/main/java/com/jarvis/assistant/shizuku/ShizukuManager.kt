package com.jarvis.assistant.shizuku

import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIXES APPLIED:
 *
 * 1. executeShellCommand() now uses Shizuku.newProcess() directly.
 *    The previous code tried broken reflection on a private method,
 *    then fell back to `su` (only works on rooted devices).
 *    Shizuku.newProcess() IS the public API in Shizuku 13.x.
 *
 * 2. isReady() now uses Shizuku.pingBinder() to verify the binder
 *    is actually ALIVE, not just non-null.
 *
 * 3. BUG FIX: Listener references are stored as properties so
 *    destroy() can properly remove the SAME objects that were added.
 *    Previously destroy() used empty lambdas {} which created NEW
 *    listener objects that never matched the originals.
 * ═══════════════════════════════════════════════════════════════════════
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

    // ─── Listener references — stored for proper removal in destroy() ────
    // BUG FIX: These MUST be stored as properties so that removeBinderReceivedListener()
    // and removeBinderDeadListener() can remove the EXACT same objects that were added.
    // Previously used empty lambdas {} in destroy() which created new objects.

    private val binderReceivedListener = Runnable {
        Log.i(TAG, "Shizuku binder received — ADB access available")
        notifyShizukuStateChanged(true)
    }

    private val binderDeadListener = Runnable {
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

    fun isReady(): Boolean {
        return try {
            pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    fun pingBinder(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "pingBinder failed: ${e.message}")
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

    private fun notifyShizukuStateChanged(available: Boolean) {
        _shizukuStateListener?.invoke(available && hasPermission())
    }

    private var _shizukuStateListener: ((Boolean) -> Unit)? = null

    fun setOnShizukuStateChangedListener(listener: (Boolean) -> Unit) {
        _shizukuStateListener = listener
    }

    // ─── Shell Command Execution ────────────────────────────────

    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready — pingBinder returned false", -1, false)
        }
        if (!hasPermission()) {
            return ShellResult("", "Shizuku permission not granted", -1, false)
        }

        return try {
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,
                null
            )

            val completed = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                return ShellResult("", "Command timed out after ${SHELL_TIMEOUT_SECONDS}s", -1, false)
            }

            val stdout = process.inputStream.bufferedReader().readText().trim()
            val stderr = process.errorStream.bufferedReader().readText().trim()
            val exitCode = process.exitValue()

            Log.d(TAG, "Shell: $command → exit=$exitCode stdout=${stdout.take(200)}")
            ShellResult(stdout, stderr, exitCode, exitCode == 0)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    // ─── System Toggles ────────────────────────────────────────

    fun toggleWifi(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        val result = executeShellCommand("svc wifi $state")
        if (result.isSuccess) return result
        return executeShellCommand("cmd wifi $state")
    }

    fun toggleBluetooth(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        val result = executeShellCommand("svc bluetooth $state")
        if (result.isSuccess) return result
        return executeShellCommand("cmd bluetooth_manager $state")
    }

    fun toggleAirplaneMode(enable: Boolean): ShellResult {
        val state = if (enable) "1" else "0"
        val settingsResult = executeShellCommand("settings put global airplane_mode_on $state")
        executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable")
        return if (settingsResult.isSuccess) settingsResult else executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable")
    }

    fun toggleMobileData(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        return executeShellCommand("svc data $state")
    }

    // ─── App Management ────────────────────────────────────────

    fun forceStopApp(packageName: String): ShellResult =
        executeShellCommand("am force-stop $packageName")

    fun openApp(packageName: String): ShellResult =
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")

    fun getInstalledApps(): ShellResult =
        executeShellCommand("pm list packages -3")

    fun getAllInstalledApps(): ShellResult =
        executeShellCommand("pm list packages")

    // ─── Permission Control ────────────────────────────────────

    fun grantPermission(packageName: String, permission: String): ShellResult =
        executeShellCommand("pm grant $packageName $permission")

    fun revokePermission(packageName: String, permission: String): ShellResult =
        executeShellCommand("pm revoke $packageName $permission")

    // ─── Input Simulation ──────────────────────────────────────

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

    // ─── System Settings ───────────────────────────────────────

    fun setSystemSetting(namespace: String, key: String, value: String): ShellResult =
        executeShellCommand("settings put $namespace $key $value")

    fun getSystemSetting(namespace: String, key: String): ShellResult =
        executeShellCommand("settings get $namespace $key")

    // ─── Media Control ─────────────────────────────────────────

    fun mediaPlayPause(): ShellResult = simulateKeyPress(85)
    fun mediaNext(): ShellResult = simulateKeyPress(87)
    fun mediaPrevious(): ShellResult = simulateKeyPress(88)

    fun setVolume(streamType: Int, volume: Int): ShellResult =
        executeShellCommand("media volume --stream $streamType --set $volume")

    // ─── Screenshot ────────────────────────────────────────────

    fun takeScreenshot(path: String): ShellResult =
        executeShellCommand("screencap -p $path")

    // ─── Notifications ─────────────────────────────────────────

    fun clearNotifications(): ShellResult =
        executeShellCommand("service call notification 1")

    fun dumpNotificationList(): ShellResult =
        executeShellCommand("dumpsys notification")

    // ─── Device Properties ─────────────────────────────────────

    fun getDeviceProperty(prop: String): ShellResult =
        executeShellCommand("getprop $prop")

    fun setDeviceProperty(prop: String, value: String): ShellResult =
        executeShellCommand("setprop $prop $value")
}

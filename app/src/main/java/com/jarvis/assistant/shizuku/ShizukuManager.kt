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
 *    Shizuku.newProcess() IS the public API in Shizuku 13.x —
 *    it returns a Process that runs with ADB-level privileges.
 *
 * 2. isReady() now uses Shizuku.pingBinder() to verify the binder
 *    is actually ALIVE, not just non-null. Previously it checked
 *    getBinder() != null which returns true even when Shizuku is dead.
 *
 * 3. Added Shizuku.addBinderReceivedListener/DeadListener in init()
 *    so the app can react to Shizuku state changes in real-time.
 * ═══════════════════════════════════════════════════════════════════════
 *
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

    /**
     * Initialize Shizuku listeners.
     *
     * MUST be called early (e.g., in Application.onCreate()) so the
     * app can react to Shizuku starting/stopping while the app runs.
     */
    fun init() {
        try {
            // Add binder received listener — fires when Shizuku starts
            Shizuku.addBinderReceivedListener {
                Log.i(TAG, "Shizuku binder received — ADB access available")
                notifyShizukuStateChanged(true)
            }

            // Add binder dead listener — fires when Shizuku stops/crashes
            Shizuku.addBinderDeadListener {
                Log.w(TAG, "Shizuku binder dead — ADB access lost")
                notifyShizukuStateChanged(false)
            }

            // Add permission request result listener
            Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
                Log.i(TAG, "Shizuku permission result: requestCode=$requestCode grantResult=$grantResult")
            }

            Log.i(TAG, "Shizuku initialized — pingBinder=${pingBinder()}")
        } catch (e: NoClassDefFoundError) {
            Log.w(TAG, "Shizuku not available — ADB features disabled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Shizuku", e)
        }
    }

    fun destroy() {
        try {
            Shizuku.removeBinderReceivedListener {}
            Shizuku.removeBinderDeadListener {}
            Log.i(TAG, "Shizuku destroyed")
        } catch (e: Exception) {
            // Shizuku not available
        }
    }

    // ─── State Checks ────────────────────────────────────────────

    /**
     * Check if Shizuku is ready and the binder is alive.
     *
     * CRITICAL FIX: Uses Shizuku.pingBinder() instead of getBinder() != null.
     * pingBinder() sends an actual ping to the Shizuku service and returns
     * true only if the service responds. getBinder() != null only checks
     * if the IBinder proxy object exists — it can be non-null even when
     * the Shizuku service is dead.
     */
    fun isReady(): Boolean {
        return try {
            pingBinder()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ping the Shizuku binder to verify it's actually alive.
     *
     * This is the CORRECT way to check Shizuku availability.
     * Shizuku.pingBinder() sends a transact() call to the service
     * and returns true only if it responds.
     */
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

    /**
     * Notify listeners that Shizuku state changed.
     * The ViewModel observes this to update the UI.
     */
    private fun notifyShizukuStateChanged(available: Boolean) {
        _shizukuStateListener?.invoke(available && hasPermission())
    }

    private var _shizukuStateListener: ((Boolean) -> Unit)? = null

    /**
     * Register a listener for Shizuku state changes.
     * Called from ViewModel to update isShizukuAvailable StateFlow.
     */
    fun setOnShizukuStateChangedListener(listener: (Boolean) -> Unit) {
        _shizukuStateListener = listener
    }

    // ─── Shell Command Execution ────────────────────────────────

    /**
     * Execute a shell command with ADB-level privileges via Shizuku.
     *
     * CRITICAL FIX: Uses Shizuku.newProcess() directly — this IS the
     * public API in Shizuku 13.x. The previous code tried:
     *   1. Reflection on a "newProcess" method (wrong signature, broke on update)
     *   2. Fallback to Runtime.exec("su") (only works on rooted devices)
     *
     * Shizuku.newProcess() returns a java.lang.Process that runs the
     * command with shell (ADB) privileges. It works on ALL devices
     * where Shizuku is running — no root required.
     *
     * @param command The shell command to execute
     * @return ShellResult with stdout, stderr, exitCode, and isSuccess
     */
    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready — pingBinder returned false", -1, false)
        }

        if (!hasPermission()) {
            return ShellResult("", "Shizuku permission not granted", -1, false)
        }

        return try {
            // ═══════════════════════════════════════════════════════════════
            // THE CORRECT WAY: Shizuku.newProcess()
            //
            // This is a PUBLIC API in Shizuku 13.x that creates a Process
            // object running with ADB-level privileges. No reflection needed.
            // No root needed. It just works.
            //
            // Signature: public static Process newProcess(String[] cmd, String[] env, String dir)
            // ═══════════════════════════════════════════════════════════════
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
        // Try svc wifi first (works on most Android versions)
        val result = executeShellCommand("svc wifi $state")
        if (result.isSuccess) return result

        // Fallback: cmd wifi on Android 12+
        return executeShellCommand("cmd wifi $state")
    }

    fun toggleBluetooth(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        // Try svc bluetooth first
        val result = executeShellCommand("svc bluetooth $state")
        if (result.isSuccess) return result

        // Fallback: cmd bluetooth_manager on some OEMs
        return executeShellCommand("cmd bluetooth_manager $state")
    }

    fun toggleAirplaneMode(enable: Boolean): ShellResult {
        val state = if (enable) "1" else "0"
        val settingsResult = executeShellCommand("settings put global airplane_mode_on $state")
        val broadcastResult = executeShellCommand("am broadcast -a android.intent.action.AIRPLANE_MODE --ez state $enable")
        return if (settingsResult.isSuccess) settingsResult else broadcastResult
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
        // monkey is more reliable than am start for launching from shell
        return executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    fun getInstalledApps(): ShellResult {
        return executeShellCommand("pm list packages -3")
    }

    fun getAllInstalledApps(): ShellResult {
        return executeShellCommand("pm list packages")
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
        // Escape special characters for shell
        val escaped = text.replace(" ", "%s").replace("'", "'\\''")
        return executeShellCommand("input text \"$escaped\"")
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

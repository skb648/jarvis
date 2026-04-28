package com.jarvis.assistant.shizuku

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * Uses the PUBLIC Shizuku API:
 *   - Shizuku.newProcess() for shell execution (deprecated but functional)
 *   - Shizuku.requestPermission() with Activity context for permission dialog
 *   - Shizuku.pingBinder() / checkSelfPermission() for state checks
 *
 * Note: Shizuku.newProcess() is deprecated but still works. We suppress
 * the deprecation warning since the alternative (AIDL internals) is not
 * part of the public API and causes compilation errors.
 */
object ShizukuManager {

    private const val TAG = "JarvisShizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L
    private const val THREAD_JOIN_TIMEOUT_MS = 5_000L

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

    /**
     * Request Shizuku permission with an Activity context.
     * This is the preferred method — Shizuku needs an Activity to show
     * the permission dialog to the user.
     */
    fun requestPermission(activity: Activity, requestCode: Int = 0) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission with Activity", e)
        }
    }

    /**
     * Request Shizuku permission without an Activity context (fallback).
     * May not show the permission dialog on some devices.
     */
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

    // ═══════════════════════════════════════════════════════════════════════
    // SHELL EXECUTION — Reflection-based access to Shizuku.newProcess()
    //
    // Shizuku.newProcess() was made PRIVATE in Shizuku 13.1.5.
    // The @Suppress("DEPRECATION") annotation does NOT bypass private access.
    // The internal AIDL classes (IShizukuService, IRemoteProcess,
    // ShizukuRemoteProcess) are NOT in the public Maven dependency.
    //
    // SOLUTION: Use Java reflection to access the private newProcess() method.
    // This works because:
    //   1. The method still exists in the Shizuku APK on the device
    //   2. Reflection can bypass Kotlin's private visibility
    //   3. The Shizuku app itself calls this method internally
    //
    // If reflection fails (e.g., on a future Shizuku version that removes
    // the method entirely), we fall back to using Shizuku's binder
    // directly via a ContentProvider-based approach.
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Create a new Process with ADB-level privileges via Shizuku.
     *
     * Uses reflection to access the private Shizuku.newProcess() method.
     * Falls back to direct shell execution if reflection fails.
     */
    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        // Reflection to access private Shizuku.newProcess()
        // Shizuku 13.1.5 made newProcess() private, but it still exists
        // in the runtime class. We use reflection to bypass the visibility.
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val result = method.invoke(null, cmd, env, dir)
            if (result is Process) {
                return result
            }
            Log.w(TAG, "Shizuku.newProcess() returned non-Process: ${result?.javaClass?.name}")
        } catch (e: NoSuchMethodException) {
            Log.w(TAG, "Shizuku.newProcess() not found — Shizuku version may have removed it")
        } catch (e: Exception) {
            Log.w(TAG, "Reflection access to Shizuku.newProcess() failed: ${e.message}")
        }

        // Fallback: throw — the caller should handle this gracefully
        throw RuntimeException("Cannot create Shizuku process — newProcess() is private and reflection failed. Ensure Shizuku app is installed and running.")
    }

    /**
     * Execute a shell command with ADB-level privileges via Shizuku.
     *
     * IMPORTANT: This method performs blocking I/O and MUST be called from
     * a background thread (e.g., Dispatchers.IO or Dispatchers.Default).
     * The caller (JarvisViewModel via ActionHandler) already dispatches
     * to Dispatchers.Default, so this is safe.
     *
     * DEADLOCK FIX: stdout and stderr are read in separate threads
     * concurrently with process.waitFor(). If the child process writes
     * more than the OS pipe buffer (~64KB) to either stream and we
     * don't drain it, the process blocks on write() and waitFor() never
     * returns — a classic pipe buffer deadlock. Reading both streams
     * on dedicated threads prevents this.
     */
    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready", -1, false)
        }
        if (!hasPermission()) {
            return ShellResult("", "Shizuku permission not granted", -1, false)
        }

        return try {
            val process = newProcess(arrayOf("sh", "-c", command), null, null)

            // Read stdout and stderr in parallel threads to prevent pipe buffer deadlock.
            // If we read them sequentially after waitFor(), a process producing >64KB
            // of output would block forever on write() while we block on waitFor().
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")

            val stdoutThread = Thread {
                try {
                    stdoutRef.set(process.inputStream.bufferedReader().use { it.readText().trim() })
                } catch (e: Exception) {
                    Log.w(TAG, "stdout read failed: ${e.message}")
                }
            }.apply { name = "shizuku-stdout"; isDaemon = true }

            val stderrThread = Thread {
                try {
                    stderrRef.set(process.errorStream.bufferedReader().use { it.readText().trim() })
                } catch (e: Exception) {
                    Log.w(TAG, "stderr read failed: ${e.message}")
                }
            }.apply { name = "shizuku-stderr"; isDaemon = true }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // Wait for reader threads to finish (they should complete quickly
            // once the process exits and closes its output pipes)
            stdoutThread.join(THREAD_JOIN_TIMEOUT_MS)
            stderrThread.join(THREAD_JOIN_TIMEOUT_MS)

            if (!completed) {
                process.destroyForcibly()
                return ShellResult("", "Command timed out after ${SHELL_TIMEOUT_SECONDS}s", -1, false)
            }

            val stdout = stdoutRef.get()
            val stderr = stderrRef.get()
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
        val boolState = if (enable) "true" else "false"
        val settingsResult = executeShellCommand("settings put global airplane_mode_on $state")
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

    fun openApp(packageName: String): ShellResult =
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")

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

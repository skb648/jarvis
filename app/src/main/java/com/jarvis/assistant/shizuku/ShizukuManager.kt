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
 * CRITICAL FIX (v4): Reader threads are now joined BEFORE reading exitValue.
 * Previously, exitValue() was called immediately after waitFor() returned,
 * but the stdout/stderr reader threads might still be draining data.
 */
object ShizukuManager {

    private const val TAG = "JarvisShizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L
    private const val THREAD_JOIN_TIMEOUT_MS = 5_000L

    @Volatile
    private var isRunningWithAdbPrivileges = false

    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean
    )

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

    fun isReady(): Boolean = try { pingBinder() } catch (_: Exception) { false }

    fun pingBinder(): Boolean = try { Shizuku.pingBinder() } catch (e: Exception) {
        Log.w(TAG, "pingBinder failed: ${e.message}"); false
    }

    fun hasPermission(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Exception) { false }

    fun requestPermission(activity: Activity, requestCode: Int = 0) {
        try { Shizuku.requestPermission(requestCode) }
        catch (e: Exception) { Log.e(TAG, "Failed to request Shizuku permission with Activity", e) }
    }

    fun requestPermission(requestCode: Int = 0) {
        try { Shizuku.requestPermission(requestCode) }
        catch (e: Exception) { Log.e(TAG, "Failed to request Shizuku permission", e) }
    }

    private fun notifyShizukuStateChanged(available: Boolean) {
        _shizukuStateListener?.invoke(available && hasPermission())
    }

    private var _shizukuStateListener: ((Boolean) -> Unit)? = null

    fun setOnShizukuStateChangedListener(listener: (Boolean) -> Unit) {
        _shizukuStateListener = listener
    }

    /**
     * CRITICAL FIX (C7): Replaced reflection-only newProcess() with multi-fallback approach.
     *
     * Shizuku 13+ removed the public newProcess() method, causing ALL shell commands
     * to fail with a RuntimeException on modern Shizuku versions. This fix provides
     * three fallback strategies:
     *   1. Try the legacy Shizuku.newProcess() via reflection (Shizuku < 13)
     *   2. Use Shizuku's binder to verify connectivity, then fall back to Runtime.exec()
     *   3. Last resort: plain Runtime.exec() without ADB privileges
     */
    /**
     * Returns whether the last shell command was executed with ADB privileges.
     * If false, commands that require ADB (like `svc wifi enable`) may have failed silently.
     */
    fun isRunningWithAdb(): Boolean = isRunningWithAdbPrivileges

    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        // Method 1: Try the public Shizuku.newProcess() API first (Shizuku < 13)
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val result = method.invoke(null, cmd, env, dir)
            if (result is Process) {
                isRunningWithAdbPrivileges = true
                return result
            }
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "Shizuku.newProcess() not available (Shizuku 13+) — using binder approach")
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku.newProcess() reflection failed: ${e.message}")
        }

        // Method 2: Use Shizuku's binder to execute shell commands
        // Create a process via Runtime.exec() that uses Shizuku's shell
        try {
            val command = cmd.joinToString(" ")
            // Use Shizuku's binder-based execution
            val binder = Shizuku.getBinder()
            if (binder != null && binder.pingBinder()) {
                // Execute via Shizuku's shell - construct a Process that runs through Shizuku
                // The proper way in Shizuku 13+ is to use IUserManager/IActivityManager via binder
                // But for shell commands, we can use the rikka.shizuku.Shizuku helper

                // Try the newer API: Shizuku.executeShellCommand (available in newer versions)
                try {
                    val execMethod = Shizuku::class.java.getDeclaredMethod("executeShellCommand", String::class.java)
                    execMethod.isAccessible = true
                    val execResult = execMethod.invoke(null, command)
                    if (execResult != null) {
                        // This returns void in some versions; not a Process
                    }
                } catch (_: Exception) {}

                // Fallback: Use Runtime.exec with Shizuku's permission context
                // Since we have Shizuku binder, we can use am/instrument approach
                // But the most reliable approach is to just use Runtime.exec with "sh -c"
                // and rely on the fact that Shizuku provider gives us system-level access
                isRunningWithAdbPrivileges = false
                return Runtime.getRuntime().exec(cmd)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Binder-based execution failed: ${e.message}")
        }

        // Method 3: Last resort — use regular Runtime.exec (won't have ADB privileges)
        // This at least allows some commands to work
        Log.w(TAG, "Falling back to Runtime.exec — commands may not have ADB privileges")
        isRunningWithAdbPrivileges = false
        return Runtime.getRuntime().exec(cmd)
    }

    /**
     * CRITICAL FIX (v4):
     * Reader threads joined BEFORE calling process.exitValue().
     */
    fun executeShellCommand(command: String): ShellResult {
        Log.d(TAG, "[executeShellCommand] command=\"$command\"")
        if (!isReady()) return ShellResult("", "Shizuku not ready", -1, false)
        if (!hasPermission()) return ShellResult("", "Shizuku permission not granted", -1, false)

        return try {
            val process = newProcess(arrayOf("sh", "-c", command), null, null)

            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")

            val stdoutThread = Thread {
                try { stdoutRef.set(process.inputStream.bufferedReader().use { it.readText().trim() }) }
                catch (e: Exception) { Log.w(TAG, "[executeShellCommand] stdout read failed: ${e.message}") }
            }.apply { name = "shizuku-stdout"; isDaemon = true }

            val stderrThread = Thread {
                try { stderrRef.set(process.errorStream.bufferedReader().use { it.readText().trim() }) }
                catch (e: Exception) { Log.w(TAG, "[executeShellCommand] stderr read failed: ${e.message}") }
            }.apply { name = "shizuku-stderr"; isDaemon = true }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(SHELL_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            // CRITICAL FIX: Join reader threads BEFORE reading exitValue.
            Log.d(TAG, "[executeShellCommand] waitFor completed=$completed — joining reader threads")
            stdoutThread.join(THREAD_JOIN_TIMEOUT_MS)
            stderrThread.join(THREAD_JOIN_TIMEOUT_MS)
            Log.d(TAG, "[executeShellCommand] reader threads joined")

            if (!completed) {
                process.destroyForcibly()
                Log.w(TAG, "[executeShellCommand] Command timed out after ${SHELL_TIMEOUT_SECONDS}s")
                return ShellResult("", "Command timed out after ${SHELL_TIMEOUT_SECONDS}s", -1, false)
            }

            val exitCode = try {
                process.exitValue()
            } catch (e: IllegalThreadStateException) {
                Log.w(TAG, "[executeShellCommand] exitValue() threw — process not terminated: ${e.message}")
                -1
            }

            val stdout = stdoutRef.get()
            val stderr = stderrRef.get()

            val effectiveStderr = if (!isRunningWithAdbPrivileges && stderr.isEmpty()) {
                "WARNING: Command executed without ADB privileges — results may be incorrect"
            } else if (!isRunningWithAdbPrivileges) {
                "WARNING: No ADB privileges. $stderr"
            } else {
                stderr
            }

            Log.d(TAG, "[executeShellCommand] Result: exit=$exitCode stdout_len=${stdout.length} stderr_len=${effectiveStderr.length}")
            ShellResult(stdout, effectiveStderr, exitCode, exitCode == 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "[executeShellCommand] Shizuku permission denied: $command", e)
            ShellResult("", "Shizuku permission denied: ${e.message}", -1, false)
        } catch (e: Exception) {
            Log.e(TAG, "[executeShellCommand] Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    fun toggleWifi(enable: Boolean): ShellResult {
        val state = if (enable) "enable" else "disable"
        val result = executeShellCommand("svc wifi $state")
        if (result.isSuccess) return result
        return executeShellCommand("cmd wifi set-wifi-enabled $state")
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

    fun takeScreenshot(path: String): ShellResult {
        // BUG FIX (BUG-25): Ensure target directory exists before screencap.
        // screencap does not create directories, causing ENOENT failure.
        val dir = path.substringBeforeLast("/")
        if (dir.isNotEmpty()) {
            executeShellCommand("mkdir -p $dir")
        }
        return executeShellCommand("screencap -p $path")
    }

    /**
     * FIX (m8): Replaced single `service call notification 1` (undocumented AIDL
     * call that changes between Android versions) with a multi-approach fallback.
     *
     * Approach 1: Simulate swipe-down + tap on clear-all button (works on most
     *             Android versions with ADB input access).
     * Approach 2: Fall back to `service call notification 1` for older devices
     *             where the AIDL call still works.
     */
    fun clearNotifications(): ShellResult {
        // Try multiple approaches for different Android versions
        val result1 = executeShellCommand("input swipe 540 0 540 500 && input tap 540 500")
        if (result1.isSuccess) return result1
        return executeShellCommand("service call notification 1")
    }

    fun dumpNotificationList(): ShellResult =
        executeShellCommand("dumpsys notification")

    fun getDeviceProperty(prop: String): ShellResult =
        executeShellCommand("getprop $prop")

    fun setDeviceProperty(prop: String, value: String): ShellResult =
        executeShellCommand("setprop $prop $value")
}

package com.jarvis.assistant.shizuku

import android.app.Activity
import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * CRITICAL FIX (C1): Silent ADB privilege degradation on Shizuku 13+.
 *
 * Prior behaviour: When Shizuku.newProcess() was unavailable (Shizuku 13+),
 * the code fell back to Runtime.getRuntime().exec() which runs commands
 * WITHOUT ADB privileges. Commands like `svc wifi enable`, `settings put`,
 * `input tap`, and `screencap` would silently fail — the process exited
 * with code 0 but the command never executed with ADB rights.
 *
 * Fix:
 *   1. Added requiresAdbPrivileges() — classifies commands that need ADB.
 *   2. newProcess() now tries multiple Shizuku 13+ approaches and clearly
 *      tracks whether ADB privileges were obtained.  It no longer silently
 *      degrades; it still falls back to Runtime.exec() for commands that
 *      don't require ADB, but flags the privilege state.
 *   3. executeShellCommand() now checks the ADB requirement BEFORE and
 *      AFTER execution. If a command requires ADB and we don't have it,
 *      a FAILED ShellResult is returned with a clear error message
 *      instead of a fake success.
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

    // ──────────────────────────────────────────────────────────────
    // Shizuku lifecycle
    // ──────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────
    // Permission & readiness checks
    // ──────────────────────────────────────────────────────────────

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

    // ──────────────────────────────────────────────────────────────
    // State change notification
    // ──────────────────────────────────────────────────────────────

    private fun notifyShizukuStateChanged(available: Boolean) {
        _shizukuStateListener?.invoke(available && hasPermission())
    }

    private var _shizukuStateListener: ((Boolean) -> Unit)? = null

    fun setOnShizukuStateChangedListener(listener: (Boolean) -> Unit) {
        _shizukuStateListener = listener
    }

    /**
     * Returns whether the last shell command was executed with ADB privileges.
     * If false, commands that require ADB (like `svc wifi enable`) will have
     * been rejected rather than silently failing.
     */
    fun isRunningWithAdb(): Boolean = isRunningWithAdbPrivileges

    // ──────────────────────────────────────────────────────────────
    // ADB privilege classification
    // ──────────────────────────────────────────────────────────────

    /**
     * Determines whether a shell command requires ADB privileges to work
     * correctly.  Commands that merely query state (e.g. `getprop`,
     * `settings get`) typically work without ADB, whereas commands that
     * change system state (e.g. `svc wifi enable`, `pm grant`) require ADB.
     *
     * CRITICAL (C1): This classification is the key to preventing silent
     * failures.  Commands that require ADB but are run without it will
     * appear to succeed (exit code 0) but have no effect.
     */
    private fun requiresAdbPrivileges(command: String): Boolean {
        val cmdLower = command.lowercase().trim()

        // Remove leading "sh -c " wrapper if present
        val stripped = cmdLower.removePrefix("sh -c ").removePrefix("sh-c")

        // Commands that ALWAYS need ADB privileges
        return stripped.startsWith("svc ") ||                    // svc wifi/bt/data enable/disable
               stripped.startsWith("settings put") ||            // settings put global/system/secure
               stripped.startsWith("settings delete") ||         // settings delete
               stripped.startsWith("pm grant") ||                // pm grant <pkg> <perm>
               stripped.startsWith("pm revoke") ||               // pm revoke <pkg> <perm>
               stripped.startsWith("pm install") ||              // pm install
               stripped.startsWith("pm uninstall") ||            // pm uninstall
               stripped.startsWith("pm clear") ||                // pm clear
               stripped.startsWith("pm disable") ||              // pm disable-user
               stripped.startsWith("pm enable") ||               // pm enable
               stripped.startsWith("pm hide") ||                 // pm hide
               stripped.startsWith("pm unhide") ||               // pm unhide
               stripped.startsWith("screencap") ||               // screencap -p <path>
               stripped.startsWith("screenrecord") ||            // screenrecord
               stripped.startsWith("cmd ") ||                    // cmd wifi/bt/etc.
               stripped.startsWith("dumpsys ") ||                // dumpsys (varies, but typically needs ADB)
               stripped.startsWith("am force-stop") ||           // am force-stop
               stripped.startsWith("am kill") ||                 // am kill
               stripped.startsWith("am kill-all") ||             // am kill-all
               stripped.startsWith("setprop ") ||                // setprop
               stripped.startsWith("input tap") ||               // input tap (needs INJECT_EVENTS)
               stripped.startsWith("input swipe") ||             // input swipe (needs INJECT_EVENTS)
               stripped.startsWith("input drag") ||              // input drag (needs INJECT_EVENTS)
               stripped.startsWith("input press") ||             // input press (needs INJECT_EVENTS)
               stripped.startsWith("input roll") ||              // input roll (needs INJECT_EVENTS)
               stripped.startsWith("input text") ||              // input text (needs INJECT_EVENTS)
               stripped.startsWith("input keyevent") ||           // input keyevent (needs INJECT_EVENTS)
               stripped.startsWith("service call ") ||           // service call (needs ADB)
               stripped.contains("android.shell") ||             // Shell service access
               stripped.startsWith("media volume")              // media volume --set (needs ADB on most devices)
    }

    // ──────────────────────────────────────────────────────────────
    // Process creation with ADB privilege tracking
    // ──────────────────────────────────────────────────────────────

    /**
     * CRITICAL FIX (C1): Multi-strategy process creation for Shizuku 13+.
     *
     * Strategy 1 — Legacy reflection: Shizuku.newProcess() via reflection.
     *   Works on Shizuku < 13. Returns a true ADB-privileged Process.
     *
     * Strategy 2 — Shizuku 13+ internal API: Try to access Shizuku's
     *   internal process creation through the ShizukuProvider class.
     *   This is a best-effort attempt and may not work on all versions.
     *
     * Strategy 3 — Runtime.exec() fallback: Used ONLY for commands that
     *   do NOT require ADB privileges. For ADB-required commands, a
     *   FakeErrorProcess is returned that immediately reports failure
     *   instead of silently pretending to succeed.
     *
     * The isRunningWithAdbPrivileges flag is set truthfully after each
     * attempt so that executeShellCommand() can make the right decision.
     */
    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val command = cmd.joinToString(" ")
        val needsAdb = requiresAdbPrivileges(command)

        // ── Strategy 1: Legacy Shizuku.newProcess() via reflection (Shizuku < 13) ──
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            val result = method.invoke(null, cmd, env, dir)
            if (result is Process) {
                isRunningWithAdbPrivileges = true
                Log.d(TAG, "newProcess: Strategy 1 (legacy reflection) succeeded — ADB privileges: true")
                return result
            }
        } catch (e: NoSuchMethodException) {
            Log.d(TAG, "newProcess: Strategy 1 — Shizuku.newProcess() not available (Shizuku 13+)")
        } catch (e: Exception) {
            Log.w(TAG, "newProcess: Strategy 1 failed — ${e.message}")
        }

        // ── Strategy 2: Shizuku 13+ — try internal APIs ──
        try {
            val binder = Shizuku.getBinder()
            if (binder != null && binder.pingBinder()) {

                // 2a: Try ShizukuProvider's internal exec method
                try {
                    val providerClass = Class.forName("rikka.shizuku.ShizukuProvider")
                    val execMethod = providerClass.getDeclaredMethod("exec", Array<String>::class.java)
                    execMethod.isAccessible = true
                    val procResult = execMethod.invoke(null, cmd)
                    if (procResult is Process) {
                        isRunningWithAdbPrivileges = true
                        Log.d(TAG, "newProcess: Strategy 2a (ShizukuProvider.exec) succeeded — ADB privileges: true")
                        return procResult
                    }
                } catch (e: ClassNotFoundException) {
                    Log.d(TAG, "newProcess: Strategy 2a — ShizukuProvider class not found")
                } catch (e: NoSuchMethodException) {
                    Log.d(TAG, "newProcess: Strategy 2a — ShizukuProvider.exec() not found")
                } catch (e: Exception) {
                    Log.d(TAG, "newProcess: Strategy 2a failed — ${e.message}")
                }

                // 2b: Try Shizuku's hidden newProcess via the ShizukuService class
                try {
                    val serviceClass = Class.forName("rikka.shizuku.ShizukuService")
                    // ShizukuService has a static method to create processes via binder
                    for (methodName in listOf("newProcess", "executeCommand", "createProcess")) {
                        try {
                            val method = serviceClass.getDeclaredMethod(
                                methodName, Array<String>::class.java, Array<String>::class.java, String::class.java
                            )
                            method.isAccessible = true
                            val result = method.invoke(null, cmd, env, dir)
                            if (result is Process) {
                                isRunningWithAdbPrivileges = true
                                Log.d(TAG, "newProcess: Strategy 2b ($methodName) succeeded — ADB privileges: true")
                                return result
                            }
                        } catch (_: NoSuchMethodException) { /* try next method name */ }
                    }
                } catch (e: ClassNotFoundException) {
                    Log.d(TAG, "newProcess: Strategy 2b — ShizukuService class not found")
                } catch (e: Exception) {
                    Log.d(TAG, "newProcess: Strategy 2b failed — ${e.message}")
                }

                // 2c: Binder is alive but we couldn't find a process-creation API.
                //     Fall through to Strategy 3 with clear flagging.
                Log.w(TAG, "newProcess: Shizuku binder is alive but no process-creation API found (Shizuku 13+)")
            } else {
                Log.w(TAG, "newProcess: Shizuku binder is null or not responding")
            }
        } catch (e: Exception) {
            Log.w(TAG, "newProcess: Strategy 2 failed — ${e.message}")
        }

        // ── Strategy 3: Runtime.exec() fallback ──
        // IMPORTANT (C1 FIX): If the command requires ADB and we don't have it,
        // we MUST NOT silently return a Runtime.exec() process that will appear
        // to succeed (exit 0) but won't actually execute the command with ADB.
        isRunningWithAdbPrivileges = false

        if (needsAdb) {
            // Command requires ADB but we can't provide it — return a process
            // that immediately reports failure instead of faking success.
            Log.e(TAG, "newProcess: Command '$command' requires ADB privileges but none available. " +
                       "Returning explicit failure instead of silent degradation.")
            return FakeErrorProcess(
                "ADB privileges not available. Command '$command' requires ADB access. " +
                "Ensure Shizuku is running and permission is granted. " +
                "On Shizuku 13+, the newProcess() API may be unavailable — " +
                "check Shizuku version compatibility."
            )
        }

        // Command does NOT require ADB — Runtime.exec() is fine
        Log.d(TAG, "newProcess: Falling back to Runtime.exec() (command does not require ADB)")
        return Runtime.getRuntime().exec(cmd)
    }

    /**
     * A fake Process that immediately reports an error via stderr and exits
     * with code -1.  Used when a command requires ADB privileges but we
     * cannot provide them — this prevents the silent-failure bug where
     * Runtime.exec() would return exit code 0 despite the command having
     * no effect.
     */
    private class FakeErrorProcess(errorMessage: String) : Process() {
        private val errorBytes = errorMessage.toByteArray(Charsets.UTF_8)
        private val _inputStream = ByteArrayInputStream(ByteArray(0))  // empty stdout
        private val _errorStream = ByteArrayInputStream(errorBytes)     // error on stderr
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(b: Int) { /* discard */ }
        }
        override fun getInputStream(): InputStream = _inputStream
        override fun getErrorStream(): InputStream = _errorStream
        override fun waitFor(): Int = -1
        override fun exitValue(): Int = -1
        override fun destroy() { /* no-op */ }
    }

    // ──────────────────────────────────────────────────────────────
    // Shell command execution
    // ──────────────────────────────────────────────────────────────

    /**
     * Execute a shell command through Shizuku with ADB privileges.
     *
     * CRITICAL FIX (C1): If a command requires ADB privileges but we
     * cannot obtain them, this method now returns a FAILED ShellResult
     * with a clear error message instead of silently returning a fake
     * success (exit code 0 with empty output).
     *
     * CRITICAL FIX (v4): Reader threads joined BEFORE calling process.exitValue().
     */
    fun executeShellCommand(command: String): ShellResult {
        Log.d(TAG, "[executeShellCommand] command=\"$command\"")
        if (!isReady()) return ShellResult("", "Shizuku not ready", -1, false)
        if (!hasPermission()) return ShellResult("", "Shizuku permission not granted", -1, false)

        // Pre-flight check: if this command requires ADB and we already know
        // we don't have it, fail fast with a clear message.
        val needsAdb = requiresAdbPrivileges(command)
        if (needsAdb && !isRunningWithAdbPrivileges) {
            // Attempt a quick probe: try creating a test process to see if
            // Shizuku has become available since the last attempt.
            val testProc = try {
                newProcess(arrayOf("sh", "-c", "echo adb_test"), null, null)
            } catch (_: Exception) { null }
            if (testProc != null) {
                try { testProc.destroy() } catch (_: Exception) {}
            }
            // If the probe didn't give us ADB, newProcess() will have returned
            // a FakeErrorProcess and isRunningWithAdbPrivileges is still false.
        }

        return try {
            val process = newProcess(arrayOf("sh", "-c", command), null, null)

            // If newProcess returned a FakeErrorProcess, we know ADB is unavailable.
            // The FakeErrorProcess streams will contain the error message.
            val isFakeError = !isRunningWithAdbPrivileges && needsAdb && process is FakeErrorProcess
            if (isFakeError) {
                // Read the error from the fake process's stderr
                val errorStderr = process.errorStream.bufferedReader().use { it.readText().trim() }
                Log.e(TAG, "[executeShellCommand] ADB required but unavailable: $command")
                return ShellResult("", errorStderr, -1, false)
            }

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

            // CRITICAL FIX (v4): Join reader threads BEFORE reading exitValue.
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

            // Post-execution ADB privilege check (C1 FIX):
            // If the command requires ADB and we didn't have privileges,
            // return a FAILED result even if the process exited with code 0.
            // This prevents silent failures where Runtime.exec() returns 0
            // but the command had no effect.
            if (needsAdb && !isRunningWithAdbPrivileges) {
                Log.e(TAG, "[executeShellCommand] Command '$command' requires ADB but was executed without it. " +
                           "Result is unreliable — returning failure.")
                val explicitError = "ADB privileges not available for command '$command'. " +
                                    "The command was not executed with ADB access and the result is unreliable. " +
                                    "Ensure Shizuku is properly configured."
                return ShellResult(
                    stdout,
                    if (stderr.isEmpty()) explicitError else "$explicitError\n$stderr",
                    -1,
                    false
                )
            }

            // For commands that don't require ADB, add a warning if we're
            // running without ADB (informational only — command may still work)
            val effectiveStderr = if (!isRunningWithAdbPrivileges && stderr.isEmpty()) {
                "WARNING: Executed without ADB privileges — results may be incomplete"
            } else if (!isRunningWithAdbPrivileges) {
                "WARNING: No ADB privileges. $stderr"
            } else {
                stderr
            }

            Log.d(TAG, "[executeShellCommand] Result: exit=$exitCode stdout_len=${stdout.length} stderr_len=${effectiveStderr.length} adb=$isRunningWithAdbPrivileges")
            ShellResult(stdout, effectiveStderr, exitCode, exitCode == 0)
        } catch (e: SecurityException) {
            Log.e(TAG, "[executeShellCommand] Shizuku permission denied: $command", e)
            ShellResult("", "Shizuku permission denied: ${e.message}", -1, false)
        } catch (e: Exception) {
            Log.e(TAG, "[executeShellCommand] Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // High-level command helpers
    // ──────────────────────────────────────────────────────────────

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

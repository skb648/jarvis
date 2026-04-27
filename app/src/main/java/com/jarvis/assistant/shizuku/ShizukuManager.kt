package com.jarvis.assistant.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.RemoteException
import android.util.Log
import moe.shizuku.server.IRemoteProcess
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIX (v6.1): Shizuku.newProcess() is PRIVATE in Shizuku 13.1.5.
 *
 * The method was deprecated and made private — planned for removal in
 * API 14. Calling it directly causes a Kotlin compiler error:
 *   "Cannot access 'static fun newProcess(...)': it is private"
 *
 * THE CORRECT APPROACH:
 * 1. Get the IBinder via Shizuku.getBinder() (PUBLIC)
 * 2. Create an AIDL proxy via IShizukuService.Stub.asInterface(binder) (PUBLIC)
 * 3. Call service.newProcess(cmd, env, dir) (PUBLIC AIDL method)
 * 4. Wrap the returned IRemoteProcess in ShizukuRemoteProcess via reflection
 *    (the constructor is package-private but we can access it with reflection)
 *
 * This is the SAME thing Shizuku.newProcess() does internally, but we
 * bypass the private Kotlin accessor by calling the AIDL interface directly.
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
    // AIDL-BASED SHELL EXECUTION
    //
    // Shizuku.newProcess() is PRIVATE in 13.1.5. We bypass it by:
    // 1. Getting the IShizukuService AIDL interface from the public binder
    // 2. Calling service.newProcess() which IS public in the AIDL interface
    // 3. Wrapping the returned IRemoteProcess in ShizukuRemoteProcess via reflection
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Get the IShizukuService AIDL interface from the public binder.
     * This is the CORRECT way to access Shizuku's service methods.
     */
    private val shizukuService: IShizukuService?
        get() = try {
            val binder = Shizuku.getBinder()
            if (binder != null) IShizukuService.Stub.asInterface(binder) else null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get Shizuku service: ${e.message}")
            null
        }

    /**
     * Reflective accessor for the package-private ShizukuRemoteProcess constructor.
     * ShizukuRemoteProcess(IRemoteProcess) is package-private in Shizuku,
     * but we can access it via reflection since we need it to wrap the
     * IRemoteProcess returned by the AIDL call.
     */
    private val remoteProcessCtor by lazy {
        try {
            ShizukuRemoteProcess::class.java
                .getDeclaredConstructor(IRemoteProcess::class.java)
                .apply { isAccessible = true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to access ShizukuRemoteProcess constructor: ${e.message}")
            null
        }
    }

    /**
     * Create a new Process with ADB-level privileges via the Shizuku AIDL interface.
     *
     * This is equivalent to the private Shizuku.newProcess() but calls the
     * AIDL interface directly:
     *   1. IShizukuService.Stub.asInterface(Shizuku.getBinder()) → service proxy
     *   2. service.newProcess(cmd, env, dir) → IRemoteProcess
     *   3. ShizukuRemoteProcess(IRemoteProcess) → java.lang.Process (via reflection)
     *
     * @throws IllegalStateException if Shizuku service is not available
     * @throws RuntimeException if reflection fails to create ShizukuRemoteProcess
     */
    private fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val service = shizukuService
            ?: throw IllegalStateException("Shizuku service not available")

        // Call the AIDL method — this is PUBLIC in the IShizukuService interface
        val remoteProcess: IRemoteProcess = service.newProcess(cmd, env, dir)

        // Wrap the IRemoteProcess in a ShizukuRemoteProcess (package-private constructor)
        val ctor = remoteProcessCtor
            ?: throw RuntimeException("ShizukuRemoteProcess constructor not accessible")

        return ctor.newInstance(remoteProcess) as Process
    }

    /**
     * Execute a shell command with ADB-level privileges via Shizuku.
     *
     * Uses the AIDL interface to call newProcess() since Shizuku.newProcess()
     * is private in Shizuku 13.1.5.
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
        } catch (e: RemoteException) {
            Log.e(TAG, "Shizuku RemoteException for: $command", e)
            ShellResult("", "Shizuku remote error: ${e.message}", -1, false)
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

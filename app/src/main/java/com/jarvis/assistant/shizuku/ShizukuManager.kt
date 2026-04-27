package com.jarvis.assistant.shizuku

import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import java.util.concurrent.TimeUnit

/**
 * JARVIS Shizuku Manager — ADB-level system control without root.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * CRITICAL FIXES APPLIED (v3):
 *
 * 1. Shizuku.newProcess() is PRIVATE in Shizuku 13.1.5.
 *    FIX: Access IShizukuService.newProcess() via the Shizuku binder
 *    using AIDL transact(). The transaction code for newProcess is
 *    computed from the AIDL method index.
 *
 * 2. Listener types corrected: OnBinderReceivedListener and
 *    OnBinderDeadListener are interfaces, NOT Runnable.
 *
 * 3. destroy() now removes the exact same listener objects that were added.
 * ═══════════════════════════════════════════════════════════════════════
 */
object ShizukuManager {

    private const val TAG = "JarvisShizuku"
    private const val SHELL_TIMEOUT_SECONDS = 15L

    // IShizukuService AIDL interface descriptor
    private const val SHIZUKU_SERVICE_DESCRIPTOR = "rikka.shizuku.IShizukuService"

    // AIDL transaction codes for IShizukuService methods
    // Based on the IShizukuService.aidl from Shizuku source:
    //   1=exit, 2=getUid, 3=checkPermission, 4=getFlagsForUid,
    //   5=updateFlagsForUid, 6=checkRemotePermission,
    //   7=dispatchPermissionConfirmationResult, 8=attachUserService,
    //   9=peekUserService, 10=getLatestServiceVersion,
    //   11=getServerPatchVersion, 12=getSELinuxContext,
    //   13=newProcess
    private const val TRANSACTION_NEW_PROCESS = IBinder.FIRST_CALL_TRANSACTION + 12

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

    // ─── Shell Command Execution ────────────────────────────────

    /**
     * Execute a shell command with ADB-level privileges via Shizuku.
     *
     * Uses IShizukuService.newProcess() AIDL method via the Shizuku binder.
     * The returned ShizukuRemoteProcess extends Process, so we can read
     * stdout/stderr and get exit code normally.
     */
    fun executeShellCommand(command: String): ShellResult {
        if (!isReady()) {
            return ShellResult("", "Shizuku not ready", -1, false)
        }
        if (!hasPermission()) {
            return ShellResult("", "Shizuku permission not granted", -1, false)
        }

        return try {
            val binder = Shizuku.getBinder()
                ?: return ShellResult("", "Shizuku binder is null", -1, false)

            val process = newProcessViaAidl(binder, arrayOf("sh", "-c", command), null, null)

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
            ShellResult("", "Shizuku permission denied: ${e.message}", -1, false)
        } catch (e: Exception) {
            Log.e(TAG, "Shell command failed: $command", e)
            ShellResult("", e.message ?: "Unknown error", -1, false)
        }
    }

    /**
     * Call IShizukuService.newProcess() via AIDL transact().
     *
     * The AIDL interface:
     *   ShizukuRemoteProcess newProcess(in String[] cmd, in String[] env, String dir)
     *
     * We construct the Parcel data matching the AIDL wire format,
     * call binder.transact(), and read the ShizukuRemoteProcess from
     * the reply.
     */
    private fun newProcessViaAidl(
        binder: IBinder,
        cmd: Array<String>,
        env: Array<String>?,
        dir: String?
    ): Process {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()

        try {
            // Write AIDL interface token
            data.writeInterfaceToken(SHIZUKU_SERVICE_DESCRIPTOR)

            // Write cmd: String[]
            data.writeStringArray(cmd)

            // Write env: String[] (nullable)
            data.writeStringArray(env)

            // Write dir: String (nullable)
            data.writeString(dir)

            // Execute the AIDL transaction
            val result = binder.transact(TRANSACTION_NEW_PROCESS, data, reply, 0)
            if (!result) {
                throw RuntimeException("IShizukuService.newProcess() transaction failed — the AIDL method index may be wrong for this Shizuku version")
            }

            // Read exception (if any)
            reply.readException()

            // Read the returned ShizukuRemoteProcess (which is Parcelable)
            // ShizukuRemoteProcess has a CREATOR that reads from Parcel
            val process = ShizukuRemoteProcess.CREATOR.createFromParcel(reply)
                ?: throw RuntimeException("Failed to create ShizukuRemoteProcess from AIDL reply")

            return process
        } finally {
            reply.recycle()
            data.recycle()
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

    fun forceStopApp(packageName: String): ShellResult =
        executeShellCommand("am force-stop $packageName")

    fun openApp(packageName: String): ShellResult =
        executeShellCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")

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

package com.jarvis.assistant.control

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * System-level device control: volume, torch, WiFi, Bluetooth, brightness,
 * ringer, battery, hotspot, clipboard and accessibility-driven UI automation.
 */
class SystemController(private val context: Context) {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    // ------------------------------------------------------------- volume

    fun setVolume(direction: String, level: Int?): String {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        when (direction) {
            "mute" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, 0)
            "unmute" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, 0)
            "up" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, 0)
            "down" -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, 0)
            "set" -> level?.let {
                audioManager.setStreamVolume(stream, (it * max / 100).coerceIn(0, max), 0)
            }
        }
        return ""
    }

    // ------------------------------------------------------------- toggles

    fun toggle(target: String, on: Boolean): String = when (target) {
        "torch" -> setTorch(on)
        "wifi" -> setWifi(on)
        "bluetooth" -> setBluetooth(on)
        else -> ""
    }

    @SuppressLint("MissingPermission")
    private fun setTorch(on: Boolean): String {
        return try {
            val cm = context.getSystemService(CameraManager::class.java)
            val id = cm.cameraIdList.firstOrNull { cid ->
                cm.getCameraCharacteristics(cid)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "Is phone pe torch support nahi hai."
            cm.setTorchMode(id, on)
            ""
        } catch (e: SecurityException) {
            "Torch ke liye Camera permission chahiye — Settings me de do."
        } catch (e: Exception) {
            "Torch switch nahi ho paya."
        }
    }

    private fun setWifi(on: Boolean): String {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        return try {
            @Suppress("DEPRECATION")
            val ok = wm.setWifiEnabled(on)
            if (ok) "" else quickSettingsToggle()
        } catch (e: Exception) {
            quickSettingsToggle()
        }
    }

    @SuppressLint("MissingPermission")
    private fun setBluetooth(on: Boolean): String {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
                ?: return "Is phone pe Bluetooth nahi hai."
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ blocks app-initiated BT toggles on most devices
                if (on) adapter.enable() else adapter.disable()
                quickSettingsToggle()
            } else {
                val ok = if (on) adapter.enable() else adapter.disable()
                if (ok) "" else quickSettingsToggle()
            }
        } catch (e: SecurityException) {
            "Bluetooth control ke liye permission chahiye."
        } catch (e: Exception) {
            quickSettingsToggle()
        }
    }

    /** Fallback that works on every phone: open quick settings via accessibility and tap the tile. */
    private fun quickSettingsToggle(): String {
        val svc = VoiceAccessibilityService.instance
        if (svc == null) return "Is phone pe direct toggle band hai — Accessibility enable karo, main tile khud dabaa dunga."
        svc.doGlobal("quick_settings")
        return ""
    }

    // -------------------------------------------------------------- hotspot

    @SuppressLint("MissingPermission")
    fun setHotspot(on: Boolean): String {
        // Best-effort via reflection (hidden API) — works on most devices < Android 13.
        return try {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            val method = wm.javaClass.getMethod(
                "setWifiApEnabled",
                android.net.wifi.WifiConfiguration::class.java,
                Boolean::class.javaPrimitiveType
            )
            method.invoke(wm, null, on)
            if (on) "Hotspot on ho raha hai!" else "Hotspot band ho raha hai!"
        } catch (e: Exception) {
            quickSettingsToggle()
            if (on) "Hotspot tile khol diya — tile par tap karo." else "Hotspot tile khol diya — tile par tap karo."
        }
    }

    // ----------------------------------------------------------- brightness

    fun setBrightness(direction: String, level: Int?): String {
        if (!Settings.System.canWrite(context)) {
            return "Brightness ke liye 'Write settings' access chahiye — Settings me enable karo."
        }
        val cr = context.contentResolver
        val current = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when (direction) {
            "up" -> (current + 45).coerceAtMost(255)
            "down" -> (current - 45).coerceAtLeast(10)
            "set" -> level ?: current
            else -> current
        }
        runCatching {
            Settings.System.putInt(
                cr,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        }
        return ""
    }

    // -------------------------------------------------------------- ringer

    fun setRinger(mode: String): String {
        return try {
            when (mode) {
                "silent" -> audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                "vibrate" -> audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                else -> audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
            ""
        } catch (e: SecurityException) {
            "Ringer change ke liye Do Not Disturb access chahiye."
        }
    }

    // ------------------------------------------------------------- clipboard

    fun setClipboard(text: String): String {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("jarvis", text))
        return ""
    }

    fun pasteClipboard(): String {
        val svc = VoiceAccessibilityService.instance
            ?: return "Paste ke liye Accessibility enable karo."
        return if (svc.performPaste()) "" else "Paste karne ki jagah nahi mili."
    }

    // -------------------------------------------------------------- battery

    fun batteryStatus(): String {
        val intent = context.registerReceiver(
            null,
            android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return ""
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct = if (scale > 0) level * 100 / scale else level
        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        return "Battery $pct percent hai, " +
            if (charging) "aur charging ho rahi hai. Sab under control!" else "charging nahi ho rahi."
    }

    fun statusReport(): String {
        val battery = batteryStatus()
        val vol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val wifi = runCatching {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            wm.isWifiEnabled
        }.getOrDefault(false)
        val time = SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
        return "$battery Volume $vol out of $maxVol. WiFi ${if (wifi) "on" else "off"}. Time $time. Sab kuch under control, boss!"
    }

    // ------------------------------------------------------------- UI action

    fun uiAction(action: String, target: String?): String {
        val svc = VoiceAccessibilityService.instance
            ?: return "UI control ke liye pehle Accessibility enable karo — Settings > JARVIS UI Control."
        return when (action) {
            "screenshot" -> if (svc.doGlobal("screenshot")) "" else "Screenshot nahi le paya."
            "lock" -> {
                svc.doGlobal("lock")
                ""
            }
            "click" -> {
                val t = target ?: return "Kya click karna hai, batao."
                if (svc.findAndClick(t)) "" else "\"$t\" screen par nahi mila."
            }
            "scroll_down" -> if (svc.scroll(true)) "" else "Scroll karne ko kuch nahi mila."
            "scroll_up" -> if (svc.scroll(false)) "" else "Scroll karne ko kuch nahi mila."
            "back" -> if (svc.doGlobal("back")) "" else "Back nahi ho paya."
            "home" -> if (svc.doGlobal("home")) "" else "Home nahi khula."
            "notifications" -> if (svc.doGlobal("notifications")) "" else "Notification shade nahi khula."
            "quick_settings" -> if (svc.doGlobal("quick_settings")) "" else "Quick settings nahi khula."
            else -> ""
        }
    }
}

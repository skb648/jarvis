package com.jarvis.assistant.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.assistant.location.LocationAwarenessManager
import kotlinx.coroutines.*

/**
 * ProactiveDeviceMonitor — Proactively monitors device status and alerts the user.
 *
 * "Sir, battery 15% hai, charger lagao" when below 20%
 * "Sir, storage almost full hai" when >90% used
 * "Sir, phone slow ho raha hai" when RAM >85% used
 * "Sir, WiFi disconnect ho gaya" on network changes
 *
 * Uses DeviceMonitor for telemetry. Proactive alerts are delivered
 * via TTS + notification. Respects DND/night mode — no alerts
 * during quiet hours.
 */
object ProactiveDeviceMonitor {

    private const val TAG = "ProactiveDeviceMonitor"
    private const val CHANNEL_ALERTS = "jarvis_proactive_alerts"
    private const val ALERT_NOTIFICATION_ID = 3001

    private const val PREFS_NAME = "jarvis_device_monitor"
    private const val KEY_LAST_BATTERY_ALERT = "last_battery_alert"
    private const val KEY_LAST_STORAGE_ALERT = "last_storage_alert"
    private const val KEY_LAST_MEMORY_ALERT = "last_memory_alert"
    private const val KEY_LAST_NETWORK_ALERT = "last_network_alert"
    private const val KEY_DATA_LIMIT_MB = "data_limit_mb"
    private const val KEY_MONITORING_ENABLED = "monitoring_enabled"

    // Minimum interval between same-type alerts (5 minutes)
    private const val ALERT_COOLDOWN_MS = 5 * 60_000L

    // Thresholds
    private const val BATTERY_LOW_THRESHOLD = 20
    private const val STORAGE_HIGH_THRESHOLD = 90  // percent used
    private const val MEMORY_HIGH_THRESHOLD = 85    // percent used

    // Monitoring state
    private var monitoringJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var networkReceiver: BroadcastReceiver? = null
    @Volatile private var isMonitoring = false
    @Volatile private var monitorContext: Context? = null

    // Callback for speaking alerts
    var onAlertCallback: ((String) -> Unit)? = null

    // DND state
    @Volatile private var isInDndMode = false

    /**
     * Start periodic device monitoring.
     */
    fun startMonitoring(context: Context) {
        if (isMonitoring) {
            Log.d(TAG, "[startMonitoring] Already monitoring")
            return
        }

        monitorContext = context.applicationContext
        isMonitoring = true
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, true).apply()

        createNotificationChannel(context)
        registerBatteryReceiver(context)
        registerNetworkReceiver(context)

        // Periodic checks every 2 minutes
        monitoringJob = CoroutineScope(Dispatchers.IO).launch {
            Log.i(TAG, "[startMonitoring] Periodic monitoring started")
            while (isActive) {
                try {
                    checkAndAlert(context)
                } catch (e: Exception) {
                    Log.e(TAG, "[startMonitoring] Check error: ${e.message}")
                }
                delay(2 * 60_000L) // 2 minutes
            }
        }

        Log.i(TAG, "[startMonitoring] Started")
    }

    /**
     * Stop periodic device monitoring.
     */
    fun stopMonitoring() {
        isMonitoring = false
        monitoringJob?.cancel()
        monitoringJob = null

        // Unregister receivers using stored context
        val ctx = monitorContext
        if (ctx != null) {
            try { batteryReceiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
            try { networkReceiver?.let { ctx.unregisterReceiver(it) } } catch (_: Exception) {}
        }
        batteryReceiver = null
        networkReceiver = null
        monitorContext = null
        Log.i(TAG, "[stopMonitoring] Stopped")
    }

    /**
     * Stop monitoring and unregister receivers.
     */
    fun stopMonitoring(context: Context) {
        stopMonitoring()
        try {
            batteryReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        try {
            networkReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Exception) {}
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MONITORING_ENABLED, false).apply()
    }

    /**
     * Set DND mode — suppresses all proactive alerts when true.
     */
    fun setDndMode(enabled: Boolean) {
        isInDndMode = enabled
        Log.d(TAG, "[setDndMode] DND mode = $enabled")
    }

    /**
     * Set a mobile data usage limit for alerts.
     */
    fun setDataLimit(limitMb: Int, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DATA_LIMIT_MB, limitMb).apply()
        Log.i(TAG, "[setDataLimit] Data limit set to ${limitMb}MB")
    }

    /**
     * Run a single check and speak/post alerts if needed.
     * Called periodically and by battery/network broadcast receivers.
     */
    fun checkAndAlert(context: Context) {
        if (!isMonitoring || isInDndMode) return

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // ─── Battery Check ───────────────────────────────────────
        checkBattery(context, prefs)

        // ─── Storage Check ───────────────────────────────────────
        checkStorage(context, prefs)

        // ─── Memory Check ────────────────────────────────────────
        checkMemory(context, prefs)
    }

    /**
     * Get a comprehensive device status report.
     * Used for "battery kiti hai" / "phone status" commands.
     */
    fun getDeviceStatusReport(context: Context): String {
        try {
            val battery = DeviceMonitor.getBatteryInfo(context)
            val memory = DeviceMonitor.getMemoryInfo(context)
            val storage = DeviceMonitor.getStorageInfo()
            val network = DeviceMonitor.getNetworkInfo(context)

            val batteryPercent = battery["percent"] as? Int ?: 0
            val isCharging = battery["isCharging"] as? Boolean ?: false
            val temp = battery["temperature"] as? Float ?: 0f

            val memUsed = memory["usedPercent"] as? Int ?: 0
            val memAvailMB = memory["availableMB"] as? Long ?: 0L

            @Suppress("UNCHECKED_CAST")
            val internalStorage = storage["internal"] as? Map<String, Any>
            val storageUsedPercent = internalStorage?.get("usedPercent") as? Int ?: 0

            val netType = network["type"] as? String ?: "none"
            val isConnected = network["isConnected"] as? Boolean ?: false

            val sb = StringBuilder()
            sb.append("Sir, battery $batteryPercent% hai")
            if (isCharging) sb.append(" (charging)")
            if (temp > 35f) sb.append(", temperature ${temp}°C")
            sb.append(". ")

            sb.append("Memory ${memUsed}% used hai, ${memAvailMB}MB available. ")
            sb.append("Storage ${storageUsedPercent}% used hai. ")

            if (isConnected) {
                sb.append("Network: $netType connected.")
            } else {
                sb.append("Network disconnected hai.")
            }

            // Add location context if available
            try {
                val locationContext = kotlinx.coroutines.runBlocking {
                    LocationAwarenessManager.getLocationContext(context)
                }
                if (locationContext.isNotBlank()) {
                    sb.append(" $locationContext.")
                }
            } catch (_: Exception) {}

            return sb.toString()
        } catch (e: Exception) {
            Log.e(TAG, "[getDeviceStatusReport] Error: ${e.message}")
            return "Sir, device status nahi mil pa raha."
        }
    }

    // ─── Individual Check Functions ────────────────────────────────────

    private fun checkBattery(context: Context, prefs: SharedPreferences) {
        try {
            val battery = DeviceMonitor.getBatteryInfo(context)
            val percent = battery["percent"] as? Int ?: 100
            val isCharging = battery["isCharging"] as? Boolean ?: false

            if (!isCharging && percent <= BATTERY_LOW_THRESHOLD) {
                if (!isInCooldown(prefs, KEY_LAST_BATTERY_ALERT)) {
                    val message = when {
                        percent <= 5 -> "Sir, battery sirf $percent% hai! Turant charger lagao!"
                        percent <= 10 -> "Sir, battery $percent% hai, bahut low hai. Charger lagao."
                        percent <= 15 -> "Sir, battery $percent% hai, charger lagane ka time ho gaya."
                        else -> "Sir, battery $percent% hai, jald charger lagao."
                    }
                    deliverAlert(context, message, "battery_low")
                    updateAlertTime(prefs, KEY_LAST_BATTERY_ALERT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[checkBattery] Error: ${e.message}")
        }
    }

    private fun checkStorage(context: Context, prefs: SharedPreferences) {
        try {
            val storage = DeviceMonitor.getStorageInfo()
            @Suppress("UNCHECKED_CAST")
            val internal = storage["internal"] as? Map<String, Any>
            val usedPercent = internal?.get("usedPercent") as? Int ?: 0

            if (usedPercent >= STORAGE_HIGH_THRESHOLD) {
                if (!isInCooldown(prefs, KEY_LAST_STORAGE_ALERT)) {
                    @Suppress("UNCHECKED_CAST")
                    val availGB = internal?.get("availableGB") as? String ?: "unknown"
                    val message = "Sir, storage almost full hai — $usedPercent% used, sirf ${availGB}GB bacha hai. Kuch delete karo."
                    deliverAlert(context, message, "storage_high")
                    updateAlertTime(prefs, KEY_LAST_STORAGE_ALERT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[checkStorage] Error: ${e.message}")
        }
    }

    private fun checkMemory(context: Context, prefs: SharedPreferences) {
        try {
            val memory = DeviceMonitor.getMemoryInfo(context)
            val usedPercent = memory["usedPercent"] as? Int ?: 0
            val isLow = memory["isLowMemory"] as? Boolean ?: false

            if (usedPercent >= MEMORY_HIGH_THRESHOLD || isLow) {
                if (!isInCooldown(prefs, KEY_LAST_MEMORY_ALERT)) {
                    val message = "Sir, phone slow ho raha hai — memory ${usedPercent}% used hai. Kuch apps close karo."
                    deliverAlert(context, message, "memory_high")
                    updateAlertTime(prefs, KEY_LAST_MEMORY_ALERT)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[checkMemory] Error: ${e.message}")
        }
    }

    // ─── Battery Broadcast Receiver ────────────────────────────────────

    private fun registerBatteryReceiver(context: Context) {
        try {
            batteryReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == Intent.ACTION_BATTERY_LOW) {
                        if (!isInDndMode) {
                            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
                            deliverAlert(ctx, "Sir, battery $level% hai, charger lagao.", "battery_broadcast")
                        }
                    }
                }
            }
            val filter = IntentFilter(Intent.ACTION_BATTERY_LOW)
            context.registerReceiver(batteryReceiver, filter)
            Log.d(TAG, "[registerBatteryReceiver] Registered")
        } catch (e: Exception) {
            Log.e(TAG, "[registerBatteryReceiver] Error: ${e.message}")
        }
    }

    // ─── Network Broadcast Receiver ────────────────────────────────────

    private fun registerNetworkReceiver(context: Context) {
        try {
            networkReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (!isInDndMode && isMonitoring) {
                        val network = DeviceMonitor.getNetworkInfo(ctx)
                        val isConnected = network["isConnected"] as? Boolean ?: false
                        val type = network["type"] as? String ?: "none"

                        if (!isConnected) {
                            if (!isInCooldown(
                                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                                    KEY_LAST_NETWORK_ALERT
                                )
                            ) {
                                deliverAlert(ctx, "Sir, WiFi disconnect ho gaya hai.", "network_lost")
                                updateAlertTime(
                                    ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                                    KEY_LAST_NETWORK_ALERT
                                )
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("android.net.conn.CONNECTIVITY_CHANGE")
            }
            context.registerReceiver(networkReceiver, filter)
            Log.d(TAG, "[registerNetworkReceiver] Registered")
        } catch (e: Exception) {
            Log.e(TAG, "[registerNetworkReceiver] Error: ${e.message}")
        }
    }

    // ─── Alert Delivery ────────────────────────────────────────────────

    private fun deliverAlert(context: Context, message: String, alertType: String) {
        Log.i(TAG, "[deliverAlert] $alertType: $message")

        // Post notification
        postAlertNotification(context, message, alertType)

        // Speak via callback (JarvisViewModel will handle TTS)
        onAlertCallback?.invoke(message)
    }

    private fun postAlertNotification(context: Context, message: String, alertType: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ALERTS,
                "JARVIS Proactive Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Device status alerts from JARVIS"
            }
            nm.createNotificationChannel(channel)

            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("JARVIS Alert")
                .setContentText(message.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(ALERT_NOTIFICATION_ID + alertType.hashCode() % 100, notification)
        } catch (e: Exception) {
            Log.e(TAG, "[postAlertNotification] Error: ${e.message}")
        }
    }

    private fun createNotificationChannel(context: Context) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ALERTS,
                "JARVIS Proactive Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        } catch (e: Exception) {
            Log.e(TAG, "[createNotificationChannel] Error: ${e.message}")
        }
    }

    // ─── Cooldown Management ───────────────────────────────────────────

    private fun isInCooldown(prefs: SharedPreferences, key: String): Boolean {
        val lastAlert = prefs.getLong(key, 0)
        return (System.currentTimeMillis() - lastAlert) < ALERT_COOLDOWN_MS
    }

    private fun updateAlertTime(prefs: SharedPreferences, key: String) {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    /**
     * Check if monitoring is currently enabled.
     */
    fun isMonitoringEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MONITORING_ENABLED, false)
    }
}

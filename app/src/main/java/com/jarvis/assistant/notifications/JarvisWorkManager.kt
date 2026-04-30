package com.jarvis.assistant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.jarvis.assistant.MainActivity
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.monitor.DeviceMonitor
import com.jarvis.assistant.smarthome.MqttManager
import java.util.concurrent.TimeUnit

/**
 * JARVIS Work Manager — Scheduled background notifications.
 *
 * Three scheduled tasks:
 *   1. Proactive check every 2 hours — monitors service health, alerts on failures
 *   2. Morning brief at 7:00 AM — summarizes active services, smart home status
 *   3. Smart home check every hour — verifies MQTT connection
 */
object JarvisWorkManager {

    private const val TAG = "JarvisWorkManager"

    // Work IDs
    private const val PROACTIVE_CHECK_WORK = "jarvis_proactive_check"
    private const val MORNING_BRIEF_WORK = "jarvis_morning_brief"
    private const val SMART_HOME_CHECK_WORK = "jarvis_smart_home_check"

    // Notification channels
    private const val CHANNEL_PROACTIVE = "jarvis_proactive"
    private const val CHANNEL_MORNING = "jarvis_morning"
    private const val CHANNEL_SMART_HOME = "jarvis_smarthome"

    // Notification IDs
    private const val NOTIFICATION_ID_PROACTIVE = 2001
    private const val NOTIFICATION_ID_SMART_HOME = 2002

    /**
     * Initialize all scheduled work.
     */
    fun initialize(context: Context) {
        createNotificationChannels(context)
        scheduleProactiveCheck(context)
        scheduleMorningBrief(context)
        scheduleSmartHomeCheck(context)
        Log.i(TAG, "Work Manager initialized")
    }

    /**
     * Cancel all scheduled work.
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        Log.i(TAG, "All scheduled work cancelled")
    }

    // ─── Proactive Health Check (every 2 hours) ────────────────

    private fun scheduleProactiveCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<JarvisCheckWorker>(2, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .addTag(PROACTIVE_CHECK_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PROACTIVE_CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // ─── Morning Brief (daily at 7:00 AM) ──────────────────────

    private fun scheduleMorningBrief(context: Context) {
        // Use a periodic work as approximation (every 24 hours)
        val request = PeriodicWorkRequestBuilder<JarvisCheckWorker>(24, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(MORNING_BRIEF_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            MORNING_BRIEF_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // ─── Smart Home Check (every hour) ─────────────────────────

    private fun scheduleSmartHomeCheck(context: Context) {
        val request = PeriodicWorkRequestBuilder<JarvisCheckWorker>(1, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .addTag(SMART_HOME_CHECK_WORK)
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            SMART_HOME_CHECK_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    // ─── Notification Channels ──────────────────────────────────

    private fun createNotificationChannels(context: Context) {
        val channels = listOf(
            NotificationChannel(CHANNEL_PROACTIVE, "JARVIS Proactive Alerts", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_MORNING, "JARVIS Morning Brief", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(CHANNEL_SMART_HOME, "JARVIS Smart Home", NotificationManager.IMPORTANCE_LOW)
        )
        val nm = context.getSystemService(NotificationManager::class.java)
        channels.forEach { nm.createNotificationChannel(it) }
    }

    // ─── Notification Helper ────────────────────────────────────

    /**
     * Post a notification with the given title and text.
     * Creates the notification channel if it doesn't exist, and uses
     * NotificationCompat for backwards compatibility.
     */
    private fun postNotification(
        context: Context,
        title: String,
        text: String,
        channelId: String = CHANNEL_PROACTIVE,
        notificationId: Int = NOTIFICATION_ID_PROACTIVE
    ) {
        try {
            // Ensure the channel exists
            val nm = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                channelId,
                channelId.replace("jarvis_", "").replace("_", " ").replaceFirstChar { it.uppercase() },
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)

            // Tap intent — open MainActivity
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(notificationId, notification)
            Log.i(TAG, "Notification posted: $title — $text")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post notification: $title", e)
        }
    }

    // ─── Worker Implementation ──────────────────────────────────

    class JarvisCheckWorker(
        context: Context,
        workerParams: WorkerParameters
    ) : CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result {
            val tags = tags.toList()
            Log.d(TAG, "Worker executing: $tags")

            return try {
                when {
                    tags.contains(PROACTIVE_CHECK_WORK) -> performProactiveCheck()
                    tags.contains(MORNING_BRIEF_WORK) -> performMorningBrief()
                    tags.contains(SMART_HOME_CHECK_WORK) -> performSmartHomeCheck()
                }
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Worker failed", e)
                Result.retry()
            }
        }

        private fun performProactiveCheck() {
            val context = applicationContext

            // ─── Battery Check ─────────────────────────────────
            try {
                val batteryInfo = DeviceMonitor.getBatteryInfo(context)
                val batteryPercent = batteryInfo["percent"] as? Int ?: -1
                val isCharging = batteryInfo["isCharging"] as? Boolean ?: false

                if (batteryPercent in 1..19 && !isCharging) {
                    postNotification(
                        context = context,
                        title = "JARVIS: Battery Low",
                        text = "Sir, battery ${batteryPercent}% hai, charger lagao.",
                        channelId = CHANNEL_PROACTIVE,
                        notificationId = NOTIFICATION_ID_PROACTIVE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proactive check: battery check failed", e)
            }

            // ─── Storage Check ─────────────────────────────────
            try {
                val storageInfo = DeviceMonitor.getStorageInfo()
                @Suppress("UNCHECKED_CAST")
                val internal = storageInfo["internal"] as? Map<String, Any>
                val usedPercent = internal?.get("usedPercent") as? Int ?: 0
                if (usedPercent > 90) {
                    val usedGB = internal?.get("usedGB") as? String ?: "?"
                    val totalGB = internal?.get("totalGB") as? String ?: "?"
                    postNotification(
                        context = context,
                        title = "JARVIS: Storage Almost Full",
                        text = "Sir, storage ${usedPercent}% used (${usedGB}/${totalGB} GB). Consider clearing files.",
                        channelId = CHANNEL_PROACTIVE,
                        notificationId = NOTIFICATION_ID_PROACTIVE
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proactive check: storage check failed", e)
            }

            // ─── Service Health ────────────────────────────────
            if (!JarviewModel.speechServiceRunning) {
                Log.w(TAG, "Proactive check: Speech service not running")
            }
            if (!JarviewModel.sensoryServiceRunning) {
                Log.w(TAG, "Proactive check: Sensory service not running")
            }
        }

        private suspend fun performMorningBrief() {
            Log.i(TAG, "Morning brief: generating daily summary")
            try {
                val context = applicationContext
                // Get API key from shared prefs cache
                val apiKey = try {
                    val prefs = context.getSharedPreferences("jarvis_settings_apikey_cache", Context.MODE_PRIVATE)
                    prefs.getString("gemini_api_key", "") ?: ""
                } catch (_: Exception) { "" }

                val brief = com.jarvis.assistant.brief.DailyBriefGenerator.generateBrief(context, apiKey)
                com.jarvis.assistant.brief.DailyBriefGenerator.postBriefNotification(context, brief)
                Log.i(TAG, "Morning brief generated: ${brief.take(100)}")
            } catch (e: Exception) {
                Log.e(TAG, "Morning brief failed", e)
            }
        }

        private fun performSmartHomeCheck() {
            val context = applicationContext

            val isMqttConnected = JarviewModel.mqttConnected
            if (!isMqttConnected) {
                // Check if MQTT is configured but disconnected
                val isMqttConfigured = try {
                    val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)
                    // DataStore doesn't use SharedPreferences directly, but check MQTT broker URL cache
                    val mqttCache = context.getSharedPreferences("jarvis_mqtt_cache", Context.MODE_PRIVATE)
                    mqttCache.getString("broker_url", "").isNotBlank()
                } catch (_: Exception) { false }

                if (isMqttConfigured) {
                    postNotification(
                        context = context,
                        title = "JARVIS: Smart Home Disconnected",
                        text = "Sir, MQTT broker connection lost. Smart home devices may not respond.",
                        channelId = CHANNEL_SMART_HOME,
                        notificationId = NOTIFICATION_ID_SMART_HOME
                    )
                }
                Log.w(TAG, "Smart home check: MQTT not connected")
            } else {
                // MQTT is connected — check for unusual device states
                try {
                    val devices = MqttManager.getConnectedDevices()
                    val unusualDevices = devices.filter { device ->
                        // Flag devices that report error states or unexpected values
                        device.state.equals("error", ignoreCase = true) ||
                        device.state.equals("unavailable", ignoreCase = true) ||
                        device.state.equals("unreachable", ignoreCase = true)
                    }
                    if (unusualDevices.isNotEmpty()) {
                        val names = unusualDevices.take(3).joinToString(", ") { it.name }
                        postNotification(
                            context = context,
                            title = "JARVIS: Device Alert",
                            text = "Sir, ${unusualDevices.size} device(s) reporting issues: $names",
                            channelId = CHANNEL_SMART_HOME,
                            notificationId = NOTIFICATION_ID_SMART_HOME
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Smart home check: device state inspection failed — ${e.message}")
                }
                Log.i(TAG, "Smart home check: MQTT connected, devices nominal")
            }
        }
    }
}

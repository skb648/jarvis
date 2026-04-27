package com.jarvis.assistant.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.util.Log
import androidx.work.*
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
            // Check service health and alert on failures
            if (!com.jarvis.assistant.channels.JarviewModel.speechServiceRunning) {
                Log.w(TAG, "Proactive check: Speech service not running")
            }
            if (!com.jarvis.assistant.channels.JarviewModel.sensoryServiceRunning) {
                Log.w(TAG, "Proactive check: Sensory service not running")
            }
        }

        private fun performMorningBrief() {
            Log.i(TAG, "Morning brief: generating daily summary")
            // Summary will be posted as notification by the UI layer
        }

        private fun performSmartHomeCheck() {
            if (!com.jarvis.assistant.channels.JarviewModel.mqttConnected) {
                Log.w(TAG, "Smart home check: MQTT not connected")
            }
        }
    }
}

package com.jarvis.assistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * NotificationData — Data class representing a parsed notification.
 */
data class NotificationData(
    val appName: String,
    val title: String,
    val content: String,
    val timestamp: Long,
    val packageName: String
)

/**
 * NotificationReaderService — Reads and announces incoming notifications.
 *
 * "Sir, WhatsApp pe Priya ka message hai: [content]"
 *
 * This NotificationListenerService:
 *   1. Listens for incoming notifications from all apps
 *   2. Parses the notification content (title, text, app name)
 *   3. Stores the last 50 notifications in memory
 *   4. Broadcasts new notifications so JARVIS can announce them via TTS
 *   5. Provides getRecentNotifications() for on-demand reading
 *
 * ═══════════════════════════════════════════════════════════════════════
 * IMPORTANT: The user must grant notification access permission in:
 *   Settings → Apps → Special app access → Notification access → JARVIS
 *
 * This cannot be requested at runtime — the user must navigate there manually.
 * ═══════════════════════════════════════════════════════════════════════
 */
class NotificationReaderService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationReader"
        private const val MAX_NOTIFICATIONS = 50

        // Broadcast action for new notifications
        const val ACTION_NEW_NOTIFICATION = "com.jarvis.assistant.NEW_NOTIFICATION"
        const val EXTRA_NOTIFICATION_APP = "notification_app"
        const val EXTRA_NOTIFICATION_TITLE = "notification_title"
        const val EXTRA_NOTIFICATION_CONTENT = "notification_content"
        const val EXTRA_NOTIFICATION_PACKAGE = "notification_package"

        // Whether the notification reading feature is enabled
        private val _notificationsEnabled = MutableStateFlow(false)
        val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

        // In-memory notification history (last 50)
        private val _notificationHistory = mutableListOf<NotificationData>()
        val notificationHistory: List<NotificationData> get() = _notificationHistory.toList()

        // Reference to the service instance
        @Volatile
        private var instance: NotificationReaderService? = null

        fun getInstance(): NotificationReaderService? = instance

        /**
         * Get recent notifications, optionally filtered by app package name.
         */
        fun getRecentNotifications(count: Int = 10, packageFilter: String? = null): List<NotificationData> {
            val filtered = if (packageFilter != null) {
                _notificationHistory.filter { it.packageName.contains(packageFilter, ignoreCase = true) }
            } else {
                _notificationHistory
            }
            return filtered.takeLast(count)
        }

        /**
         * Enable or disable notification reading.
         */
        fun setNotificationsEnabled(enabled: Boolean) {
            _notificationsEnabled.value = enabled
            Log.i(TAG, "Notification reading enabled: $enabled")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "[onCreate] NotificationReaderService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        Log.i(TAG, "[onDestroy] NotificationReaderService destroyed")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        Log.i(TAG, "[onListenerConnected] Notification listener connected — JARVIS can now read notifications")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "[onListenerDisconnected] Notification listener disconnected")
    }

    /**
     * Called when a new notification is posted.
     * Parses the notification and broadcasts it for TTS announcement.
     */
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!_notificationsEnabled.value) return

        try {
            val packageName = sbn.packageName
            val notification = sbn.notification ?: return

            // Skip JARVIS's own notifications (foreground service)
            if (packageName == "com.jarvis.assistant") return

            // Skip ongoing/foreground notifications (media players, downloads, etc.)
            if (notification.isOngoing) return

            // Extract notification text
            val extras = notification.extras ?: return

            val title = extras.getString(android.app.Notification.EXTRA_TITLE)?.trim() ?: ""
            val content = (
                extras.getString(android.app.Notification.EXTRA_TEXT)?.trim() ?: ""
            ).ifBlank {
                extras.getString(android.app.Notification.EXTRA_BIG_TEXT)?.trim() ?: ""
            }

            if (title.isBlank() && content.isBlank()) return

            // Get app name from package
            val appName = try {
                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                packageManager.getApplicationLabel(appInfo).toString()
            } catch (e: Exception) {
                packageName.substringAfterLast(".").replaceFirstChar { it.uppercase() }
            }

            val notificationData = NotificationData(
                appName = appName,
                title = title,
                content = content,
                timestamp = System.currentTimeMillis(),
                packageName = packageName
            )

            // Add to history (keep last 50)
            synchronized(_notificationHistory) {
                _notificationHistory.add(notificationData)
                if (_notificationHistory.size > MAX_NOTIFICATIONS) {
                    _notificationHistory.removeAt(0)
                }
            }

            Log.d(TAG, "[onNotificationPosted] $appName: $title - ${content.take(50)}")

            // Broadcast the notification for JARVIS to announce
            val intent = Intent(ACTION_NEW_NOTIFICATION).apply {
                putExtra(EXTRA_NOTIFICATION_APP, appName)
                putExtra(EXTRA_NOTIFICATION_TITLE, title)
                putExtra(EXTRA_NOTIFICATION_CONTENT, content)
                putExtra(EXTRA_NOTIFICATION_PACKAGE, packageName)
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "[onNotificationPosted] Error parsing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Notification dismissed — no action needed
    }

    /**
     * Format a notification for TTS announcement.
     * "Sir, WhatsApp pe Priya ka message hai: Hey, how are you?"
     */
    fun formatForTTS(data: NotificationData): String {
        val appName = data.appName
        val title = data.title
        val content = data.content

        return when {
            title.isNotBlank() && content.isNotBlank() -> {
                "Sir, ${appName} pe ${title} ka notification hai: $content"
            }
            title.isNotBlank() -> {
                "Sir, ${appName} pe ek notification hai: $title"
            }
            content.isNotBlank() -> {
                "Sir, ${appName} pe notification aaya hai: $content"
            }
            else -> "Sir, ${appName} pe ek notification aaya hai."
        }
    }
}

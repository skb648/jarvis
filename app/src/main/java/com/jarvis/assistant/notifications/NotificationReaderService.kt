package com.jarvis.assistant.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.provider.ContactsContract
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
// LocalBroadcastManager is deprecated — using regular BroadcastReceiver instead
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
 *   6. Announces incoming calls with caller name resolution
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

        // Incoming call broadcast
        const val ACTION_INCOMING_CALL = "com.jarvis.assistant.INCOMING_CALL"
        const val EXTRA_CALLER_NAME = "caller_name"
        const val EXTRA_CALLER_NUMBER = "caller_number"

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

        // Callback for new notifications
        var onNotificationReceived: ((NotificationData) -> Unit)? = null

        // Callback for incoming calls
        var onIncomingCall: ((callerName: String, callerNumber: String) -> Unit)? = null

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

    private var phoneStateReceiver: BroadcastReceiver? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        registerPhoneStateReceiver()
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
            if (notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0) return

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

            // Notify via callback
            onNotificationReceived?.invoke(notificationData)

            // Broadcast the notification for JARVIS to announce
            val intent = Intent(ACTION_NEW_NOTIFICATION).apply {
                putExtra(EXTRA_NOTIFICATION_APP, appName)
                putExtra(EXTRA_NOTIFICATION_TITLE, title)
                putExtra(EXTRA_NOTIFICATION_CONTENT, content)
                putExtra(EXTRA_NOTIFICATION_PACKAGE, packageName)
                // Android 14+ requires explicit broadcast
                setPackage("com.jarvis.assistant")
            }
            sendBroadcast(intent)

        } catch (e: Exception) {
            Log.e(TAG, "[onNotificationPosted] Error parsing notification: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Notification dismissed — no action needed
    }

    // ─── Phone State Receiver ──────────────────────────────────────────

    private fun registerPhoneStateReceiver() {
        try {
            phoneStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action == TelephonyManager.ACTION_PHONE_STATE_CHANGED) {
                        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                        if (state == TelephonyManager.EXTRA_STATE_RINGING) {
                            val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: "Unknown"
                            val callerName = resolveContactName(ctx, number) ?: number
                            Log.i(TAG, "[PhoneState] Incoming call from $callerName ($number)")

                            // Notify via callback
                            onIncomingCall?.invoke(callerName, number)

                            // Also broadcast for other components
                            val callIntent = Intent(ACTION_INCOMING_CALL).apply {
                                putExtra(EXTRA_CALLER_NAME, callerName)
                                putExtra(EXTRA_CALLER_NUMBER, number)
                                setPackage("com.jarvis.assistant")
                            }
                            sendBroadcast(callIntent)
                        }
                    }
                }
            }
            val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
            registerReceiver(phoneStateReceiver, filter)
            Log.d(TAG, "[registerPhoneStateReceiver] Phone state receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "[registerPhoneStateReceiver] Error: ${e.message}")
        }
    }

    private fun resolveContactName(context: Context, phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(phoneNumber))
            val cursor = context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                } else null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "[resolveContactName] READ_CONTACTS permission not granted")
            null
        } catch (e: Exception) {
            Log.w(TAG, "[resolveContactName] Error: ${e.message}")
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { phoneStateReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
        instance = null
        Log.i(TAG, "[onDestroy] NotificationReaderService destroyed")
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

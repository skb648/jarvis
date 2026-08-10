package com.jarvis.assistant.control

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Notification listener — enabling it in Android Settings unlocks:
 *  1. MediaSessionManager.getActiveSessions() for ALL music apps
 *     (full play/pause/next control)
 *  2. The NotificationReader ("kya naya aaya?")
 */
class MediaNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        if (sbn.packageName == packageName) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString().orEmpty()
        val big = extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val appLabel = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrDefault(sbn.packageName)

        NotificationReader.add(
            sbn,
            appLabel,
            title,
            text.ifBlank { big }
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // warm the reader with existing notifications
        runCatching {
            activeNotifications.forEach { onNotificationPosted(it) }
        }
    }
}

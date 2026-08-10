package com.jarvis.assistant.control

import android.content.Context
import android.service.notification.StatusBarNotification
import java.util.LinkedHashMap

/**
 * Recent notifications — fed by [MediaNotificationListener].
 * "kya naya aaya?" -> JARVIS sunaata hai aapke notifications.
 */
object NotificationReader {

    data class Notif(val app: String, val title: String, val text: String, val time: Long)

    private val recent = LinkedHashMap<String, Notif>(30, 0.75f, true)

    @Synchronized
    fun add(sbn: StatusBarNotification, appLabel: String, title: String, text: String) {
        val key = sbn.key
        recent[key] = Notif(appLabel, title, text, sbn.postTime)
        while (recent.size > 20) {
            val oldest = recent.entries.firstOrNull()?.key ?: break
            recent.remove(oldest)
        }
    }

    @Synchronized
    fun read(context: Context, count: Int = 5): String {
        val own = context.packageName
        val list = recent.values
            .filter { it.text.isNotBlank() || it.title.isNotBlank() }
            .takeLast(count)
        if (list.isEmpty()) return "Koi nayi notification nahi hai, boss. Sab clear!"
        val sb = StringBuilder("Aaj ki notifications, suno: ")
        list.forEachIndexed { i, n ->
            val content = n.title.ifBlank { n.text }
            sb.append("${i + 1}. ")
            if (n.app.isNotBlank()) sb.append("${n.app} se: ")
            sb.append(content).append(". ")
        }
        return sb.toString()
    }
}

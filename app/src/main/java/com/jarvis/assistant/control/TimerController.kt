package com.jarvis.assistant.control

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import java.util.Calendar

/**
 * Alarms, timers, reminders and daily routines (all via AlarmManager so they
 * survive app restarts).
 */
class TimerController(private val context: Context) {

    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun startTimer(seconds: Long): String {
        val triggerAt = System.currentTimeMillis() + seconds * 1000
        schedule(triggerAt, pendingIntent("TIMER", requestCode = (triggerAt % 100000).toInt()))
        return ""
    }

    fun setAlarm(hour: Int, minute: Int): String {
        val cal = nextOccurrence(hour, minute)
        schedule(cal.timeInMillis, pendingIntent("ALARM", requestCode = hour * 60 + minute))
        return ""
    }

    fun setReminder(text: String, hour: Int, minute: Int): String {
        val cal = nextOccurrence(hour, minute)
        val pi = pendingIntent(
            "REMINDER",
            requestCode = (hour * 60 + minute + text.hashCode()) % 100000,
            extra = text
        )
        schedule(cal.timeInMillis, pi)
        return ""
    }

    suspend fun scheduleRoutine(hour: Int, minute: Int, action: String): String {
        val app = context.applicationContext as JarvisApp
        app.settings.addRoutine(hour, minute, action)
        val cal = nextOccurrence(hour, minute)
        val pi = pendingIntent(
            "ROUTINE",
            requestCode = (hour * 60 + minute) % 100000 + 50000,
            extra = action
        )
        runCatching {
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.timeInMillis,
                AlarmManager.INTERVAL_DAY,
                pi
            )
        }
        return ""
    }

    /** Called after reboot to re-arm all routines. */
    fun rescheduleRoutines(routines: Set<String>) {
        for (entry in routines) {
            val parts = entry.split("|")
            if (parts.size != 2) continue
            val time = parts[0].split(":")
            val action = parts[1]
            val hour = time.getOrNull(0)?.toIntOrNull() ?: continue
            val minute = time.getOrNull(1)?.toIntOrNull() ?: 0
            val cal = nextOccurrence(hour, minute)
            val pi = pendingIntent(
                "ROUTINE",
                requestCode = (hour * 60 + minute) % 100000 + 50000,
                extra = action
            )
            runCatching {
                alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    cal.timeInMillis,
                    AlarmManager.INTERVAL_DAY,
                    pi
                )
            }
        }
    }

    private fun pendingIntent(type: String, requestCode: Int, extra: String? = null): PendingIntent {
        val intent = Intent(context, ActionReceiver::class.java)
            .putExtra("type", type)
            .apply { if (extra != null) putExtra("extra", extra) }
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun schedule(triggerAt: Long, pi: PendingIntent) {
        val am = alarmManager
        if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
            am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            runCatching {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }.onFailure {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    private fun nextOccurrence(hour: Int, minute: Int): Calendar =
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }
}

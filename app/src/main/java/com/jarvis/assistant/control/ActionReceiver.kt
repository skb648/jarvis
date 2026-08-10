package com.jarvis.assistant.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.NotificationManager
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.model.Emotion
import kotlinx.coroutines.runBlocking

/**
 * Handles fired alarms / timers / reminders / routines.
 */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        val app = context.applicationContext as JarvisApp

        when (type) {
            "TIMER" -> {
                showAlarm(context, "Timer Complete!", "Time ho gaya boss!")
                app.tts.speak("Time ho gaya boss! Timer complete!", Emotion.EXCITED)
            }
            "ALARM" -> {
                showAlarm(context, "Alarm!", "Uth jao boss!")
                app.tts.speak("Good morning boss! Uth jao, kaam ka din hai!", Emotion.EXCITED)
            }
            "REMINDER" -> {
                val text = intent.getStringExtra("extra") ?: "Reminder"
                showAlarm(context, "Reminder", text)
                app.tts.speak("Reminder boss: $text", Emotion.NEUTRAL)
            }
            "ROUTINE" -> {
                val action = intent.getStringExtra("extra") ?: return
                val pending = goAsync()
                Thread {
                    try {
                        runBlocking { app.brain.processCommandText(action) }
                    } catch (_: Exception) {}
                    pending.finish()
                }.start()
            }
        }
    }

    private fun showAlarm(context: Context, title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(
            (System.currentTimeMillis() % 100000).toInt(),
            NotificationHelper.alarmNotification(context, title, body)
        )
    }
}

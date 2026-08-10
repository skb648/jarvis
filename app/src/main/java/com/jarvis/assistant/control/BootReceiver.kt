package com.jarvis.assistant.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/** Re-arms daily routines after a reboot. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        Thread {
            try {
                val app = context.applicationContext as JarvisApp
                val routines = runBlocking { app.settings.settings.first().routines }
                TimerController(context).rescheduleRoutines(routines)
            } catch (_: Exception) {}
            pending.finish()
        }.start()
    }
}

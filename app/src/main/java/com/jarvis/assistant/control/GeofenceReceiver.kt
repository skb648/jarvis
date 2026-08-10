package com.jarvis.assistant.control

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import android.app.NotificationManager
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.LocationServices
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.core.NotificationHelper
import com.jarvis.assistant.model.Emotion

/**
 * Fires when the user arrives home — speaks the geofence reminder.
 */
class GeofenceReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_ENTER) return
        val text = intent.getStringExtra("text") ?: return
        val id = intent.getStringExtra("id") ?: return
        val app = context.applicationContext as JarvisApp

        // cleanup: remove the one-shot geofence
        val pending = goAsync()
        Thread {
            runCatching {
                val client = LocationServices.getGeofencingClient(context)
                com.google.android.gms.tasks.Tasks.await(client.removeGeofences(listOf(id)))
            }
            pending.finish()
        }.start()

        app.tts.speak("Boss, ghar pahunch gaye! Yaad karo — $text", Emotion.HAPPY)

        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(
            (System.currentTimeMillis() % 100000).toInt(),
            NotificationHelper.commandNotification(context, "🏠 Ghar pahunch gaye!", text)
        )
    }
}

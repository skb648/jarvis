package com.jarvis.assistant.control

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.Manifest
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Location reminders — "jab ghar pahunchu to paani lena".
 * Creates a geofence around the user's current location (treated as "home")
 * and fires when they arrive back.
 */
class GeofenceController(private val context: Context) {

    private val geofencingClient: GeofencingClient = LocationServices.getGeofencingClient(context)

    suspend fun addHomeReminder(text: String): String = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext "Location reminder ke liye Location permission chahiye."
        }
        val location = runCatching {
            Tasks.await(LocationServices.getFusedLocationProviderClient(context).lastLocation)
        }.getOrNull()

        if (location == null) {
            return@withContext "Location nahi mil rahi — GPS on karke try karo."
        }
        val requestId = "jarvis_home_${System.currentTimeMillis() % 1000000}"
        val geofence = Geofence.Builder()
            .setRequestId(requestId)
            .setCircularRegion(location.latitude, location.longitude, 300f)
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
            .build()

        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofence(geofence)
            .build()

        val pi = android.app.PendingIntent.getBroadcast(
            context,
            (requestId.hashCode() and 0x7fffffff) % 100000,
            Intent(context, GeofenceReceiver::class.java)
                .putExtra("text", text)
                .putExtra("id", requestId),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val ok = runCatching { Tasks.await(geofencingClient.addGeofences(request, pi)); true }
            .getOrDefault(false)
        if (!ok) {
            "Geofence set nahi ho paya — location permission/GPS check karo."
        } else {
            ""
        }
    }
}

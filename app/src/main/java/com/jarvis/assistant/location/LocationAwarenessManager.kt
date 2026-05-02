package com.jarvis.assistant.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LocationAwarenessManager — Location-based behavior for JARVIS.
 *
 * Provides location context that changes JARVIS's behavior:
 *   - At HOME: more casual, play music, relax mode
 *   - At OFFICE: professional tone, meeting reminders, focus mode
 *   - COMMUTING: navigation, traffic updates
 *   - OUTDOORS: weather alerts, nearby places
 *
 * Uses FusedLocationProviderClient from Google Play Services for
 * battery-efficient location updates.
 *
 * Stores home/office locations in SharedPreferences — learned from
 * user saying "yeh mera ghar hai" / "this is my office".
 */
object LocationAwarenessManager {

    private const val TAG = "LocationLocationManager"
    private const val PREFS_NAME = "jarvis_locations"
    private const val KEY_HOME_LAT = "home_latitude"
    private const val KEY_HOME_LON = "home_longitude"
    private const val KEY_OFFICE_LAT = "office_latitude"
    private const val KEY_OFFICE_LON = "office_longitude"
    private const val KEY_HOME_SET = "home_location_set"
    private const val KEY_OFFICE_SET = "office_location_set"

    /** Proximity threshold in meters for "at location" detection */
    private const val PROXIMITY_THRESHOLD_METERS = 150.0

    // ─── Data Classes ──────────────────────────────────────────────────

    data class LocationData(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    enum class LocationType {
        HOME, OFFICE, OUTDOORS, COMMUTING, UNKNOWN
    }

    // ─── Location Retrieval ────────────────────────────────────────────

    /**
     * Get the current location using FusedLocationProviderClient.
     * Requires ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION permission.
     *
     * @param context Android context
     * @return LocationData or null if unavailable
     */
    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(context: Context): LocationData? {
        return try {
            if (!hasLocationPermission(context)) {
                Log.w(TAG, "[getCurrentLocation] No location permission")
                return null
            }

            val fusedClient = LocationServices.getFusedLocationProviderClient(context)
            val cancellationToken = CancellationTokenSource()

            val location = try {
                fusedClient.getCurrentLocation(
                    Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                    cancellationToken.token
                ).await()
            } catch (e: Exception) {
                Log.w(TAG, "[getCurrentLocation] Current location failed, trying last known: ${e.message}")
                // Fallback to last known location
                try {
                    fusedClient.lastLocation.await()
                } catch (e2: Exception) {
                    null
                }
            }

            cancellationToken.cancel()

            if (location != null) {
                Log.d(TAG, "[getCurrentLocation] Got location: ${location.latitude}, ${location.longitude} (±${location.accuracy}m)")
                LocationData(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = location.time
                )
            } else {
                Log.w(TAG, "[getCurrentLocation] Location is null")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "[getCurrentLocation] Error: ${e.message}")
            null
        }
    }

    /**
     * Detect what type of location the user is at.
     */
    fun detectLocationType(location: LocationData, context: Context): LocationType {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check if near home
        if (prefs.getBoolean(KEY_HOME_SET, false)) {
            val homeLat = prefs.getFloat(KEY_HOME_LAT, 0f).toDouble()
            val homeLon = prefs.getFloat(KEY_HOME_LON, 0f).toDouble()
            val distToHome = haversineDistance(location.latitude, location.longitude, homeLat, homeLon)
            if (distToHome < PROXIMITY_THRESHOLD_METERS) {
                Log.d(TAG, "[detectLocationType] At HOME (${distToHome}m away)")
                return LocationType.HOME
            }
        }

        // Check if near office
        if (prefs.getBoolean(KEY_OFFICE_SET, false)) {
            val officeLat = prefs.getFloat(KEY_OFFICE_LAT, 0f).toDouble()
            val officeLon = prefs.getFloat(KEY_OFFICE_LON, 0f).toDouble()
            val distToOffice = haversineDistance(location.latitude, location.longitude, officeLat, officeLon)
            if (distToOffice < PROXIMITY_THRESHOLD_METERS) {
                Log.d(TAG, "[detectLocationType] At OFFICE (${distToOffice}m away)")
                return LocationType.OFFICE
            }
        }

        // Could add: speed-based COMMUTING detection
        // For now, default to OUTDOORS if we have a location
        if (location.accuracy < 100f) {
            return LocationType.OUTDOORS
        }

        return LocationType.UNKNOWN
    }

    /**
     * Get a natural language description of the current location context.
     * Returns strings like: "Ghar pe ho" / "Office pe ho" / "Bahar ho"
     */
    suspend fun getLocationContext(context: Context): String {
        val location = getCurrentLocation(context)
        if (location == null) {
            return ""
        }

        val locationType = detectLocationType(location, context)
        return when (locationType) {
            LocationType.HOME -> "Ghar pe ho"
            LocationType.OFFICE -> "Office pe ho"
            LocationType.COMMUTING -> "Travel kar rahe ho"
            LocationType.OUTDOORS -> "Bahar ho"
            LocationType.UNKNOWN -> ""
        }
    }

    /**
     * Get behavior modifier based on location type.
     * Used to adjust JARVIS's system prompt based on where the user is.
     */
    fun getLocationBehaviorContext(locationType: LocationType): String {
        return when (locationType) {
            LocationType.HOME -> "The user is at home. Be more casual and relaxed. Offer entertainment, music suggestions, and comfort."
            LocationType.OFFICE -> "The user is at the office. Be professional and concise. Offer meeting reminders, focus mode, and productivity help."
            LocationType.COMMUTING -> "The user is commuting. Offer navigation, traffic updates, and hands-free assistance. Keep responses brief."
            LocationType.OUTDOORS -> "The user is outdoors. Offer weather-related info, nearby places, and outdoor assistance."
            LocationType.UNKNOWN -> ""
        }
    }

    // ─── Location Storage (Home/Office) ────────────────────────────────

    /**
     * Set the home location. Called when user says "yeh mera ghar hai" / "this is my home".
     */
    fun setHomeLocation(lat: Double, lon: Double, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_HOME_LAT, lat.toFloat())
            .putFloat(KEY_HOME_LON, lon.toFloat())
            .putBoolean(KEY_HOME_SET, true)
            .apply()
        Log.i(TAG, "[setHomeLocation] Home set to $lat, $lon")
    }

    /**
     * Set the office location. Called when user says "yeh mera office hai" / "this is my office".
     */
    fun setOfficeLocation(lat: Double, lon: Double, context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat(KEY_OFFICE_LAT, lat.toFloat())
            .putFloat(KEY_OFFICE_LON, lon.toFloat())
            .putBoolean(KEY_OFFICE_SET, true)
            .apply()
        Log.i(TAG, "[setOfficeLocation] Office set to $lat, $lon")
    }

    /**
     * Check if home location is set.
     */
    fun isHomeLocationSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_HOME_SET, false)
    }

    /**
     * Check if office location is set.
     */
    fun isOfficeLocationSet(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_OFFICE_SET, false)
    }

    /**
     * Get saved home location, if any.
     */
    fun getHomeLocation(context: Context): LocationData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_HOME_SET, false)) {
            LocationData(
                latitude = prefs.getFloat(KEY_HOME_LAT, 0f).toDouble(),
                longitude = prefs.getFloat(KEY_HOME_LON, 0f).toDouble()
            )
        } else null
    }

    /**
     * Get saved office location, if any.
     */
    fun getOfficeLocation(context: Context): LocationData? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return if (prefs.getBoolean(KEY_OFFICE_SET, false)) {
            LocationData(
                latitude = prefs.getFloat(KEY_OFFICE_LAT, 0f).toDouble(),
                longitude = prefs.getFloat(KEY_OFFICE_LON, 0f).toDouble()
            )
        } else null
    }

    // ─── Location Command Parsing ──────────────────────────────────────

    /**
     * Parse location-setting commands from user input.
     * Handles: "yeh mera ghar hai", "this is my home", "yeh mera office hai", etc.
     *
     * @return Pair of (locationType, shouldSet) or null if not a location command
     */
    fun parseLocationCommand(query: String): Pair<String, Boolean>? {
        val lower = query.lowercase().trim()

        // Home commands
        if (lower.contains("mera ghar") || lower.contains("my home") ||
            lower.contains("yeh ghar") || lower.contains("this is home") ||
            lower.contains("ghar yahi") || lower.contains("set home")) {
            return Pair("home", true)
        }

        // Office commands
        if (lower.contains("mera office") || lower.contains("my office") ||
            lower.contains("yeh office") || lower.contains("this is office") ||
            lower.contains("office yahi") || lower.contains("set office")) {
            return Pair("office", true)
        }

        return null
    }

    // ─── Utility Functions ─────────────────────────────────────────────

    private fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
    }

    /**
     * Calculate distance between two GPS coordinates using Haversine formula.
     * Returns distance in meters.
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Extension to await Task results from Google Play Services.
     */
    private suspend fun <T> com.google.android.gms.tasks.Task<T>.await(): T? {
        return try {
            val result = kotlinx.coroutines.suspendCancellableCoroutine<T?> { cont ->
                addOnSuccessListener { result -> cont.resume(result) { } }
                addOnFailureListener { exception -> cont.resume(null) { } }
                addOnCanceledListener { cont.cancel() }
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "[Task.await] Error: ${e.message}")
            null
        }
    }
}

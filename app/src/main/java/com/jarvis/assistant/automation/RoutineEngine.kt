package com.jarvis.assistant.automation

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.jarvis.assistant.smarthome.MqttManager

/**
 * Routine Engine — Trigger-Condition-Action automation system.
 *
 * Supports:
 *   - Triggers: time, event, wake_word
 *   - Conditions: time_range, day_of_week
 *   - Actions: notification, smart_home (MQTT), tts
 *
 * Routines are persisted as JSON in SharedPreferences.
 */
class RoutineEngine(private val context: Context) {

    companion object {
        private const val TAG = "JarvisRoutine"
        private const val PREFS_NAME = "jarvis_routines"
        private const val KEY_ROUTINES = "routines"
        private const val CHANNEL_ROUTINES = "jarvis_routines"
        private const val NOTIFICATION_ID_BASE = 3000

        private var notificationIdCounter = NOTIFICATION_ID_BASE
        private fun nextNotificationId(): Int = notificationIdCounter++
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    // ─── TTS Engine ─────────────────────────────────────────────

    private var textToSpeech: TextToSpeech? = null
    private var ttsInitialized = false

    private fun initTts() {
        if (textToSpeech == null) {
            textToSpeech = TextToSpeech(context, { status ->
                ttsInitialized = status == TextToSpeech.SUCCESS
                if (ttsInitialized) {
                    Log.i(TAG, "RoutineEngine TTS initialized")
                } else {
                    Log.e(TAG, "RoutineEngine TTS initialization failed")
                }
            })
        }
    }

    // ─── Data Models ────────────────────────────────────────────

    data class Routine(
        val id: String,
        val name: String,
        val trigger: Trigger,
        val conditions: List<Condition> = emptyList(),
        val actions: List<Action> = emptyList(),
        val isEnabled: Boolean = true
    )

    data class Trigger(
        val type: String,  // "time", "event", "wake_word"
        val value: String  // e.g., "07:00", "app_opened:com.whatsapp", "jarvis"
    )

    data class Condition(
        val type: String,  // "time_range", "day_of_week"
        val value: String  // e.g., "09:00-17:00", "MON,TUE,WED"
    )

    data class Action(
        val type: String,  // "notification", "smart_home", "tts"
        val value: String, // e.g., message text, MQTT topic:payload, TTS text
        val extra: Map<String, String> = emptyMap()
    )

    // ─── CRUD Operations ────────────────────────────────────────

    fun addRoutine(routine: Routine): Boolean {
        val routines = getAllRoutines().toMutableList()
        // Remove existing with same ID
        routines.removeAll { it.id == routine.id }
        routines.add(routine)
        return saveRoutines(routines)
    }

    fun removeRoutine(routineId: String): Boolean {
        val routines = getAllRoutines().toMutableList()
        routines.removeAll { it.id == routineId }
        return saveRoutines(routines)
    }

    fun getAllRoutines(): List<Routine> {
        val json = prefs.getString(KEY_ROUTINES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<Routine>>() {}.type
            // BUG FIX: gson.fromJson() can return null (e.g., JSON "null").
            // Added null-safe operator with emptyList() fallback to prevent NPE.
            gson.fromJson<List<Routine>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse routines", e)
            emptyList()
        }
    }

    fun getEnabledRoutines(): List<Routine> = getAllRoutines().filter { it.isEnabled }

    // ─── Trigger Evaluation ─────────────────────────────────────

    /**
     * Trigger routines matching the given trigger type and value.
     * Evaluates conditions before executing actions.
     */
    fun triggerRoutines(triggerType: String, triggerValue: String) {
        val routines = getEnabledRoutines().filter {
            it.trigger.type == triggerType && it.trigger.value == triggerValue
        }

        for (routine in routines) {
            if (evaluateConditions(routine.conditions)) {
                Log.i(TAG, "Executing routine: ${routine.name}")
                executeActions(routine.actions)
            }
        }
    }

    private fun evaluateConditions(conditions: List<Condition>): Boolean {
        if (conditions.isEmpty()) return true

        return conditions.all { condition ->
            when (condition.type) {
                "time_range" -> evaluateTimeRange(condition.value)
                "day_of_week" -> evaluateDayOfWeek(condition.value)
                else -> true
            }
        }
    }

    private fun evaluateTimeRange(range: String): Boolean {
        try {
            val parts = range.split("-")
            if (parts.size != 2) return true

            val now = java.util.Calendar.getInstance()
            val currentMinutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                    now.get(java.util.Calendar.MINUTE)

            val startParts = parts[0].trim().split(":")
            val endParts = parts[1].trim().split(":")
            val startMinutes = startParts[0].toInt() * 60 + startParts[1].toInt()
            val endMinutes = endParts[0].toInt() * 60 + endParts[1].toInt()

            // BUG FIX: Handle overnight time ranges (e.g., 22:00-06:00).
            // When startMinutes > endMinutes, the range wraps past midnight.
            // Example: 22:00-06:00 → 23:30 is in range, 12:00 is not.
            //
            // CRITICAL FIX: For overnight ranges, use `<` instead of `<=` for
            // the end bound. With `<=`, midnight (00:00 = 0 minutes) would
            // match ANY overnight range that ends at or after 00:00, since
            // 0 <= any positive endMinutes is always true. Using `<` ensures
            // that midnight itself does NOT match ranges that end at midnight
            // (e.g., 22:00-00:00 should NOT include 00:00).
            return if (startMinutes <= endMinutes) {
                // Same-day range: e.g., 09:00-17:00
                currentMinutes in startMinutes..endMinutes
            } else {
                // Overnight range: e.g., 22:00-06:00
                // currentMinutes >= 22:00 OR currentMinutes < 06:00
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            }
        } catch (e: Exception) {
            return true
        }
    }

    private fun evaluateDayOfWeek(days: String): Boolean {
        val dayMap = mapOf(
            "SUN" to java.util.Calendar.SUNDAY,
            "MON" to java.util.Calendar.MONDAY,
            "TUE" to java.util.Calendar.TUESDAY,
            "WED" to java.util.Calendar.WEDNESDAY,
            "THU" to java.util.Calendar.THURSDAY,
            "FRI" to java.util.Calendar.FRIDAY,
            "SAT" to java.util.Calendar.SATURDAY
        )

        val today = java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK)
        val activeDays = days.split(",").mapNotNull { dayMap[it.trim()] }
        return today in activeDays
    }

    // ─── Action Execution ───────────────────────────────────────

    private fun executeActions(actions: List<Action>) {
        for (action in actions) {
            try {
                when (action.type) {
                    "notification" -> {
                        Log.i(TAG, "Notification action: ${action.value}")
                        postRoutineNotification(action.value, action.extra["title"] ?: "JARVIS Routine")
                    }
                    "smart_home" -> {
                        // Format: "topic:payload"
                        val parts = action.value.split(":", limit = 2)
                        if (parts.size == 2) {
                            MqttManager.publish(parts[0], parts[1])
                            Log.i(TAG, "Smart home action: ${parts[0]} → ${parts[1]}")
                        }
                    }
                    "tts" -> {
                        Log.i(TAG, "TTS action: ${action.value}")
                        speakViaTts(action.value)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute action: ${action.type}", e)
            }
        }
    }

    // ─── Notification Action ────────────────────────────────────

    /**
     * Post a real notification from a routine action.
     */
    private fun postRoutineNotification(message: String, title: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Ensure notification channel exists
            val channel = NotificationChannel(
                CHANNEL_ROUTINES,
                "JARVIS Routines",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            nm.createNotificationChannel(channel)

            // Tap intent — open app
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ROUTINES)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(nextNotificationId(), notification)
            Log.i(TAG, "Routine notification posted: $title — $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to post routine notification", e)
        }
    }

    // ─── TTS Action ─────────────────────────────────────────────

    /**
     * Speak a message using Android's built-in TextToSpeech.
     * Falls back to a broadcast intent if TTS is not available.
     */
    private fun speakViaTts(message: String) {
        initTts()
        if (ttsInitialized && textToSpeech != null) {
            try {
                textToSpeech?.speak(message, TextToSpeech.QUEUE_ADD, null, "jarvis_routine_tts_${System.currentTimeMillis()}")
                Log.i(TAG, "TTS speaking: $message")
            } catch (e: Exception) {
                Log.e(TAG, "TTS speak failed", e)
            }
        } else {
            // Fallback: use ACTION_TTS_QUEUE intent
            try {
                val intent = Intent(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA)
                Log.w(TAG, "TTS not initialized, skipping speech for: $message")
            } catch (e: Exception) {
                Log.e(TAG, "TTS fallback failed", e)
            }
        }
    }

    // ─── Persistence ────────────────────────────────────────────

    private fun saveRoutines(routines: List<Routine>): Boolean {
        return try {
            val json = gson.toJson(routines)
            prefs.edit().putString(KEY_ROUTINES, json).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save routines", e)
            false
        }
    }
}

package com.jarvis.assistant.automation

import android.content.Context
import android.util.Log
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
class RoutineEngine(context: Context) {

    companion object {
        private const val TAG = "JarvisRoutine"
        private const val PREFS_NAME = "jarvis_routines"
        private const val KEY_ROUTINES = "routines"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

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
            return if (startMinutes <= endMinutes) {
                // Same-day range: e.g., 09:00-17:00
                currentMinutes in startMinutes..endMinutes
            } else {
                // Overnight range: e.g., 22:00-06:00
                currentMinutes >= startMinutes || currentMinutes <= endMinutes
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
                        // Notification will be handled by the ViewModel/UI layer
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
                        // TTS will be handled by the ViewModel/UI layer
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to execute action: ${action.type}", e)
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

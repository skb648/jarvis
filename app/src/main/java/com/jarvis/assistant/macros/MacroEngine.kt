package com.jarvis.assistant.macros

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.shizuku.ShizukuManager

/**
 * MacroEngine — Voice-activated shortcut macros for JARVIS.
 *
 * Predefined macros allow the user to switch "modes" with a single
 * voice command, executing multiple device actions in sequence:
 *
 *   - "Office mode" → WiFi ON, silent, calendar open, emails check
 *   - "Home mode"   → WiFi ON, volume up, music app
 *   - "Night mode"  → DND on, brightness low, alarm set
 *   - "Gaming mode" → DND on, brightness high, performance mode
 *   - "Meeting mode"→ Silent, calendar open, notes app
 *   - "Drive mode"  → Bluetooth on, maps open, DND except calls
 *
 * Users can also create custom macros via:
 *   "create macro [name] that does [actions]"
 *
 * Custom macros are persisted in SharedPreferences as JSON.
 */
object MacroEngine {

    private const val TAG = "MacroEngine"
    private const val PREFS_NAME = "jarvis_macros"
    private const val CUSTOM_MACROS_KEY = "custom_macros"

    // ─── Data Classes ──────────────────────────────────────────────────

    enum class MacroActionType {
        SET_WIFI,        // Enable/disable WiFi
        SET_BLUETOOTH,   // Enable/disable Bluetooth
        SET_SILENT,      // Set ringer to silent/vibrate
        SET_VOLUME,      // Set media volume level
        OPEN_APP,        // Open an app by package name
        SET_BRIGHTNESS,  // Set screen brightness
        SET_DND,         // Enable Do Not Disturb
        OPEN_CALENDAR,   // Open calendar app
        OPEN_MAPS,       // Open maps/navigation
        SET_ALARM        // Set an alarm
    }

    data class MacroAction(
        val type: MacroActionType,
        val value: String = "",      // e.g., package name, volume level
        val enabled: Boolean = true  // ON or OFF for toggles
    )

    data class MacroDefinition(
        val name: String,
        val triggerPhrases: List<String>,
        val actions: List<MacroAction>,
        val description: String = ""
    )

    // ─── Predefined Macros ─────────────────────────────────────────────

    private val predefinedMacros = listOf(
        MacroDefinition(
            name = "Office Mode",
            triggerPhrases = listOf("office mode", "office mode on", "kaam mode", "kaam shuru"),
            description = "WiFi ON, silent mode, calendar open, emails check",
            actions = listOf(
                MacroAction(MacroActionType.SET_WIFI, enabled = true),
                MacroAction(MacroActionType.SET_SILENT, enabled = true),
                MacroAction(MacroActionType.OPEN_CALENDAR),
                MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.gm")
            )
        ),
        MacroDefinition(
            name = "Home Mode",
            triggerPhrases = listOf("home mode", "home mode on", "ghar mode", "ghar mode on"),
            description = "WiFi ON, volume up, music app",
            actions = listOf(
                MacroAction(MacroActionType.SET_WIFI, enabled = true),
                MacroAction(MacroActionType.SET_VOLUME, value = "70"),
                MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.apps.youtube.music")
            )
        ),
        MacroDefinition(
            name = "Night Mode",
            triggerPhrases = listOf("night mode", "goodnight", "shubhratri", "good night", "so ja"),
            description = "DND on, brightness low, alarm set for morning",
            actions = listOf(
                MacroAction(MacroActionType.SET_DND, enabled = true),
                MacroAction(MacroActionType.SET_BRIGHTNESS, value = "30"),
                MacroAction(MacroActionType.SET_ALARM)
            )
        ),
        MacroDefinition(
            name = "Gaming Mode",
            triggerPhrases = listOf("gaming mode", "game mode", "game shuru"),
            description = "DND on, brightness high, performance mode",
            actions = listOf(
                MacroAction(MacroActionType.SET_DND, enabled = true),
                MacroAction(MacroActionType.SET_BRIGHTNESS, value = "230"),
                MacroAction(MacroActionType.SET_VOLUME, value = "80")
            )
        ),
        MacroDefinition(
            name = "Meeting Mode",
            triggerPhrases = listOf("meeting mode", "meeting mein", "meeting start"),
            description = "Silent, calendar open, notes app",
            actions = listOf(
                MacroAction(MacroActionType.SET_SILENT, enabled = true),
                MacroAction(MacroActionType.OPEN_CALENDAR),
                MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.keep")
            )
        ),
        MacroDefinition(
            name = "Drive Mode",
            triggerPhrases = listOf("drive mode", "driving", "gaadi chala rahe", "driving mode", "chalo chalte"),
            description = "Bluetooth on, maps open, DND except calls",
            actions = listOf(
                MacroAction(MacroActionType.SET_BLUETOOTH, enabled = true),
                MacroAction(MacroActionType.OPEN_MAPS),
                MacroAction(MacroActionType.SET_DND, enabled = true)
            )
        )
    )

    // ─── Public API ────────────────────────────────────────────────────

    /**
     * Get all available macros (predefined + custom).
     */
    fun getAvailableMacros(context: Context): List<MacroDefinition> {
        val custom = loadCustomMacros(context)
        return predefinedMacros + custom
    }

    /**
     * Check if a query matches any macro trigger phrase.
     * Returns the matching MacroDefinition, or null if no match.
     */
    fun matchMacro(query: String, context: Context): MacroDefinition? {
        val normalized = query.lowercase().trim()
        val allMacros = getAvailableMacros(context)

        for (macro in allMacros) {
            for (phrase in macro.triggerPhrases) {
                if (normalized == phrase || normalized.contains(phrase)) {
                    Log.i(TAG, "[matchMacro] Matched macro '${macro.name}' with phrase '$phrase'")
                    return macro
                }
            }
        }
        return null
    }

    /**
     * Execute a macro by name. Runs all actions in sequence.
     *
     * @param name The macro name (e.g., "Office Mode")
     * @param context Android context for executing actions
     * @return Human-readable result string describing what was done
     */
    fun executeMacro(name: String, context: Context): String {
        val allMacros = getAvailableMacros(context)
        val macro = allMacros.find {
            it.name.equals(name, ignoreCase = true)
        }

        if (macro == null) {
            Log.w(TAG, "[executeMacro] Macro '$name' not found")
            return "Sir, I couldn't find a macro called '$name'."
        }

        Log.i(TAG, "[executeMacro] Executing macro '${macro.name}' with ${macro.actions.size} actions")
        val results = mutableListOf<String>()

        for (action in macro.actions) {
            val result = executeAction(action, context)
            results.add(result)
        }

        val summary = "${macro.name} activated, Sir. " + results.joinToString(". ")
        Log.i(TAG, "[executeMacro] Macro '${macro.name}' complete: $summary")
        return summary
    }

    /**
     * Create a custom macro from a natural language description.
     * Parses: "create macro [name] that does [actions]"
     *
     * @param input The raw user input
     * @param context Android context for persistence
     * @return Result message
     */
    fun createCustomMacro(input: String, context: Context): String {
        // Parse: "create macro [name] that does [actions]"
        val regex = Regex("""(?:create|make|add)\s+macro\s+(\w+(?:\s+\w+)?)\s+(?:that\s+)?(?:does|with|to)\s+(.+)""", RegexOption.IGNORE_CASE)
        val match = regex.find(input)

        if (match == null) {
            return "Sir, I couldn't parse that macro. Say: 'create macro [name] that does [actions]'."
        }

        val name = match.groupValues[1].trim().replaceFirstChar { it.uppercase() }
        val actionsDesc = match.groupValues[2].trim()

        // Parse actions from natural language
        val actions = parseActionsFromText(actionsDesc)
        if (actions.isEmpty()) {
            return "Sir, I couldn't understand the actions for macro '$name'. Try: wifi on, silent, open calendar."
        }

        val macro = MacroDefinition(
            name = "$name (Custom)",
            triggerPhrases = listOf(name.lowercase(), "${name.lowercase()} mode"),
            actions = actions,
            description = "Custom macro: $actionsDesc"
        )

        saveCustomMacro(macro, context)
        Log.i(TAG, "[createCustomMacro] Created macro '$name' with ${actions.size} actions")
        return "Macro '$name' created with ${actions.size} actions, Sir. Say '$name' to activate it."
    }

    // ─── Action Execution ──────────────────────────────────────────────

    private fun executeAction(action: MacroAction, context: Context): String {
        return when (action.type) {
            MacroActionType.SET_WIFI -> {
                val success = setWifi(action.enabled, context)
                "WiFi ${if (action.enabled) "ON" else "OFF"}: ${if (success) "done" else "failed"}"
            }
            MacroActionType.SET_BLUETOOTH -> {
                val success = setBluetooth(action.enabled, context)
                "Bluetooth ${if (action.enabled) "ON" else "OFF"}: ${if (success) "done" else "failed"}"
            }
            MacroActionType.SET_SILENT -> {
                val success = setSilentMode(context)
                "Silent mode: ${if (success) "enabled" else "failed"}"
            }
            MacroActionType.SET_VOLUME -> {
                val level = action.value.toIntOrNull() ?: 50
                setVolume(level, context)
                "Volume set to $level%"
            }
            MacroActionType.OPEN_APP -> {
                val success = openApp(action.value, context)
                "Open ${action.value}: ${if (success) "done" else "failed"}"
            }
            MacroActionType.SET_BRIGHTNESS -> {
                val level = action.value.toIntOrNull() ?: 128
                val success = setBrightness(level, context)
                "Brightness set to $level: ${if (success) "done" else "failed"}"
            }
            MacroActionType.SET_DND -> {
                val success = setDnd(context)
                "Do Not Disturb: ${if (success) "enabled" else "failed"}"
            }
            MacroActionType.OPEN_CALENDAR -> {
                val success = openCalendar(context)
                "Calendar: ${if (success) "opened" else "failed"}"
            }
            MacroActionType.OPEN_MAPS -> {
                val success = openMaps(context)
                "Maps: ${if (success) "opened" else "failed"}"
            }
            MacroActionType.SET_ALARM -> {
                openAlarmApp(context)
                "Alarm app opened — please set your morning alarm"
            }
        }
    }

    // ─── Action Implementations ────────────────────────────────────────

    /**
     * Toggle WiFi on/off.
     *
     * Uses Shizuku exclusively for toggling. On Android 10+ (API 29),
     * WifiManager.isWifiEnabled is deprecated and no longer works for third-party apps.
     * If Shizuku is unavailable, opens WiFi settings as a fallback so the user can
     * toggle manually — this is the correct behavior per Android guidelines.
     */
    private fun setWifi(enable: Boolean, context: Context): Boolean {
        return try {
            // Try Shizuku first (can actually toggle WiFi programmatically)
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.toggleWifi(enable)
                if (result.isSuccess) return true
            }
            // Fallback: open WiFi settings so the user can toggle manually.
            // Cannot toggle WiFi without Shizuku on Android 10+.
            Log.w(TAG, "[setWifi] Shizuku unavailable — opening WiFi settings for manual toggle")
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            false
        } catch (e: Exception) {
            Log.e(TAG, "[setWifi] Error: ${e.message}")
            false
        }
    }

    /**
     * Toggle Bluetooth on/off.
     *
     * Uses Shizuku exclusively for toggling. On Android 13+ (API 33),
     * BluetoothAdapter.enable()/disable() is deprecated and requires
     * BLUETOOTH_PRIVILEGED permission unavailable to third-party apps.
     * If Shizuku is unavailable, opens Bluetooth settings as a fallback.
     */
    private fun setBluetooth(enable: Boolean, context: Context): Boolean {
        return try {
            // Try Shizuku first (can actually toggle Bluetooth programmatically)
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.toggleBluetooth(enable)
                if (result.isSuccess) return true
            }
            // Fallback: open Bluetooth settings so the user can toggle manually.
            // Cannot toggle Bluetooth without Shizuku on Android 13+.
            Log.w(TAG, "[setBluetooth] Shizuku unavailable — opening Bluetooth settings for manual toggle")
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            false
        } catch (e: Exception) {
            Log.e(TAG, "[setBluetooth] Error: ${e.message}")
            false
        }
    }

    private fun setSilentMode(context: Context): Boolean {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                // Use Shizuku for DND access on Android 7+
                ShizukuManager.executeShellCommand("settings put global zen_mode 1")
                true
            } else {
                audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "[setSilentMode] Error: ${e.message}")
            false
        }
    }

    private fun setVolume(levelPercent: Int, context: Context) {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVolume = (maxVolume * levelPercent / 100).coerceIn(0, maxVolume)

            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                ShizukuManager.executeShellCommand("media volume --stream 3 --set $targetVolume")
            } else {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVolume, 0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[setVolume] Error: ${e.message}")
        }
    }

    private fun openApp(packageName: String, context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else {
                Log.w(TAG, "[openApp] App not found: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[openApp] Error: ${e.message}")
            false
        }
    }

    private fun setBrightness(level: Int, context: Context): Boolean {
        return try {
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                true
            } else if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.setSystemSetting("system", "screen_brightness", level.toString())
                result.isSuccess
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[setBrightness] Error: ${e.message}")
            false
        }
    }

    private fun setDnd(context: Context): Boolean {
        return try {
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.executeShellCommand("settings put global zen_mode 1")
                result.isSuccess
            } else {
                // Try NotificationManager (requires ACCESS_NOTIFICATION_POLICY permission)
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    true
                } else {
                    Log.w(TAG, "[setDnd] No notification policy access")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "[setDnd] Error: ${e.message}")
            false
        }
    }

    private fun openCalendar(context: Context): Boolean {
        return try {
            val startMillis = System.currentTimeMillis()
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = CalendarContract.Events.CONTENT_URI
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            // Fallback: try generic calendar intent
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName("com.google.android.calendar", "com.android.calendar.AllInOneActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            } catch (e2: Exception) {
                Log.e(TAG, "[openCalendar] Error: ${e2.message}")
                false
            }
        }
    }

    private fun openMaps(context: Context): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("google.navigation://")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.google.android.apps.maps")
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[openMaps] Error: ${e.message}")
            false
        }
    }

    private fun openAlarmApp(context: Context) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Set for 7:00 AM tomorrow as default
                putExtra(AlarmClock.EXTRA_HOUR, 7)
                putExtra(AlarmClock.EXTRA_MINUTES, 0)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Morning alarm (JARVIS)")
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "[openAlarmApp] Error: ${e.message}")
            // Fallback: just open the clock app
            try {
                val intent = Intent(Intent.ACTION_MAIN).apply {
                    setPackage("com.google.android.deskclock")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }

    // ─── Custom Macro Persistence ──────────────────────────────────────

    private fun saveCustomMacro(macro: MacroDefinition, context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = loadCustomMacroJsonList(prefs)
            existing.add(macroToMap(macro))
            prefs.edit()
                .putString(CUSTOM_MACROS_KEY, com.google.gson.Gson().toJson(existing))
                .apply()
            Log.i(TAG, "[saveCustomMacro] Saved macro '${macro.name}'")
        } catch (e: Exception) {
            Log.e(TAG, "[saveCustomMacro] Error: ${e.message}")
        }
    }

    private fun loadCustomMacros(context: Context): List<MacroDefinition> {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonList = loadCustomMacroJsonList(prefs)
            jsonList.mapNotNull { mapToMacro(it) }
        } catch (e: Exception) {
            Log.e(TAG, "[loadCustomMacros] Error: ${e.message}")
            emptyList()
        }
    }

    private fun loadCustomMacroJsonList(prefs: android.content.SharedPreferences): MutableList<Map<String, Any>> {
        val json = prefs.getString(CUSTOM_MACROS_KEY, "[]") ?: "[]"
        return try {
            val type = object : com.google.gson.reflect.TypeToken<List<Map<String, Any>>>() {}.type
            com.google.gson.Gson().fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun macroToMap(macro: MacroDefinition): Map<String, Any> {
        return mapOf(
            "name" to macro.name,
            "triggerPhrases" to macro.triggerPhrases,
            "description" to macro.description,
            "actions" to macro.actions.map { mapOf("type" to it.type.name, "value" to it.value, "enabled" to it.enabled) }
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToMacro(map: Map<String, Any>): MacroDefinition? {
        return try {
            val name = map["name"] as? String ?: return null
            val phrases = (map["triggerPhrases"] as? List<String>) ?: listOf(name.lowercase())
            val desc = map["description"] as? String ?: ""
            val actionMaps = map["actions"] as? List<Map<String, Any>> ?: return null
            val actions = actionMaps.mapNotNull { am ->
                val typeName = am["type"] as? String ?: return@mapNotNull null
                val value = am["value"] as? String ?: ""
                val enabled = am["enabled"] as? Boolean ?: true
                MacroAction(MacroActionType.valueOf(typeName), value, enabled)
            }
            MacroDefinition(name, phrases, actions, desc)
        } catch (e: Exception) {
            Log.e(TAG, "[mapToMacro] Error: ${e.message}")
            null
        }
    }

    // ─── Natural Language Action Parsing ───────────────────────────────

    /**
     * Parse actions from natural language text.
     * Example: "wifi on, silent mode, open calendar"
     */
    private fun parseActionsFromText(text: String): List<MacroAction> {
        val actions = mutableListOf<MacroAction>()
        val lower = text.lowercase()

        // WiFi
        if (lower.contains("wifi on") || lower.contains("wi-fi on")) {
            actions.add(MacroAction(MacroActionType.SET_WIFI, enabled = true))
        } else if (lower.contains("wifi off") || lower.contains("wi-fi off")) {
            actions.add(MacroAction(MacroActionType.SET_WIFI, enabled = false))
        }

        // Bluetooth
        if (lower.contains("bluetooth on")) {
            actions.add(MacroAction(MacroActionType.SET_BLUETOOTH, enabled = true))
        } else if (lower.contains("bluetooth off")) {
            actions.add(MacroAction(MacroActionType.SET_BLUETOOTH, enabled = false))
        }

        // Silent / Vibrate
        if (lower.contains("silent") || lower.contains("vibrate") || lower.contains("khamosh")) {
            actions.add(MacroAction(MacroActionType.SET_SILENT, enabled = true))
        }

        // DND
        if (lower.contains("do not disturb") || lower.contains("dnd") || lower.contains("disturb mat")) {
            actions.add(MacroAction(MacroActionType.SET_DND, enabled = true))
        }

        // Volume
        val volumeMatch = Regex("""volume\s+(?:to\s+)?(\d+)""").find(lower)
        if (volumeMatch != null) {
            actions.add(MacroAction(MacroActionType.SET_VOLUME, value = volumeMatch.groupValues[1]))
        } else if (lower.contains("volume up") || lower.contains("awaz badhao")) {
            actions.add(MacroAction(MacroActionType.SET_VOLUME, value = "80"))
        } else if (lower.contains("volume down") || lower.contains("awaz kam")) {
            actions.add(MacroAction(MacroActionType.SET_VOLUME, value = "30"))
        }

        // Brightness
        val brightnessMatch = Regex("""brightness\s+(?:to\s+)?(\d+)""").find(lower)
        if (brightnessMatch != null) {
            actions.add(MacroAction(MacroActionType.SET_BRIGHTNESS, value = brightnessMatch.groupValues[1]))
        } else if (lower.contains("brightness low") || lower.contains("dim")) {
            actions.add(MacroAction(MacroActionType.SET_BRIGHTNESS, value = "30"))
        } else if (lower.contains("brightness high") || lower.contains("bright")) {
            actions.add(MacroAction(MacroActionType.SET_BRIGHTNESS, value = "230"))
        }

        // Open apps
        if (lower.contains("calendar") || lower.contains("taqeeb")) {
            actions.add(MacroAction(MacroActionType.OPEN_CALENDAR))
        }
        if (lower.contains("maps") || lower.contains("navigation") || lower.contains("nakshe")) {
            actions.add(MacroAction(MacroActionType.OPEN_MAPS))
        }
        if (lower.contains("alarm") || lower.contains("jaagte")) {
            actions.add(MacroAction(MacroActionType.SET_ALARM))
        }
        if (lower.contains("music") || lower.contains("gaana")) {
            actions.add(MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.apps.youtube.music"))
        }
        if (lower.contains("notes") || lower.contains("yaad")) {
            actions.add(MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.keep"))
        }
        if (lower.contains("email") || lower.contains("mail") || lower.contains("chitthi")) {
            actions.add(MacroAction(MacroActionType.OPEN_APP, value = "com.google.android.gm"))
        }

        return actions
    }
}

package com.jarvis.assistant.actions

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.shizuku.ShizukuManager

/**
 * ActionHandler — Intercepts AI responses and executes REAL Android actions.
 *
 * CRITICAL FIX: This class prevents JARVIS from "hallucinating" actions.
 *
 * When Gemini returns a response like "Opening YouTube for you, Sir.",
 * the ActionHandler intercepts it BEFORE speaking and:
 *   1. Parses the intent (open app, toggle setting, etc.)
 *   2. Executes the REAL Android action via Intent or Shizuku
 *   3. Only allows the "Opening X" response AFTER the intent is
 *      successfully fired
 *
 * If the action FAILS, JARVIS says "I couldn't open X" instead of
 * lying about having done it.
 */
object ActionHandler {

    private const val TAG = "ActionHandler"

    /**
     * Result of an action execution attempt.
     */
    sealed class ActionResult {
        /** Action was successfully executed */
        data class Success(val message: String) : ActionResult()

        /** Action failed - use this message instead of the AI's hallucinated one */
        data class Failed(val message: String) : ActionResult()

        /** No action was detected in the response */
        data object NoAction : ActionResult()
    }

    // Well-known app name to package mapping
    private val appAliases = mapOf(
        "youtube" to "com.google.android.youtube",
        "maps" to "com.google.android.apps.maps",
        "gmail" to "com.google.android.gm",
        "chrome" to "com.android.chrome",
        "drive" to "com.google.android.apps.docs",
        "photos" to "com.google.android.apps.photos",
        "play store" to "com.android.vending",
        "play music" to "com.google.android.music",
        "google" to "com.google.android.googlequicksearchbox",
        "google home" to "com.google.android.apps.chromecast.app",
        "translate" to "com.google.android.apps.translate",
        "calendar" to "com.google.android.calendar",
        "clock" to "com.android.deskclock",
        "calculator" to "com.google.android.calculator",
        "weather" to "com.google.android.apps.weather",
        "whatsapp" to "com.whatsapp",
        "instagram" to "com.instagram.android",
        "twitter" to "com.twitter.android",
        "x" to "com.twitter.android",
        "facebook" to "com.facebook.katana",
        "telegram" to "org.telegram.messenger",
        "snapchat" to "com.snapchat.android",
        "discord" to "com.discord",
        "spotify" to "com.spotify.music",
        "netflix" to "com.netflix.mediaclient",
        "samsung internet" to "com.sec.android.app.sbrowser",
        "samsung health" to "com.sec.android.app.shealth",
        "galaxy store" to "com.sec.android.app.samsungapps",
        "messages" to "com.google.android.apps.messaging",
        "sms" to "com.google.android.apps.messaging",
        "phone" to "com.google.android.dialer",
        "dialer" to "com.google.android.dialer",
        "settings" to "com.android.settings",
        "files" to "com.google.android.apps.nbu.files",
        "camera" to "com.google.android.GoogleCamera",
    )

    /**
     * Intercept an AI response and execute any actions found in it.
     *
     * This is the main entry point. Call this BEFORE the response is
     * spoken aloud. If the AI claims to have opened an app, this method
     * will actually open it. If the action fails, the response is
     * modified to reflect the failure instead of hallucinating success.
     */
    fun interceptAndExecute(aiResponse: String, context: Context): Pair<String, ActionResult> {
        val lower = aiResponse.lowercase()

        // 1. Open / Launch app patterns
        val openPatterns = listOf(
            Regex("""(?:i(?:'ll| will)?\s+(?:open|launch|start)\s+(\w+(?:\s+\w+){0,2}))"""),
            Regex("""(?:opening\s+(\w+(?:\s+\w+){0,2}))"""),
            Regex("""(?:let me\s+(?:open|launch)\s+(\w+(?:\s+\w+){0,2}))"""),
            Regex("""(?:i'm\s+(?:opening|launching)\s+(\w+(?:\s+\w+){0,2}))"""),
            Regex("""(?:i've\s+(?:opened|launched)\s+(\w+(?:\s+\w+){0,2}))"""),
        )

        for (pattern in openPatterns) {
            val match = pattern.find(lower) ?: continue
            val rawAppName = match.groupValues[1].trim()
                .removeSuffix(" for you").removeSuffix(" now").removeSuffix(" sir").trim()
            val appName = rawAppName.removeSuffix(".").trim()

            val result = executeOpenApp(appName, context)
            return when (result) {
                is ActionResult.Success -> aiResponse to result
                is ActionResult.Failed -> {
                    val corrected = "I couldn't open ${appName.replaceFirstChar { it.uppercase() }}. ${result.message}"
                    corrected to result
                }
                is ActionResult.NoAction -> aiResponse to result
            }
        }

        // 2. Toggle settings patterns
        val toggleOnPatterns = mapOf(
            Regex("""(?:turn(?:ing)? on|enabl(?:ing|e)|switch(?:ing)? on)\s+wifi""") to "wifi_on",
            Regex("""(?:turn(?:ing)? on|enabl(?:ing|e)|switch(?:ing)? on)\s+bluetooth""") to "bluetooth_on",
            Regex("""(?:turn(?:ing)? on|enabl(?:ing|e)|switch(?:ing)? on)\s+(?:airplane|flight)\s*(?:mode)?""") to "airplane_on",
            Regex("""(?:turn(?:ing)? on|enabl(?:ing|e)|switch(?:ing)? on)\s+(?:mobile\s+)?data""") to "data_on",
        )
        val toggleOffPatterns = mapOf(
            Regex("""(?:turn(?:ing)? off|disabl(?:ing|e)|switch(?:ing)? off)\s+wifi""") to "wifi_off",
            Regex("""(?:turn(?:ing)? off|disabl(?:ing|e)|switch(?:ing)? off)\s+bluetooth""") to "bluetooth_off",
            Regex("""(?:turn(?:ing)? off|disabl(?:ing|e)|switch(?:ing)? off)\s+(?:airplane|flight)\s*(?:mode)?""") to "airplane_off",
            Regex("""(?:turn(?:ing)? off|disabl(?:ing|e)|switch(?:ing)? off)\s+(?:mobile\s+)?data""") to "data_off",
        )

        for ((pattern, action) in toggleOnPatterns) {
            if (pattern.containsMatchIn(lower)) {
                val result = executeToggle(action, context)
                return aiResponse to result
            }
        }
        for ((pattern, action) in toggleOffPatterns) {
            if (pattern.containsMatchIn(lower)) {
                val result = executeToggle(action, context)
                return aiResponse to result
            }
        }

        // 3. Volume patterns
        if (lower.contains("volume up") || lower.contains("louder")) {
            val result = executeVolume("up", context)
            return aiResponse to result
        }
        if (lower.contains("volume down") || lower.contains("quieter") || lower.contains("softer")) {
            val result = executeVolume("down", context)
            return aiResponse to result
        }
        if (lower.contains("mute") || lower.contains("silent mode")) {
            val result = executeVolume("mute", context)
            return aiResponse to result
        }

        // 4. Call patterns
        val callMatch = Regex("""(?:call(?:ing|ed)?|dial(?:ing|ed)?|ring(?:ing)?)\s+(\S+)""").find(lower)
        if (callMatch != null) {
            val target = callMatch.groupValues[1]
            val result = executeCall(target, context)
            return aiResponse to result
        }

        // 5. Open URL patterns
        val urlMatch = Regex("""(?:open(?:ing)?|go(?:ing)? to|visit(?:ing)?)\s+(https?://\S+|\w+\.\w+\S*)""").find(lower)
        if (urlMatch != null) {
            var url = urlMatch.groupValues[1]
            if (!url.startsWith("http")) url = "https://$url"
            val result = executeOpenUrl(url, context)
            return aiResponse to result
        }

        return aiResponse to ActionResult.NoAction
    }

    // Action Executors

    private fun executeOpenApp(appName: String, context: Context): ActionResult {
        Log.i(TAG, "Executing OPEN APP: $appName")

        val knownPackage = appAliases[appName]
        if (knownPackage != null) {
            val launched = tryLaunchApp(knownPackage, context)
            if (launched) {
                Log.i(TAG, "Successfully launched $appName via known package: $knownPackage")
                return ActionResult.Success("Opening ${appName.replaceFirstChar { it.uppercase() }} for you, Sir.")
            }

            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.openApp(knownPackage)
                if (result.isSuccess) {
                    Log.i(TAG, "Force-launched $appName via Shizuku")
                    return ActionResult.Success("Opening ${appName.replaceFirstChar { it.uppercase() }} via system command, Sir.")
                }
            }

            return ActionResult.Failed("The app may not be installed on this device.")
        }

        val foundPackage = searchPackageByName(appName, context)
        if (foundPackage != null) {
            val launched = tryLaunchApp(foundPackage, context)
            if (launched) {
                Log.i(TAG, "Successfully launched $appName via discovered package: $foundPackage")
                return ActionResult.Success("Opening ${appName.replaceFirstChar { it.uppercase() }} for you, Sir.")
            }

            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.openApp(foundPackage)
                if (result.isSuccess) {
                    return ActionResult.Success("Opening ${appName.replaceFirstChar { it.uppercase() }} via system command, Sir.")
                }
            }
        }

        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            val searchResult = ShizukuManager.getInstalledApps()
            if (searchResult.isSuccess) {
                val matchingLine = searchResult.stdout.lines().firstOrNull {
                    it.contains(appName, ignoreCase = true)
                }
                if (matchingLine != null) {
                    val pkg = matchingLine.substringAfter("package:").trim()
                    val openResult = ShizukuManager.openApp(pkg)
                    if (openResult.isSuccess) {
                        return ActionResult.Success("Opening ${appName.replaceFirstChar { it.uppercase() }} for you, Sir.")
                    }
                }
            }
        }

        Log.w(TAG, "Could not find or launch app: $appName")
        return ActionResult.Failed("I couldn't find an app matching '$appName'.")
    }

    private fun tryLaunchApp(packageName: String, context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "Launched $packageName via standard Intent")
                true
            } else {
                val launchIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setPackage(packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(launchIntent)
                Log.i(TAG, "Launched $packageName via LAUNCHER Intent")
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch $packageName via Intent: ${e.message}")
            false
        }
    }

    private fun searchPackageByName(appName: String, context: Context): String? {
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val lowerName = appName.lowercase()

            val exactMatch = apps.firstOrNull {
                it.packageName.contains(lowerName, ignoreCase = true)
            }
            if (exactMatch != null) return exactMatch.packageName

            val labelMatch = apps.firstOrNull { appInfo ->
                try {
                    val label = pm.getApplicationLabel(appInfo).toString().lowercase()
                    label == lowerName || label.contains(lowerName)
                } catch (e: Exception) { false }
            }
            if (labelMatch != null) return labelMatch.packageName

            null
        } catch (e: Exception) {
            Log.w(TAG, "PackageManager search failed: ${e.message}")
            null
        }
    }

    private fun executeToggle(action: String, context: Context): ActionResult {
        if (!ShizukuManager.isReady() || !ShizukuManager.hasPermission()) {
            return ActionResult.Failed("I need Shizuku access to toggle system settings. Please connect Shizuku first.")
        }

        val result = when (action) {
            "wifi_on" -> ShizukuManager.toggleWifi(true)
            "wifi_off" -> ShizukuManager.toggleWifi(false)
            "bluetooth_on" -> ShizukuManager.toggleBluetooth(true)
            "bluetooth_off" -> ShizukuManager.toggleBluetooth(false)
            "airplane_on" -> ShizukuManager.toggleAirplaneMode(true)
            "airplane_off" -> ShizukuManager.toggleAirplaneMode(false)
            "data_on" -> ShizukuManager.toggleMobileData(true)
            "data_off" -> ShizukuManager.toggleMobileData(false)
            else -> return ActionResult.NoAction
        }

        return if (result.isSuccess) {
            ActionResult.Success("Done, Sir.")
        } else {
            ActionResult.Failed("I couldn't toggle that setting. ${result.stderr}")
        }
    }

    private fun executeVolume(direction: String, context: Context): ActionResult {
        if (!ShizukuManager.isReady() || !ShizukuManager.hasPermission()) {
            return try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                when (direction) {
                    "up" -> {
                        am.adjustVolume(android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                        ActionResult.Success("Volume increased, Sir.")
                    }
                    "down" -> {
                        am.adjustVolume(android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                        ActionResult.Success("Volume decreased, Sir.")
                    }
                    "mute" -> {
                        am.adjustVolume(android.media.AudioManager.ADJUST_MUTE, 0)
                        ActionResult.Success("Volume muted, Sir.")
                    }
                    else -> ActionResult.NoAction
                }
            } catch (e: Exception) {
                ActionResult.Failed("I need permission to control volume.")
            }
        }

        val result = when (direction) {
            "up" -> ShizukuManager.executeShellCommand("media volume --stream 3 --adj raise")
            "down" -> ShizukuManager.executeShellCommand("media volume --stream 3 --adj lower")
            "mute" -> ShizukuManager.executeShellCommand("media volume --stream 3 --set 0")
            else -> return ActionResult.NoAction
        }

        return if (result.isSuccess) {
            ActionResult.Success("Volume ${if (direction == "mute") "muted" else direction}, Sir.")
        } else {
            ActionResult.Failed("Could not adjust volume. ${result.stderr}")
        }
    }

    private fun executeCall(target: String, context: Context): ActionResult {
        return try {
            val uri = "tel:$target"
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opening dialer for $target, Sir.")
        } catch (e: Exception) {
            ActionResult.Failed("Could not initiate call: ${e.message}")
        }
    }

    private fun executeOpenUrl(url: String, context: Context): ActionResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ActionResult.Success("Opening $url, Sir.")
        } catch (e: Exception) {
            ActionResult.Failed("Could not open URL: ${e.message}")
        }
    }
}

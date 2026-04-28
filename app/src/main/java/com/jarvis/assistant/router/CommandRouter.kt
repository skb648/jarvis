package com.jarvis.assistant.router

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.shizuku.ShizukuManager

/**
 * Command Router — Routes user intents to the appropriate executor.
 *
 * Determines whether a user query is a SYSTEM command (open app, toggle wifi,
 * volume control, etc.) that can be handled locally, or a CONVERSATIONAL
 * query that needs to be sent to the Gemini AI API.
 *
 * System commands are resolved immediately without AI latency.
 * Conversational queries fall through to the Gemini pipeline.
 *
 * Supported system commands:
 *   - "open [app name]" / "launch [app name]" → Opens app via Intent or Shizuku
 *   - "turn on/off wifi" → Toggles WiFi via Shizuku
 *   - "turn on/off bluetooth" → Toggles Bluetooth via Shizuku
 *   - "turn on/off airplane mode" → Toggles via Shizuku
 *   - "turn on/off mobile data" → Toggles via Shizuku
 *   - "volume up/down/mute" → Volume control via Shizuku
 *   - "take screenshot" → Via AccessibilityService
 *   - "go back/home" → Global actions via AccessibilityService
 *   - "open settings" → Opens Android Settings
 *   - "set brightness [0-255]" → Screen brightness via Settings
 *   - "set alarm/timer" → Opens clock app
 *   - "call [contact]" → Opens dialer
 *   - "open [url]" → Opens browser
 */
object CommandRouter {

    private const val TAG = "CommandRouter"

    /**
     * Result of routing a user command.
     */
    sealed class RouteResult {
        /** System command was handled locally — no AI needed */
        data class Handled(val response: String, val emotion: String = "calm") : RouteResult()

        /** Command needs AI processing — fall through to Gemini */
        data class NeedsAI(val query: String) : RouteResult()
    }

    // ─── Well-known app name → package mapping ──────────────────
    // Delegated to shared AppRegistry (BUG #12 fix: eliminates duplicate map)
    private val appAliases: Map<String, String> get() = com.jarvis.assistant.actions.AppRegistry.appAliases

    /**
     * Route a user query to the appropriate handler.
     * Returns [RouteResult.Handled] if a system command was executed,
     * or [RouteResult.NeedsAI] if it should go to Gemini.
     */
    fun route(query: String, context: Context): RouteResult {
        val normalized = query.lowercase().trim()

        // ─── App Opening ──────────────────────────────────────
        val openMatch = Regex("""(?:open|launch|start|run)\s+(.+)""", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            return handleOpenApp(appName, context)
        }

        // ─── WiFi Toggle ──────────────────────────────────────
        if (normalized.matches(Regex("""(?:turn on|enable|switch on)\s+wifi"""))) {
            return handleToggle("wifi", true)
        }
        if (normalized.matches(Regex("""(?:turn off|disable|switch off)\s+wifi"""))) {
            return handleToggle("wifi", false)
        }

        // ─── Bluetooth Toggle ─────────────────────────────────
        if (normalized.matches(Regex("""(?:turn on|enable|switch on)\s+bluetooth"""))) {
            return handleToggle("bluetooth", true)
        }
        if (normalized.matches(Regex("""(?:turn off|disable|switch off)\s+bluetooth"""))) {
            return handleToggle("bluetooth", false)
        }

        // ─── Airplane Mode Toggle ─────────────────────────────
        if (normalized.matches(Regex("""(?:turn on|enable|switch on)\s+(?:airplane|airplane mode|flight)"""))) {
            return handleToggle("airplane", true)
        }
        if (normalized.matches(Regex("""(?:turn off|disable|switch off)\s+(?:airplane|airplane mode|flight)"""))) {
            return handleToggle("airplane", false)
        }

        // ─── Mobile Data Toggle ───────────────────────────────
        if (normalized.matches(Regex("""(?:turn on|enable|switch on)\s+(?:mobile data|data)"""))) {
            return handleToggle("data", true)
        }
        if (normalized.matches(Regex("""(?:turn off|disable|switch off)\s+(?:mobile data|data)"""))) {
            return handleToggle("data", false)
        }

        // ─── Volume Control ───────────────────────────────────
        if (normalized.contains("volume up") || normalized.contains("louder")) {
            return handleVolume("up")
        }
        if (normalized.contains("volume down") || normalized.contains("quieter") || normalized.contains("softer")) {
            return handleVolume("down")
        }
        if (normalized.contains("mute") || normalized.contains("silent")) {
            return handleVolume("mute")
        }

        // ─── Navigation ──────────────────────────────────────
        if (normalized in listOf("go back", "back", "go back")) {
            return handleGlobalAction("back")
        }
        if (normalized in listOf("go home", "home", "go to home screen")) {
            return handleGlobalAction("home")
        }
        if (normalized in listOf("recents", "recent apps", "show recents")) {
            return handleGlobalAction("recents")
        }
        if (normalized.contains("notification") && normalized.contains("open")) {
            return handleGlobalAction("notifications")
        }
        if (normalized.contains("quick settings")) {
            return handleGlobalAction("quick_settings")
        }

        // ─── Screenshot ──────────────────────────────────────
        if (normalized.contains("screenshot") || normalized.contains("screen capture")) {
            return handleScreenshot()
        }

        // ─── Brightness ──────────────────────────────────────
        val brightnessMatch = Regex("""(?:set\s+)?brightness\s+(?:to\s+)?(\d+)""").find(normalized)
        if (brightnessMatch != null) {
            val level = brightnessMatch.groupValues[1].toIntOrNull()?.coerceIn(0, 255)
            if (level != null) {
                return handleBrightness(level, context)
            }
        }

        // ─── Open URL ────────────────────────────────────────
        val urlMatch = Regex("""(?:open|go to|visit)\s+(https?://\S+|\w+\.\w+\S*)""").find(normalized)
        if (urlMatch != null) {
            var url = urlMatch.groupValues[1]
            if (!url.startsWith("http")) url = "https://$url"
            return handleOpenUrl(url, context)
        }

        // ─── Call ────────────────────────────────────────────
        val callMatch = Regex("""(?:call|dial|ring)\s+(.+)""").find(normalized)
        if (callMatch != null) {
            val target = callMatch.groupValues[1].trim()
            return handleCall(target, context)
        }

        // ─── No system command matched → route to Gemini AI ──
        return RouteResult.NeedsAI(query)
    }

    // ─── Command Handlers ──────────────────────────────────────

    private fun handleOpenApp(appName: String, context: Context): RouteResult {
        val packageName = appAliases[appName]

        if (packageName != null) {
            // Known app — try Intent first, then Shizuku fallback
            val launched = tryLaunchApp(packageName, context)
            if (launched) {
                return RouteResult.Handled(
                    "Opening ${appName.replaceFirstChar { it.uppercase() }} for you, Sir.",
                    "confident"
                )
            }

            // Fallback: Shizuku
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.openApp(packageName)
                if (result.isSuccess) {
                    return RouteResult.Handled(
                        "Opening ${appName.replaceFirstChar { it.uppercase() }} via system command, Sir.",
                        "confident"
                    )
                }
            }

            return RouteResult.Handled(
                "I couldn't open ${appName.replaceFirstChar { it.uppercase() }}. The app may not be installed.",
                "confused"
            )
        }

        // Unknown app name — try Shizuku with a fuzzy match or pass to AI
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            // Try to find the package
            val searchResult = ShizukuManager.getInstalledApps()
            if (searchResult.isSuccess) {
                val matchingLine = searchResult.stdout.lines().firstOrNull {
                    it.contains(appName, ignoreCase = true)
                }
                if (matchingLine != null) {
                    val pkg = matchingLine.substringAfter("package:").trim()
                    val openResult = ShizukuManager.openApp(pkg)
                    if (openResult.isSuccess) {
                        return RouteResult.Handled(
                            "Opening ${appName.replaceFirstChar { it.uppercase() }} for you, Sir.",
                            "confident"
                        )
                    }
                }
            }
        }

        // Can't resolve — let AI handle it
        return RouteResult.NeedsAI("Open the app: $appName")
    }

    private fun tryLaunchApp(packageName: String, context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch app via Intent: $packageName", e)
            false
        }
    }

    private fun handleToggle(systemFeature: String, enable: Boolean): RouteResult {
        val action = if (enable) "on" else "off"

        if (!ShizukuManager.isReady() || !ShizukuManager.hasPermission()) {
            return RouteResult.Handled(
                "I need Shizuku access to toggle $systemFeature. Please connect Shizuku first.",
                "confused"
            )
        }

        val result = when (systemFeature) {
            "wifi" -> ShizukuManager.toggleWifi(enable)
            "bluetooth" -> ShizukuManager.toggleBluetooth(enable)
            "airplane" -> ShizukuManager.toggleAirplaneMode(enable)
            "data" -> ShizukuManager.toggleMobileData(enable)
            else -> return RouteResult.NeedsAI("Turn $action $systemFeature")
        }

        return if (result.isSuccess) {
            RouteResult.Handled(
                "${systemFeature.replaceFirstChar { it.uppercase() }} turned $action, Sir.",
                "confident"
            )
        } else {
            RouteResult.Handled(
                "I couldn't toggle $systemFeature. ${result.stderr}",
                "stressed"
            )
        }
    }

    private fun handleVolume(direction: String): RouteResult {
        if (!ShizukuManager.isReady() || !ShizukuManager.hasPermission()) {
            return RouteResult.Handled(
                "I need Shizuku access to control volume. Please connect Shizuku first.",
                "confused"
            )
        }

        val result = when (direction) {
            "up" -> ShizukuManager.executeShellCommand("media volume --stream 3 --adj raise")
            "down" -> ShizukuManager.executeShellCommand("media volume --stream 3 --adj lower")
            "mute" -> ShizukuManager.executeShellCommand("media volume --stream 3 --set 0")
            else -> return RouteResult.NeedsAI("Set volume $direction")
        }

        return if (result.isSuccess) {
            RouteResult.Handled(
                "Volume ${if (direction == "mute") "muted" else direction}, Sir.",
                "confident"
            )
        } else {
            RouteResult.Handled("Could not adjust volume. ${result.stderr}", "stressed")
        }
    }

    private fun handleGlobalAction(action: String): RouteResult {
        val svc = JarviewModel.accessibilityService?.get()
        if (svc == null) {
            return RouteResult.Handled(
                "I need Accessibility Service enabled to perform $action.",
                "confused"
            )
        }

        val success = when (action) {
            "back" -> svc.goBack()
            "home" -> svc.goHome()
            "recents" -> svc.openRecents()
            "notifications" -> svc.openNotifications()
            "quick_settings" -> svc.openQuickSettings()
            else -> false
        }

        return if (success) {
            RouteResult.Handled("Done, Sir.", "calm")
        } else {
            RouteResult.Handled("I couldn't perform that action.", "stressed")
        }
    }

    private fun handleScreenshot(): RouteResult {
        val svc = JarviewModel.accessibilityService?.get()
        if (svc != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            val success = svc.takeScreenshot()
            return if (success) {
                RouteResult.Handled("Screenshot taken, Sir.", "confident")
            } else {
                RouteResult.Handled("Could not take screenshot.", "stressed")
            }
        }

        // Shizuku fallback
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            val path = "/sdcard/Pictures/screenshots/jarvis_${System.currentTimeMillis()}.png"
            val result = ShizukuManager.takeScreenshot(path)
            return if (result.isSuccess) {
                RouteResult.Handled("Screenshot saved to $path, Sir.", "confident")
            } else {
                RouteResult.Handled("Could not take screenshot. ${result.stderr}", "stressed")
            }
        }

        return RouteResult.Handled("I need Accessibility or Shizuku to take screenshots.", "confused")
    }

    private fun handleBrightness(level: Int, context: Context): RouteResult {
        return try {
            // Try system write settings
            if (Settings.System.canWrite(context)) {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, level)
                RouteResult.Handled("Brightness set to $level, Sir.", "confident")
            } else if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.setSystemSetting("system", "screen_brightness", level.toString())
                if (result.isSuccess) {
                    RouteResult.Handled("Brightness set to $level, Sir.", "confident")
                } else {
                    RouteResult.Handled("Could not set brightness. ${result.stderr}", "stressed")
                }
            } else {
                RouteResult.Handled("I need system write permission to adjust brightness.", "confused")
            }
        } catch (e: Exception) {
            RouteResult.Handled("Could not set brightness: ${e.message}", "stressed")
        }
    }

    private fun handleOpenUrl(url: String, context: Context): RouteResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RouteResult.Handled("Opening $url, Sir.", "confident")
        } catch (e: Exception) {
            RouteResult.Handled("Could not open URL: ${e.message}", "stressed")
        }
    }

    private fun handleCall(target: String, context: Context): RouteResult {
        return try {
            // If it looks like a phone number
            val isNumber = target.all { it.isDigit() || it in "+-() " }
            val uri = if (isNumber) "tel:$target" else "tel:$target"
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RouteResult.Handled("Opening dialer for $target, Sir.", "calm")
        } catch (e: Exception) {
            RouteResult.Handled("Could not initiate call: ${e.message}", "stressed")
        }
    }

}

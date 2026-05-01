package com.jarvis.assistant.router

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.Manifest
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log
import com.jarvis.assistant.brief.DailyBriefGenerator
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.location.LocationAwarenessManager
import com.jarvis.assistant.macros.MacroEngine
import com.jarvis.assistant.monitor.ProactiveDeviceMonitor
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
 *   - "call [contact]" → Opens dialer or direct calls
 *   - "open [url]" → Opens browser
 *   - "install/download/get [app]" → Autonomous install task
 *   - "answer/reject call" → Call answer/reject
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

        /** Vision command — trigger camera + AI vision */
        data class VisionCommand(val prompt: String) : RouteResult()

        /** Notification command — read notifications */
        data class NotificationCommand(val appFilter: String? = null) : RouteResult()

        /** Macro command — execute a shortcut macro */
        data class MacroCommand(val macroName: String) : RouteResult()

        /** Daily brief command — generate morning brief */
        data class DailyBriefCommand(val hour: Int? = null, val minute: Int? = null) : RouteResult()

        /** Location command — set home/office location */
        data class LocationCommand(val locationType: String) : RouteResult()

        /** Device status command — check battery/storage etc */
        data class DeviceStatusCommand(val component: String = "all") : RouteResult()

        /** Autonomous task — install/download/get an app via AI+accessibility */
        data class AutonomousTask(val taskType: String, val target: String, val prompt: String) : RouteResult()
    }

    // ─── Well-known app name → package mapping ──────────────────
    // Delegated to shared AppRegistry (BUG #12 fix: eliminates duplicate map)
    private val appAliases: Map<String, String> get() = com.jarvis.assistant.actions.AppRegistry.appAliases

    // ─── Hinglish → English normalization map ───────────────────
    private val hinglishMap = mapOf(
        "kholo" to "open",
        "band karo" to "turn off",
        "off karo" to "turn off",
        "on karo" to "turn on",
        "chalu karo" to "turn on",
        "install karo" to "install",
        "download karo" to "download",
        "get karo" to "get",
        "bhejo" to "send",
        "samjhao" to "explain",
        "dikhao" to "show",
        "sunao" to "play",
        "chalao" to "run",
        "rukho" to "stop",
        "holo" to "open",
    )

    /**
     * Normalize Hinglish phrasings to their English equivalents.
     * E.g. "free fire kholo" → "open free fire", "install karo" → "install"
     */
    private fun normalizeHinglish(input: String): String {
        var result = input
        // Handle "X kholo" → "open X" (kholo at end after a word)
        result = Regex("""(\S+)\s+kholo""", RegexOption.IGNORE_CASE).replace(result) { match ->
            "open ${match.groupValues[1]}"
        }
        // Handle "X karo" patterns at end of string
        for ((hinglish, english) in hinglishMap) {
            if (hinglish.contains(" ")) {
                // Multi-word replacements like "band karo" → "turn off"
                result = result.replace(Regex(Regex.escape(hinglish), RegexOption.IGNORE_CASE), english)
            }
        }
        // Handle single-word suffixes like "install karo" → "install"
        result = Regex("""(install|download|get)\s+karo""", RegexOption.IGNORE_CASE).replace(result) { match ->
            match.groupValues[1]
        }
        // Handle standalone "karo" at end after other words → treat as action verb
        // e.g. "wifi on karo" already handled above
        // Handle "X karo" for toggle patterns: "wifi on karo" → "turn on wifi"
        result = Regex("""(\S+)\s+on\s*karo""", RegexOption.IGNORE_CASE).replace(result) { match ->
            "turn on ${match.groupValues[1]}"
        }
        result = Regex("""(\S+)\s+off\s*karo""", RegexOption.IGNORE_CASE).replace(result) { match ->
            "turn off ${match.groupValues[1]}"
        }
        // Handle remaining single-word replacements (but not "kholo" which is already handled)
        for ((hinglish, english) in hinglishMap) {
            if (!hinglish.contains(" ") && hinglish != "kholo" && hinglish != "holo") {
                result = result.replace(Regex("""\b${Regex.escape(hinglish)}\b""", RegexOption.IGNORE_CASE), english)
            }
        }
        return result
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     */
    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,       // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[a.length][b.length]
    }

    /**
     * Normalize a string for fuzzy matching: lowercase, remove spaces & punctuation.
     */
    private fun normalizeForFuzzy(s: String): String {
        return s.lowercase().replace(Regex("[\\s\\p{Punct}]"), "")
    }

    /**
     * Calculate token-overlap bonus between input and a known alias.
     * Returns 0..2 bonus (each matching token reduces effective distance by 1).
     */
    private fun tokenOverlapBonus(input: String, alias: String): Int {
        val inputTokens = input.lowercase().split(Regex("\\s+")).toSet()
        val aliasTokens = alias.lowercase().split(Regex("\\s+")).toSet()
        return inputTokens.intersect(aliasTokens).size.coerceAtMost(2)
    }

    /**
     * Route a user query to the appropriate handler.
     * Returns [RouteResult.Handled] if a system command was executed,
     * or [RouteResult.NeedsAI] if it should go to Gemini.
     */
    fun route(query: String, context: Context): RouteResult {
        val normalized = normalizeHinglish(query.lowercase().trim())

        // ─── Call Answer / Reject ────────────────────────────────
        if (normalized == "answer" || normalized == "answer call" || normalized == "receive") {
            // Actually answer the call using accessibility service or Shizuku
            val svc = JarviewModel.accessibilityService?.get()
            if (svc != null) {
                // Try clicking the answer button via accessibility
                val clicked = svc.autoClick("Answer") || svc.autoClick("answer") ||
                        svc.autoClick("Accept") || svc.autoClick("accept") ||
                        svc.clickNodeById("answer") || svc.clickNodeById("accept_call")
                if (clicked) {
                    return RouteResult.Handled("Answering the call, Sir.", "calm")
                }
            }
            // Fallback: Shizuku keyevent for CALL button
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                ShizukuManager.executeShellCommand("input keyevent KEYCODE_CALL")
                return RouteResult.Handled("Answering the call, Sir.", "calm")
            }
            return RouteResult.Handled("I couldn't answer the call — Accessibility Service or Shizuku is needed.", "stressed")
        }
        if (normalized == "reject" || normalized == "decline" || normalized == "reject call") {
            // Actually reject the call using accessibility service or Shizuku
            val svc = JarviewModel.accessibilityService?.get()
            if (svc != null) {
                // Try clicking the reject/decline button via accessibility
                val clicked = svc.autoClick("Decline") || svc.autoClick("decline") ||
                        svc.autoClick("Reject") || svc.autoClick("reject") ||
                        svc.autoClick("Hang up") || svc.autoClick("End call") ||
                        svc.clickNodeById("decline") || svc.clickNodeById("reject_call")
                if (clicked) {
                    return RouteResult.Handled("Rejecting the call, Sir.", "calm")
                }
            }
            // Fallback: Shizuku keyevent for ENDCALL button
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                ShizukuManager.executeShellCommand("input keyevent KEYCODE_ENDCALL")
                return RouteResult.Handled("Rejecting the call, Sir.", "calm")
            }
            return RouteResult.Handled("I couldn't reject the call — Accessibility Service or Shizuku is needed.", "stressed")
        }

        // ─── Install / Download / Get commands (AutonomousTask) ─
        val installMatch = Regex("""(?:install|download|get)\s+(.+)""", RegexOption.IGNORE_CASE).find(normalized)
        if (installMatch != null) {
            val target = installMatch.groupValues[1].trim()
            return RouteResult.AutonomousTask("install", target,
                "Open Play Store, search for '$target', and tap Install using the accessibility service tools.")
        }

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
        // FIX #2: Removed duplicate "go back" from the list
        if (normalized in listOf("go back", "back")) {
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
            // FIX #8 (M2): Exclude "call back" / "call me back" from dialer routing
            if (target.equals("back", ignoreCase = true) || target.equals("me back", ignoreCase = true)) {
                // Fall through — let AI or callback handler deal with it
            } else {
                return handleCall(target, context)
            }
        }

        // ─── Vision Commands ─────────────────────────────────────
        // FIX #1 (C3): Removed "screenshot" from vision command match to avoid
        // conflict with the screenshot handler above. Vision commands now only match:
        // "kya hai yeh", "what is this", "dekh kya hai", "look at this",
        // "describe", "identify", "scan", "take a photo", "photo le"
        if (normalized.matches(Regex("(?:kya hai yeh|what is this|dekh kya hai|look at this|describe|identify|scan)")) ||
            normalized.contains("take a photo") || normalized.contains("photo le")) {
            val prompt = when {
                normalized.contains("describe") -> "Describe what you see in detail"
                normalized.contains("identify") -> "Identify objects and text in the image"
                normalized.contains("scan") -> "Scan and read any text or QR codes visible"
                else -> "What do you see?"
            }
            return RouteResult.VisionCommand(prompt)
        }

        // ─── Notification Commands ─────────────────────────────────
        if (normalized.matches(Regex("(?:read notifications|koi notification hai|any messages|any notifications|notifications padho)"))) {
            return RouteResult.NotificationCommand(null)
        }
        if (normalized.matches(Regex("(?:whatsapp pe kya aaya|whatsapp notifications|telegram pe kya aaya|instagram notifications)"))) {
            val appFilter = when {
                normalized.contains("whatsapp") -> "whatsapp"
                normalized.contains("telegram") -> "telegram"
                normalized.contains("instagram") -> "instagram"
                normalized.contains("gmail") -> "gmail"
                else -> null
            }
            return RouteResult.NotificationCommand(appFilter)
        }

        // ─── Macro Commands ──────────────────────────────────────
        val macro = MacroEngine.matchMacro(normalized, context)
        if (macro != null) {
            return RouteResult.MacroCommand(macro.name)
        }

        // ─── Create Custom Macro ──────────────────────────────
        if (normalized.matches(Regex("(?:create|make|add)\\s+macro\\s+.*"))) {
            return RouteResult.MacroCommand("__create__")
        }

        // ─── Daily Brief Commands ──────────────────────────────
        if (normalized.matches(Regex("(?:good morning|daily brief|aaj ka plan|morning brief|aaj ka summary|din ki shuruaat)"))) {
            return RouteResult.DailyBriefCommand()
        }

        // ─── Location Commands ─────────────────────────────────
        val locationCmd = LocationAwarenessManager.parseLocationCommand(normalized)
        if (locationCmd != null) {
            return RouteResult.LocationCommand(locationCmd.first)
        }

        // ─── Device Status Commands ────────────────────────────
        if (normalized.matches(Regex("(?:battery kiti|how much battery|phone status|device status|battery level|battery check|storage kitta|memory kitti|phone ka haal)"))) {
            val component = when {
                normalized.contains("battery") -> "battery"
                normalized.contains("storage") -> "storage"
                normalized.contains("memory") -> "memory"
                else -> "all"
            }
            return RouteResult.DeviceStatusCommand(component)
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

        // ── FIX #4: Fuzzy matching with Levenshtein edit distance ──
        // When exact match fails, try fuzzy matching against known aliases
        val fuzzyResult = fuzzyMatchApp(appName)
        if (fuzzyResult != null) {
            val (matchedAlias, matchedPackage) = fuzzyResult
            val launched = tryLaunchApp(matchedPackage, context)
            if (launched) {
                return RouteResult.Handled(
                    "Opening ${matchedAlias.replaceFirstChar { it.uppercase() }} for you, Sir.",
                    "confident"
                )
            }

            // Shizuku fallback for fuzzy match
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val result = ShizukuManager.openApp(matchedPackage)
                if (result.isSuccess) {
                    return RouteResult.Handled(
                        "Opening ${matchedAlias.replaceFirstChar { it.uppercase() }} via system command, Sir.",
                        "confident"
                    )
                }
            }

            return RouteResult.Handled(
                "I couldn't open ${matchedAlias.replaceFirstChar { it.uppercase() }}. The app may not be installed.",
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

    /**
     * Fuzzy match an app name against known aliases using Levenshtein distance
     * with token-overlap bonus.
     * Returns the (alias, packageName) pair if a close match is found, null otherwise.
     *
     * Examples: "yotube" → YouTube, "free fier" → Free Fire, "watsap" → WhatsApp
     */
    private fun fuzzyMatchApp(input: String): Pair<String, String>? {
        val inputNorm = normalizeForFuzzy(input)
        if (inputNorm.isEmpty()) return null

        var bestAlias: String? = null
        var bestPackage: String? = null
        var bestDistance = Int.MAX_VALUE

        for ((alias, packageName) in appAliases) {
            val aliasNorm = normalizeForFuzzy(alias)
            val dist = levenshtein(inputNorm, aliasNorm)
            val bonus = tokenOverlapBonus(input, alias)
            val effectiveDist = dist - bonus

            // Max allowed errors: 3 for longer names, 1 for short words (≤4 chars)
            val maxAllowed = if (aliasNorm.length <= 4) 1 else 3

            if (effectiveDist in 0..maxAllowed && effectiveDist < bestDistance) {
                bestDistance = effectiveDist
                bestAlias = alias
                bestPackage = packageName
            }
        }

        if (bestAlias != null && bestPackage != null) {
            return Pair(bestAlias, bestPackage)
        }
        return null
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

    /**
     * FIX #3 (M1): Handle call with actual contact resolution.
     * - Queries ContactsContract for the contact name to get phone number
     * - If CALL_PHONE permission is granted, uses ACTION_CALL for direct calling
     * - If only a number is provided, uses ACTION_CALL directly (if permission granted)
     * - Falls back to ACTION_DIAL (just opens dialer) if no CALL_PHONE permission
     */
    private fun handleCall(target: String, context: Context): RouteResult {
        return try {
            val isNumber = target.all { it.isDigit() || it in "+-() " }

            if (isNumber) {
                // Direct number provided — call if we have permission, else dial
                val uri = "tel:$target"
                val hasCallPermission = context.checkCallingOrSelfPermission(Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return if (hasCallPermission) {
                    RouteResult.Handled("Calling $target, Sir.", "calm")
                } else {
                    RouteResult.Handled("Opening dialer for $target, Sir.", "calm")
                }
            }

            // Contact name — resolve via ContactsContract
            val resolvedNumber = resolveContactNumber(target, context)
            if (resolvedNumber != null) {
                val uri = "tel:$resolvedNumber"
                val hasCallPermission = context.checkCallingOrSelfPermission(Manifest.permission.CALL_PHONE) ==
                        PackageManager.PERMISSION_GRANTED
                val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
                val intent = Intent(action, Uri.parse(uri)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return if (hasCallPermission) {
                    RouteResult.Handled("Calling $target, Sir.", "calm")
                } else {
                    RouteResult.Handled("Opening dialer for $target, Sir.", "calm")
                }
            }

            // Could not resolve contact — open dialer with the raw text
            val uri = "tel:$target"
            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(uri)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            RouteResult.Handled("Couldn't find contact '$target'. Opening dialer, Sir.", "confused")
        } catch (e: Exception) {
            RouteResult.Handled("Could not initiate call: ${e.message}", "stressed")
        }
    }

    /**
     * Resolve a contact name to a phone number using ContactsContract.
     * Returns the first matching phone number, or null if not found.
     */
    private fun resolveContactNumber(name: String, context: Context): String? {
        return try {
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            // Search by display name (case-insensitive via SQL)
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$name%")

            val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (numberIndex >= 0) {
                        return it.getString(numberIndex)
                    }
                }
            }
            null
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing READ_CONTACTS permission for contact lookup", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve contact: $name", e)
            null
        }
    }

}

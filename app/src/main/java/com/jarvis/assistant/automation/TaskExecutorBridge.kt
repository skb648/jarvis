package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.actions.AppRegistry
import com.jarvis.assistant.monitor.SelfDiagnosticManager
import com.jarvis.assistant.search.WebSearchEngine
import com.jarvis.assistant.services.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.jarvis.assistant.shizuku.ShizukuManager
import java.lang.ref.WeakReference

/**
 * TaskExecutorBridge — Executes autonomous task chains from Groq Function Calling.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v6.0) — AUTONOMOUS AGENT TASK EXECUTION:
 *
 * This bridge connects Groq's structured tool-call responses to actual
 * Android actions. When Groq returns a function call like:
 *   {"name": "open_and_search", "args": {"app": "youtube", "query": "cats"}}
 *
 * This bridge:
 *   1. Parses the function call
 *   2. Executes step 1 (open YouTube via deep link)
 *   3. Waits for the app to load
 *   4. Executes step 2 (type "cats" in search and press enter)
 *
 * Supports:
 *   - open_and_search(app, query) — Deep link + search intent
 *   - click_button(label) — Accessibility auto-click
 *   - inject_text(content) — Deep text injection into focused EditText
 *   - scroll(direction) — Scroll the screen
 *   - go_back() — Navigate back
 *   - go_home() — Go to home screen
 * ═══════════════════════════════════════════════════════════════════════
 */
object TaskExecutorBridge {

    private const val TAG = "TaskExecutor"

    @Volatile
    var accessibilityService: WeakReference<JarvisAccessibilityService>? = null

    /**
     * Result of executing a single task step.
     */
    sealed class StepResult {
        data class Success(val message: String) : StepResult()
        data class Failed(val message: String) : StepResult()
    }

    /**
     * Execute a single tool call from Groq's function calling response.
     *
     * @param toolName The name of the tool to call
     * @param args The arguments for the tool
     * @param context Android context for launching intents
     * @return StepResult indicating success or failure
     */
    suspend fun executeToolCall(toolName: String, args: Map<String, String>, context: Context): StepResult {
        Log.i(TAG, "[executeToolCall] tool=$toolName args=$args")

        return when (toolName) {
            "open_and_search" -> executeOpenAndSearch(args, context)
            "click_button" -> executeClickButton(args)
            "click_ui_element" -> executeClickUiElement(args)
            "inject_text" -> executeInjectText(args)
            "scroll" -> executeScroll(args)
            "scroll_screen" -> executeScrollScreen(args)
            "go_back" -> executeGoBack()
            "go_home" -> executeGoHome()
            "perform_global_action" -> executeGlobalAction(args)
            "open_app" -> executeOpenApp(args, context)
            "search_playstore" -> executeSearchPlayStore(args, context)
            "dispatch_gesture" -> executeDispatchGesture(args)
            "dump_screen" -> executeDumpScreen()
            "diagnose_system" -> executeDiagnoseSystem(args)
            "search_web" -> executeSearchWeb(args)
            "set_alarm" -> executeSetAlarm(args, context)
            "open_url" -> executeOpenUrl(args, context)
            "copy_to_clipboard" -> executeCopyToClipboard(args, context)
            "get_battery_status" -> executeGetBatteryStatus(context)
            "get_device_info" -> executeGetDeviceInfo(context)
            "toggle_wifi" -> executeToggleWifi(args, context)
            "toggle_bluetooth" -> executeToggleBluetooth(args, context)
            "set_brightness" -> executeSetBrightness(args, context)
            "make_phone_call" -> executeMakePhoneCall(args, context)
            "send_sms" -> executeSendSms(args, context)
            "take_screenshot" -> executeTakeScreenshot()
            "set_volume" -> executeSetVolume(args, context)
            "create_calendar_event" -> executeCreateCalendarEvent(args, context)
            "read_notifications" -> executeReadNotifications(args)
            "press_key" -> executePressKey(args)
            "swipe_gesture" -> executeSwipeGesture(args)
            else -> StepResult.Failed("Unknown tool: $toolName")
        }
    }

    /**
     * Execute a multi-step task chain.
     * Each step is a tool call. Steps are executed sequentially with
     * appropriate delays between them to allow the UI to update.
     *
     * @param steps List of (toolName, args) pairs
     * @param context Android context
     * @param onStepComplete Callback after each step completes
     * @return List of results for each step
     */
    suspend fun executeTaskChain(
        steps: List<Pair<String, Map<String, String>>>,
        context: Context,
        onStepComplete: ((Int, StepResult) -> Unit)? = null
    ): List<StepResult> {
        val results = mutableListOf<StepResult>()

        for ((index, step) in steps.withIndex()) {
            val (toolName, args) = step
            Log.i(TAG, "[executeTaskChain] Step ${index + 1}/${steps.size}: $toolName($args)")

            val result = executeToolCall(toolName, args, context)
            results.add(result)
            onStepComplete?.invoke(index, result)

            // If a step fails, decide whether to continue
            if (result is StepResult.Failed) {
                Log.w(TAG, "[executeTaskChain] Step ${index + 1} failed: ${result.message}")
                // Continue with remaining steps — some failures are expected
                // (e.g., button not found yet because page hasn't loaded)
            }

            // Inter-step delay: let the UI update between actions
            if (index < steps.size - 1) {
                val delayMs = when (toolName) {
                    "open_and_search", "open_app" -> 2000L  // Wait for app to launch
                    "click_button" -> 800L                   // Wait for UI response
                    "inject_text" -> 500L                    // Brief pause after typing
                    else -> 500L
                }
                kotlinx.coroutines.delay(delayMs)
            }
        }

        Log.i(TAG, "[executeTaskChain] Completed ${results.size} steps: " +
                "${results.count { it is StepResult.Success }} success, " +
                "${results.count { it is StepResult.Failed }} failed")

        return results
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Tool Implementations
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * open_and_search — Opens an app and searches for a query.
     *
     * Uses deep links / search intents for common apps (YouTube, Maps, PlayStore)
     * to bypass manual clicking whenever possible. Falls back to opening the
     * app and then using accessibility to type in the search field.
     */
    private suspend fun executeOpenAndSearch(args: Map<String, String>, context: Context): StepResult {
        val app = args["app"]?.lowercase()?.trim() ?: return StepResult.Failed("Missing 'app' argument")
        val query = args["query"]?.trim() ?: return StepResult.Failed("Missing 'query' argument")

        Log.i(TAG, "[open_and_search] app=$app query=$query")

        // Try deep link / search intent first for known apps
        val deepLinkResult = tryDeepLinkSearch(app, query, context)
        if (deepLinkResult != null) return deepLinkResult

        // Fallback: open the app, then use accessibility to search
        val packageName = AppRegistry.appAliases[app]
        if (packageName != null) {
            val launched = launchApp(packageName, context)
            if (launched) {
                // After app launches, try to find and click the search field
                // This requires the accessibility service
                return try {
                    delay(2000) // Wait for app to load
                    val svc = accessibilityService?.get()
                    if (svc != null) {
                        // Try to find and click search icon/field
                        val searchClicked = svc.autoClick("search") ||
                                svc.autoClick("Search") ||
                                svc.clickNodeById("search") ||
                                svc.clickNodeById("menu_search") ||
                                svc.clickNodeById("search_bar")

                        if (searchClicked) {
                            delay(500)
                            // Inject the query into the now-focused search field
                            val injected = svc.injectTextToFocusedField(query)
                            if (injected) {
                                // Press Enter/Search
                                delay(300)
                                svc.autoClick("search") || svc.autoClick("Search")
                                StepResult.Success("Opened $app and searched for '$query'")
                            } else {
                                StepResult.Success("Opened $app, found search field but couldn't inject text")
                            }
                        } else {
                            StepResult.Success("Opened $app but couldn't find search field")
                        }
                    } else {
                        StepResult.Success("Opened $app — accessibility not available for search automation")
                    }
                } catch (e: Exception) {
                    StepResult.Failed("Error during search: ${e.message}")
                }
            }
        }

        return StepResult.Failed("Could not open app '$app'")
    }

    /**
     * Try to open an app using deep links / search intents.
     * Returns null if no deep link is available for this app.
     */
    private fun tryDeepLinkSearch(app: String, query: String, context: Context): StepResult? {
        return when (app) {
            "youtube" -> {
                try {
                    // YouTube search intent — much faster than manual clicking
                    val intent = Intent(Intent.ACTION_SEARCH).apply {
                        setPackage("com.google.android.youtube")
                        putExtra("query", query)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "[deepLink] YouTube search intent launched for '$query'")
                    StepResult.Success("Searching YouTube for '$query' via deep link")
                } catch (e: Exception) {
                    Log.w(TAG, "[deepLink] YouTube search intent failed: ${e.message}")
                    null // Fall through to regular app launch
                }
            }

            "maps" -> {
                try {
                    // Google Maps geo intent — instant search
                    val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        setPackage("com.google.android.apps.maps")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "[deepLink] Maps geo intent launched for '$query'")
                    StepResult.Success("Searching Maps for '$query' via deep link")
                } catch (e: Exception) {
                    Log.w(TAG, "[deepLink] Maps intent failed: ${e.message}")
                    null
                }
            }

            "play store", "playstore" -> {
                try {
                    // Play Store search intent
                    val uri = Uri.parse("market://search?q=${Uri.encode(query)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "[deepLink] Play Store search intent launched for '$query'")
                    StepResult.Success("Searching Play Store for '$query' via deep link")
                } catch (e: Exception) {
                    // Fallback to web Play Store
                    try {
                        val uri = Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}")
                        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        StepResult.Success("Searching Play Store for '$query' via web link")
                    } catch (e2: Exception) {
                        null
                    }
                }
            }

            "google" -> {
                try {
                    val uri = Uri.parse("https://www.google.com/search?q=${Uri.encode(query)}")
                    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    StepResult.Success("Searching Google for '$query'")
                } catch (e: Exception) {
                    null
                }
            }

            else -> null // No deep link available
        }
    }

    private suspend fun executeClickButton(args: Map<String, String>): StepResult {
        val label = args["label"]?.trim() ?: return StepResult.Failed("Missing 'label' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val clicked = svc.autoClick(label)
            if (clicked) {
                delay(500)
                return StepResult.Success("Clicked button '$label'")
            }
        }

        // Better Shizuku fallback: use uiautomator to find and click by text
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[click_button] Accessibility failed, trying Shizuku fallback for '$label'")
            // Try to find the element's coordinates via uiautomator dump
            val dumpResult = ShizukuManager.executeShellCommand("uiautomator dump /dev/tty 2>/dev/null")
            if (dumpResult.isSuccess && dumpResult.stdout.isNotBlank()) {
                // Parse bounds from XML - find the element with matching text
                val boundsRegex = """text="[^"]*$label[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)]""".toRegex(RegexOption.IGNORE_CASE)
                val match = boundsRegex.find(dumpResult.stdout)
                if (match != null) {
                    val left = match.groupValues[1].toIntOrNull() ?: 0
                    val top = match.groupValues[2].toIntOrNull() ?: 0
                    val right = match.groupValues[3].toIntOrNull() ?: 0
                    val bottom = match.groupValues[4].toIntOrNull() ?: 0
                    val centerX = (left + right) / 2
                    val centerY = (top + bottom) / 2
                    val tapResult = ShizukuManager.executeShellCommand("input tap $centerX $centerY")
                    if (tapResult.isSuccess) {
                        delay(500)
                        return StepResult.Success("Clicked '$label' at ($centerX, $centerY) via Shizuku")
                    }
                }
            }
            // No valid fallback — KEYCODE_ENTER does not click a button
            return StepResult.Failed("All click methods failed for '$label'")
        }

        return StepResult.Failed("Could not click '$label' — enable Accessibility Service or Shizuku")
    }

    private suspend fun executeInjectText(args: Map<String, String>): StepResult {
        val content = args["content"]?.trim() ?: return StepResult.Failed("Missing 'content' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val injected = svc.injectTextToFocusedField(content)
            if (injected) {
                delay(300)
                return StepResult.Success("Injected ${content.length} characters into focused field")
            }
        }

        // Fallback: Shizuku shell command for text injection
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[inject_text] Accessibility failed, trying Shizuku fallback")
            // URL-encode the text for the input command, replacing spaces with %s
            val encodedContent = content.replace(" ", "%s").replace("&", "\\&")
                .replace("<", "\\<").replace(">", "\\>").replace("(", "\\(")
                .replace(")", "\\)").replace(";", "\\;").replace("|", "\\|")
                .replace("*", "\\*").replace("~", "\\~").replace("\"", "\\\"")
                .replace("'", "\\'").replace("`", "\\`")
            val result = ShizukuManager.executeShellCommand("input text \"$encodedContent\"")
            return if (result.isSuccess) {
                delay(300)
                StepResult.Success("Injected text via Shizuku: ${content.length} chars")
            } else {
                StepResult.Failed("Shizuku text injection failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not inject text — enable Accessibility Service or Shizuku")
    }

    private suspend fun executeScroll(args: Map<String, String>): StepResult {
        val direction = args["direction"]?.lowercase()?.trim() ?: "down"
        if (direction !in listOf("up", "down")) {
            return StepResult.Failed("Invalid scroll direction: $direction (use 'up' or 'down')")
        }

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val scrolled = svc.performScroll(direction)
            if (scrolled) {
                delay(400)
                return StepResult.Success("Scrolled $direction")
            }
        }

        // Fallback: Shizuku shell command for swipe-based scrolling
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[scroll] Accessibility failed, trying Shizuku fallback for $direction")
            val sw = com.jarvis.assistant.channels.JarviewModel.screenWidth
            val sh = com.jarvis.assistant.channels.JarviewModel.screenHeight
            // Fallback to 1080x1920 if screen dimensions not yet populated
            val effectiveSw = if (sw > 0) sw else 1080
            val effectiveSh = if (sh > 0) sh else 1920
            val centerX = effectiveSw / 2
            val centerY = effectiveSh / 2
            val swipeCmd = when (direction) {
                "down" -> "input swipe $centerX ${(effectiveSh * 0.8).toInt()} $centerX ${(effectiveSh * 0.2).toInt()}"
                "up" -> "input swipe $centerX ${(effectiveSh * 0.2).toInt()} $centerX ${(effectiveSh * 0.8).toInt()}"
                else -> null
            }
            if (swipeCmd != null) {
                val result = ShizukuManager.executeShellCommand(swipeCmd)
                return if (result.isSuccess) {
                    delay(400)
                    StepResult.Success("Scrolled $direction via Shizuku")
                } else {
                    StepResult.Failed("Shizuku scroll $direction failed: ${result.stderr}")
                }
            }
        }

        return StepResult.Failed("Could not scroll $direction — enable Accessibility Service or Shizuku")
    }

    private fun executeGoBack(): StepResult {
        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            if (svc.goBack()) return StepResult.Success("Went back")
        }

        // Fallback: Shizuku shell command
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[go_back] Accessibility failed, trying Shizuku fallback")
            val result = ShizukuManager.executeShellCommand("input keyevent KEYCODE_BACK")
            return if (result.isSuccess) {
                StepResult.Success("Went back via Shizuku")
            } else {
                StepResult.Failed("Shizuku go back failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not go back — enable Accessibility Service or Shizuku")
    }

    private fun executeGoHome(): StepResult {
        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            if (svc.goHome()) return StepResult.Success("Went home")
        }

        // Fallback: Shizuku shell command
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[go_home] Accessibility failed, trying Shizuku fallback")
            val result = ShizukuManager.executeShellCommand("input keyevent KEYCODE_HOME")
            return if (result.isSuccess) {
                StepResult.Success("Went home via Shizuku")
            } else {
                StepResult.Failed("Shizuku go home failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not go home — enable Accessibility Service or Shizuku")
    }

    private fun executeOpenApp(args: Map<String, String>, context: Context): StepResult {
        val app = args["app"]?.lowercase()?.trim() ?: return StepResult.Failed("Missing 'app' argument")
        val packageName = AppRegistry.appAliases[app]
            ?: return StepResult.Failed("Unknown app: $app")

        val launched = launchApp(packageName, context)
        return if (launched) {
            StepResult.Success("Opened $app")
        } else {
            StepResult.Failed("Could not open $app")
        }
    }

    /**
     * search_playstore — Search for an app on Google Play Store.
     * Uses the Play Store's search URI scheme to open directly
     * to search results for the given query.
     */
    private fun executeSearchPlayStore(args: Map<String, String>, context: Context): StepResult {
        val query = args["query"]?.trim() ?: return StepResult.Failed("Missing 'query' argument")
        return try {
            val uri = Uri.parse("market://search?q=${Uri.encode(query)}")
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[searchPlayStore] Opened Play Store search for: $query")
            StepResult.Success("Opened Play Store searching for '$query'")
        } catch (e: Exception) {
            // Fallback: open Play Store via web URL if market:// URI fails
            try {
                val webUri = Uri.parse("https://play.google.com/store/search?q=${Uri.encode(query)}")
                val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(webIntent)
                Log.i(TAG, "[searchPlayStore] Opened Play Store via web for: $query")
                StepResult.Success("Opened Play Store (web) searching for '$query'")
            } catch (e2: Exception) {
                Log.e(TAG, "[searchPlayStore] Failed: ${e2.message}")
                StepResult.Failed("Could not open Play Store: ${e2.message}")
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEW AUTONOMOUS AGENT TOOL IMPLEMENTATIONS
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * click_ui_element — Click a UI element by text label or view ID.
     * Enhanced version of click_button that also supports view IDs.
     */
    private suspend fun executeClickUiElement(args: Map<String, String>): StepResult {
        val textOrId = args["text_or_id"]?.trim() ?: return StepResult.Failed("Missing 'text_or_id' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            // Try clicking by text first, then by ID
            val clickedByText = svc.autoClick(textOrId)
            if (clickedByText) {
                delay(500)
                return StepResult.Success("Clicked UI element with text '$textOrId'")
            }

            // If text click failed, try by view ID
            val clickedById = svc.clickNodeById(textOrId)
            if (clickedById) {
                delay(500)
                return StepResult.Success("Clicked UI element with ID '$textOrId'")
            }
        }

        // Better Shizuku fallback: use uiautomator to find and click by text
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[click_ui_element] Accessibility failed, trying Shizuku fallback for '$textOrId'")
            // Try to find the element's coordinates via uiautomator dump
            val dumpResult = ShizukuManager.executeShellCommand("uiautomator dump /dev/tty 2>/dev/null")
            if (dumpResult.isSuccess && dumpResult.stdout.isNotBlank()) {
                // Parse bounds from XML - find the element with matching text
                val boundsRegex = """text="[^"]*$textOrId[^"]*"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)]""".toRegex(RegexOption.IGNORE_CASE)
                val match = boundsRegex.find(dumpResult.stdout)
                if (match != null) {
                    val left = match.groupValues[1].toIntOrNull() ?: 0
                    val top = match.groupValues[2].toIntOrNull() ?: 0
                    val right = match.groupValues[3].toIntOrNull() ?: 0
                    val bottom = match.groupValues[4].toIntOrNull() ?: 0
                    val centerX = (left + right) / 2
                    val centerY = (top + bottom) / 2
                    val tapResult = ShizukuManager.executeShellCommand("input tap $centerX $centerY")
                    if (tapResult.isSuccess) {
                        delay(500)
                        return StepResult.Success("Clicked '$textOrId' at ($centerX, $centerY) via Shizuku")
                    }
                }
            }
            // No valid fallback — KEYCODE_ENTER does not click a UI element
            return StepResult.Failed("All click methods failed for '$textOrId'")
        }

        return StepResult.Failed("Could not find or click UI element '$textOrId' — enable Accessibility Service or Shizuku. Try dump_screen to see available elements.")
    }

    /**
     * scroll_screen — Enhanced scroll with left/right support.
     */
    private fun executeScrollScreen(args: Map<String, String>): StepResult {
        val direction = args["direction"]?.lowercase()?.trim() ?: "down"
        if (direction !in listOf("up", "down", "left", "right")) {
            return StepResult.Failed("Invalid scroll direction: $direction (use 'up', 'down', 'left', or 'right')")
        }

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val scrolled = svc.performScroll(direction)
            if (scrolled) return StepResult.Success("Scrolled $direction")
        }

        // Fallback: Shizuku shell command for swipe-based scrolling
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[scroll_screen] Accessibility failed, trying Shizuku fallback for $direction")
            val sw = com.jarvis.assistant.channels.JarviewModel.screenWidth
            val sh = com.jarvis.assistant.channels.JarviewModel.screenHeight
            // Fallback to 1080x1920 if screen dimensions not yet populated
            val effectiveSw = if (sw > 0) sw else 1080
            val effectiveSh = if (sh > 0) sh else 1920
            val centerX = effectiveSw / 2
            val centerY = effectiveSh / 2
            val swipeCmd = when (direction) {
                "down" -> "input swipe $centerX ${(effectiveSh * 0.8).toInt()} $centerX ${(effectiveSh * 0.2).toInt()}"
                "up" -> "input swipe $centerX ${(effectiveSh * 0.2).toInt()} $centerX ${(effectiveSh * 0.8).toInt()}"
                "left" -> "input swipe ${(effectiveSw * 0.8).toInt()} $centerY ${(effectiveSw * 0.2).toInt()} $centerY"
                "right" -> "input swipe ${(effectiveSw * 0.2).toInt()} $centerY ${(effectiveSw * 0.8).toInt()} $centerY"
                else -> null
            }
            if (swipeCmd != null) {
                val result = ShizukuManager.executeShellCommand(swipeCmd)
                return if (result.isSuccess) {
                    StepResult.Success("Scrolled $direction via Shizuku")
                } else {
                    StepResult.Failed("Shizuku scroll $direction failed: ${result.stderr}")
                }
            }
        }

        return StepResult.Failed("Could not scroll $direction — enable Accessibility Service or Shizuku")
    }

    /**
     * perform_global_action — Execute system navigation actions.
     */
    private fun executeGlobalAction(args: Map<String, String>): StepResult {
        val actionType = args["action_type"]?.lowercase()?.trim()
            ?: return StepResult.Failed("Missing 'action_type' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val result = when (actionType) {
                "back" -> svc.goBack()
                "home" -> svc.goHome()
                "recents" -> svc.openRecents()
                "notifications" -> svc.openNotifications()
                "quick_settings" -> svc.openQuickSettings()
                "power_dialog" -> svc.openPowerDialog()
                "screenshot" -> svc.takeScreenshot()
                else -> return StepResult.Failed("Unknown global action: $actionType")
            }
            if (result) return StepResult.Success("Performed global action: $actionType")
        }

        // Fallback: Shizuku shell commands for common global actions
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[perform_global_action] Accessibility failed, trying Shizuku fallback for $actionType")
            val shellCmd = when (actionType) {
                "back" -> "input keyevent KEYCODE_BACK"
                "home" -> "input keyevent KEYCODE_HOME"
                "recents" -> "input keyevent KEYCODE_APP_SWITCH"
                "notifications" -> "input swipe 0 0 0 500" // swipe down from top
                "quick_settings" -> "input swipe 0 0 0 500" // same gesture
                "power_dialog" -> "input keyevent KEYCODE_POWER"
                else -> null
            }
            if (shellCmd != null) {
                val result = ShizukuManager.executeShellCommand(shellCmd)
                return if (result.isSuccess) {
                    StepResult.Success("Performed $actionType via Shizuku")
                } else {
                    StepResult.Failed("Shizuku $actionType failed: ${result.stderr}")
                }
            }
        }

        return StepResult.Failed("Could not perform global action: $actionType — enable Accessibility Service or Shizuku")
    }

    /**
     * dispatch_gesture — Tap or swipe at exact coordinates.
     */
    private fun executeDispatchGesture(args: Map<String, String>): StepResult {
        val x = args["x"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'x' coordinate")
        val y = args["y"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'y' coordinate")
        val action = args["action"]?.lowercase()?.trim() ?: "tap"

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val result = when (action) {
                "tap" -> svc.performTap(x, y)
                "long_press" -> svc.performLongPress(x, y)
                "swipe_up" -> svc.performSwipe(x, y + 300, x, y - 300)
                "swipe_down" -> svc.performSwipe(x, y - 300, x, y + 300)
                else -> return StepResult.Failed("Unknown gesture action: $action")
            }
            if (result) return StepResult.Success("Dispatched gesture: $action at ($x, $y)")
        }

        // Fallback: Shizuku shell commands for gestures
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[dispatch_gesture] Accessibility failed, trying Shizuku fallback for $action at ($x, $y)")
            val shellCmd = when (action) {
                "tap" -> "input tap $x $y"
                "long_press" -> "input swipe $x $y $x $y 800" // hold position for 800ms
                "swipe_up" -> "input swipe $x ${y + 300} $x ${y - 300}"
                "swipe_down" -> "input swipe $x ${y - 300} $x ${y + 300}"
                else -> return StepResult.Failed("Unknown gesture action: $action")
            }
            val result = ShizukuManager.executeShellCommand(shellCmd)
            return if (result.isSuccess) {
                StepResult.Success("Dispatched gesture via Shizuku: $action at ($x, $y)")
            } else {
                StepResult.Failed("Shizuku gesture $action failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not dispatch gesture: $action at ($x, $y) — enable Accessibility Service or Shizuku")
    }

    /**
     * dump_screen — Read the current screen for AI context.
     * Returns a structured description of all visible interactive elements.
     */
    private fun executeDumpScreen(): StepResult {
        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val screenContext = svc.dumpScreenForAI()
            return StepResult.Success(screenContext)
        }

        // Fallback: Shizuku shell command to dump UI hierarchy
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[dump_screen] Accessibility failed, trying Shizuku fallback")
            val result = ShizukuManager.executeShellCommand("uiautomator dump /dev/tty 2>/dev/null")
            return if (result.isSuccess && result.stdout.isNotBlank()) {
                StepResult.Success("Screen dump via Shizuku (raw XML): ${result.stdout.take(3000)}")
            } else {
                StepResult.Failed("Shizuku screen dump failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Cannot read screen — enable Accessibility Service or Shizuku")
    }

    /**
     * diagnose_system — Read JARVIS's own crash logs for self-diagnosis.
     */
    private fun executeDiagnoseSystem(args: Map<String, String>): StepResult {
        val issueType = args["issue_type"]?.trim() ?: "general"
        val report = SelfDiagnosticManager.diagnoseIssue(issueType)
        return StepResult.Success(report)
    }

    /**
     * search_web — Search the web using the integrated WebSearchEngine.
     * Uses Google Custom Search API if configured, otherwise falls back to DuckDuckGo.
     */
    private suspend fun executeSearchWeb(args: Map<String, String>): StepResult {
        val query = args["query"]?.trim() ?: return StepResult.Failed("Missing 'query' argument")
        return try {
            val result = withContext(Dispatchers.IO) {
                WebSearchEngine.search(query)
            }
            StepResult.Success(result)
        } catch (e: Exception) {
            Log.e(TAG, "[executeSearchWeb] Error: ${e.message}")
            StepResult.Failed("Web search failed: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Alarm / Timer / Reminder
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * set_alarm — Set an alarm, timer, or reminder via Android intents.
     */
    private suspend fun executeSetAlarm(args: Map<String, String>, context: Context): StepResult {
        val type = args["type"]?.lowercase()?.trim() ?: "alarm"
        val hour = args["hour"]?.toIntOrNull()
        val minute = args["minute"]?.toIntOrNull() ?: 0
        val message = args["message"]?.trim() ?: ""

        return try {
            when (type) {
                "alarm" -> {
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (hour != null) putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        if (message.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    context.startActivity(intent)
                    val timeStr = if (hour != null) String.format("%02d:%02d", hour, minute) else ""
                    Log.i(TAG, "[setAlarm] Alarm set for $timeStr — $message")
                    StepResult.Success("Alarm set for $timeStr${if (message.isNotBlank()) " — $message" else ""}")
                }
                "timer" -> {
                    val durationSeconds = hour?.let { it * 3600 + minute * 60 } ?: (minute * 60)
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (durationSeconds > 0) durationSeconds else 60)
                        if (message.isNotBlank()) putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, message)
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    context.startActivity(intent)
                    Log.i(TAG, "[setTimer] Timer set for $durationSeconds seconds — $message")
                    StepResult.Success("Timer set for ${durationSeconds / 60} minutes${if (message.isNotBlank()) " — $message" else ""}")
                }
                "reminder" -> {
                    // Android doesn't have a standard reminder intent; use alarm as fallback
                    val intent = Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (hour != null) putExtra(android.provider.AlarmClock.EXTRA_HOUR, hour)
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, minute)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Reminder: ${message.ifBlank { "Reminder" }}")
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                    }
                    context.startActivity(intent)
                    val timeStr = if (hour != null) String.format("%02d:%02d", hour, minute) else ""
                    Log.i(TAG, "[setReminder] Reminder set for $timeStr — $message")
                    StepResult.Success("Reminder set for $timeStr — ${message.ifBlank { "Reminder" }}")
                }
                else -> StepResult.Failed("Unknown alarm type: $type (use 'alarm', 'timer', or 'reminder')")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[setAlarm] Failed: ${e.message}")
            StepResult.Failed("Could not set $type: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NEW v17 TOOLS — Open URL, Clipboard, Battery, Device Info, WiFi, BT, Brightness
    // ═══════════════════════════════════════════════════════════════════════

    private fun executeOpenUrl(args: Map<String, String>, context: Context): StepResult {
        val url = args["url"]?.trim() ?: return StepResult.Failed("Missing 'url' argument")
        return try {
            var finalUrl = url
            if (!finalUrl.startsWith("http://") && !finalUrl.startsWith("https://")) {
                finalUrl = "https://$finalUrl"
            }
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(finalUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[openUrl] Opened URL: $finalUrl")
            StepResult.Success("Opened URL: $finalUrl")
        } catch (e: Exception) {
            Log.e(TAG, "[openUrl] Failed: ${e.message}")
            StepResult.Failed("Failed to open URL: ${e.message}")
        }
    }

    private fun executeCopyToClipboard(args: Map<String, String>, context: Context): StepResult {
        val text = args["text"]?.trim() ?: return StepResult.Failed("Missing 'text' argument")
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("JARVIS", text)
            clipboard.setPrimaryClip(clip)
            Log.i(TAG, "[copyToClipboard] Copied ${text.length} chars to clipboard")
            StepResult.Success("Copied to clipboard: \"${text.take(100)}${if (text.length > 100) "..." else ""}\"")
        } catch (e: Exception) {
            Log.e(TAG, "[copyToClipboard] Failed: ${e.message}")
            StepResult.Failed("Failed to copy to clipboard: ${e.message}")
        }
    }

    private fun executeGetBatteryStatus(context: Context): StepResult {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            val chargeStatus = if (isCharging) "Charging" else "Discharging"
            val status = "Battery: $level% — $chargeStatus"
            Log.i(TAG, "[getBatteryStatus] $status")
            StepResult.Success(status)
        } catch (e: Exception) {
            StepResult.Failed("Failed to get battery status: ${e.message}")
        }
    }

    private fun executeGetDeviceInfo(context: Context): StepResult {
        return try {
            val info = StringBuilder()
            info.append("Device: ${android.os.Build.MODEL}\n")
            info.append("Manufacturer: ${android.os.Build.MANUFACTURER}\n")
            info.append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
            info.append("Security Patch: ${android.os.Build.VERSION.SECURITY_PATCH}\n")

            // Screen resolution
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val display = windowManager.defaultDisplay
            val metrics = android.util.DisplayMetrics()
            display.getMetrics(metrics)
            info.append("Screen: ${metrics.widthPixels}x${metrics.heightPixels} (${metrics.densityDpi}dpi)\n")

            // Storage
            val statFs = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val totalBytes = statFs.totalBytes
            val availableBytes = statFs.availableBytes
            info.append("Storage: ${availableBytes / (1024*1024*1024)}GB free / ${totalBytes / (1024*1024*1024)}GB total\n")

            // RAM
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memInfo)
            info.append("RAM: ${memInfo.availMem / (1024*1024)}MB free / ${memInfo.totalMem / (1024*1024)}MB total")

            val result = info.toString()
            Log.i(TAG, "[getDeviceInfo] Collected device info")
            StepResult.Success(result)
        } catch (e: Exception) {
            StepResult.Failed("Failed to get device info: ${e.message}")
        }
    }

    private fun executeToggleWifi(args: Map<String, String>, context: Context): StepResult {
        val enable = args["enable"]?.toBoolean() ?: return StepResult.Failed("Missing 'enable' argument")
        return try {
            // Try Shizuku first
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val shizukuResult = ShizukuManager.executeShellCommand("svc wifi ${if (enable) "enable" else "disable"}")
                if (shizukuResult.isSuccess) {
                    Log.i(TAG, "[toggleWifi] WiFi ${if (enable) "enabled" else "disabled"} via Shizuku")
                    return StepResult.Success("WiFi ${if (enable) "enabled" else "disabled"} via Shizuku")
                }
            }
            // Fallback: Open WiFi settings via accessibility
            val svc = accessibilityService?.get()
            if (svc != null) {
                val intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                StepResult.Success("Opened WiFi settings — please toggle manually if needed")
            } else {
                StepResult.Failed("Cannot toggle WiFi — enable Shizuku or Accessibility Service")
            }
        } catch (e: Exception) {
            StepResult.Failed("Failed to toggle WiFi: ${e.message}")
        }
    }

    private fun executeToggleBluetooth(args: Map<String, String>, context: Context): StepResult {
        val enable = args["enable"]?.toBoolean() ?: return StepResult.Failed("Missing 'enable' argument")
        return try {
            if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
                val shizukuResult = ShizukuManager.executeShellCommand("svc bluetooth ${if (enable) "enable" else "disable"}")
                if (shizukuResult.isSuccess) {
                    return StepResult.Success("Bluetooth ${if (enable) "enabled" else "disabled"} via Shizuku")
                }
            }
            // Fallback: Open Bluetooth settings
            val intent = Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            StepResult.Success("Opened Bluetooth settings — please toggle manually if needed")
        } catch (e: Exception) {
            StepResult.Failed("Failed to toggle Bluetooth: ${e.message}")
        }
    }

    private fun executeSetBrightness(args: Map<String, String>, context: Context): StepResult {
        val level = args["level"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'level' argument (0-255)")
        if (level < 0 || level > 255) return StepResult.Failed("Brightness must be 0-255, got $level")
        return try {
            // Try writing to system settings
            android.provider.Settings.System.putInt(
                context.contentResolver,
                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                level
            )
            Log.i(TAG, "[setBrightness] Set brightness to $level")
            StepResult.Success("Screen brightness set to $level (of 255)")
        } catch (e: Exception) {
            StepResult.Failed("Failed to set brightness: ${e.message}. May need WRITE_SETTINGS permission.")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Phone / SMS / Screenshot / Volume
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * make_phone_call — Initiate a phone call.
     * Uses ACTION_CALL if the app has CALL_PHONE permission, otherwise
     * falls back to ACTION_DIAL which opens the dialer.
     */
    private fun executeMakePhoneCall(args: Map<String, String>, context: Context): StepResult {
        val phoneNumber = args["phone_number"]?.trim() ?: args["number"]?.trim()
            ?: return StepResult.Failed("Missing 'phone_number' argument")

        return try {
            // Try ACTION_CALL first (requires CALL_PHONE permission)
            val callIntent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(callIntent)
            Log.i(TAG, "[makePhoneCall] Calling $phoneNumber via ACTION_CALL")
            StepResult.Success("Calling $phoneNumber")
        } catch (e: SecurityException) {
            // No CALL_PHONE permission — fall back to ACTION_DIAL (opens dialer)
            try {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
                Log.i(TAG, "[makePhoneCall] Opened dialer for $phoneNumber (no CALL_PHONE permission)")
                StepResult.Success("Opened dialer for $phoneNumber (CALL_PHONE permission not granted)")
            } catch (e2: Exception) {
                Log.e(TAG, "[makePhoneCall] Failed to open dialer: ${e2.message}")
                StepResult.Failed("Failed to call $phoneNumber: ${e2.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[makePhoneCall] Failed: ${e.message}")
            StepResult.Failed("Failed to call $phoneNumber: ${e.message}")
        }
    }

    /**
     * send_sms — Open SMS app with pre-filled number and message.
     * Uses ACTION_SENDTO with smsto: URI scheme.
     */
    private fun executeSendSms(args: Map<String, String>, context: Context): StepResult {
        val phoneNumber = args["phone_number"]?.trim() ?: args["number"]?.trim()
            ?: return StepResult.Failed("Missing 'phone_number' argument")
        val message = args["message"]?.trim() ?: ""

        return try {
            val uri = Uri.parse("smsto:$phoneNumber")
            val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (message.isNotBlank()) {
                    putExtra("sms_body", message)
                }
            }
            context.startActivity(intent)
            Log.i(TAG, "[sendSms] Opened SMS for $phoneNumber${if (message.isNotBlank()) " with message" else ""}")
            StepResult.Success("Opened SMS to $phoneNumber${if (message.isNotBlank()) " with pre-filled message" else ""}")
        } catch (e: Exception) {
            Log.e(TAG, "[sendSms] Failed: ${e.message}")
            StepResult.Failed("Failed to send SMS to $phoneNumber: ${e.message}")
        }
    }

    /**
     * take_screenshot — Take a screenshot using the accessibility service.
     * Falls back to Shizuku screencap command if accessibility is unavailable.
     */
    private fun executeTakeScreenshot(): StepResult {
        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val result = svc.takeScreenshot()
            if (result) {
                Log.i(TAG, "[takeScreenshot] Screenshot taken via Accessibility Service")
                return StepResult.Success("Screenshot taken via Accessibility Service")
            }
        }

        // Fallback: Shizuku screencap command
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[takeScreenshot] Accessibility failed, trying Shizuku fallback")
            val screenshotsDir = java.io.File("/sdcard/Pictures/Jarvis/Screenshots")
            if (!screenshotsDir.exists()) screenshotsDir.mkdirs()
            val screenshotFile = java.io.File(screenshotsDir, "screenshot_${System.currentTimeMillis()}.png")
            val result = ShizukuManager.executeShellCommand("screencap -p ${screenshotFile.absolutePath}")
            return if (result.isSuccess) {
                Log.i(TAG, "[takeScreenshot] Screenshot saved to ${screenshotFile.absolutePath}")
                StepResult.Success("Screenshot saved to ${screenshotFile.absolutePath}")
            } else {
                StepResult.Failed("Shizuku screenshot failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Cannot take screenshot — enable Accessibility Service or Shizuku")
    }

    /**
     * set_volume — Set the media volume level.
     * Uses AudioManager to adjust the media stream volume.
     */
    private fun executeSetVolume(args: Map<String, String>, context: Context): StepResult {
        val level = args["level"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'level' argument")
        val streamType = args["stream"]?.lowercase()?.trim() ?: "media"

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager

            val stream = when (streamType) {
                "media", "music" -> android.media.AudioManager.STREAM_MUSIC
                "ring", "ringer", "ringtone" -> android.media.AudioManager.STREAM_RING
                "alarm" -> android.media.AudioManager.STREAM_ALARM
                "notification", "notifications" -> android.media.AudioManager.STREAM_NOTIFICATION
                "system" -> android.media.AudioManager.STREAM_SYSTEM
                "voice_call", "call" -> android.media.AudioManager.STREAM_VOICE_CALL
                else -> android.media.AudioManager.STREAM_MUSIC
            }

            val maxVolume = audioManager.getStreamMaxVolume(stream)
            val clampedLevel = level.coerceIn(0, maxVolume)
            audioManager.setStreamVolume(stream, clampedLevel, 0)

            Log.i(TAG, "[setVolume] Set $streamType volume to $clampedLevel (max: $maxVolume)")
            StepResult.Success("Set $streamType volume to $clampedLevel (max: $maxVolume)")
        } catch (e: SecurityException) {
            Log.e(TAG, "[setVolume] Permission denied: ${e.message}")
            StepResult.Failed("Failed to set volume: Permission denied. May need WRITE_SETTINGS or Do Not Disturb access.")
        } catch (e: Exception) {
            Log.e(TAG, "[setVolume] Failed: ${e.message}")
            StepResult.Failed("Failed to set volume: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════════════
    // NEW v21 TOOLS — Calendar, Notifications, Key Press, Swipe Gesture
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * create_calendar_event — Create a calendar event using Android's Calendar Provider.
     * Requires WRITE_CALENDAR permission.
     */
    private fun executeCreateCalendarEvent(args: Map<String, String>, context: Context): StepResult {
        val title = args["title"]?.trim() ?: return StepResult.Failed("Missing 'title' argument")
        val startTimeStr = args["start_time"]?.trim() ?: return StepResult.Failed("Missing 'start_time' argument")
        val endTimeStr = args["end_time"]?.trim()
        val description = args["description"]?.trim() ?: ""
        val location = args["location"]?.trim() ?: ""

        return try {
            // Parse ISO 8601 start time
            val startMillis = parseIso8601ToMillis(startTimeStr)
                ?: return StepResult.Failed("Invalid start_time format: '$startTimeStr'. Use ISO 8601 (e.g., '2025-03-15T14:00:00')")

            // Parse end time, default to 1 hour after start
            val endMillis = if (endTimeStr != null) {
                parseIso8601ToMillis(endTimeStr) ?: startMillis + 3600_000L
            } else {
                startMillis + 3600_000L
            }

            val values = android.content.ContentValues().apply {
                put(android.provider.CalendarContract.Events.DTSTART, startMillis)
                put(android.provider.CalendarContract.Events.DTEND, endMillis)
                put(android.provider.CalendarContract.Events.TITLE, title)
                if (description.isNotBlank()) put(android.provider.CalendarContract.Events.DESCRIPTION, description)
                if (location.isNotBlank()) put(android.provider.CalendarContract.Events.EVENT_LOCATION, location)
                put(android.provider.CalendarContract.Events.CALENDAR_ID, getDefaultCalendarId(context))
                put(android.provider.CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }

            val uri = context.contentResolver.insert(android.provider.CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                Log.i(TAG, "[createCalendarEvent] Event '$title' created: $uri")
                StepResult.Success("Calendar event '$title' created for $startTimeStr")
            } else {
                StepResult.Failed("Failed to insert calendar event — may need WRITE_CALENDAR permission")
            }
        } catch (e: SecurityException) {
            StepResult.Failed("Missing WRITE_CALENDAR permission: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "[createCalendarEvent] Failed: ${e.message}")
            StepResult.Failed("Failed to create calendar event: ${e.message}")
        }
    }

    /**
     * Get the default calendar ID for event creation.
     */
    private fun getDefaultCalendarId(context: Context): Long {
        return try {
            val projection = arrayOf(
                android.provider.CalendarContract.Calendars._ID,
                android.provider.CalendarContract.Calendars.IS_PRIMARY
            )
            val uri = android.provider.CalendarContract.Calendars.CONTENT_URI
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val isPrimary = cursor.getInt(1)
                    if (isPrimary == 1) return id
                }
                if (cursor.moveToFirst()) return cursor.getLong(0)
            }
            1L // Fallback
        } catch (e: Exception) {
            1L
        }
    }

    /**
     * Parse ISO 8601 datetime string to epoch millis.
     * Supports formats: "2025-03-15T14:00:00", "2025-03-15T14:00:00+05:30"
     */
    private fun parseIso8601ToMillis(isoStr: String): Long? {
        return try {
            // Try java.time if available (API 26+)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val instant = java.time.OffsetDateTime.parse(isoStr).toInstant()
                    return instant.toEpochMilli()
                } catch (_: Exception) {}
                try {
                    val ldt = java.time.LocalDateTime.parse(isoStr)
                    return ldt.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {}
            }
            // Fallback: simple manual parse for "yyyy-MM-ddTHH:mm:ss"
            val parts = isoStr.split("T")
            if (parts.size != 2) return null
            val dateParts = parts[0].split("-")
            val timeParts = parts[1].split("+").first().split(":")
            if (dateParts.size != 3 || timeParts.size < 2) return null
            val cal = java.util.Calendar.getInstance()
            cal.set(dateParts[0].toInt(), dateParts[1].toInt() - 1, dateParts[2].toInt(),
                timeParts[0].toInt(), timeParts[1].toInt(),
                if (timeParts.size > 2) timeParts[2].toInt() else 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /**
     * read_notifications — Read recent notifications from NotificationReaderService.
     */
    private fun executeReadNotifications(args: Map<String, String>): StepResult {
        val limit = (args["limit"]?.toIntOrNull() ?: 10).coerceIn(1, 50)

        return try {
            val notifications = com.jarvis.assistant.notifications.NotificationReaderService
                .getRecentNotifications(limit)

            if (notifications.isEmpty()) {
                StepResult.Success("No recent notifications found. Enable notification access for JARVIS in Settings.")
            } else {
                val sb = StringBuilder("Recent notifications (${notifications.size}):\n")
                notifications.forEach { n ->
                    sb.append("- [${n.appName}] ${n.title}")
                    if (n.content.isNotBlank()) sb.append(": ${n.content.take(100)}")
                    sb.append("\n")
                }
                Log.i(TAG, "[readNotifications] Read ${notifications.size} notifications")
                StepResult.Success(sb.toString().trimEnd())
            }
        } catch (e: Exception) {
            Log.e(TAG, "[readNotifications] Failed: ${e.message}")
            StepResult.Failed("Failed to read notifications: ${e.message}. Enable notification access for JARVIS.")
        }
    }

    /**
     * press_key — Press a specific hardware or system key.
     */
    private fun executePressKey(args: Map<String, String>): StepResult {
        val key = args["key"]?.lowercase()?.trim()
            ?: return StepResult.Failed("Missing 'key' argument")

        val validKeys = listOf("volume_up", "volume_down", "power", "enter", "back", "home", "recent")
        if (key !in validKeys) {
            return StepResult.Failed("Unknown key: $key. Supported: ${validKeys.joinToString(", ")}")
        }

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            // Use global actions for navigation keys
            val globalResult = when (key) {
                "back" -> svc.goBack()
                "home" -> svc.goHome()
                "recent" -> svc.openRecents()
                else -> false
            }
            if (globalResult) return StepResult.Success("Pressed $key via Accessibility Service")
        }

        // Fallback: Shizuku shell command
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            val keyEventName = when (key) {
                "volume_up" -> "KEYCODE_VOLUME_UP"
                "volume_down" -> "KEYCODE_VOLUME_DOWN"
                "power" -> "KEYCODE_POWER"
                "enter" -> "KEYCODE_ENTER"
                "back" -> "KEYCODE_BACK"
                "home" -> "KEYCODE_HOME"
                "recent" -> "KEYCODE_APP_SWITCH"
                else -> "KEYCODE_$key".uppercase()
            }
            val result = ShizukuManager.executeShellCommand("input keyevent $keyEventName")
            return if (result.isSuccess) {
                StepResult.Success("Pressed $key via Shizuku")
            } else {
                StepResult.Failed("Shizuku key press failed for $key: ${result.stderr}")
            }
        }

        return StepResult.Failed("Cannot press key '$key' — enable Accessibility Service or Shizuku")
    }

    /**
     * swipe_gesture — Perform a swipe with custom start/end coordinates and duration.
     */
    private fun executeSwipeGesture(args: Map<String, String>): StepResult {
        val startX = args["start_x"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'start_x' coordinate")
        val startY = args["start_y"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'start_y' coordinate")
        val endX = args["end_x"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'end_x' coordinate")
        val endY = args["end_y"]?.toIntOrNull() ?: return StepResult.Failed("Missing or invalid 'end_y' coordinate")
        val duration = (args["duration"]?.toIntOrNull() ?: 300).coerceIn(50, 5000)

        // Use GestureController for the swipe
        val success = com.jarvis.assistant.gesture.GestureController.performSwipe(
            startX, startY, endX, endY, duration.toLong()
        )

        return if (success) {
            Log.i(TAG, "[swipeGesture] Swipe ($startX,$startY) → ($endX,$endY) duration=${duration}ms")
            StepResult.Success("Swiped from ($startX,$startY) to ($endX,$endY) in ${duration}ms")
        } else {
            // Shizuku fallback already handled by GestureController
            StepResult.Failed("Swipe gesture failed from ($startX,$startY) to ($endX,$endY)")
        }
    }

    private fun launchApp(packageName: String, context: Context): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "[launchApp] Launched $packageName via Intent")
                true
            } else {
                Log.w(TAG, "[launchApp] No launch intent for $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "[launchApp] Failed: ${e.message}")
            false
        }
    }
}

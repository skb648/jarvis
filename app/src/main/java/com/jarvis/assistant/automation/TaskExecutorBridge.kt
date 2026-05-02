package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.actions.AppRegistry
import com.jarvis.assistant.services.JarvisAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import com.jarvis.assistant.shizuku.ShizukuManager
import java.lang.ref.WeakReference

/**
 * TaskExecutorBridge — Executes autonomous task chains from Gemini Function Calling.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v6.0) — AUTONOMOUS AGENT TASK EXECUTION:
 *
 * This bridge connects Gemini's structured tool-call responses to actual
 * Android actions. When Gemini returns a function call like:
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
     * Execute a single tool call from Gemini's function calling response.
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
            "generate_image" -> executeGenerateImage(args)
            "generate_video" -> executeGenerateVideo(args)
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

    private fun executeClickButton(args: Map<String, String>): StepResult {
        val label = args["label"]?.trim() ?: return StepResult.Failed("Missing 'label' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val clicked = svc.autoClick(label)
            if (clicked) return StepResult.Success("Clicked button '$label'")
        }

        // Fallback: Shizuku shell command for tapping
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[click_button] Accessibility failed, trying Shizuku fallback for '$label'")
            // Use input keyevent as a generic tap substitute
            val result = ShizukuManager.executeShellCommand("input keyevent KEYCODE_ENTER")
            return if (result.isSuccess) {
                StepResult.Success("Attempted click via Shizuku for '$label'")
            } else {
                StepResult.Failed("Shizuku fallback failed for '$label': ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not click '$label' — enable Accessibility Service or Shizuku")
    }

    private fun executeInjectText(args: Map<String, String>): StepResult {
        val content = args["content"]?.trim() ?: return StepResult.Failed("Missing 'content' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val injected = svc.injectTextToFocusedField(content)
            if (injected) return StepResult.Success("Injected ${content.length} characters into focused field")
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
                StepResult.Success("Injected text via Shizuku: ${content.length} chars")
            } else {
                StepResult.Failed("Shizuku text injection failed: ${result.stderr}")
            }
        }

        return StepResult.Failed("Could not inject text — enable Accessibility Service or Shizuku")
    }

    private fun executeScroll(args: Map<String, String>): StepResult {
        val direction = args["direction"]?.lowercase()?.trim() ?: "down"
        if (direction !in listOf("up", "down")) {
            return StepResult.Failed("Invalid scroll direction: $direction (use 'up' or 'down')")
        }

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            val scrolled = svc.performScroll(direction)
            if (scrolled) return StepResult.Success("Scrolled $direction")
        }

        // Fallback: Shizuku shell command for swipe-based scrolling
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[scroll] Accessibility failed, trying Shizuku fallback for $direction")
            val swipeCmd = when (direction) {
                "down" -> "input swipe 540 1500 540 500"
                "up" -> "input swipe 540 500 540 1500"
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
    private fun executeClickUiElement(args: Map<String, String>): StepResult {
        val textOrId = args["text_or_id"]?.trim() ?: return StepResult.Failed("Missing 'text_or_id' argument")

        // Try accessibility service first
        val svc = accessibilityService?.get()
        if (svc != null) {
            // Try clicking by text first, then by ID
            val clickedByText = svc.autoClick(textOrId)
            if (clickedByText) return StepResult.Success("Clicked UI element with text '$textOrId'")

            // If text click failed, try by view ID
            val clickedById = svc.clickNodeById(textOrId)
            if (clickedById) return StepResult.Success("Clicked UI element with ID '$textOrId'")
        }

        // Fallback: Shizuku shell command
        if (ShizukuManager.isReady() && ShizukuManager.hasPermission()) {
            Log.i(TAG, "[click_ui_element] Accessibility failed, trying Shizuku fallback for '$textOrId'")
            val result = ShizukuManager.executeShellCommand("input keyevent KEYCODE_ENTER")
            return if (result.isSuccess) {
                StepResult.Success("Attempted click via Shizuku for '$textOrId'")
            } else {
                StepResult.Failed("Shizuku fallback failed for '$textOrId': ${result.stderr}")
            }
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
            val swipeCmd = when (direction) {
                "down" -> "input swipe 540 1500 540 500"
                "up" -> "input swipe 540 500 540 1500"
                "left" -> "input swipe 900 960 200 960"
                "right" -> "input swipe 200 960 900 960"
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
        val report = com.jarvis.assistant.monitor.SelfDiagnosticManager.diagnoseIssue(issueType)
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
                com.jarvis.assistant.search.WebSearchEngine().search(query)
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
    private fun executeSetAlarm(args: Map<String, String>, context: Context): StepResult {
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
    // Image / Video Generation
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * generate_image — Generate an image using Gemini Imagen API.
     * Calls the v1beta/models/imagen-3.0-generate-002:predict endpoint.
     */
    private suspend fun executeGenerateImage(args: Map<String, String>): StepResult {
        val prompt = args["prompt"]?.trim() ?: return StepResult.Failed("Missing 'prompt' argument")
        val style = args["style"]?.trim() ?: ""

        return withContext(Dispatchers.IO) {
            try {
                // Get API key from JarviewModel
                val apiKey = com.jarvis.assistant.channels.JarviewModel.geminiApiKey
                if (apiKey.isBlank()) {
                    return@withContext StepResult.Failed("Gemini API key not set — cannot generate image")
                }

                val fullPrompt = if (style.isNotBlank()) "$prompt, $style style" else prompt

                val requestBody = org.json.JSONObject().apply {
                    put("instances", org.json.JSONArray().put(
                        org.json.JSONObject().put("prompt", fullPrompt)
                    ))
                    put("parameters", org.json.JSONObject().apply {
                        put("sampleCount", 1)
                    })
                }.toString()

                val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict?key=${apiKey.trim()}")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 120_000

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode != 200) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                    connection.disconnect()
                    Log.e(TAG, "[generateImage] HTTP $responseCode: ${errorBody.take(300)}")
                    return@withContext StepResult.Failed("Image generation failed: HTTP $responseCode — ${errorBody.take(200)}")
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // Parse the response to extract base64 image
                val root = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                val predictions = root.getAsJsonArray("predictions")
                val firstPrediction = predictions?.firstOrNull()?.asJsonObject
                val bytesBase64 = firstPrediction?.get("bytesBase64Encoded")?.asString

                if (!bytesBase64.isNullOrBlank()) {
                    // Save image to a file and return the path
                    val imageBytes = android.util.Base64.decode(bytesBase64, android.util.Base64.NO_WRAP)
                    val imagesDir = java.io.File("/sdcard/Pictures/Jarvis")
                    if (!imagesDir.exists()) imagesDir.mkdirs()
                    val imageFile = java.io.File(imagesDir, "jarvis_img_${System.currentTimeMillis()}.png")
                    java.io.FileOutputStream(imageFile).use { it.write(imageBytes) }
                    Log.i(TAG, "[generateImage] Image saved to ${imageFile.absolutePath}")
                    StepResult.Success("Image generated and saved to ${imageFile.absolutePath}. Prompt: \"$fullPrompt\"")
                } else {
                    StepResult.Success("Image generation request sent for: \"$fullPrompt\". Check the Gemini API response for the generated image.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[generateImage] Failed: ${e.message}")
                StepResult.Failed("Image generation failed: ${e.message}")
            }
        }
    }

    /**
     * generate_video — Placeholder for future Veo API integration.
     * Video generation is not yet available through the public Gemini API.
     */
    private fun executeGenerateVideo(args: Map<String, String>): StepResult {
        val prompt = args["prompt"]?.trim() ?: return StepResult.Failed("Missing 'prompt' argument")
        Log.i(TAG, "[generateVideo] Video generation requested for: $prompt — not yet available")
        return StepResult.Failed("Video generation is a future feature. The Veo API is not yet publicly available. Your prompt was: \"$prompt\"")
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

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

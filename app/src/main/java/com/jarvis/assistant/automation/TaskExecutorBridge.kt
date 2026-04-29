package com.jarvis.assistant.automation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.assistant.actions.AppRegistry
import com.jarvis.assistant.services.JarvisAccessibilityService
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
    fun executeToolCall(toolName: String, args: Map<String, String>, context: Context): StepResult {
        Log.i(TAG, "[executeToolCall] tool=$toolName args=$args")

        return when (toolName) {
            "open_and_search" -> executeOpenAndSearch(args, context)
            "click_button" -> executeClickButton(args)
            "inject_text" -> executeInjectText(args)
            "scroll" -> executeScroll(args)
            "go_back" -> executeGoBack()
            "go_home" -> executeGoHome()
            "open_app" -> executeOpenApp(args, context)
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
    private fun executeOpenAndSearch(args: Map<String, String>, context: Context): StepResult {
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
                    Thread.sleep(2000) // Wait for app to load
                    val svc = accessibilityService?.get()
                    if (svc != null) {
                        // Try to find and click search icon/field
                        val searchClicked = svc.autoClick("search") ||
                                svc.autoClick("Search") ||
                                svc.clickNodeById("search") ||
                                svc.clickNodeById("menu_search") ||
                                svc.clickNodeById("search_bar")

                        if (searchClicked) {
                            Thread.sleep(500)
                            // Inject the query into the now-focused search field
                            val injected = svc.injectTextToFocusedField(query)
                            if (injected) {
                                // Press Enter/Search
                                Thread.sleep(300)
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

        val svc = accessibilityService?.get()
            ?: return StepResult.Failed("Accessibility service not connected")

        val clicked = svc.autoClick(label)
        return if (clicked) {
            StepResult.Success("Clicked button '$label'")
        } else {
            StepResult.Failed("Could not find or click button '$label'")
        }
    }

    private fun executeInjectText(args: Map<String, String>): StepResult {
        val content = args["content"]?.trim() ?: return StepResult.Failed("Missing 'content' argument")

        val svc = accessibilityService?.get()
            ?: return StepResult.Failed("Accessibility service not connected")

        val injected = svc.injectTextToFocusedField(content)
        return if (injected) {
            StepResult.Success("Injected ${content.length} characters into focused field")
        } else {
            StepResult.Failed("Could not inject text — no editable field focused")
        }
    }

    private fun executeScroll(args: Map<String, String>): StepResult {
        val direction = args["direction"]?.lowercase()?.trim() ?: "down"
        if (direction !in listOf("up", "down")) {
            return StepResult.Failed("Invalid scroll direction: $direction (use 'up' or 'down')")
        }

        val svc = accessibilityService?.get()
            ?: return StepResult.Failed("Accessibility service not connected")

        val scrolled = svc.performScroll(direction)
        return if (scrolled) {
            StepResult.Success("Scrolled $direction")
        } else {
            StepResult.Failed("Could not scroll $direction")
        }
    }

    private fun executeGoBack(): StepResult {
        val svc = accessibilityService?.get()
            ?: return StepResult.Failed("Accessibility service not connected")

        return if (svc.goBack()) {
            StepResult.Success("Went back")
        } else {
            StepResult.Failed("Could not go back")
        }
    }

    private fun executeGoHome(): StepResult {
        val svc = accessibilityService?.get()
            ?: return StepResult.Failed("Accessibility service not connected")

        return if (svc.goHome()) {
            StepResult.Success("Went home")
        } else {
            StepResult.Failed("Could not go home")
        }
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

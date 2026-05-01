package com.jarvis.assistant.automation

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.jarvis.assistant.channels.JarviewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * GeminiFunctionCaller — Handles Gemini's Function Calling / Tool Use responses.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v6.0) — GEMINI FUNCTION CALLING (The Planner):
 *
 * When the user says "Search Total Gaming on YouTube", instead of returning
 * a text reply, Gemini returns a structured function call:
 *
 *   {
 *     "functionCall": {
 *       "name": "open_and_search",
 *       "args": { "app": "youtube", "query": "Total Gaming" }
 *     }
 *   }
 *
 * This class:
 *   1. Defines the tools available to Gemini (open_and_search, click_button, etc.)
 *   2. Sends queries to Gemini with tool definitions
 *   3. Parses function call responses
 *   4. Routes function calls to TaskExecutorBridge for execution
 *   5. If the function call requires follow-up (multi-step), sends the
 *      result back to Gemini for the next step in the chain
 *
 * IMPORTANT: Does NOT change the Gemini API URL or model (stays on 2.5-flash).
 * ═══════════════════════════════════════════════════════════════════════
 */
object GeminiFunctionCaller {

    private const val TAG = "GeminiFunctionCaller"

    /** The Gemini model to use — DO NOT CHANGE per user requirement */
    private const val MODEL = "gemini-2.5-flash"

    /** Maximum number of tool-call round-trips before giving up */
    private const val MAX_TOOL_ROUNDS = 5

    // ═══════════════════════════════════════════════════════════════════════
    // Tool Definitions — These are sent to Gemini so it knows what tools
    // are available. Gemini decides when to call them based on the user's query.
    // ═══════════════════════════════════════════════════════════════════════

    private val TOOL_DEFINITIONS = org.json.JSONObject().apply {
        put("functionDeclarations", org.json.JSONArray().apply {
            // open_and_search — Opens an app and searches for a query
            put(org.json.JSONObject().apply {
                put("name", "open_and_search")
                put("description", "Open an app and search for something. Uses deep links when available for instant results. " +
                        "Supports: youtube, maps, play store, google, chrome, spotify, and more.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("app", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The app to open (e.g., 'youtube', 'maps', 'play store')")
                        })
                        put("query", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query (e.g., 'Total Gaming', 'restaurants near me')")
                        })
                    })
                    put("required", org.json.JSONArray().put("app").put("query"))
                })
            })

            // click_button — Click a UI element by its text label
            put(org.json.JSONObject().apply {
                put("name", "click_button")
                put("description", "Click a button or UI element on the current screen by its visible text label. " +
                        "Use after opening an app to interact with on-screen elements.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("label", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The visible text of the button or element to click (e.g., 'Play', 'Subscribe', 'Send')")
                        })
                    })
                    put("required", org.json.JSONArray().put("label"))
                })
            })

            // inject_text — Type text into the currently focused text field
            put(org.json.JSONObject().apply {
                put("name", "inject_text")
                put("description", "Type or inject text into the currently focused text input field. " +
                        "Use for entering code, messages, URLs, or any text into apps like Replit, " +
                        "Chrome, WhatsApp, etc. The text is instantly injected — no character-by-character typing.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("content", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The text content to inject into the focused field")
                        })
                    })
                    put("required", org.json.JSONArray().put("content"))
                })
            })

            // scroll — Scroll the screen in a direction
            put(org.json.JSONObject().apply {
                put("name", "scroll")
                put("description", "Scroll the current screen up or down to reveal more content.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("direction", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "Direction to scroll: 'up' or 'down'")
                            put("enum", org.json.JSONArray().put("up").put("down"))
                        })
                    })
                    put("required", org.json.JSONArray().put("direction"))
                })
            })

            // go_back — Navigate back
            put(org.json.JSONObject().apply {
                put("name", "go_back")
                put("description", "Press the back button to navigate to the previous screen.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {})
                })
            })

            // go_home — Go to home screen
            put(org.json.JSONObject().apply {
                put("name", "go_home")
                put("description", "Press the home button to go to the home screen.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {})
                })
            })

            // open_app — Just open an app (without searching)
            put(org.json.JSONObject().apply {
                put("name", "open_app")
                put("description", "Open an app by name. Use this when the user just wants to open an app without searching.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("app", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The app name to open (e.g., 'youtube', 'chrome', 'whatsapp')")
                        })
                    })
                    put("required", org.json.JSONArray().put("app"))
                })
            })

            // search_playstore — Search the Google Play Store
            put(org.json.JSONObject().apply {
                put("name", "search_playstore")
                put("description", "Search for an app on the Google Play Store. Use this when the user wants to install or find an app. " +
                        "Opens the Play Store with the search query pre-filled.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The app name or search query for Play Store (e.g., 'FF Lite', 'WhatsApp')")
                        })
                    })
                    put("required", org.json.JSONArray().put("query"))
                })
            })

            // ═══════════════════════════════════════════════════════════════════
            // PHASE 2: NEW AUTONOMOUS AGENT TOOLS
            // ═══════════════════════════════════════════════════════════════════

            // click_ui_element — Click any UI element by text or ID (enhanced click_button)
            put(org.json.JSONObject().apply {
                put("name", "click_ui_element")
                put("description", "Click a UI element on the current screen by its visible text label OR its view ID. " +
                        "This is the primary tool for interacting with any on-screen button, switch, or link. " +
                        "Use this AFTER dump_screen to identify the correct element.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("text_or_id", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The visible text label or view ID of the element to click (e.g., 'Install', 'com.android.vending:id/install_button')")
                        })
                    })
                    put("required", org.json.JSONArray().put("text_or_id"))
                })
            })

            // scroll_screen — Scroll the screen in a direction (enhanced scroll)
            put(org.json.JSONObject().apply {
                put("name", "scroll_screen")
                put("description", "Scroll the current screen up, down, left, or right to reveal more content. " +
                        "Use this when the target element is not visible on the current screen.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("direction", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "Direction to scroll: 'up', 'down', 'left', or 'right'")
                            put("enum", org.json.JSONArray().put("up").put("down").put("left").put("right"))
                        })
                    })
                    put("required", org.json.JSONArray().put("direction"))
                })
            })

            // perform_global_action — System navigation actions
            put(org.json.JSONObject().apply {
                put("name", "perform_global_action")
                put("description", "Perform a global system action like pressing Back, Home, Recents, or opening notifications/quick settings. " +
                        "Use this for navigating the OS when you can't find a UI element to click.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("action_type", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The global action to perform")
                            put("enum", org.json.JSONArray().put("back").put("home").put("recents").put("notifications").put("quick_settings").put("power_dialog").put("screenshot"))
                        })
                    })
                    put("required", org.json.JSONArray().put("action_type"))
                })
            })

            // dispatch_gesture — Tap at exact X,Y coordinates
            put(org.json.JSONObject().apply {
                put("name", "dispatch_gesture")
                put("description", "Tap or swipe at exact screen coordinates. Use as a fallback when click_ui_element fails " +
                        "(e.g., for WebView elements, game UIs, or elements without text/ID). " +
                        "Get coordinates from the dump_screen tool's bounds data.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("x", org.json.JSONObject().apply {
                            put("type", "integer")
                            put("description", "X coordinate on screen")
                        })
                        put("y", org.json.JSONObject().apply {
                            put("type", "integer")
                            put("description", "Y coordinate on screen")
                        })
                        put("action", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "Type of gesture to perform")
                            put("enum", org.json.JSONArray().put("tap").put("long_press").put("swipe_up").put("swipe_down"))
                        })
                    })
                    put("required", org.json.JSONArray().put("x").put("y").put("action"))
                })
            })

            // dump_screen — Read the current screen for AI context
            put(org.json.JSONObject().apply {
                put("name", "dump_screen")
                put("description", "Read the current screen's interactive elements and text content. " +
                        "Use this to 'see' what's on screen BEFORE deciding which element to click. " +
                        "Returns a structured description of all visible buttons, text, and interactive elements. " +
                        "ALWAYS call this before click_ui_element if you're unsure what's on screen.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {})
                })
            })

            // diagnose_system — Read JARVIS's own crash logs
            put(org.json.JSONObject().apply {
                put("name", "diagnose_system")
                put("description", "Read JARVIS's own application logs (logcat) to diagnose errors and crashes. " +
                        "Use this when something isn't working (mic fails, service dies, etc.) to find the root cause. " +
                        "If the user asks 'Why is X not working?', call this tool first to analyze the error.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("issue_type", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The type of issue to diagnose: 'mic', 'accessibility', 'crash', 'network', 'battery', or 'general'")
                            put("enum", org.json.JSONArray().put("mic").put("accessibility").put("crash").put("network").put("battery").put("general"))
                        })
                    })
                })
            })

            // search_web — Search the internet
            put(org.json.JSONObject().apply {
                put("name", "search_web")
                put("description", "Search the web for information. Use this when the user asks about current events, " +
                        "facts you're unsure about, or anything that requires up-to-date information. " +
                        "Returns search results with titles, snippets, and URLs.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply {
                            put("type", "string")
                            put("description", "The search query (e.g., 'latest Android 16 features', 'weather in Delhi today')")
                        })
                    })
                    put("required", org.json.JSONArray().put("query"))
                })
            })
        })
    }

    /**
     * Result of processing a Gemini function call response.
     */
    sealed class ProcessResult {
        /** A function call was detected and executed */
        data class ToolExecuted(
            val toolName: String,
            val stepResult: TaskExecutorBridge.StepResult,
            val aiResponse: String?
        ) : ProcessResult()

        /** A text-only response (no function call) */
        data class TextOnly(val response: String) : ProcessResult()

        /** Multiple tool calls detected (multi-step task) */
        data class MultiStep(
            val steps: List<Pair<String, Map<String, String>>>,
            val results: List<TaskExecutorBridge.StepResult>
        ) : ProcessResult()

        /** An error occurred */
        data class Error(val message: String) : ProcessResult()
    }

    /**
     * Send a query to Gemini with tool definitions and process the response.
     * If Gemini returns a function call, execute it and optionally send
     * the result back for follow-up.
     *
     * CRITICAL FIX: When Gemini returns text that DESCRIBES an action instead
     * of using a function call, we detect this and route it to TaskExecutorBridge
     * so the action actually gets executed. This fixes the #1 user complaint:
     * "JARVIS says it clicked something but didn't actually do it."
     *
     * @param query The user's query
     * @param apiKey Gemini API key
     * @param context Android context
     * @param historyJson Conversation history as JSON
     * @return ProcessResult indicating what happened
     */
    suspend fun processWithTools(
        query: String,
        apiKey: String,
        context: Context,
        historyJson: String = "[]"
    ): ProcessResult {
        if (apiKey.isBlank()) return ProcessResult.Error("API key not set")

        // Build the system prompt with screen context AND system status
        val screenContext = getScreenContextForAI()
        val systemStatusBlock = com.jarvis.assistant.monitor.SystemDiagnosticManager.getSystemStatusBlock(context)
        val systemPrompt = buildSystemPrompt(screenContext, systemStatusBlock)

        // Build the request with tools
        val requestBuilder = buildRequestWithTools(query, systemPrompt, historyJson)

        // Send to Gemini and process the response
        val response = sendToGemini(requestBuilder, apiKey)

        return when (response) {
            is GeminiResponse.FunctionCall -> handleFunctionCall(response, apiKey, context, query, systemPrompt, historyJson)
            is GeminiResponse.Text -> {
                // CRITICAL FIX: Gemini returned text instead of a function call.
                // Check if the text DESCRIBES an action that should have been a tool call.
                // If so, extract the action and execute it via TaskExecutorBridge.
                val extractedToolCall = extractToolCallFromText(response.text, query)
                if (extractedToolCall != null) {
                    Log.i(TAG, "[processWithTools] Gemini returned text describing an action — converting to tool call: ${extractedToolCall.first}(${extractedToolCall.second})")
                    val funcCall = GeminiResponse.FunctionCall(extractedToolCall.first, extractedToolCall.second)
                    handleFunctionCall(funcCall, apiKey, context, query, systemPrompt, historyJson)
                } else {
                    ProcessResult.TextOnly(response.text)
                }
            }
            is GeminiResponse.Error -> ProcessResult.Error(response.message)
        }
    }

    /**
     * CRITICAL FIX: When Gemini returns TEXT that describes an action instead of
     * using a function call, try to extract the action and convert it to a tool call.
     *
     * This handles cases like:
     *   - "I'll click the Install button" → click_button(label="Install")
     *   - "I'm opening YouTube" → open_app(app="youtube")
     *   - "I've opened YouTube and searched for cats" → open_and_search(app="youtube", query="cats")
     *   - "Scrolling down" → scroll(direction="down")
     *   - "I'll go back" → go_back()
     *   - "I'm typing 'hello'" → inject_text(content="hello")
     *
     * Returns (toolName, args) if an action is detected, null otherwise.
     */
    private fun extractToolCallFromText(text: String, originalQuery: String): Pair<String, Map<String, String>>? {
        val lower = text.lowercase()
        val queryLower = originalQuery.lowercase()

        // ═══ Pattern 1: "click/tap/press [label]" ═══
        val clickPatterns = listOf(
            Regex("""(?:i(?:'ll| will|'ve)?\s+)?(?:click|tap|press)\s+(?:the\s+)?(?:button\s+)?['"""']?(.+?)['"""']?\s*(?:button|for\s+you|now|sir|,|$)"""),
            Regex("""(?:clicking|tapping|pressing)\s+(?:the\s+)?(?:button\s+)?['"""']?(.+?)['"""']?\s*(?:button|for\s+you|now|sir|,|$)"""),
            Regex("""(?:i(?:'ve| have))?\s+(?:clicked|tapped|pressed)\s+(?:the\s+)?(?:button\s+)?['"""']?(.+?)['"""']?\s*(?:button|for\s+you|now|sir|,|$)"")
        )
        for (pattern in clickPatterns) {
            val match = pattern.find(lower) ?: continue
            val label = match.groupValues[1].trim()
                .removeSuffix(".").removeSuffix("for you").removeSuffix("now").removeSuffix("sir").trim()
            if (label.isNotBlank() && label.length <= 50) {
                return Pair("click_button", mapOf("label" to label))
            }
        }

        // ═══ Pattern 2: "open/launch [app]" (without search) ═══
        val openPatterns = listOf(
            Regex("""(?:i(?:'ll| will|'ve)?\s+)?(?:open|launch|start)\s+(?:the\s+)?(\w+(?:\s+\w+)?)\s*(?:app|for\s+you|now|sir|,|$)"""),
            Regex("""(?:opening|launching|starting)\s+(?:the\s+)?(\w+(?:\s+\w+)?)\s*(?:app|for\s+you|now|sir|,|$)""")
        )
        // Only match if the original query doesn't contain "search" — otherwise it should be open_and_search
        val isSearchQuery = queryLower.contains("search") || queryLower.contains("find") || queryLower.contains("look for")

        for (pattern in openPatterns) {
            val match = pattern.find(lower) ?: continue
            val app = match.groupValues[1].trim()
                .removeSuffix(".").removeSuffix("for you").removeSuffix("now").removeSuffix("sir").trim()
            if (app.isNotBlank() && app.length <= 30 && !isSearchQuery) {
                return Pair("open_app", mapOf("app" to app))
            }
        }

        // ═══ Pattern 3: "open [app] and search for [query]" ═══
        val openAndSearchPattern = Regex("""(?:open|launch)\s+(\w+(?:\s+\w+)?)\s+and\s+(?:search|find|look)\s+(?:for\s+)?(.+?)(?:\s*$|\s*[,.;])""")
        val openSearchMatch = openAndSearchPattern.find(lower)
        if (openSearchMatch != null) {
            val app = openSearchMatch.groupValues[1].trim()
            val query = openSearchMatch.groupValues[2].trim().removeSuffix(".").removeSuffix("for you").trim()
            if (app.isNotBlank() && query.isNotBlank()) {
                return Pair("open_and_search", mapOf("app" to app, "query" to query))
            }
        }

        // ═══ Pattern 4: "scroll up/down" ═══
        val scrollPattern = Regex("""(?:scrolling|scroll|swipe)\s+(up|down|left|right)""")
        val scrollMatch = scrollPattern.find(lower)
        if (scrollMatch != null) {
            return Pair("scroll", mapOf("direction" to scrollMatch.groupValues[1]))
        }

        // ═══ Pattern 5: "go back" / "press back" ═══
        if (lower.contains("go back") || lower.contains("press back") || lower.contains("going back") || lower.contains("pressing back")) {
            return Pair("go_back", emptyMap())
        }

        // ═══ Pattern 6: "type/inject text" ═══
        val injectPattern = Regex("""(?:typing|injecting|entering|type|inject|enter)\s+['"""']?(.+?)['"""']?\s*(?:into|in|for|now|sir|,|$)""")
        val injectMatch = injectPattern.find(lower)
        if (injectMatch != null) {
            val content = injectMatch.groupValues[1].trim().removeSuffix(".").trim()
            if (content.isNotBlank()) {
                return Pair("inject_text", mapOf("content" to content))
            }
        }

        // ═══ Pattern 7: "go home" / "press home" ═══
        if (lower.contains("go home") || lower.contains("press home") || lower.contains("going home") || lower.contains("pressing home")) {
            return Pair("go_home", emptyMap())
        }

        // No action detected in text — it's a genuine conversational response
        return null
    }

    /**
     * Build the JARVIS system prompt with current screen context AND system status.
     */
    private fun buildSystemPrompt(screenContext: String, systemStatusBlock: String = ""): String {
        return """You are JARVIS, Tony Stark's autonomous AI assistant. You are NOT just a chatbot — you are an AGENT that can SEE the screen, CLICK buttons, SCROLL, TYPE text, OPEN apps, and COMPLETE tasks autonomously.
You are sophisticated, witty, and always helpful. You speak concisely and with British elegance.
You address the user as "Sir" or "Ma'am".

You have MEMORY of past conversations. If the MEMORY CONTEXT section contains relevant past conversations, reference them naturally. Start relevant responses with 'Sir, apne [time] bola tha...' when recalling what the user said before. This makes you feel like a true assistant who remembers.

═══════════════════════════════════════════════════════════════
CRITICAL RULE — ALWAYS USE FUNCTION CALLING FOR ACTIONS:
═══════════════════════════════════════════════════════════════
When you need to interact with the device (open apps, click buttons, scroll, type text, go back, search, etc.), you MUST use the available function tools. NEVER just describe what you would do — actually call the function.

Examples:
- User says "click Install" → Call click_button(label="Install")  ✅
  NOT: "I'll click the Install button for you" ❌
- User says "open YouTube" → Call open_app(app="youtube")  ✅
  NOT: "Opening YouTube for you, Sir" ❌
- User says "search cats on YouTube" → Call open_and_search(app="youtube", query="cats")  ✅
  NOT: "I'll search for cats on YouTube" ❌
- User says "scroll down" → Call scroll(direction="down")  ✅
  NOT: "Scrolling down now" ❌
- User says "type hello" → Call inject_text(content="hello")  ✅
  NOT: "I'll type hello for you" ❌

If you describe an action in text without calling the function, NOTHING will happen on the device. The user will be frustrated. ALWAYS use function calls for actions.
═══════════════════════════════════════════════════════════════

AUTONOMOUS REASONING (ReAct Protocol):
When the user asks you to DO something, follow the ReAct pattern:
1. THINK: Analyze the goal and plan the steps needed
2. ACT: Call the appropriate tool to execute one step
3. OBSERVE: After the tool result, assess what happened
4. ITERATE: If the goal is not met, THINK again and take the next step
5. COMPLETE: When the goal is achieved, briefly confirm to the user

Example: "Install FF Lite on Play Store"
→ ACT: Call open_and_search(app="play store", query="FF Lite")
→ ACT: Call dump_screen() to see the search results
→ ACT: Call click_ui_element(text_or_id="FF Lite")
→ ACT: Call click_ui_element(text_or_id="Install")
→ COMPLETE: "FF Lite is being installed, Sir."

SELF-DIAGNOSIS:
If a tool call fails or something isn't working:
1. Call diagnose_system() to read JARVIS's own logs
2. Analyze the error from the logs
3. Explain the root cause to the user with a suggested fix
4. Never guess — always check the logs first

CURRENT SCREEN CONTEXT:
$screenContext

$systemStatusBlock

AVAILABLE TOOLS (use these to ACT — ALWAYS call these instead of describing actions in text):
- open_and_search: Open an app and search for content
- click_button: Click a button by its text label (legacy, prefer click_ui_element)
- click_ui_element: Click any visible UI element by text or view ID
- inject_text: Type text into the focused input field
- scroll: Scroll the screen up or down (legacy, prefer scroll_screen)
- scroll_screen: Scroll in any direction (up/down/left/right)
- go_back: Press the back button
- go_home: Go to home screen
- perform_global_action: Execute system actions (back, home, recents, notifications, quick_settings, power_dialog, screenshot)
- open_app: Open an app by name
- search_playstore: Search the Play Store
- dispatch_gesture: Tap or swipe at exact X,Y coordinates (fallback for non-native UI)
- dump_screen: Read what's currently on screen (use before clicking)
- diagnose_system: Read JARVIS's crash logs to diagnose errors
- search_web: Search the web for information

IMPORTANT RULES:
1. CRITICAL: ALWAYS use function calls for device actions. NEVER just describe what you would do. Text-only responses perform NO actions on the device.
2. For MULTI-STEP tasks, execute steps sequentially — don't try to do everything at once.
3. After each action, use dump_screen to verify the result before proceeding.
4. If an action fails, try an alternative approach (e.g., scroll down and look again).
5. Be persistent — don't give up after one failure. Try at least 2-3 approaches.
6. If you detect an emotion in the user's query, prefix your text response with [EMOTION:emotion] where emotion is one of: neutral, happy, sad, angry, calm, surprised, urgent, stressed, confused, playful.
7. For pure questions (no device action needed), respond with text normally — only use function calls when the user wants something DONE on the device."""
    }

    /**
     * Build the Gemini API request body with tool definitions.
     */
    private fun buildRequestWithTools(
        query: String,
        systemPrompt: String,
        historyJson: String
    ): String {
        val contentsArray = org.json.JSONArray()

        // Add history
        try {
            val historyArr = org.json.JSONArray(historyJson)
            for (i in 0 until historyArr.length()) {
                val entry = historyArr.getJSONObject(i)
                val role = entry.optString("role", "user")
                val content = entry.optString("content", "")
                if (content.isNotBlank()) {
                    contentsArray.put(org.json.JSONObject().apply {
                        put("role", if (role == "model") "model" else "user")
                        put("parts", org.json.JSONArray().put(
                            org.json.JSONObject().put("text", content)
                        ))
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse history: ${e.message}")
        }

        // Add current query
        contentsArray.put(org.json.JSONObject().apply {
            put("role", "user")
            put("parts", org.json.JSONArray().put(
                org.json.JSONObject().put("text", query)
            ))
        })

        return org.json.JSONObject().apply {
            put("contents", contentsArray)
            put("systemInstruction", org.json.JSONObject().apply {
                put("parts", org.json.JSONArray().put(
                    org.json.JSONObject().put("text", systemPrompt)
                ))
            })
            put("tools", org.json.JSONArray().put(TOOL_DEFINITIONS))
            put("generationConfig", org.json.JSONObject().apply {
                put("temperature", 0.3)  // Lower temperature for more consistent tool use
                put("maxOutputTokens", 1024)
            })
        }.toString()
    }

    /**
     * Handle a function call response from Gemini.
     * Executes the tool call and sends the result back to Gemini if needed.
     */
    private suspend fun handleFunctionCall(
        response: GeminiResponse.FunctionCall,
        apiKey: String,
        context: Context,
        originalQuery: String,
        systemPrompt: String,
        historyJson: String,
        roundCounter: Int = 0
    ): ProcessResult {
        val toolName = response.name
        val args = response.args

        Log.i(TAG, "[handleFunctionCall] Gemini requested tool: $toolName($args) round=$roundCounter")

        // Enforce MAX_TOOL_ROUNDS to prevent infinite tool loops
        if (roundCounter >= MAX_TOOL_ROUNDS) {
            Log.w(TAG, "[handleFunctionCall] MAX_TOOL_ROUNDS ($MAX_TOOL_ROUNDS) reached — stopping")
            return ProcessResult.TextOnly("Maximum tool rounds reached")
        }

        // Execute the tool call
        val stepResult = TaskExecutorBridge.executeToolCall(toolName, args, context)

        Log.i(TAG, "[handleFunctionCall] Tool result: $stepResult")

        // Send the tool result back to Gemini for a follow-up response
        // This allows Gemini to plan the next step in a multi-step task
        val followUpResponse = sendToolResultBack(
            originalQuery, toolName, args, stepResult,
            apiKey, systemPrompt, historyJson
        )

        val aiMessage = when (followUpResponse) {
            is GeminiResponse.Text -> followUpResponse.text
            is GeminiResponse.FunctionCall -> {
                // Gemini wants to call another tool — execute it with incremented round counter
                Log.i(TAG, "[handleFunctionCall] Follow-up tool call: ${followUpResponse.name} round=${roundCounter + 1}")
                val nextResult = handleFunctionCall(
                    followUpResponse, apiKey, context, originalQuery, systemPrompt, historyJson,
                    roundCounter + 1
                )
                when (nextResult) {
                    is ProcessResult.ToolExecuted -> nextResult.aiResponse ?: "Executed ${followUpResponse.name}"
                    is ProcessResult.TextOnly -> nextResult.response
                    is ProcessResult.MultiStep -> nextResult.steps.map { it.first }.joinToString(", ")
                    is ProcessResult.Error -> nextResult.message
                }
            }
            is GeminiResponse.Error -> "Action completed but follow-up failed: ${followUpResponse.message}"
        }

        return ProcessResult.ToolExecuted(toolName, stepResult, aiMessage)
    }

    /**
     * Send the tool execution result back to Gemini so it can continue
     * the conversation or plan the next step.
     */
    private suspend fun sendToolResultBack(
        originalQuery: String,
        toolName: String,
        toolArgs: Map<String, String>,
        stepResult: TaskExecutorBridge.StepResult,
        apiKey: String,
        systemPrompt: String,
        historyJson: String
    ): GeminiResponse {
        val resultMessage = when (stepResult) {
            is TaskExecutorBridge.StepResult.Success -> stepResult.message
            is TaskExecutorBridge.StepResult.Failed -> "FAILED: ${stepResult.message}"
        }

        val requestBody = org.json.JSONObject().apply {
            put("contents", org.json.JSONArray().apply {
                put(org.json.JSONObject().apply {
                    put("role", "user")
                    put("parts", org.json.JSONArray().put(
                        org.json.JSONObject().put("text", originalQuery)
                    ))
                })
                put(org.json.JSONObject().apply {
                    put("role", "model")
                    put("parts", org.json.JSONArray().put(
                        org.json.JSONObject().apply {
                            put("functionCall", org.json.JSONObject().apply {
                                put("name", toolName)
                                put("args", org.json.JSONObject(toolArgs))
                            })
                        }
                    ))
                })
                put(org.json.JSONObject().apply {
                    put("role", "function")
                    put("parts", org.json.JSONArray().put(
                        org.json.JSONObject().apply {
                            put("functionResponse", org.json.JSONObject().apply {
                                put("name", toolName)
                                put("response", org.json.JSONObject().apply {
                                    put("result", resultMessage)
                                })
                            })
                        }
                    ))
                })
            })
            put("systemInstruction", org.json.JSONObject().apply {
                put("parts", org.json.JSONArray().put(
                    org.json.JSONObject().put("text", systemPrompt)
                ))
            })
            put("tools", org.json.JSONArray().put(TOOL_DEFINITIONS))
            put("generationConfig", org.json.JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 1024)
            })
        }.toString()

        return sendToGemini(requestBody, apiKey)
    }

    /**
     * Send a request to the Gemini API and parse the response.
     * Wraps the HTTP call in withContext(Dispatchers.IO) to avoid blocking the calling thread.
     */
    private suspend fun sendToGemini(requestBody: String, apiKey: String): GeminiResponse {
        return withContext(Dispatchers.IO) {
            try {
                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent?key=${apiKey.trim()}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: "unknown"
                    Log.e(TAG, "[sendToGemini] HTTP $responseCode: ${errorBody.take(500)}")
                    connection.disconnect()
                    return@withContext GeminiResponse.Error("Gemini API error: HTTP $responseCode")
                }

                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                parseGeminiResponse(responseBody)
            } catch (e: Exception) {
                Log.e(TAG, "[sendToGemini] Exception: ${e.message}")
                GeminiResponse.Error("Network error: ${e.message?.take(100)}")
            }
        }
    }

    /**
     * Parse the Gemini API response to detect function calls or text.
     */
    private fun parseGeminiResponse(responseBody: String): GeminiResponse {
        return try {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val candidates = root.getAsJsonArray("candidates")
            val firstCandidate = candidates?.firstOrNull()?.asJsonObject
            val content = firstCandidate?.getAsJsonObject("content")
            val parts = content?.getAsJsonArray("parts")
            val firstPart = parts?.firstOrNull()?.asJsonObject

            if (firstPart == null) {
                return GeminiResponse.Error("Empty response from Gemini")
            }

            // Check for function call
            if (firstPart.has("functionCall")) {
                val functionCall = firstPart.getAsJsonObject("functionCall")
                val name = functionCall.get("name")?.asString ?: ""
                val argsObj = functionCall.getAsJsonObject("args")
                val args = mutableMapOf<String, String>()
                if (argsObj != null) {
                    for ((key, value) in argsObj.entrySet()) {
                        args[key] = value.asString
                    }
                }
                Log.i(TAG, "[parseGeminiResponse] Function call detected: $name($args)")
                return GeminiResponse.FunctionCall(name, args)
            }

            // Text response
            val text = firstPart.get("text")?.asString ?: ""
            if (text.isNotBlank()) {
                GeminiResponse.Text(text)
            } else {
                GeminiResponse.Error("Empty text response from Gemini")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[parseGeminiResponse] Parse error: ${e.message}")
            GeminiResponse.Error("Failed to parse Gemini response: ${e.message}")
        }
    }

    /**
     * Get the current screen context for AI awareness.
     */
    private fun getScreenContextForAI(): String {
        val svc = TaskExecutorBridge.accessibilityService?.get()
        return svc?.getScreenContextForAI() ?: "Screen context unavailable — accessibility service not connected"
    }

    /**
     * Sealed class representing different types of Gemini API responses.
     */
    sealed class GeminiResponse {
        data class FunctionCall(val name: String, val args: Map<String, String>) : GeminiResponse()
        data class Text(val text: String) : GeminiResponse()
        data class Error(val message: String) : GeminiResponse()
    }
}

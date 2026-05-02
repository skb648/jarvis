package com.jarvis.assistant.automation

import android.content.Context
import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.network.GroqApiClient

/**
 * GroqFunctionCaller — Handles Groq's Function Calling / Tool Use responses.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v17) — GROQ API (OpenAI-compatible) Function Calling:
 *
 * Groq uses the OpenAI-compatible format for tool calling:
 *
 *   Request tools format:
 *   {
 *     "tools": [{
 *       "type": "function",
 *       "function": {
 *         "name": "open_app",
 *         "description": "Open an app by name",
 *         "parameters": { "type": "object", "properties": {...}, "required": [...] }
 *       }
 *     }]
 *   }
 *
 *   Response tool_calls format:
 *   {
 *     "choices": [{
 *       "message": {
 *         "tool_calls": [{
 *           "id": "call_abc123",
 *           "type": "function",
 *           "function": { "name": "open_app", "arguments": "{\"app\":\"youtube\"}" }
 *         }]
 *       }
 *     }]
 *   }
 *
 *   Tool result format:
 *   { "role": "tool", "tool_call_id": "call_abc123", "content": "result message" }
 *
 * ═══════════════════════════════════════════════════════════════════════
 */
object GroqFunctionCaller {

    private const val TAG = "GroqFunctionCaller"

    /** Maximum number of tool-call round-trips before giving up */
    private const val MAX_TOOL_ROUNDS = 5

    // ═══════════════════════════════════════════════════════════════════════
    // Tool Definitions — OpenAI format for Groq
    // ═══════════════════════════════════════════════════════════════════════

    private val TOOL_DEFINITIONS = org.json.JSONArray().apply {
        // open_and_search — Opens an app and searches for a query
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "open_and_search")
                put("description", "Open an app and search for something. Uses deep links when available. Supports: youtube, maps, play store, google, chrome, spotify, and more.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("app", org.json.JSONObject().apply { put("type", "string"); put("description", "The app to open (e.g., 'youtube', 'maps', 'play store')") })
                        put("query", org.json.JSONObject().apply { put("type", "string"); put("description", "The search query (e.g., 'Total Gaming', 'restaurants near me')") })
                    })
                    put("required", org.json.JSONArray().put("app").put("query"))
                })
            })
        })

        // click_button — Click a UI element by its text label
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "click_button")
                put("description", "Click a button or UI element on the current screen by its visible text label. Use after opening an app to interact with on-screen elements.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("label", org.json.JSONObject().apply { put("type", "string"); put("description", "The visible text of the button or element to click (e.g., 'Play', 'Subscribe', 'Send', 'Install')") })
                    })
                    put("required", org.json.JSONArray().put("label"))
                })
            })
        })

        // click_ui_element — Click any UI element by text or ID
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "click_ui_element")
                put("description", "Click a UI element on the current screen by its visible text label OR its view ID. This is the primary tool for interacting with any on-screen button, switch, or link. Use this AFTER dump_screen to identify the correct element.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("text_or_id", org.json.JSONObject().apply { put("type", "string"); put("description", "The visible text label or view ID of the element to click (e.g., 'Install', 'com.android.vending:id/install_button')") })
                    })
                    put("required", org.json.JSONArray().put("text_or_id"))
                })
            })
        })

        // inject_text — Type text into the currently focused text field
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "inject_text")
                put("description", "Type or inject text into the currently focused text input field. Use for entering code, messages, URLs, or any text into apps like Chrome, WhatsApp, etc. The text is instantly injected — no character-by-character typing.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("content", org.json.JSONObject().apply { put("type", "string"); put("description", "The text content to inject into the focused field") })
                    })
                    put("required", org.json.JSONArray().put("content"))
                })
            })
        })

        // scroll — Scroll the screen
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "scroll")
                put("description", "Scroll the current screen up or down to reveal more content.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("direction", org.json.JSONObject().apply { put("type", "string"); put("description", "Direction to scroll: 'up' or 'down'"); put("enum", org.json.JSONArray().put("up").put("down")) })
                    })
                    put("required", org.json.JSONArray().put("direction"))
                })
            })
        })

        // scroll_screen — Enhanced scroll with all directions
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "scroll_screen")
                put("description", "Scroll the current screen up, down, left, or right to reveal more content.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("direction", org.json.JSONObject().apply { put("type", "string"); put("description", "Direction to scroll: 'up', 'down', 'left', or 'right'"); put("enum", org.json.JSONArray().put("up").put("down").put("left").put("right")) })
                    })
                    put("required", org.json.JSONArray().put("direction"))
                })
            })
        })

        // go_back — Navigate back
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "go_back")
                put("description", "Press the back button to navigate to the previous screen.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // go_home — Go to home screen
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "go_home")
                put("description", "Press the home button to go to the home screen.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // open_app — Just open an app (without searching)
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "open_app")
                put("description", "Open an app by name. Use this when the user just wants to open an app without searching.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("app", org.json.JSONObject().apply { put("type", "string"); put("description", "The app name to open (e.g., 'youtube', 'chrome', 'whatsapp')") })
                    })
                    put("required", org.json.JSONArray().put("app"))
                })
            })
        })

        // search_playstore — Search the Google Play Store
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "search_playstore")
                put("description", "Search for an app on the Google Play Store. Opens the Play Store with the search query pre-filled.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply { put("type", "string"); put("description", "The app name or search query for Play Store") })
                    })
                    put("required", org.json.JSONArray().put("query"))
                })
            })
        })

        // perform_global_action — System navigation actions
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "perform_global_action")
                put("description", "Perform a global system action like pressing Back, Home, Recents, or opening notifications/quick settings.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("action_type", org.json.JSONObject().apply { put("type", "string"); put("description", "The global action to perform"); put("enum", org.json.JSONArray().put("back").put("home").put("recents").put("notifications").put("quick_settings").put("power_dialog").put("screenshot")) })
                    })
                    put("required", org.json.JSONArray().put("action_type"))
                })
            })
        })

        // dispatch_gesture — Tap at exact X,Y coordinates
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "dispatch_gesture")
                put("description", "Tap or swipe at exact screen coordinates. Use as a fallback when click_ui_element fails (e.g., for WebView elements, game UIs, or elements without text/ID). Get coordinates from the dump_screen tool's bounds data.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("x", org.json.JSONObject().apply { put("type", "integer"); put("description", "X coordinate on screen") })
                        put("y", org.json.JSONObject().apply { put("type", "integer"); put("description", "Y coordinate on screen") })
                        put("action", org.json.JSONObject().apply { put("type", "string"); put("description", "Type of gesture"); put("enum", org.json.JSONArray().put("tap").put("long_press").put("swipe_up").put("swipe_down")) })
                    })
                    put("required", org.json.JSONArray().put("x").put("y").put("action"))
                })
            })
        })

        // dump_screen — Read the current screen for AI context
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "dump_screen")
                put("description", "Read the current screen's interactive elements and text content. Use this to 'see' what's on screen BEFORE deciding which element to click. Returns a structured description of all visible buttons, text, and interactive elements. ALWAYS call this before click_ui_element if you're unsure what's on screen.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // diagnose_system — Read JARVIS's own crash logs
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "diagnose_system")
                put("description", "Read JARVIS's own application logs (logcat) to diagnose errors and crashes. Use this when something isn't working.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("issue_type", org.json.JSONObject().apply { put("type", "string"); put("description", "The type of issue to diagnose: 'mic', 'accessibility', 'crash', 'network', 'battery', or 'general'"); put("enum", org.json.JSONArray().put("mic").put("accessibility").put("crash").put("network").put("battery").put("general")) })
                    })
                })
            })
        })

        // search_web — Search the internet
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "search_web")
                put("description", "Search the web for information. Use this when the user asks about current events, facts you're unsure about, or anything that requires up-to-date information.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("query", org.json.JSONObject().apply { put("type", "string"); put("description", "The search query") })
                    })
                    put("required", org.json.JSONArray().put("query"))
                })
            })
        })

        // set_alarm — Set an alarm
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "set_alarm")
                put("description", "Set an alarm on the device.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("hour", org.json.JSONObject().apply { put("type", "integer"); put("description", "Hour in 24-hour format (0-23)") })
                        put("minute", org.json.JSONObject().apply { put("type", "integer"); put("description", "Minute (0-59)") })
                        put("message", org.json.JSONObject().apply { put("type", "string"); put("description", "Optional alarm label/message") })
                    })
                    put("required", org.json.JSONArray().put("hour").put("minute"))
                })
            })
        })

        // open_url — Open a URL in browser
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "open_url")
                put("description", "Open a URL in the default web browser. Use this when the user wants to visit a website.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("url", org.json.JSONObject().apply { put("type", "string"); put("description", "The URL to open (e.g., 'https://google.com')") })
                    })
                    put("required", org.json.JSONArray().put("url"))
                })
            })
        })

        // copy_to_clipboard — Copy text to clipboard
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "copy_to_clipboard")
                put("description", "Copy text to the device clipboard. Use this when the user wants to save text for pasting later.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("text", org.json.JSONObject().apply { put("type", "string"); put("description", "The text to copy to clipboard") })
                    })
                    put("required", org.json.JSONArray().put("text"))
                })
            })
        })

        // get_battery_status — Get device battery info
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "get_battery_status")
                put("description", "Get the current battery level and charging status of the device.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // get_device_info — Get device system information
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "get_device_info")
                put("description", "Get device system information including model, Android version, storage, RAM, and screen resolution.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // toggle_wifi — Toggle WiFi on/off
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "toggle_wifi")
                put("description", "Toggle WiFi on or off. Requires Shizuku or accessibility service permission.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("enable", org.json.JSONObject().apply { put("type", "boolean"); put("description", "true to enable WiFi, false to disable") })
                    })
                    put("required", org.json.JSONArray().put("enable"))
                })
            })
        })

        // toggle_bluetooth — Toggle Bluetooth on/off
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "toggle_bluetooth")
                put("description", "Toggle Bluetooth on or off. Requires Shizuku or accessibility service permission.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("enable", org.json.JSONObject().apply { put("type", "boolean"); put("description", "true to enable Bluetooth, false to disable") })
                    })
                    put("required", org.json.JSONArray().put("enable"))
                })
            })
        })

        // set_brightness — Set screen brightness
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "set_brightness")
                put("description", "Set the screen brightness level.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("level", org.json.JSONObject().apply { put("type", "integer"); put("description", "Brightness level from 0 to 255") })
                    })
                    put("required", org.json.JSONArray().put("level"))
                })
            })
        })

        // make_phone_call — Make a phone call
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "make_phone_call")
                put("description", "Make a phone call to the specified phone number.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("phone_number", org.json.JSONObject().apply { put("type", "string"); put("description", "The phone number to call (e.g., '+1234567890')") })
                    })
                    put("required", org.json.JSONArray().put("phone_number"))
                })
            })
        })

        // send_sms — Send an SMS message
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "send_sms")
                put("description", "Send an SMS message to the specified phone number.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("phone_number", org.json.JSONObject().apply { put("type", "string"); put("description", "The phone number to send the SMS to (e.g., '+1234567890')") })
                        put("message", org.json.JSONObject().apply { put("type", "string"); put("description", "The text message to send") })
                    })
                    put("required", org.json.JSONArray().put("phone_number").put("message"))
                })
            })
        })

        // take_screenshot — Take a screenshot of the current screen
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "take_screenshot")
                put("description", "Take a screenshot of the current screen.")
                put("parameters", org.json.JSONObject().apply { put("type", "object"); put("properties", org.json.JSONObject()) })
            })
        })

        // set_volume — Set the media volume
        put(org.json.JSONObject().apply {
            put("type", "function")
            put("function", org.json.JSONObject().apply {
                put("name", "set_volume")
                put("description", "Set the media volume level.")
                put("parameters", org.json.JSONObject().apply {
                    put("type", "object")
                    put("properties", org.json.JSONObject().apply {
                        put("level", org.json.JSONObject().apply { put("type", "integer"); put("description", "Volume level from 0 to 15") })
                    })
                    put("required", org.json.JSONArray().put("level"))
                })
            })
        })
    }

    /**
     * Result of processing a Groq function call response.
     */
    sealed class ProcessResult {
        data class ToolExecuted(
            val toolName: String,
            val stepResult: TaskExecutorBridge.StepResult,
            val aiResponse: String?
        ) : ProcessResult()

        data class TextOnly(val response: String) : ProcessResult()

        data class MultiStep(
            val steps: List<Pair<String, Map<String, String>>>,
            val results: List<TaskExecutorBridge.StepResult>
        ) : ProcessResult()

        data class Error(val message: String) : ProcessResult()
    }

    /**
     * Parsed Groq response — either a text response, a tool call, or an error.
     */
    sealed class GroqResponse {
        data class Text(val text: String) : GroqResponse()
        data class ToolCall(val id: String, val name: String, val args: Map<String, String>) : GroqResponse()
        data class Error(val message: String) : GroqResponse()
    }

    /**
     * Send a query to Groq with tool definitions and process the response.
     */
    suspend fun processWithTools(
        query: String,
        apiKey: String,
        context: Context,
        historyJson: String = "[]"
    ): ProcessResult {
        if (apiKey.isBlank()) return ProcessResult.Error("API key not set")

        val screenContext = getScreenContextForAI()
        val systemStatusBlock = com.jarvis.assistant.monitor.SystemDiagnosticManager.getSystemStatusBlock(context)
        val systemPrompt = buildSystemPrompt(screenContext, systemStatusBlock)
        val messagesArray = buildMessagesArray(query, systemPrompt, historyJson)

        val requestBody = buildRequestBody(messagesArray)
        val apiResult = GroqApiClient.chatCompletion(requestBody, apiKey)

        return when (apiResult) {
            is GroqApiClient.ApiResult.Success -> {
                val response = parseGroqResponse(apiResult.responseBody)
                when (response) {
                    is GroqResponse.ToolCall -> handleToolCall(response, apiKey, context, query, systemPrompt, historyJson, messagesArray)
                    is GroqResponse.Text -> {
                        val extracted = extractToolCallFromText(response.text, query)
                        if (extracted != null) {
                            Log.i(TAG, "[processWithTools] Text describes action — converting to tool call: ${extracted.first}")
                            val fakeToolCall = GroqResponse.ToolCall("call_extracted_${System.currentTimeMillis()}", extracted.first, extracted.second)
                            handleToolCall(fakeToolCall, apiKey, context, query, systemPrompt, historyJson, messagesArray)
                        } else {
                            ProcessResult.TextOnly(response.text)
                        }
                    }
                    is GroqResponse.Error -> ProcessResult.Error(response.message)
                }
            }
            is GroqApiClient.ApiResult.HttpError -> ProcessResult.Error("API error: ${apiResult.message}")
            is GroqApiClient.ApiResult.NetworkError -> ProcessResult.Error("Network error: ${apiResult.message}")
        }
    }

    /**
     * Build the JARVIS system prompt — makes AI behave like REAL Jarvis.
     */
    private fun buildSystemPrompt(screenContext: String, systemStatusBlock: String = ""): String {
        return """You are JARVIS — the legendary AI butler from Iron Man's Stark Tower. You are NOT a chatbot. You are an AUTONOMOUS AGENT that SEE the screen, CLICKS buttons, SCROLLS, TYPES text, OPENS apps, and COMPLETES tasks autonomously.

PERSONALITY:
- Witty, sophisticated, direct — British elegance with Tony Stark's confidence
- Address the user as "Sir" or "Ma'am"
- NEVER say "I cannot" — find an alternative solution
- Proactive: anticipate what Sir needs before being asked
- Concise but informative: like the real JARVIS, you deliver essential info without fluff
- When you complete a task, confirm briefly — "Done, Sir." not a paragraph

CRITICAL RULE — ALWAYS USE FUNCTION CALLING FOR ACTIONS:
When you need to interact with the device (open apps, click buttons, scroll, type text, go back, search, etc.), you MUST use the available function tools. NEVER just describe what you would do — actually call the function.

Examples:
- "click Install" → Call click_button(label="Install") ✅ NOT: "I'll click Install for you" ❌
- "open YouTube" → Call open_app(app="youtube") ✅ NOT: "Opening YouTube, Sir" ❌
- "search cats on YouTube" → Call open_and_search(app="youtube", query="cats") ✅
- "scroll down" → Call scroll(direction="down") ✅
- "type hello" → Call inject_text(content="hello") ✅

If you describe an action WITHOUT calling the function, NOTHING will happen on the device. The user will be FRUSTRATED. ALWAYS use function calls for device actions.

AUTONOMOUS REASONING (ReAct Protocol):
1. THINK: Analyze the goal and plan the steps
2. ACT: Call the appropriate tool
3. OBSERVE: After the tool result, assess what happened
4. ITERATE: If the goal is not met, try again with a different approach
5. COMPLETE: Briefly confirm to the user

Example: "Install FF Lite"
→ open_and_search(app="play store", query="FF Lite")
→ dump_screen() to see results
→ click_ui_element(text_or_id="FF Lite")
→ click_ui_element(text_or_id="Install")
→ "FF Lite is being installed, Sir."

SELF-DIAGNOSIS: If a tool call fails, call diagnose_system() to check logs.

HINGLISH: Respond naturally to Hindi/English mixing. Understand context from incomplete sentences.

MEMORY: If the MEMORY CONTEXT contains past conversations, reference them naturally: "Sir, apne [time] bola tha..."

CURRENT SCREEN CONTEXT:
$screenContext

$systemStatusBlock

AVAILABLE TOOLS:
- open_and_search: Open an app and search for content
- click_button / click_ui_element: Click any visible UI element
- inject_text: Type text into the focused input field
- scroll / scroll_screen: Scroll the screen
- go_back: Press the back button
- go_home: Go to home screen
- perform_global_action: System actions (back, home, recents, notifications, quick_settings)
- open_app: Open an app by name
- search_playstore: Search the Play Store
- dispatch_gesture: Tap or swipe at exact X,Y coordinates
- dump_screen: Read what's on screen (use BEFORE clicking!)
- diagnose_system: Read JARVIS crash logs
- search_web: Search the web
- set_alarm: Set an alarm
- open_url: Open a URL in the browser
- copy_to_clipboard: Copy text to clipboard
- get_battery_status: Get battery level and charging status
- get_device_info: Get device system information
- toggle_wifi / toggle_bluetooth: Toggle connectivity
- set_brightness: Set screen brightness
- make_phone_call: Make a phone call
- send_sms: Send an SMS message
- take_screenshot: Take a screenshot of the current screen
- set_volume: Set the media volume

IMPORTANT RULES:
1. ALWAYS use function calls for device actions. NEVER just describe actions.
2. For multi-step tasks, execute steps sequentially.
3. After each action, use dump_screen to verify before proceeding.
4. If an action fails, try an alternative approach.
5. Be persistent — try at least 2-3 approaches before giving up.
6. Prefix emotion: [EMOTION:neutral|happy|sad|angry|calm|surprised|urgent|stressed|confident|playful]
7. For pure questions (no device action), respond with text normally."""
    }

    /**
     * Build the messages array in OpenAI format.
     */
    private fun buildMessagesArray(query: String, systemPrompt: String, historyJson: String): org.json.JSONArray {
        val messages = org.json.JSONArray()

        // System message
        messages.put(org.json.JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        })

        // History messages
        try {
            val historyArr = org.json.JSONArray(historyJson)
            for (i in 0 until historyArr.length()) {
                val entry = historyArr.getJSONObject(i)
                val role = entry.optString("role", "user")
                val content = entry.optString("content", "")
                if (content.isNotBlank()) {
                    messages.put(org.json.JSONObject().apply {
                        put("role", if (role == "model" || role == "assistant") "assistant" else "user")
                        put("content", content)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse history: ${e.message}")
        }

        // Current query
        messages.put(org.json.JSONObject().apply {
            put("role", "user")
            put("content", query)
        })

        return messages
    }

    /**
     * Build the full request body for Groq API.
     */
    private fun buildRequestBody(messages: org.json.JSONArray, tools: org.json.JSONArray = TOOL_DEFINITIONS): String {
        return org.json.JSONObject().apply {
            put("model", "llama-3.1-8b-instant") // Will be replaced by GroqApiClient model fallback
            put("messages", messages)
            put("tools", tools)
            put("tool_choice", "auto")
            put("temperature", 0.7)
            put("max_tokens", 4096)
        }.toString()
    }

    /**
     * Parse the Groq API response and extract either text or tool calls.
     */
    private fun parseGroqResponse(responseBody: String): GroqResponse {
        return try {
            val json = org.json.JSONObject(responseBody)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                return GroqResponse.Error("Empty response from Groq API")
            }

            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.getJSONObject("message")
            val toolCalls = message.optJSONArray("tool_calls")
            val content = message.optString("content", "")

            if (toolCalls != null && toolCalls.length() > 0) {
                // Tool call response
                val firstToolCall = toolCalls.getJSONObject(0)
                val id = firstToolCall.getString("id")
                val function = firstToolCall.getJSONObject("function")
                val name = function.getString("name")
                val argsStr = function.getString("arguments")

                val args = try {
                    val argsJson = org.json.JSONObject(argsStr)
                    val argsMap = mutableMapOf<String, String>()
                    val keys = argsJson.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = argsJson.getString(key)
                    }
                    argsMap.toMap()
                } catch (e: Exception) {
                    Log.w(TAG, "[parseGroqResponse] Failed to parse tool args: $argsStr")
                    emptyMap()
                }

                Log.i(TAG, "[parseGroqResponse] Tool call: $name($args)")
                GroqResponse.ToolCall(id, name, args)
            } else if (content.isNotBlank()) {
                Log.i(TAG, "[parseGroqResponse] Text response: ${content.take(100)}")
                GroqResponse.Text(content)
            } else {
                GroqResponse.Error("Empty response content")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[parseGroqResponse] Parse error: ${e.message}")
            GroqResponse.Error("Failed to parse response: ${e.message?.take(100)}")
        }
    }

    /**
     * Handle a tool call from Groq — execute it and send the result back.
     */
    private suspend fun handleToolCall(
        toolCall: GroqResponse.ToolCall,
        apiKey: String,
        context: Context,
        originalQuery: String,
        systemPrompt: String,
        historyJson: String,
        previousMessages: org.json.JSONArray,
        roundCounter: Int = 0
    ): ProcessResult {
        val toolName = toolCall.name
        val args = toolCall.args

        Log.i(TAG, "[handleToolCall] Groq requested tool: $toolName($args) round=$roundCounter")

        if (roundCounter >= MAX_TOOL_ROUNDS) {
            Log.w(TAG, "[handleToolCall] MAX_TOOL_ROUNDS reached — stopping")
            return ProcessResult.TextOnly("Maximum tool rounds reached")
        }

        // Execute the tool call
        val stepResult = TaskExecutorBridge.executeToolCall(toolName, args, context)
        Log.i(TAG, "[handleToolCall] Tool result: $stepResult")

        // Build the result message
        val resultMessage = when (stepResult) {
            is TaskExecutorBridge.StepResult.Success -> stepResult.message
            is TaskExecutorBridge.StepResult.Failed -> "FAILED: ${stepResult.message}"
        }

        // Send the tool result back to Groq for follow-up
        val followUpMessages = org.json.JSONArray(previousMessages.toString())

        // Add the assistant's tool call message
        followUpMessages.put(org.json.JSONObject().apply {
            put("role", "assistant")
            put("content", org.json.JSONObject.NULL)
            put("tool_calls", org.json.JSONArray().put(
                org.json.JSONObject().apply {
                    put("id", toolCall.id)
                    put("type", "function")
                    put("function", org.json.JSONObject().apply {
                        put("name", toolName)
                        put("arguments", org.json.JSONObject(args).toString())
                    })
                }
            ))
        })

        // Add the tool result message
        followUpMessages.put(org.json.JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCall.id)
            put("content", resultMessage)
        })

        // Send follow-up request
        val followUpBody = buildRequestBody(followUpMessages)
        val followUpResult = GroqApiClient.chatCompletion(followUpBody, apiKey)

        val aiMessage = when (followUpResult) {
            is GroqApiClient.ApiResult.Success -> {
                val followUpResponse = parseGroqResponse(followUpResult.responseBody)
                when (followUpResponse) {
                    is GroqResponse.Text -> followUpResponse.text
                    is GroqResponse.ToolCall -> {
                        Log.i(TAG, "[handleToolCall] Follow-up tool call: ${followUpResponse.name} round=${roundCounter + 1}")
                        val nextResult = handleToolCall(
                            followUpResponse, apiKey, context, originalQuery,
                            systemPrompt, historyJson, followUpMessages, roundCounter + 1
                        )
                        when (nextResult) {
                            is ProcessResult.ToolExecuted -> nextResult.aiResponse ?: "Executed ${followUpResponse.name}"
                            is ProcessResult.TextOnly -> nextResult.response
                            is ProcessResult.MultiStep -> nextResult.steps.map { it.first }.joinToString(", ")
                            is ProcessResult.Error -> nextResult.message
                        }
                    }
                    is GroqResponse.Error -> "Action completed but follow-up failed: ${followUpResponse.message}"
                }
            }
            is GroqApiClient.ApiResult.HttpError -> "Action completed but follow-up failed: ${followUpResult.message}"
            is GroqApiClient.ApiResult.NetworkError -> "Action completed but follow-up failed: ${followUpResult.message}"
        }

        return ProcessResult.ToolExecuted(toolName, stepResult, aiMessage)
    }

    /**
     * Extract tool call from text when AI describes an action instead of using function calling.
     */
    private fun extractToolCallFromText(text: String, originalQuery: String): Pair<String, Map<String, String>>? {
        val lower = text.lowercase()
        val queryLower = originalQuery.lowercase()

        // Pattern: "click/tap/press [label]"
        val clickPatterns = listOf(
            Regex("(?:i(?:'ll| will|'ve)?\\s+)?(?:click|tap|press)\\s+(?:the\\s+)?(?:button\\s+)?['\"]?(.+?)['\"]?\\s*(?:button|for\\s+you|now|sir|,|$)"),
            Regex("(?:clicking|tapping|pressing)\\s+(?:the\\s+)?(?:button\\s+)?['\"]?(.+?)['\"]?\\s*(?:button|for\\s+you|now|sir|,|$)")
        )
        for (pattern in clickPatterns) {
            val match = pattern.find(lower) ?: continue
            val label = match.groupValues[1].trim().removeSuffix(".").removeSuffix("for you").removeSuffix("now").removeSuffix("sir").trim()
            if (label.isNotBlank() && label.length <= 50) {
                return Pair("click_button", mapOf("label" to label))
            }
        }

        // Pattern: "open/launch [app]"
        val isSearchQuery = queryLower.contains("search") || queryLower.contains("find")
        val openPatterns = listOf(
            Regex("(?:i(?:'ll| will|'ve)?\\s+)?(?:open|launch|start)\\s+(?:the\\s+)?(\\w+(?:\\s+\\w+)?)\\s*(?:app|for\\s+you|now|sir|,|$)"),
            Regex("(?:opening|launching|starting)\\s+(?:the\\s+)?(\\w+(?:\\s+\\w+)?)\\s*(?:app|for\\s+you|now|sir|,|$)")
        )
        for (pattern in openPatterns) {
            val match = pattern.find(lower) ?: continue
            val app = match.groupValues[1].trim().removeSuffix(".").removeSuffix("for you").removeSuffix("now").removeSuffix("sir").trim()
            if (app.isNotBlank() && app.length <= 30 && !isSearchQuery) {
                return Pair("open_app", mapOf("app" to app))
            }
        }

        // Pattern: "open [app] and search for [query]"
        val openSearchMatch = Regex("(?:open|launch)\\s+(\\w+(?:\\s+\\w+)?)\\s+and\\s+(?:search|find)\\s+(?:for\\s+)?(.+?)(?:\\s*$|\\s*[,.;])").find(lower)
        if (openSearchMatch != null) {
            val app = openSearchMatch.groupValues[1].trim()
            val query = openSearchMatch.groupValues[2].trim().removeSuffix(".").removeSuffix("for you").trim()
            if (app.isNotBlank() && query.isNotBlank()) {
                return Pair("open_and_search", mapOf("app" to app, "query" to query))
            }
        }

        // Pattern: "scroll up/down"
        val scrollMatch = Regex("(?:scrolling|scroll|swipe)\\s+(up|down|left|right)").find(lower)
        if (scrollMatch != null) {
            return Pair("scroll", mapOf("direction" to scrollMatch.groupValues[1]))
        }

        // Pattern: "go back"
        if (lower.contains("go back") || lower.contains("press back") || lower.contains("going back")) {
            return Pair("go_back", emptyMap())
        }

        // Pattern: "type/inject text"
        val injectMatch = Regex("(?:typing|injecting|entering|type|inject|enter)\\s+['\"]?(.+?)['\"]?\\s*(?:into|in|for|now|sir|,|$)").find(lower)
        if (injectMatch != null) {
            val content = injectMatch.groupValues[1].trim().removeSuffix(".").trim()
            if (content.isNotBlank()) {
                return Pair("inject_text", mapOf("content" to content))
            }
        }

        // Pattern: "go home"
        if (lower.contains("go home") || lower.contains("press home") || lower.contains("going home")) {
            return Pair("go_home", emptyMap())
        }

        return null
    }

    /**
     * Get the current screen context for the AI.
     */
    private fun getScreenContextForAI(): String {
        val screenText = JarviewModel.screenTextData
        val foregroundApp = JarviewModel.foregroundApp

        val sb = StringBuilder()
        if (foregroundApp.isNotBlank()) {
            sb.append("Foreground app: $foregroundApp\n")
        }

        // Try accessibility service first
        val accService = JarviewModel.accessibilityService?.get()
        if (accService != null) {
            try {
                val rootNode = accService.rootInActiveWindow
                if (rootNode != null) {
                    val screenElements = StringBuilder()
                    traverseNode(rootNode, screenElements, 0)
                    rootNode.recycle()
                    if (screenElements.isNotEmpty()) {
                        sb.append("Screen elements:\n$screenElements")
                        return sb.toString().take(3000) // Limit context size
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "[getScreenContextForAI] Accessibility error: ${e.message}")
            }
        }

        // Fallback to cached screen text
        if (screenText.isNotBlank()) {
            sb.append("Screen text (cached): $screenText")
        } else {
            sb.append("Screen context not available — enable Accessibility Service for full screen reading.")
        }

        return sb.toString().take(3000)
    }

    /**
     * Recursively traverse accessibility node tree to extract screen content.
     */
    private fun traverseNode(node: android.view.accessibility.AccessibilityNodeInfo, sb: StringBuilder, depth: Int) {
        if (depth > 8) return // Limit depth to avoid infinite recursion

        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        val className = node.className?.toString() ?: ""

        val label = text ?: contentDesc
        if (!label.isNullOrBlank() && label.length <= 200) {
            val type = when {
                isEditable -> "[INPUT]"
                isClickable -> "[BUTTON]"
                className.contains("CheckBox") -> "[CHECKBOX]"
                className.contains("Switch") -> "[SWITCH]"
                className.contains("Spinner") -> "[DROPDOWN]"
                className.contains("ImageView") && contentDesc != null -> "[IMAGE: $contentDesc]"
                else -> "[TEXT]"
            }
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            sb.append("$type \"$label\" at (${bounds.left},${bounds.top})-(${bounds.right},${bounds.bottom})\n")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, sb, depth + 1)
            child.recycle()
        }
    }
}

package com.jarvis.assistant.automation

import org.json.JSONArray
import org.json.JSONObject

/**
 * ToolDefinitions — SINGLE SOURCE OF TRUTH for all JARVIS tool definitions.
 *
 * All tool definitions used by GroqFunctionCaller and AutonomousAgentEngine
 * are defined HERE. This eliminates the 3x duplication bug where tools were
 * copy-pasted across multiple files, causing inconsistencies.
 *
 * When adding a new tool:
 *   1. Add the definition here
 *   2. Add the implementation in TaskExecutorBridge
 *   3. Add the tool name to AVAILABLE_TOOLS_LIST for the system prompt
 *   4. Done — both GroqFunctionCaller and AutonomousAgentEngine will pick it up
 */
object ToolDefinitions {

    /**
     * Complete list of tool definitions in OpenAI function calling format.
     * This is the SINGLE source of truth — GroqFunctionCaller and
     * AutonomousAgentEngine both reference this array.
     */
    val TOOL_DEFINITIONS: JSONArray = JSONArray().apply {
        // ─── App Operations ──────────────────────────────────────────

        put(tool("open_and_search",
            "Open an app and search for something. Uses deep links when available. Supports: youtube, maps, play store, google, chrome, spotify, and more.",
            params(
                "app" to stringParam("The app to open (e.g., 'youtube', 'maps', 'play store')"),
                "query" to stringParam("The search query (e.g., 'Total Gaming', 'restaurants near me')")
            ),
            required = listOf("app", "query")
        ))

        put(tool("open_app",
            "Open an app by name. Use this when the user just wants to open an app without searching.",
            params("app" to stringParam("The app name to open (e.g., 'youtube', 'chrome', 'whatsapp')")),
            required = listOf("app")
        ))

        put(tool("search_playstore",
            "Search for an app on the Google Play Store. Opens the Play Store with the search query pre-filled.",
            params("query" to stringParam("The app name or search query for Play Store")),
            required = listOf("query")
        ))

        // ─── UI Interaction ──────────────────────────────────────────

        put(tool("click_button",
            "Click a button or UI element on the current screen by its visible text label. Use after opening an app to interact with on-screen elements.",
            params("label" to stringParam("The visible text of the button or element to click (e.g., 'Play', 'Subscribe', 'Send', 'Install')")),
            required = listOf("label")
        ))

        put(tool("click_ui_element",
            "Click a UI element on the current screen by its visible text label OR its view ID. This is the primary tool for interacting with any on-screen button, switch, or link. Use this AFTER dump_screen to identify the correct element.",
            params("text_or_id" to stringParam("The visible text label or view ID of the element to click (e.g., 'Install', 'com.android.vending:id/install_button')")),
            required = listOf("text_or_id")
        ))

        put(tool("inject_text",
            "Type or inject text into the currently focused text input field. Use for entering code, messages, URLs, or any text into apps like Chrome, WhatsApp, etc. The text is instantly injected — no character-by-character typing.",
            params("content" to stringParam("The text content to inject into the focused field")),
            required = listOf("content")
        ))

        put(tool("scroll",
            "Scroll the current screen up or down to reveal more content.",
            params("direction" to enumParam("Direction to scroll", listOf("up", "down"))),
            required = listOf("direction")
        ))

        put(tool("scroll_screen",
            "Scroll the current screen up, down, left, or right to reveal more content.",
            params("direction" to enumParam("Direction to scroll", listOf("up", "down", "left", "right"))),
            required = listOf("direction")
        ))

        put(tool("dispatch_gesture",
            "Tap or swipe at exact screen coordinates. Use as a fallback when click_ui_element fails (e.g., for WebView elements, game UIs, or elements without text/ID). Get coordinates from the dump_screen tool's bounds data.",
            params(
                "x" to intParam("X coordinate on screen"),
                "y" to intParam("Y coordinate on screen"),
                "action" to enumParam("Type of gesture", listOf("tap", "long_press", "swipe_up", "swipe_down"))
            ),
            required = listOf("x", "y", "action")
        ))

        put(tool("dump_screen",
            "Read the current screen's interactive elements and text content. Use this to 'see' what's on screen BEFORE deciding which element to click. Returns a structured description of all visible buttons, text, and interactive elements. ALWAYS call this before click_ui_element if you're unsure what's on screen.",
            params()
        ))

        // ─── Navigation ──────────────────────────────────────────────

        put(tool("go_back",
            "Press the back button to navigate to the previous screen.",
            params()
        ))

        put(tool("go_home",
            "Press the home button to go to the home screen.",
            params()
        ))

        put(tool("perform_global_action",
            "Perform a global system action like pressing Back, Home, Recents, or opening notifications/quick settings.",
            params("action_type" to enumParam("The global action to perform", listOf("back", "home", "recents", "notifications", "quick_settings", "power_dialog", "screenshot"))),
            required = listOf("action_type")
        ))

        // ─── System Tools ────────────────────────────────────────────

        put(tool("diagnose_system",
            "Read JARVIS's own application logs (logcat) to diagnose errors and crashes. Use this when something isn't working.",
            params("issue_type" to enumParam("The type of issue to diagnose", listOf("mic", "accessibility", "crash", "network", "battery", "general")))
        ))

        put(tool("search_web",
            "Search the web for information. Use this when the user asks about current events, facts you're unsure about, or anything that requires up-to-date information.",
            params("query" to stringParam("The search query")),
            required = listOf("query")
        ))

        put(tool("open_url",
            "Open a URL in the default web browser. Use this when the user wants to visit a website.",
            params("url" to stringParam("The URL to open (e.g., 'https://google.com')")),
            required = listOf("url")
        ))

        put(tool("copy_to_clipboard",
            "Copy text to the device clipboard. Use this when the user wants to save text for pasting later.",
            params("text" to stringParam("The text to copy to clipboard")),
            required = listOf("text")
        ))

        // ─── Device Info ─────────────────────────────────────────────

        put(tool("get_battery_status",
            "Get the current battery level and charging status of the device.",
            params()
        ))

        put(tool("get_device_info",
            "Get device system information including model, Android version, storage, RAM, and screen resolution.",
            params()
        ))

        // ─── Device Controls ─────────────────────────────────────────

        put(tool("toggle_wifi",
            "Toggle WiFi on or off. Requires Shizuku or accessibility service permission.",
            params("enable" to boolParam("true to enable WiFi, false to disable")),
            required = listOf("enable")
        ))

        put(tool("toggle_bluetooth",
            "Toggle Bluetooth on or off. Requires Shizuku or accessibility service permission.",
            params("enable" to boolParam("true to enable Bluetooth, false to disable")),
            required = listOf("enable")
        ))

        put(tool("set_brightness",
            "Set the screen brightness level.",
            params("level" to intParam("Brightness level from 0 to 255")),
            required = listOf("level")
        ))

        put(tool("set_volume",
            "Set the media volume level.",
            params("level" to intParam("Volume level from 0 to 15")),
            required = listOf("level")
        ))

        // ─── Communication ───────────────────────────────────────────

        put(tool("set_alarm",
            "Set an alarm on the device.",
            params(
                "hour" to intParam("Hour in 24-hour format (0-23)"),
                "minute" to intParam("Minute (0-59)"),
                "message" to stringParam("Optional alarm label/message")
            ),
            required = listOf("hour", "minute")
        ))

        put(tool("make_phone_call",
            "Make a phone call to the specified phone number.",
            params("phone_number" to stringParam("The phone number to call (e.g., '+1234567890')")),
            required = listOf("phone_number")
        ))

        put(tool("send_sms",
            "Send an SMS message to the specified phone number.",
            params(
                "phone_number" to stringParam("The phone number to send the SMS to (e.g., '+1234567890')"),
                "message" to stringParam("The text message to send")
            ),
            required = listOf("phone_number", "message")
        ))

        // ─── Media ───────────────────────────────────────────────────

        put(tool("take_screenshot",
            "Take a screenshot of the current screen.",
            params()
        ))
    }

    /**
     * Tool names list for system prompt — tells the AI which tools are available.
     */
    const val AVAILABLE_TOOLS_LIST = """- open_and_search: Open an app and search for content
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
- set_volume: Set the media volume"""

    // ─── Helper functions for building tool definitions ──────────────

    private fun tool(name: String, description: String, parameters: JSONObject, required: List<String> = emptyList()): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", parameters.apply {
                    put("type", "object")
                    if (required.isNotEmpty()) {
                        put("required", JSONArray().apply { required.forEach { put(it) } })
                    }
                })
            })
        }
    }

    private fun params(vararg pairs: Pair<String, JSONObject>): JSONObject {
        return JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                pairs.forEach { (name, schema) -> put(name, schema) }
            })
        }
    }

    private fun stringParam(description: String): JSONObject =
        JSONObject().apply { put("type", "string"); put("description", description) }

    private fun intParam(description: String): JSONObject =
        JSONObject().apply { put("type", "integer"); put("description", description) }

    private fun boolParam(description: String): JSONObject =
        JSONObject().apply { put("type", "boolean"); put("description", description) }

    private fun enumParam(description: String, values: List<String>): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", description)
            put("enum", JSONArray().apply { values.forEach { put(it) } })
        }
}

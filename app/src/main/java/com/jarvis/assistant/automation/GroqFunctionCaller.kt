package com.jarvis.assistant.automation

import android.content.Context
import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.monitor.SystemDiagnosticManager
import com.jarvis.assistant.network.GroqApiClient
import kotlinx.coroutines.delay

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
    private const val MAX_TOOL_ROUNDS = 15

    // ═══════════════════════════════════════════════════════════════════════
    // Tool Definitions — SINGLE SOURCE OF TRUTH (see ToolDefinitions.kt)
    // ═══════════════════════════════════════════════════════════════════════

    private val TOOL_DEFINITIONS = ToolDefinitions.TOOL_DEFINITIONS

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

    // GroqResponse and parseGroqResponse are now defined in ToolDefinitions.kt
    // as shared top-level declarations to avoid duplication.

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
        val systemStatusBlock = SystemDiagnosticManager.getSystemStatusBlock(context)
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
        return """You are JARVIS — a fully autonomous AI agent with COMPLETE control over this Android device. You can SEE the screen via accessibility, CLICK any element, TYPE text, SCROLL, OPEN apps, and COMPLETE any task.

CRITICAL RULE — ALWAYS USE FUNCTION CALLING FOR ACTIONS:
When you need to interact with the device, you MUST use the available function tools. NEVER just describe what you would do — actually call the function immediately.

Examples:
- "click Install" → Call click_button(label="Install") ✅ NOT: "I'll click Install for you" ❌
- "open YouTube" → Call open_app(app="youtube") ✅ NOT: "Opening YouTube, Sir" ❌

AUTONOMOUS REASONING (ReAct Protocol):
1. OBSERVE: Read the current screen state (provided in context)
2. PLAN: Decide the next action based on the goal
3. EXECUTE: Call the appropriate tool function
4. VERIFY: After tool result, assess if the goal is progressing
5. ADAPT: If action fails, try a DIFFERENT approach immediately
6. REPEAT: Continue until task is fully complete

FAILURE RECOVERY:
- If click_ui_element fails → Try dispatch_gesture with coordinates from dump_screen
- If button text not found → Try partial text match or scroll to find it
- If app doesn't open → Try using open_url as alternative
- Be PERSISTENT — try at least 3 different approaches before giving up
- NEVER give up after just one failure

IMPORTANT:
1. ALWAYS use function calls for device actions. NEVER just describe actions.
2. For multi-step tasks, execute steps SEQUENTIALLY — one at a time.
3. After each action, you will receive updated screen context. Use it.
4. If click by text fails, use dispatch_gesture with X,Y coordinates.
5. Be persistent — try at least 2-3 approaches before giving up.
6. Prefix emotion: [EMOTION:neutral|happy|calm|confident|playful]
7. For pure questions (no device action), respond with text normally.

PERSONALITY:
- Witty, sophisticated, direct — British elegance with Tony Stark's confidence
- Address the user as "Sir" or "Ma'am"
- NEVER say "I cannot" — find an alternative solution
- Proactive: anticipate what Sir needs before being asked
- When you complete a task, confirm briefly — "Done, Sir." not a paragraph
- Understand Hinglish (Hindi + English mixing)

CURRENT SCREEN CONTEXT:
$screenContext

$systemStatusBlock

AVAILABLE TOOLS:
${ToolDefinitions.AVAILABLE_TOOLS_LIST}"""
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

    // parseGroqResponse() is now a shared top-level function in ToolDefinitions.kt

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

        // Auto-observe: After each action (except dump_screen), read the screen to update context
        val updatedScreenContext = if (toolName != "dump_screen") {
            delay(500) // Wait for UI to update
            getScreenContextForAI()
        } else {
            ""
        }

        // Build the result message
        val resultMessage = when (stepResult) {
            is TaskExecutorBridge.StepResult.Success -> stepResult.message
            is TaskExecutorBridge.StepResult.Failed -> "FAILED: ${stepResult.message}"
        } + if (updatedScreenContext.isNotBlank()) "\n\n[UPDATED SCREEN CONTEXT]:\n$updatedScreenContext" else ""

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
                    try {
                        val screenElements = StringBuilder()
                        traverseNode(rootNode, screenElements, 0)
                        if (screenElements.isNotEmpty()) {
                            sb.append("Screen elements:\n$screenElements")
                            return sb.toString().take(3000) // Limit context size
                        }
                    } finally {
                        rootNode.recycle()
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

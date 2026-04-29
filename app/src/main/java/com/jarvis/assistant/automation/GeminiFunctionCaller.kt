package com.jarvis.assistant.automation

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.JsonParser
import com.jarvis.assistant.channels.JarviewModel
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

        // Build the system prompt with screen context
        val screenContext = getScreenContextForAI()
        val systemPrompt = buildSystemPrompt(screenContext)

        // Build the request with tools
        val requestBuilder = buildRequestWithTools(query, systemPrompt, historyJson)

        // Send to Gemini and process the response
        val response = sendToGemini(requestBuilder, apiKey)

        return when (response) {
            is GeminiResponse.FunctionCall -> handleFunctionCall(response, apiKey, context, query, systemPrompt, historyJson)
            is GeminiResponse.Text -> ProcessResult.TextOnly(response.text)
            is GeminiResponse.Error -> ProcessResult.Error(response.message)
        }
    }

    /**
     * Build the JARVIS system prompt with current screen context.
     */
    private fun buildSystemPrompt(screenContext: String): String {
        return """You are JARVIS, Tony Stark's AI assistant with the ability to CONTROL THE DEVICE. \
You can open apps, search, click buttons, type text, and navigate the phone. \
You are sophisticated, witty, and always helpful. You speak concisely and with British elegance. \
You address the user as "Sir" or "Ma'am".

CURRENT SCREEN CONTEXT:
$screenContext

IMPORTANT: When the user asks you to DO something (not just answer a question), use the available tools:
- open_and_search: When they want to search in an app ("Search cats on YouTube")
- click_button: When they want to click something ("Click the play button")
- inject_text: When they want to type something ("Type hello in WhatsApp")
- scroll: When they want to scroll ("Scroll down")
- go_back / go_home: When they want to navigate
- open_app: When they just want to open an app

For MULTI-STEP commands like "Open YouTube, search for X, and play the first video":
1. Call open_and_search first
2. Then click_button on the result

Always respond with a tool call when the user's intent is an action. Only give a text response for questions/conversations.

If you detect an emotion in the user's query, prefix your text response with [EMOTION:emotion] where emotion is one of: \
neutral, happy, sad, angry, calm, surprised, urgent, stressed, confused, playful."""
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
        historyJson: String
    ): ProcessResult {
        val toolName = response.name
        val args = response.args

        Log.i(TAG, "[handleFunctionCall] Gemini requested tool: $toolName($args)")

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
                // Gemini wants to call another tool — execute it
                Log.i(TAG, "[handleFunctionCall] Follow-up tool call: ${followUpResponse.name}")
                val nextResult = TaskExecutorBridge.executeToolCall(
                    followUpResponse.name, followUpResponse.args, context
                )
                "Executed ${followUpResponse.name}: ${when (nextResult) {
                    is TaskExecutorBridge.StepResult.Success -> nextResult.message
                    is TaskExecutorBridge.StepResult.Failed -> nextResult.message
                }}"
            }
            is GeminiResponse.Error -> "Action completed but follow-up failed: ${followUpResponse.message}"
        }

        return ProcessResult.ToolExecuted(toolName, stepResult, aiMessage)
    }

    /**
     * Send the tool execution result back to Gemini so it can continue
     * the conversation or plan the next step.
     */
    private fun sendToolResultBack(
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
     */
    private fun sendToGemini(requestBody: String, apiKey: String): GeminiResponse {
        return try {
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
                return GeminiResponse.Error("Gemini API error: HTTP $responseCode")
            }

            val responseBody = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            parseGeminiResponse(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "[sendToGemini] Exception: ${e.message}")
            GeminiResponse.Error("Network error: ${e.message?.take(100)}")
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

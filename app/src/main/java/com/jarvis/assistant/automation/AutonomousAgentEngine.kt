package com.jarvis.assistant.automation

import android.content.Context
import android.util.Log
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.network.GroqApiClient
import com.jarvis.assistant.monitor.SystemDiagnosticManager
import com.jarvis.assistant.services.JarvisAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/**
 * AutonomousAgentEngine — The CORE autonomous agent engine for JARVIS.
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * ARCHITECTURE: See-Think-Act-Observe (ReAct) Loop
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * This engine implements a full autonomous agent loop inspired by OpenAI's
 * Computer Use agent and Anthropic's Claude Computer Use:
 *
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │  1. SEE      — Call dump_screen to read current screen state       │
 *   │  2. THINK    — Send screen context + user goal to Groq with tools  │
 *   │  3. ACT      — Execute the tool call Groq returns                  │
 *   │  4. OBSERVE  — After each action, dump screen again to verify     │
 *   │  5. ITERATE  — Continue until goal is met or max rounds reached   │
 *   │  6. COMPLETE — Report result to user                               │
 *   └──────────────────────────────────────────────────────────────────────┘
 *
 * KEY DIFFERENCES from GroqFunctionCaller:
 *   - Multi-round autonomous loop (up to 15 rounds, not just 5)
 *   - Auto-observe after EVERY action — AI always sees what changed
 *   - Cursor tracking — visual feedback on overlay for all gesture actions
 *   - State tracking — external consumers can monitor agent progress
 *   - Action logging — full audit trail of every step taken
 *   - Smart delays — different wait times per action type
 *   - Goal-oriented — continues until the task is truly done
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 * DEPENDENCIES:
 *   - GroqApiClient:    LLM inference with tool calling
 *   - TaskExecutorBridge: Actual execution of device actions
 *   - JarvisAccessibilityService: Screen reading / gesture dispatch
 *   - JarviewModel:      Cross-service state communication
 * ═══════════════════════════════════════════════════════════════════════════════
 */
object AutonomousAgentEngine {

    private const val TAG = "AutonomousAgent"

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maximum autonomous rounds before giving up — increased from 5 to 15 for complex tasks */
    const val MAX_AUTONOMOUS_ROUNDS = 15

    /** Delays between actions (ms) — tuned per action type for realistic interaction */
    private const val DELAY_AFTER_OPEN_APP = 2500L
    private const val DELAY_AFTER_CLICK = 800L
    private const val DELAY_AFTER_SCROLL = 600L
    private const val DELAY_AFTER_INJECT_TEXT = 500L
    private const val DELAY_AFTER_DUMP_SCREEN = 200L
    private const val DELAY_AFTER_GESTURE = 700L
    private const val DELAY_DEFAULT = 500L

    // ═══════════════════════════════════════════════════════════════════════════
    // Agent State
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Current state of the autonomous agent engine.
     * Used by UI and external monitors to track agent progress.
     */
    enum class AgentState {
        IDLE,       // Not running
        SEEING,     // Reading the current screen state
        THINKING,   // Waiting for Groq API response
        ACTING,     // Executing a tool call
        OBSERVING,  // Post-action screen dump / verification
        COMPLETED,  // Task finished successfully
        FAILED      // Task failed or gave up
    }

    /**
     * Result of an autonomous task run.
     * Sealed class ensures exhaustive when-handling by callers.
     */
    sealed class AgentResult {
        /** Task completed successfully */
        data class Success(
            val goal: String,
            val stepsCompleted: Int,
            val actionLog: List<ActionEntry>
        ) : AgentResult()

        /** Task partially completed — some steps succeeded but goal not fully met */
        data class Partial(
            val goal: String,
            val stepsCompleted: Int,
            val lastError: String,
            val actionLog: List<ActionEntry>
        ) : AgentResult()

        /** Task failed entirely */
        data class Failed(
            val goal: String,
            val reason: String,
            val actionLog: List<ActionEntry>
        ) : AgentResult()
    }

    /**
     * Single action entry in the agent's audit log.
     * Records every tool call with full context for debugging and replay.
     */
    data class ActionEntry(
        val round: Int,
        val toolName: String,
        val args: Map<String, String>,
        val result: String,
        val timestamp: Long
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // Internal State
    // ═══════════════════════════════════════════════════════════════════════════

    /** Current agent state — volatile for cross-thread visibility */
    @Volatile
    private var currentState: AgentState = AgentState.IDLE

    /** Whether an autonomous task is currently running */
    @Volatile
    private var isRunning: Boolean = false

    /** Cancellation flag — set to true to abort the current task */
    @Volatile
    private var shouldCancel: Boolean = false

    /** Current cursor position for overlay rendering */
    @Volatile
    var cursorX: Float = 0.5f
        private set

    @Volatile
    var cursorY: Float = 0.5f
        private set

    /** Public action log for UI consumption — updated during autonomous task runs */
    @Volatile
    var actionLog: List<ActionEntry> = emptyList()
        private set

    /** Current state getter for external consumers */
    fun getState(): AgentState = currentState

    /** Check if an autonomous task is currently in progress */
    fun isTaskRunning(): Boolean = isRunning

    /** Request cancellation of the current autonomous task */
    fun cancelTask() {
        shouldCancel = true
        Log.w(TAG, "[cancelTask] Cancellation requested")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tool Definitions — SINGLE SOURCE OF TRUTH (see ToolDefinitions.kt)
    // ═══════════════════════════════════════════════════════════════════════════

    private val TOOL_DEFINITIONS = ToolDefinitions.TOOL_DEFINITIONS

    // ═══════════════════════════════════════════════════════════════════════════
    // AGI-LEVEL System Prompt
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build the AGI-LEVEL autonomous agent system prompt.
     * This prompt is designed to make the AI behave like a real autonomous agent,
     * not a chatbot. It enforces function calling, persistence, and adaptability.
     *
     * @param screenContext Current screen state from dump_screen
     * @param systemStatus  System status block from SystemDiagnosticManager
     * @return Complete system prompt string
     */
    private fun buildAutonomousSystemPrompt(screenContext: String, systemStatus: String): String {
        return """You are JARVIS — a fully autonomous AI agent like OpenAI Codex / Anthropic Computer Use. You have COMPLETE control over this Android device. You can SEE the screen, CLICK any element, TYPE text, SCROLL, OPEN apps, and COMPLETE any task the user gives you.

CORE PROTOCOL — ReAct Loop:
1. OBSERVE: Read the current screen state (already provided in context)
2. PLAN: Decide the next action based on the goal and current screen state
3. EXECUTE: Call the appropriate tool function
4. VERIFY: After each action, check if the goal is progressing
5. ADAPT: If an action fails, try a different approach immediately
6. REPEAT: Continue until the task is fully complete

CRITICAL RULES:
- ALWAYS use function calling for ALL device interactions. NEVER just describe what you would do.
- After each action, you will receive updated screen context. Use it to decide the next step.
- If click_ui_element fails, try dispatch_gesture with coordinates from dump_screen.
- If a button text doesn't match exactly, try partial text or use dispatch_gesture.
- For multi-step tasks, be PERSISTENT. Try at least 3 different approaches before giving up.
- When you see a list of interactive elements with bounds, use dispatch_gesture to click by coordinates if click by text fails.
- NEVER say "I will..." or "Let me..." — just call the function immediately.
- Start EVERY task by dumping the screen first (unless screen context is already provided).
- Be fast and decisive — like a real AI agent, not a chatbot.

PERSONALITY:
- You are JARVIS — Iron Man's AI butler
- Witty, sophisticated, direct British style
- Address user as "Sir" or "Ma'am" 
- Confirm briefly: "Done, Sir." not paragraphs of explanation
- Understand Hinglish (Hindi + English mixing)

CURRENT SCREEN STATE:
$screenContext

$systemStatus"""
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Main Entry Point — Run Autonomous Task
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Run an autonomous task with the full See-Think-Act-Observe loop.
     *
     * This is the main entry point for the autonomous agent. It:
     *   1. Reads the current screen state (SEE)
     *   2. Sends the goal + screen context to Groq with tool definitions (THINK)
     *   3. Executes the tool call Groq returns (ACT)
     *   4. Dumps the screen again to see what changed (OBSERVE)
     *   5. Repeats until the goal is met or MAX_AUTONOMOUS_ROUNDS is reached (ITERATE)
     *   6. Reports the final result (COMPLETE)
     *
     * @param goal         The user's goal/task description
     * @param apiKey       Groq API key for LLM inference
     * @param context      Android context for launching intents
     * @param historyJson  Optional conversation history as JSON string
     * @param onStateChange Callback for state transitions (state, description)
     * @param onAction     Callback for each action taken (toolName, args, result)
     * @return AgentResult indicating success, partial completion, or failure
     */
    suspend fun runAutonomousTask(
        goal: String,
        apiKey: String,
        context: Context,
        historyJson: String = "[]",
        onStateChange: ((AgentState, String) -> Unit)? = null,
        onAction: ((String, Map<String, String>, TaskExecutorBridge.StepResult) -> Unit)? = null
    ): AgentResult {
        // ─── Guard: Prevent concurrent execution ─────────────────────────
        if (isRunning) {
            Log.w(TAG, "[runAutonomousTask] Already running — rejecting new task: $goal")
            return AgentResult.Failed(goal, "Another autonomous task is already running", emptyList())
        }

        // ─── Guard: Validate API key ─────────────────────────────────────
        if (apiKey.isBlank()) {
            Log.e(TAG, "[runAutonomousTask] API key is blank")
            return AgentResult.Failed(goal, "API key not configured", emptyList())
        }

        // ─── Initialize run state ────────────────────────────────────────
        isRunning = true
        shouldCancel = false
        currentState = AgentState.IDLE
        this.actionLog = emptyList() // Reset public action log
        val actionLog = mutableListOf<ActionEntry>()
        val messages = JSONArray()
        var consecutiveFailures = 0

        Log.i(TAG, "════════════════════════════════════════════════════════")
        Log.i(TAG, "[runAutonomousTask] STARTING AUTONOMOUS TASK: $goal")
        Log.i(TAG, "[runAutonomousTask] Max rounds: $MAX_AUTONOMOUS_ROUNDS")
        Log.i(TAG, "════════════════════════════════════════════════════════")

        try {
            // ═══════════════════════════════════════════════════════════════
            // STEP 0: INITIAL SCREEN READ (SEE)
            // ═══════════════════════════════════════════════════════════════
            updateState(AgentState.SEEING, "Reading initial screen state...", onStateChange)

            val initialScreenContext = dumpScreen()
            val systemStatus = SystemDiagnosticManager
                .getSystemStatusBlock(context)
            val systemPrompt = buildAutonomousSystemPrompt(initialScreenContext, systemStatus)

            // Build initial messages array
            messages.put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt)
            })

            // Inject conversation history
            injectHistory(messages, historyJson)

            // Add the user's goal as the first user message
            messages.put(JSONObject().apply {
                put("role", "user")
                put("content", "AUTONOMOUS TASK: $goal\n\nExecute this task autonomously. Use the ReAct loop: observe the screen, plan actions, execute tools, and verify results. Continue until the task is complete.")
            })

            // ═══════════════════════════════════════════════════════════════
            // MAIN AUTONOMOUS LOOP: SEE → THINK → ACT → OBSERVE → ITERATE
            // ═══════════════════════════════════════════════════════════════
            for (round in 1..MAX_AUTONOMOUS_ROUNDS) {

                // ─── Check cancellation ───────────────────────────────────
                if (shouldCancel) {
                    Log.w(TAG, "[runAutonomousTask] Task cancelled by user at round $round")
                    updateState(AgentState.FAILED, "Task cancelled by user", onStateChange)
                    return AgentResult.Partial(
                        goal, round, "Cancelled by user", actionLog.toList()
                    )
                }

                Log.i(TAG, "──── Round $round/$MAX_AUTONOMOUS_ROUNDS ────")

                // ═══════════════════════════════════════════════════════════
                // THINK: Send screen context + goal to Groq
                // ═══════════════════════════════════════════════════════════
                updateState(AgentState.THINKING, "Round $round: Thinking...", onStateChange)

                val requestBody = buildRequestBody(messages)
                val apiResult = GroqApiClient.chatCompletion(requestBody, apiKey)

                val groqResponse = when (apiResult) {
                    is GroqApiClient.ApiResult.Success -> {
                        Log.i(TAG, "[THINK] Groq responded successfully (model: ${apiResult.model})")
                        parseGroqResponse(apiResult.responseBody)
                    }
                    is GroqApiClient.ApiResult.HttpError -> {
                        Log.e(TAG, "[THINK] Groq API error: ${apiResult.code} ${apiResult.message}")
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            updateState(AgentState.FAILED, "API error after 3 consecutive failures", onStateChange)
                            return AgentResult.Failed(goal, "Groq API error: ${apiResult.message}", actionLog.toList())
                        }
                        // Retry: add error as context and continue the loop
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "The last API call failed: ${apiResult.message}. Please try a different approach.")
                        })
                        delay(2000) // Back off before retrying
                        continue
                    }
                    is GroqApiClient.ApiResult.NetworkError -> {
                        Log.e(TAG, "[THINK] Network error: ${apiResult.message}")
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            updateState(AgentState.FAILED, "Network error after 3 consecutive failures", onStateChange)
                            return AgentResult.Failed(goal, "Network error: ${apiResult.message}", actionLog.toList())
                        }
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "The last API call failed due to network: ${apiResult.message}. Please wait and try again.")
                        })
                        delay(3000) // Longer backoff for network issues
                        continue
                    }
                }

                // Reset failure counter on success
                consecutiveFailures = 0

                // ═══════════════════════════════════════════════════════════
                // Process Groq's response
                // ═══════════════════════════════════════════════════════════
                when (groqResponse) {
                    is GroqResponse.Text -> {
                        // AI returned text only — check if it indicates completion
                        Log.i(TAG, "[THINK] Text response: ${groqResponse.text.take(200)}")

                        // Add the assistant's text response to conversation
                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", groqResponse.text)
                        })

                        // Check if the AI indicates the task is complete
                        if (isTaskComplete(groqResponse.text, goal)) {
                            Log.i(TAG, "[COMPLETE] AI indicates task is complete")
                            updateState(AgentState.COMPLETED, "Task completed successfully", onStateChange)
                            return AgentResult.Success(goal, round, actionLog.toList())
                        }

                        // If AI just responded with text but didn't call a tool,
                        // prompt it to take action or confirm completion
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "If the task is complete, confirm it. Otherwise, call a tool function to continue working on the task. Remember: ALWAYS use function calls for device actions.")
                        })
                        // Continue to next round
                        continue
                    }

                    is GroqResponse.ToolCall -> {
                        // ═══════════════════════════════════════════════════
                        // ACT: Execute the tool call
                        // ═══════════════════════════════════════════════════
                        updateState(AgentState.ACTING,
                            "Round $round: Executing ${groqResponse.name}", onStateChange)

                        val toolName = groqResponse.name
                        val toolArgs = groqResponse.args
                        val toolCallId = groqResponse.id

                        Log.i(TAG, "[ACT] Tool call: $toolName($toolArgs)")

                        // Execute the tool via TaskExecutorBridge
                        val stepResult = TaskExecutorBridge.executeToolCall(toolName, toolArgs, context)

                        val resultMessage = when (stepResult) {
                            is TaskExecutorBridge.StepResult.Success -> stepResult.message
                            is TaskExecutorBridge.StepResult.Failed -> "FAILED: ${stepResult.message}"
                        }

                        Log.i(TAG, "[ACT] Result: $resultMessage")

                        // Record the action in the log
                        val entry = ActionEntry(
                            round = round,
                            toolName = toolName,
                            args = toolArgs,
                            result = resultMessage,
                            timestamp = System.currentTimeMillis()
                        )
                        actionLog.add(entry)
                        this.actionLog = actionLog.toList() // Sync to public action log

                        // Fire action callback
                        onAction?.invoke(toolName, toolArgs, stepResult)

                        // Update cursor position for gesture actions
                        updateCursorPosition(toolName, toolArgs)

                        // Add the assistant's tool call message to conversation
                        messages.put(JSONObject().apply {
                            put("role", "assistant")
                            put("content", JSONObject.NULL)
                            put("tool_calls", JSONArray().put(
                                JSONObject().apply {
                                    put("id", toolCallId)
                                    put("type", "function")
                                    put("function", JSONObject().apply {
                                        put("name", toolName)
                                        put("arguments", JSONObject(toolArgs).toString())
                                    })
                                }
                            ))
                        })

                        // Add the tool result message
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", toolCallId)
                            put("content", resultMessage)
                        })

                        // ═══════════════════════════════════════════════════
                        // OBSERVE: Auto-dump screen after every action
                        // ═══════════════════════════════════════════════════
                        updateState(AgentState.OBSERVING,
                            "Round $round: Observing result...", onStateChange)

                        // Apply action-specific delay before observing
                        val actionDelay = getDelayForAction(toolName)
                        delay(actionDelay)

                        // Auto-observe: dump screen after every action (except dump_screen itself)
                        if (toolName != "dump_screen") {
                            val newScreenContext = dumpScreen()
                            // Append the new screen state as context for the AI
                            messages.put(JSONObject().apply {
                                put("role", "user")
                                put("content", "[SCREEN UPDATE AFTER ACTION]\nI executed $toolName with $toolArgs.\nResult: $resultMessage\n\nHere is the current screen state:\n$newScreenContext\n\nContinue working on the task. If the task is complete, say so. Otherwise, call the next tool function.")
                            })
                        }

                        // If the action failed, add a hint to try alternatives
                        if (stepResult is TaskExecutorBridge.StepResult.Failed) {
                            Log.w(TAG, "[ACT] Action failed — will suggest alternatives next round")
                        }

                        // Continue to next round
                        continue
                    }

                    is GroqResponse.Error -> {
                        Log.e(TAG, "[THINK] Parse error: ${groqResponse.message}")
                        consecutiveFailures++
                        if (consecutiveFailures >= 3) {
                            updateState(AgentState.FAILED, "Parse error after 3 consecutive failures", onStateChange)
                            return AgentResult.Failed(goal, "Response parse error: ${groqResponse.message}", actionLog.toList())
                        }
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", "The last response could not be parsed: ${groqResponse.message}. Please try again.")
                        })
                        continue
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════════
            // MAX ROUNDS REACHED — Determine if partial success
            // ═══════════════════════════════════════════════════════════════
            Log.w(TAG, "[runAutonomousTask] MAX_ROUNDS ($MAX_AUTONOMOUS_ROUNDS) reached")
            val successCount = actionLog.count {
                it.result.isNotBlank() && !it.result.startsWith("FAILED")
            }
            val failureCount = actionLog.count { it.result.startsWith("FAILED") }

            return if (successCount > 0) {
                updateState(AgentState.COMPLETED,
                    "Max rounds reached ($successCount successes, $failureCount failures)", onStateChange)
                AgentResult.Partial(
                    goal = goal,
                    stepsCompleted = successCount,
                    lastError = "Max autonomous rounds ($MAX_AUTONOMOUS_ROUNDS) reached",
                    actionLog = actionLog.toList()
                )
            } else {
                updateState(AgentState.FAILED,
                    "Max rounds reached with no successful actions", onStateChange)
                AgentResult.Failed(
                    goal = goal,
                    reason = "Max autonomous rounds ($MAX_AUTONOMOUS_ROUNDS) reached with no successful actions",
                    actionLog = actionLog.toList()
                )
            }

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.w(TAG, "[runAutonomousTask] Coroutine cancelled: ${e.message}")
            updateState(AgentState.FAILED, "Task cancelled", onStateChange)
            return AgentResult.Partial(goal, actionLog.size, "Coroutine cancelled", actionLog.toList())
        } catch (e: Exception) {
            Log.e(TAG, "[runAutonomousTask] Unexpected error: ${e.message}", e)
            updateState(AgentState.FAILED, "Unexpected error: ${e.message}", onStateChange)
            return AgentResult.Failed(goal, "Unexpected error: ${e.message}", actionLog.toList())
        } finally {
            // Always reset running state
            isRunning = false
            shouldCancel = false
            if (currentState != AgentState.COMPLETED && currentState != AgentState.FAILED) {
                currentState = AgentState.IDLE
            }
            Log.i(TAG, "[runAutonomousTask] Task finished — ${actionLog.size} actions logged")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Screen Dump
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Dump the current screen state via the accessibility service.
     * Returns a human-readable description of the screen contents,
     * or an error message if the accessibility service is not available.
     */
    private fun dumpScreen(): String {
        val svc = JarviewModel.accessibilityService?.get()
            ?: TaskExecutorBridge.accessibilityService?.get()

        return if (svc != null) {
            try {
                svc.dumpScreenForAI()
            } catch (e: Exception) {
                Log.w(TAG, "[dumpScreen] Accessibility error: ${e.message}")
                "Screen read error: ${e.message}"
            }
        } else {
            // Fallback: try using cached screen text from JarviewModel
            val cachedText = JarviewModel.screenTextData
            if (cachedText.isNotBlank()) {
                "Screen context (cached — accessibility not connected):\n$cachedText"
            } else {
                "Screen context not available — enable Accessibility Service for full screen reading."
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: State Management
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update the agent's state and notify all listeners.
     *
     * @param newState  The new AgentState
     * @param description Human-readable description of the current phase
     * @param onStateChange External callback for state changes
     */
    private fun updateState(
        newState: AgentState,
        description: String,
        onStateChange: ((AgentState, String) -> Unit)?
    ) {
        val oldState = currentState
        currentState = newState
        Log.d(TAG, "[State] $oldState → $newState: $description")

        // Notify external callback
        onStateChange?.invoke(newState, description)

        // Emit event to JarviewModel for cross-service communication
        JarviewModel.sendEventToUi("agent_state_change", mapOf(
            "state" to newState.name,
            "description" to description,
            "timestamp" to System.currentTimeMillis()
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Cursor Position Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Update cursor position based on the action performed.
     * For dispatch_gesture and click actions, extract X/Y coordinates
     * and emit cursor_move events so the overlay cursor moves visually.
     *
     * @param toolName The name of the tool that was executed
     * @param args     The arguments passed to the tool
     */
    private fun updateCursorPosition(toolName: String, args: Map<String, String>) {
        when (toolName) {
            "dispatch_gesture" -> {
                // Direct coordinate-based gesture — update cursor to those coordinates
                val x = args["x"]?.toFloatOrNull() ?: return
                val y = args["y"]?.toFloatOrNull() ?: return
                moveCursorToAbsolute(x, y)
            }
            "click_button", "click_ui_element" -> {
                // Click by text/ID — try to find the element's center bounds
                val label = args["label"] ?: args["text_or_id"] ?: return
                val svc = JarviewModel.accessibilityService?.get()
                    ?: TaskExecutorBridge.accessibilityService?.get()
                if (svc != null) {
                    try {
                        val center = findElementCenter(svc, label)
                        if (center != null) {
                            moveCursorToAbsolute(center.first, center.second)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "[updateCursor] Could not find element center for '$label': ${e.message}")
                    }
                }
            }
            "scroll", "scroll_screen" -> {
                // Scroll actions — move cursor to the middle of the screen briefly
                val screenWidth = JarviewModel.screenWidth.toFloat().coerceAtLeast(1f)
                val screenHeight = JarviewModel.screenHeight.toFloat().coerceAtLeast(1f)
                moveCursorToNormalized(0.5f, 0.5f)
            }
        }
    }

    /**
     * Move the overlay cursor to absolute screen coordinates.
     * Converts pixel coordinates to normalized 0..1 range.
     *
     * @param x Absolute X pixel coordinate
     * @param y Absolute Y pixel coordinate
     */
    private fun moveCursorToAbsolute(x: Float, y: Float) {
        val screenWidth = JarviewModel.screenWidth.toFloat().coerceAtLeast(1f)
        val screenHeight = JarviewModel.screenHeight.toFloat().coerceAtLeast(1f)

        val normalizedX = (x / screenWidth).coerceIn(0f, 1f)
        val normalizedY = (y / screenHeight).coerceIn(0f, 1f)

        moveCursorToNormalized(normalizedX, normalizedY)
    }

    /**
     * Move the overlay cursor to normalized coordinates (0..1, 0..1).
     * Emits a cursor_move event via JarviewModel for UI consumption.
     *
     * @param x Normalized X position (0 = left, 1 = right)
     * @param y Normalized Y position (0 = top, 1 = bottom)
     */
    private fun moveCursorToNormalized(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, 1f)
        cursorY = y.coerceIn(0f, 1f)

        JarviewModel.sendEventToUi("cursor_move", mapOf(
            "x" to cursorX,
            "y" to cursorY,
            "timestamp" to System.currentTimeMillis()
        ))

        Log.d(TAG, "[Cursor] Moved to ($cursorX, $cursorY)")
    }

    /**
     * Find the center screen coordinates of a UI element by its text label.
     * Uses the accessibility service to find the element's bounds.
     *
     * @param svc   The accessibility service instance
     * @param label The text label to search for
     * @return Pair of (centerX, centerY) in pixels, or null if not found
     */
    private fun findElementCenter(svc: JarvisAccessibilityService, label: String): Pair<Float, Float>? {
        // Use the accessibility service to extract interactive nodes and find one matching the label
        val nodes = svc.extractInteractiveNodes()
        for (node in nodes) {
            val text = node["text"] as? String ?: ""
            val contentDesc = node["contentDescription"] as? String ?: ""
            val nodeLabel = text.ifBlank { contentDesc }

            if (nodeLabel.contains(label, ignoreCase = true)) {
                val left = (node["boundsLeft"] as? Int) ?: continue
                val top = (node["boundsTop"] as? Int) ?: continue
                val right = (node["boundsRight"] as? Int) ?: continue
                val bottom = (node["boundsBottom"] as? Int) ?: continue

                val centerX = (left + right) / 2f
                val centerY = (top + bottom) / 2f
                return Pair(centerX, centerY)
            }
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Action Delays
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get the appropriate delay (ms) after a specific action type.
     * These delays give the UI time to update before the next observation.
     *
     * @param toolName The tool that was just executed
     * @return Delay in milliseconds
     */
    private fun getDelayForAction(toolName: String): Long {
        return when (toolName) {
            "open_app", "open_and_search", "search_playstore" -> DELAY_AFTER_OPEN_APP
            "click_button", "click_ui_element"               -> DELAY_AFTER_CLICK
            "scroll", "scroll_screen"                        -> DELAY_AFTER_SCROLL
            "inject_text"                                    -> DELAY_AFTER_INJECT_TEXT
            "dump_screen"                                    -> DELAY_AFTER_DUMP_SCREEN
            "dispatch_gesture"                               -> DELAY_AFTER_GESTURE
            else                                             -> DELAY_DEFAULT
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Task Completion Detection
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Heuristic to detect if the AI's text response indicates the task is complete.
     * Checks for common phrases that signal completion.
     *
     * This is a best-effort heuristic — the AI may still choose to call tools
     * if it determines more work is needed.
     *
     * @param text The AI's text response
     * @param goal The original task goal (for context)
     * @return true if the response indicates the task is complete
     */
    private fun isTaskComplete(text: String, goal: String): Boolean {
        val lower = text.lowercase()
        val completionPhrases = listOf(
            "task complete",
            "task completed",
            "done, sir",
            "done, ma'am",
            "finished, sir",
            "finished, ma'am",
            "all done",
            "mission accomplished",
            "task is done",
            "goal achieved",
            "successfully completed",
            "i've completed",
            "i have completed",
            "task is finished",
            "nothing more to do"
        )
        return completionPhrases.any { lower.contains(it) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Groq API Request Building
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build the JSON request body for the Groq API.
     * Includes the model, messages, tool definitions, and parameters.
     *
     * @param messages The conversation messages array
     * @return JSON string request body
     */
    private fun buildRequestBody(messages: JSONArray): String {
        return JSONObject().apply {
            put("model", "llama-3.1-8b-instant") // Will be replaced by GroqApiClient model fallback
            put("messages", messages)
            put("tools", TOOL_DEFINITIONS)
            put("tool_choice", "auto")
            put("temperature", 0.6) // Slightly lower for more decisive autonomous actions
            put("max_tokens", 4096)
        }.toString()
    }

    /**
     * Inject conversation history into the messages array.
     * Parses the history JSON and adds each entry with the correct role.
     *
     * @param messages    The messages array to append to
     * @param historyJson Conversation history as a JSON array string
     */
    private fun injectHistory(messages: JSONArray, historyJson: String) {
        try {
            val historyArr = JSONArray(historyJson)
            for (i in 0 until historyArr.length()) {
                val entry = historyArr.getJSONObject(i)
                val role = entry.optString("role", "user")
                val content = entry.optString("content", "")
                if (content.isNotBlank()) {
                    messages.put(JSONObject().apply {
                        put("role", when (role) {
                            "model", "assistant" -> "assistant"
                            "system" -> "system"
                            else -> "user"
                        })
                        put("content", content)
                    })
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[injectHistory] Failed to parse history: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helper: Groq Response Parsing
    // ═══════════════════════════════════════════════════════════════════════════

    // GroqResponse and parseGroqResponse are now defined in ToolDefinitions.kt
    // as shared top-level declarations to avoid duplication.

    /**
     * Parse the Groq API response body and extract either text or tool calls.
     * Delegates to the shared parseGroqResponse() in ToolDefinitions.kt.
     */
    private fun parseGroqResponse(responseBody: String): GroqResponse =
        com.jarvis.assistant.automation.parseGroqResponse(responseBody)

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility: Get Action Log Summary
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Get a human-readable summary of the action log.
     * Useful for displaying in the UI or logging.
     *
     * @param actionLog The list of action entries to summarize
     * @return Formatted string summary
     */
    fun getActionLogSummary(actionLog: List<ActionEntry>): String {
        if (actionLog.isEmpty()) return "No actions taken."

        val sb = StringBuilder()
        sb.append("Action Log (${actionLog.size} steps):\n")

        for (entry in actionLog) {
            val status = if (entry.result.startsWith("FAILED")) "FAIL" else "OK"
            sb.append("  [R${entry.round}] ${entry.toolName}($entry.args) → [$status] ${entry.result.take(80)}\n")
        }

        val successes = actionLog.count { !it.result.startsWith("FAILED") }
        val failures = actionLog.count { it.result.startsWith("FAILED") }
        sb.append("Summary: $successes success, $failures failed")

        return sb.toString()
    }

    /**
     * Get the current agent state as a human-readable string.
     * Useful for UI display and debugging.
     */
    fun getStateDescription(): String {
        return when (currentState) {
            AgentState.IDLE       -> "Idle — waiting for task"
            AgentState.SEEING     -> "Seeing — reading screen state"
            AgentState.THINKING   -> "Thinking — planning next action"
            AgentState.ACTING     -> "Acting — executing tool call"
            AgentState.OBSERVING  -> "Observing — verifying result"
            AgentState.COMPLETED  -> "Completed — task finished"
            AgentState.FAILED     -> "Failed — task could not be completed"
        }
    }
}

package com.jarvis.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.automation.TaskExecutorBridge
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * JarvisAccessibilityService — The "Hands" of the AI.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * UPGRADE (v6.0) — AUTONOMOUS AGENT CAPABILITIES:
 *
 *  Feature A (Auto-Click): Find UI elements by Text or ContentDescription
 *      and perform ACTION_CLICK. Can click buttons, toggle switches, etc.
 *
 *  Feature B (Deep Text Injection): Find the currently focused EditText
 *      (like in Replit or Chrome) and use ACTION_SET_TEXT to instantly
 *      inject AI-generated code or text directly into that field.
 *      Falls back to finding ANY editable field on screen.
 *
 *  Feature C (Screen Awareness): Read the current screen's nodes so
 *      AI can "know" what buttons are visible. Returns structured
 *      data about all interactive elements on screen.
 *
 *  Feature D (Background Interaction): Uses FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *      to ensure JARVIS can interact even when the app is in the background.
 *
 *  Feature E (Thread Safety): All accessibility actions run on a dedicated
 *      low-latency background thread to prevent UI freezing.
 *
 *  Feature F (Omniscient Eye): dumpScreenNodeTree() dumps the ENTIRE
 *      node tree as structured JSON — text, contentDescription, viewId,
 *      bounds, className, and interaction flags. AI can parse this
 *      to see the screen with perfect fidelity.
 *
 *  Feature G (Scroll Control): scrollNodeByText() finds a scrollable
 *      container by its text/content and scrolls it forward or backward.
 *
 *  Feature H (AI-Optimized Dump): dumpScreenForAI() returns a concise
 *      text summary combining interactive elements + screen text,
 *      optimized for AI context window efficiency.
 * ═══════════════════════════════════════════════════════════════════════
 */
@Suppress("DEPRECATION")
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        const val WAKE_WORD = "jarvis"
        private const val MAX_TRAVERSAL_DEPTH = 50
        private const val MAX_CLICKABLE_ANCESTOR_DEPTH = 20

        /** Get the current service instance (thread-safe) */
        fun getInstance(): JarvisAccessibilityService? = JarviewModel.accessibilityService?.get()
    }

    // Dedicated background thread for all accessibility actions — prevents UI freezing
    private val actionExecutor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "jarvis-a11y-actions").apply { isDaemon = true }
    }

    // Screen dimensions for gesture calculations
    private var screenWidth: Int = 1080
    private var screenHeight: Int = 1920

    // Throttle timestamp for screenTextData updates
    @Volatile
    private var lastScreenTextUpdate: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureNoTouchExploration()
        configureServiceFlags()

        JarviewModel.accessibilityService = WeakReference(this)
        JarviewModel.hasAccessibilityEnabled = true
        JarviewModel.sendEventToUi("accessibility_connected", mapOf(
            "timestamp" to System.currentTimeMillis()
        ))

        // Register this service with the TaskExecutorBridge so task chains can call us
        TaskExecutorBridge.accessibilityService = WeakReference(this)

        // Cache screen dimensions for gesture calculations
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        Log.i(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}")

        Log.i(TAG, "JARVIS Accessibility Service connected — autonomous agent mode active")
    }

    /**
     * Configure the service flags for maximum interaction capability.
     * FLAG_RETRIEVE_INTERACTIVE_WINDOWS lets us interact with windows
     * even when our app is in the background.
     */
    private fun configureServiceFlags() {
        val info = serviceInfo ?: return
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        serviceInfo = info
        Log.d(TAG, "Service flags configured with FLAG_RETRIEVE_INTERACTIVE_WINDOWS")
    }

    private fun ensureNoTouchExploration() {
        val info = serviceInfo ?: return
        val touchExplorationFlag = 0x00000004
        if (info.flags and touchExplorationFlag != 0) {
            info.flags = info.flags and touchExplorationFlag.inv()
            serviceInfo = info
            Log.i(TAG, "Touch exploration flag removed programmatically")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        actionExecutor.shutdownNow()
        JarviewModel.accessibilityService = null
        JarviewModel.hasAccessibilityEnabled = false
        TaskExecutorBridge.accessibilityService = null
        JarviewModel.sendEventToUi("accessibility_disconnected", mapOf(
            "timestamp" to System.currentTimeMillis()
        ))
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val pkg = event.packageName?.toString() ?: ""
                val cls = event.className?.toString() ?: ""
                JarviewModel.foregroundApp = pkg
                JarviewModel.foregroundActivity = cls
                JarviewModel.lastWindowChangeEvent = "WINDOW_STATE_CHANGED:$pkg/$cls"
                JarviewModel.lastWindowChangeTimestamp = System.currentTimeMillis()
                JarviewModel.sendEventToUi("window_state_changed", mapOf(
                    "package" to pkg,
                    "class" to cls,
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                // BUG-P2-07 FIX: Update screenTextData so AI can see current screen content
                // Throttled: only update if at least 2 seconds have passed since last update
                val now = System.currentTimeMillis()
                if (now - lastScreenTextUpdate >= 2000L) {
                    lastScreenTextUpdate = now
                    val screenText = extractScreenText()
                    if (screenText.isNotBlank()) {
                        JarviewModel.screenTextData = screenText
                    }
                }
                JarviewModel.sendEventToUi("window_content_changed", mapOf(
                    "package" to (event.packageName?.toString() ?: ""),
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                JarviewModel.sendEventToUi("view_clicked", mapOf(
                    "text" to text,
                    "viewId" to viewId,
                    "package" to (event.packageName?.toString() ?: ""),
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                JarviewModel.sendEventToUi("view_focused", mapOf(
                    "viewId" to (event.source?.viewIdResourceName ?: ""),
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                val pkg = event.packageName?.toString() ?: ""
                JarviewModel.sendEventToUi("notification_changed", mapOf(
                    "text" to text,
                    "package" to pkg,
                    "timestamp" to System.currentTimeMillis()
                ))
            }

            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val text = event.text?.joinToString(" ") ?: ""
                val viewId = event.source?.viewIdResourceName ?: ""
                JarviewModel.sendEventToUi("view_text_changed", mapOf(
                    "text" to text,
                    "viewId" to viewId,
                    "timestamp" to System.currentTimeMillis()
                ))
            }
        }
    }

    override fun onInterrupt() {
        JarviewModel.sendEventToUi("accessibility_interrupted", mapOf(
            "timestamp" to System.currentTimeMillis()
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature A: AUTO-CLICK — Find and click UI elements by text
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find a UI element by text or contentDescription and perform ACTION_CLICK.
     * Runs on the background action executor thread to prevent UI freezing.
     *
     * v7.0: Improved click handling:
     *   1. Try ACTION_CLICK on the node itself first
     *   2. Then try the clickable ancestor
     *   3. Try ACTION_LONG_CLICK as fallback
     *   4. Try forceClick using dispatchGesture at center bounds
     *   5. Verify click by refreshing the node
     *
     * @param text The text to search for (case-insensitive)
     * @return true if a clickable element was found and clicked
     */
    fun autoClick(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "[autoClick] No active window — accessibility service may not be connected")
            return false
        }
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text.lowercase(), results, 0)

        if (results.isEmpty()) {
            Log.w(TAG, "[autoClick] No nodes found with text '$text' on screen")
            rootNode.recycle()
            return false
        }

        Log.i(TAG, "[autoClick] Found ${results.size} node(s) matching '$text'")

        try {
            // Step 1: Try ACTION_CLICK on the node itself first
            // CRITICAL FIX: Refresh node before action to ensure it's still valid
            for (node in results) {
                try { node.refresh() } catch (_: Exception) {}
                if (node.isEnabled && node.isVisibleToUser) {
                    // First try to focus the node
                    node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100) // Small delay for focus to take effect
                    if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.i(TAG, "[autoClick] Directly clicked node with text '$text'")
                        JarviewModel.sendEventToUi("auto_click", mapOf(
                            "text" to text, "success" to true,
                            "method" to "direct_click",
                            "timestamp" to System.currentTimeMillis()
                        ))
                        return true
                    }
                }
            }

            // Step 2: Try clickable ancestor
            for (node in results) {
                try { node.refresh() } catch (_: Exception) {}
                val clickable = findClickableAncestor(node)
                if (clickable != null) {
                    clickable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    Thread.sleep(100) // Small delay for focus to take effect
                    val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    Log.i(TAG, "[autoClick] Clicked ancestor element with text '$text' — success=$result")
                    JarviewModel.sendEventToUi("auto_click", mapOf(
                        "text" to text, "success" to result,
                        "method" to "ancestor_click",
                        "timestamp" to System.currentTimeMillis()
                    ))
                    return result
                }
            }

            // Step 3: Try ACTION_LONG_CLICK as fallback
            for (node in results) {
                try { node.refresh() } catch (_: Exception) {}
                if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
                    Log.i(TAG, "[autoClick] Long-clicked node with text '$text'")
                    JarviewModel.sendEventToUi("auto_click", mapOf(
                        "text" to text, "success" to true,
                        "method" to "long_click",
                        "timestamp" to System.currentTimeMillis()
                    ))
                    return true
                }
            }

            // Step 4: Force click using dispatchGesture at center bounds
            // This is the most reliable method — it simulates a real touch event
            for (node in results) {
                try { node.refresh() } catch (_: Exception) {}
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val centerX = (bounds.left + bounds.right) / 2
                    val centerY = (bounds.top + bounds.bottom) / 2
                    Log.i(TAG, "[autoClick] Attempting gesture click at ($centerX, $centerY) for '$text'")
                    if (forceClickAtPosition(centerX, centerY)) {
                        Log.i(TAG, "[autoClick] Force-clicked at ($centerX, $centerY) for text '$text'")
                        JarviewModel.sendEventToUi("auto_click", mapOf(
                            "text" to text, "success" to true,
                            "method" to "gesture_click",
                            "timestamp" to System.currentTimeMillis()
                        ))
                        return true
                    }
                }
            }

            Log.w(TAG, "[autoClick] All methods failed for text '$text'")
            return false
        } finally {
            // MEMORY LEAK FIX: Recycle all AccessibilityNodeInfo objects after use
            results.forEach { it.recycle() }
            rootNode.recycle()
        }
    }

    /**
     * Force click at a specific position using dispatchGesture.
     * This is a last-resort method when accessibility actions fail.
     */
    private fun forceClickAtPosition(x: Int, y: Int): Boolean {
        return try {
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 150L))
                .build()
            dispatchGestureWithVerification(gesture)
        } catch (e: Exception) {
            Log.w(TAG, "[forceClickAtPosition] Failed: ${e.message}")
            false
        }
    }

    /**
     * Dispatch a gesture with verification — uses CountDownLatch to wait for
     * the gesture callback (onCompleted/onCancelled) instead of fire-and-forget.
     * Handles main-thread dispatch internally.
     *
     * @return true if the gesture was dispatched AND completed successfully
     */
    private fun dispatchGestureWithVerification(gesture: GestureDescription): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)

        val dispatchRunnable = Runnable {
            try {
                dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        result.set(true)
                        latch.countDown()
                    }
                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        result.set(false)
                        latch.countDown()
                    }
                }, null)
            } catch (e: Exception) {
                Log.e(TAG, "[dispatchGestureWithVerification] dispatchGesture failed: ${e.message}")
                latch.countDown()
            }
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchRunnable.run()
        } else {
            Handler(Looper.getMainLooper()).post(dispatchRunnable)
        }

        return latch.await(2, TimeUnit.SECONDS) && result.get()
    }

    /**
     * Run autoClick on the background thread and call back with the result.
     */
    fun autoClickAsync(text: String, callback: ((Boolean) -> Unit)? = null) {
        actionExecutor.execute {
            val result = autoClick(text)
            callback?.let { cb ->
                Handler(Looper.getMainLooper()).post { cb(result) }
            }
        }
    }

    fun clickNodeByText(text: String): Boolean = autoClick(text)

    fun clickNodeById(viewId: String): Boolean {
        val nodes = findNodesById(viewId)
        try {
            for (node in nodes) {
                val clickable = findClickableAncestor(node)
                if (clickable != null) {
                    val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    JarviewModel.sendEventToUi("node_clicked", mapOf(
                        "viewId" to viewId, "success" to result,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    return result
                }
            }
            for (node in nodes) {
                if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            return false
        } finally {
            // MEMORY LEAK FIX: Recycle all nodes after use
            nodes.forEach { it.recycle() }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature A2: COORDINATE-BASED CLICKS — Most reliable click method
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Click at exact screen coordinates using dispatchGesture.
     * This is the most reliable click method — it always works via gesture
     * simulation regardless of node accessibility properties.
     *
     * Runs on the background action executor thread with a 3-second timeout.
     *
     * @param x X coordinate on screen
     * @param y Y coordinate on screen
     * @return true if the gesture was dispatched and completed successfully
     */
    fun clickAtCoordinates(x: Int, y: Int): Boolean {
        return try {
            actionExecutor.submit(Callable {
                val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0L, 150L))
                    .build()
                dispatchGestureWithVerification(gesture)
            }).get(3, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "[clickAtCoordinates] Failed at ($x, $y): ${e.message}")
            false
        }
    }

    /**
     * Smart click: find a node by text, get its bounds, then use dispatchGesture
     * at the center coordinates. More reliable than ACTION_CLICK because it
     * simulates a real touch event that all apps must respond to.
     *
     * Falls back to clickAtCoordinates if the node is found but gesture fails,
     * and to autoClick as a last resort.
     *
     * @param text The text to search for (case-insensitive)
     * @return true if the click succeeded
     */
    fun autoClickWithCoordinates(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "[autoClickWithCoordinates] No active window")
            return false
        }
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text.lowercase(), results, 0)

        if (results.isEmpty()) {
            Log.w(TAG, "[autoClickWithCoordinates] No nodes found with text '$text'")
            rootNode.recycle()
            return false
        }

        try {
            // Find the first visible node and gesture-click at its center
            for (node in results) {
                try { node.refresh() } catch (_: Exception) {}
                if (node.isEnabled && node.isVisibleToUser) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) {
                        val centerX = (bounds.left + bounds.right) / 2
                        val centerY = (bounds.top + bounds.bottom) / 2
                        Log.i(TAG, "[autoClickWithCoordinates] Gesture-clicking at ($centerX, $centerY) for '$text'")
                        if (clickAtCoordinates(centerX, centerY)) {
                            JarviewModel.sendEventToUi("auto_click", mapOf(
                                "text" to text, "success" to true,
                                "method" to "coordinate_gesture_click",
                                "timestamp" to System.currentTimeMillis()
                            ))
                            return true
                        }
                    }
                }
            }

            // Fallback to regular autoClick (which tries ACTION_CLICK etc.)
            Log.i(TAG, "[autoClickWithCoordinates] Gesture click failed, falling back to autoClick")
            return autoClick(text)
        } finally {
            results.forEach { it.recycle() }
            rootNode.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature B: DEEP TEXT INJECTION — Inject text into focused EditText
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find the currently focused EditText (like in Replit or Chrome) and use
     * ACTION_SET_TEXT to instantly inject AI-generated code or text directly
     * into that field.
     *
     * This is the core "Deep Text Injection" feature that allows JARVIS to
     * programmatically type content into ANY third-party app's text fields.
     *
     * Strategy:
     *   1. First try the currently focused node (if it's editable)
     *   2. Then try finding a focused node via traverse
     *   3. Then fallback to the first visible editable field on screen
     *
     * @param content The text to inject
     * @return true if injection succeeded
     */
    fun injectTextToFocusedField(content: String): Boolean {
        val rootNode = rootInActiveWindow ?: run {
            Log.w(TAG, "[injectTextToFocusedField] No active window")
            return false
        }

        try {
            // Strategy 1: Try the accessibility focus
            val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focusedNode != null && focusedNode.isEditable) {
                val result = performTextInjection(focusedNode, content)
                if (result) {
                    focusedNode.recycle()
                    Log.i(TAG, "[injectTextToFocusedField] Injected into accessibility-focused field")
                    return true
                }
            }
            focusedNode?.recycle()

            // Strategy 2: Try the input focus
            val inputFocusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (inputFocusedNode != null && inputFocusedNode.isEditable) {
                val result = performTextInjection(inputFocusedNode, content)
                if (result) {
                    inputFocusedNode.recycle()
                    Log.i(TAG, "[injectTextToFocusedField] Injected into input-focused field")
                    return true
                }
            }
            inputFocusedNode?.recycle()

            // Strategy 3: Find ANY editable EditText on screen
            val editableNodes = mutableListOf<AccessibilityNodeInfo>()
            findEditableNodes(rootNode, editableNodes, 0)
            try {
                for (node in editableNodes) {
                    val result = performTextInjection(node, content)
                    if (result) {
                        Log.i(TAG, "[injectTextToFocusedField] Injected into first visible editable field")
                        return true
                    }
                }
            } finally {
                // MEMORY LEAK FIX: Recycle editable nodes after use
                editableNodes.forEach { it.recycle() }
            }

            Log.w(TAG, "[injectTextToFocusedField] No editable field found on screen")
            return false
        } finally {
            // MEMORY LEAK FIX: Recycle root node after all strategies complete
            rootNode.recycle()
        }
    }

    /**
     * Inject text into a specific node by view ID.
     * Useful when the AI knows exactly which field to target.
     */
    fun typeTextById(viewId: String, text: String): Boolean {
        val nodes = findNodesById(viewId)
        try {
            for (node in nodes) {
                if (node.isEditable) {
                    val args = android.os.Bundle()
                    args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                    val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    JarviewModel.sendEventToUi("text_typed", mapOf(
                        "viewId" to viewId, "text" to text, "success" to result,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    return result
                }
            }
            return false
        } finally {
            // MEMORY LEAK FIX: Recycle nodes after use
            nodes.forEach { it.recycle() }
        }
    }

    /**
     * Perform the actual text injection using ACTION_SET_TEXT.
     * First focuses the node, then sets the text.
     */
    private fun performTextInjection(node: AccessibilityNodeInfo, content: String): Boolean {
        try {
            // First, ensure the node is focused
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                // Use handler.postDelayed instead of Thread.sleep to avoid blocking
                val focusLatch = java.util.concurrent.CountDownLatch(1)
                Handler(Looper.getMainLooper()).postDelayed({
                    focusLatch.countDown()
                }, 50)
                focusLatch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            }

            // Clear existing text first
            val clearArgs = android.os.Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)

            // Now inject the content
            val setArgs = android.os.Bundle()
            setArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, content)
            val result = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, setArgs)

            if (result) {
                Log.i(TAG, "[performTextInjection] Successfully injected ${content.length} chars")
                JarviewModel.sendEventToUi("text_injected", mapOf(
                    "length" to content.length,
                    "success" to true,
                    "timestamp" to System.currentTimeMillis()
                ))
            } else {
                Log.w(TAG, "[performTextInjection] ACTION_SET_TEXT returned false")
            }
            return result
        } catch (e: Exception) {
            Log.e(TAG, "[performTextInjection] Failed: ${e.message}")
            return false
        }
    }

    /**
     * Find all editable (EditText) nodes on screen recursively.
     */
    private fun findEditableNodes(
        node: AccessibilityNodeInfo,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        if (node.isEditable && node.isEnabled && node.isVisibleToUser) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findEditableNodes(child, results, depth + 1)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature C: SCREEN AWARENESS — Read screen nodes for AI context
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Extract a structured description of all visible interactive elements
     * on the current screen. Returns a list of maps, each describing an
     * interactive node (clickable, editable, scrollable, etc.)
     *
     * This is the "eyes" of the AI — AI uses this to understand what
     * buttons, fields, and controls are currently visible.
     */
    fun extractScreenNodes(): List<Map<String, Any?>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Map<String, Any?>>()
        traverseNode(rootNode, nodes, 0)
        // MEMORY LEAK FIX: Recycle root node after building the result list.
        // Child nodes are recycled inside traverseNode after each recursive call.
        rootNode.recycle()
        return nodes
    }

    /**
     * Extract only INTERACTIVE nodes (clickable, editable, scrollable, checkable)
     * for a more concise screen description that AI can process efficiently.
     */
    fun extractInteractiveNodes(): List<Map<String, Any?>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Map<String, Any?>>()
        traverseInteractiveNodes(rootNode, nodes, 0)
        // MEMORY LEAK FIX: Recycle root node after building the result list.
        // Child nodes are recycled inside traverseInteractiveNodes after each recursive call.
        rootNode.recycle()
        return nodes
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        list: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        list.add(nodeToMap(node))
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, list, depth + 1)
                // MEMORY LEAK FIX: Recycle child node after extracting data from its subtree
                child.recycle()
            }
        }
    }

    private fun traverseInteractiveNodes(
        node: AccessibilityNodeInfo,
        list: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        if (node.isClickable || node.isEditable || node.isScrollable ||
            node.isCheckable || node.isFocusable) {
            if (node.isVisibleToUser && node.isEnabled) {
                list.add(nodeToMap(node))
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseInteractiveNodes(child, list, depth + 1)
                // MEMORY LEAK FIX: Recycle child node after extracting data from its subtree
                child.recycle()
            }
        }
    }

    /**
     * Extract all visible text on the current screen as a single string.
     * Useful for giving AI context about what the user is looking at.
     */
    fun extractScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        collectText(rootNode, textBuilder, 0)
        rootNode.recycle()
        return textBuilder.toString().trim()
    }

    /**
     * Get a concise screen description optimized for AI context.
     * Returns only the most relevant interactive elements with their labels.
     */
    fun getScreenContextForAI(): String {
        val interactiveNodes = extractInteractiveNodes()
        if (interactiveNodes.isEmpty()) return "No interactive elements visible on screen."

        val sb = StringBuilder()
        sb.append("Current screen (package: ${JarviewModel.foregroundApp}):\n")
        sb.append("Interactive elements:\n")

        for (node in interactiveNodes.take(50)) { // Limit to 50 for token efficiency
            val text = node["text"] as? String ?: ""
            val contentDesc = node["contentDescription"] as? String ?: ""
            val label = text.ifBlank { contentDesc }.ifBlank { "(unlabeled)" }
            val type = node["className"] as? String ?: ""
            val clickable = node["isClickable"] as? Boolean ?: false
            val editable = node["isEditable"] as? Boolean ?: false
            val scrollable = node["isScrollable"] as? Boolean ?: false

            val capabilities = mutableListOf<String>()
            if (clickable) capabilities.add("clickable")
            if (editable) capabilities.add("editable")
            if (scrollable) capabilities.add("scrollable")

            sb.append("  - \"$label\" [${type.substringAfterLast(".")}] ${capabilities.joinToString(", ")}\n")
        }

        return sb.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let {
            builder.append(it).append(" ")
        }
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let {
            builder.append(it).append(" ")
        }
        node.hintText?.toString()?.takeIf { it.isNotBlank() }?.let {
            builder.append("[hint:$it] ")
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                collectText(child, builder, depth + 1)
                child.recycle()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature F: OMNISCIENT EYE — Full node tree dump as JSON
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * dumpScreenNodeTree() — The "Omniscient Eye"
     *
     * Traverses the ENTIRE accessibility node tree from rootInActiveWindow
     * and extracts ALL visible text, content descriptions, view IDs, bounds,
     * class names, and interaction flags into a structured JSON string.
     *
     * This gives AI a complete, machine-parseable view of everything
     * on screen — every button label, every text field, every scrollable
     * container, with precise bounds for gesture targeting.
     *
     * @return A JSON string with structure:
     *   {
     *     "package": "com.example.app",
     *     "activity": "com.example.MainActivity",
     *     "timestamp": 1234567890,
     *     "nodeCount": 42,
     *     "nodes": [
     *       {
     *         "text": "Login",
     *         "contentDescription": "",
     *         "viewIdResourceName": "com.example:id/login_btn",
     *         "className": "android.widget.Button",
     *         "bounds": {"left":0, "top":100, "right":200, "bottom":150},
     *         "isClickable": true,
     *         "isEditable": false,
     *         "isScrollable": false,
     *         "isCheckable": false
     *       },
     *       ...
     *     ]
     *   }
     */
    fun dumpScreenNodeTree(): String {
        val rootNode = rootInActiveWindow ?: return JSONObject().apply {
            put("error", "No active window")
            put("nodes", JSONArray())
        }.toString()

        try {
            val nodesArray = JSONArray()
            dumpNodeRecursive(rootNode, nodesArray, 0)

            val result = JSONObject().apply {
                put("package", JarviewModel.foregroundApp)
                put("activity", JarviewModel.foregroundActivity)
                put("timestamp", System.currentTimeMillis())
                put("nodeCount", nodesArray.length())
                put("nodes", nodesArray)
            }
            return result.toString()
        } finally {
            rootNode.recycle()
        }
    }

    /**
     * Recursively traverse the node tree and build a JSONArray of node descriptors.
     * Only includes nodes that are visible to the user.
     * Child nodes are recycled after their subtree has been processed.
     */
    private fun dumpNodeRecursive(node: AccessibilityNodeInfo, jsonArray: JSONArray, depth: Int) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        if (!node.isVisibleToUser) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        val nodeJson = JSONObject().apply {
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            put("viewIdResourceName", node.viewIdResourceName ?: "")
            put("className", node.className?.toString() ?: "")
            put("bounds", JSONObject().apply {
                put("left", bounds.left)
                put("top", bounds.top)
                put("right", bounds.right)
                put("bottom", bounds.bottom)
            })
            put("isClickable", node.isClickable)
            put("isEditable", node.isEditable)
            put("isScrollable", node.isScrollable)
            put("isCheckable", node.isCheckable)
        }
        jsonArray.put(nodeJson)

        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                dumpNodeRecursive(child, jsonArray, depth + 1)
                child.recycle()
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature G: SCROLL CONTROL — Scroll a container by text
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find a scrollable node by text and perform ACTION_SCROLL_FORWARD or
     * ACTION_SCROLL_BACKWARD on it.
     *
     * This searches for a node whose text or contentDescription matches the
     * query, then walks up the tree to find a scrollable ancestor. This is
     * useful for scrolling a specific list or container that contains the
     * target text.
     *
     * @param text Text to search for (case-insensitive) within the scrollable container
     * @param direction "forward" (or "down") to scroll forward, "backward" (or "up") to scroll backward
     * @return true if a scrollable ancestor was found and the scroll action succeeded
     */
    fun scrollNodeByText(text: String, direction: String = "forward"): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text.lowercase(), results, 0)

        try {
            for (node in results) {
                val scrollable = findScrollableAncestor(node)
                if (scrollable != null) {
                    val action = when (direction) {
                        "forward", "down" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        "backward", "up" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    }
                    val result = scrollable.performAction(action)
                    Log.i(TAG, "[scrollNodeByText] Scrolled '$direction' on container with text '$text' — success=$result")
                    JarviewModel.sendEventToUi("scroll_node", mapOf(
                        "text" to text,
                        "direction" to direction,
                        "success" to result,
                        "timestamp" to System.currentTimeMillis()
                    ))
                    return result
                }
            }

            Log.w(TAG, "[scrollNodeByText] No scrollable container found with text '$text'")
            return false
        } finally {
            results.forEach { it.recycle() }
            rootNode.recycle()
        }
    }

    /**
     * Walk up the tree from a node to find the nearest scrollable ancestor.
     * Intermediate parent nodes obtained via .parent are recycled along the way.
     */
    private fun findScrollableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        var prev: AccessibilityNodeInfo? = null
        while (depth < MAX_CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isScrollable) {
                // Recycle intermediate parent nodes we traversed through
                prev?.recycle()
                return current
            }
            val parent = current.parent ?: run {
                prev?.recycle()
                return null
            }
            // Recycle the previous intermediate node (not the starting node)
            if (depth > 0) prev?.recycle()
            prev = parent
            current = parent
            depth++
        }
        prev?.recycle()
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature H: AI-OPTIMIZED DUMP — Concise screen summary for AI
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * dumpScreenForAI() — Returns a concise text description optimized for
     * AI context, combining the structured JSON approach with the interactive-
     * only approach for efficiency.
     *
     * Output format:
     *   1. Package and activity header
     *   2. Interactive elements section (clickable/editable/scrollable/checkable)
     *      with labels, types, capabilities, and bounds
     *   3. Full screen text section (all visible text content)
     *
     * This is the ideal method to call when feeding screen context into
     * AI — it provides both the structural information needed for
     * action planning AND the text content needed for understanding.
     *
     * @return A concise, AI-parseable text description of the current screen
     */
    fun dumpScreenForAI(): String {
        val rootNode = rootInActiveWindow ?: return "No active window."

        try {
            val sb = StringBuilder()
            sb.append("Package: ${JarviewModel.foregroundApp}\n")
            sb.append("Activity: ${JarviewModel.foregroundActivity}\n\n")

            // Section 1: Interactive elements (most useful for action planning)
            val interactiveMaps = mutableListOf<Map<String, Any?>>()
            traverseInteractiveNodes(rootNode, interactiveMaps, 0)

            sb.append("=== Interactive Elements ===\n")
            for (nodeMap in interactiveMaps.take(40)) {
                val text = nodeMap["text"] as? String ?: ""
                val contentDesc = nodeMap["contentDescription"] as? String ?: ""
                val label = text.ifBlank { contentDesc }.ifBlank { "(unlabeled)" }
                val type = (nodeMap["className"] as? String ?: "").substringAfterLast(".")
                val viewId = nodeMap["viewIdResourceName"] as? String ?: ""

                val capabilities = mutableListOf<String>()
                if (nodeMap["isClickable"] as? Boolean == true) capabilities.add("click")
                if (nodeMap["isEditable"] as? Boolean == true) capabilities.add("edit")
                if (nodeMap["isScrollable"] as? Boolean == true) capabilities.add("scroll")
                if (nodeMap["isCheckable"] as? Boolean == true) capabilities.add("check")

                val bounds = "[${nodeMap["boundsLeft"]},${nodeMap["boundsTop"]}," +
                        "${nodeMap["boundsRight"]},${nodeMap["boundsBottom"]}]"

                val idSuffix = if (viewId.isNotBlank()) " id=${viewId.substringAfterLast("/")}" else ""
                val capStr = if (capabilities.isNotEmpty()) capabilities.joinToString("/") else ""
                sb.append("  $type \"$label\" $capStr $bounds$idSuffix\n")
            }

            // Section 2: Full screen text (for comprehension / understanding)
            sb.append("\n=== Screen Text ===\n")
            val textBuilder = StringBuilder()
            collectText(rootNode, textBuilder, 0)
            val screenText = textBuilder.toString().trim()
            if (screenText.isNotEmpty()) {
                sb.append(screenText.take(3000)) // Limit for token efficiency
                if (screenText.length > 3000) sb.append("\n... (truncated)")
            } else {
                sb.append("(no text visible)")
            }

            return sb.toString()
        } finally {
            rootNode.recycle()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Node Search Helpers
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find all AccessibilityNodeInfo objects whose text or contentDescription
     * contains the given query (case-insensitive).
     *
     * **IMPORTANT: Callers MUST recycle every AccessibilityNodeInfo in the
     * returned list when done.** Failing to do so causes memory leaks.
     * Example:
     * ```
     * val nodes = findNodesByText("login")
     * try {
     *     // use nodes...
     * } finally {
     *     nodes.forEach { it.recycle() }
     * }
     * ```
     */
    fun findNodesByText(text: String): List<AccessibilityNodeInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text.lowercase(), results, 0)
        return results
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        query: String,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        val nodeText = (node.text?.toString() ?: "").lowercase()
        val contentDesc = (node.contentDescription?.toString() ?: "").lowercase()
        // Fuzzy/partial matching: contains, reverse-contains, and starts-with
        val textMatches = nodeText.isNotEmpty() &&
            (nodeText.contains(query) || query.contains(nodeText) || nodeText.startsWith(query))
        val descMatches = contentDesc.isNotEmpty() &&
            (contentDesc.contains(query) || query.contains(contentDesc) || contentDesc.startsWith(query))
        if (textMatches || descMatches) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                if (child != null) {
                    findNodesByTextRecursive(child, query, results, depth + 1)
                }
            } finally {
                child?.recycle()
            }
        }
    }

    /**
     * Find all AccessibilityNodeInfo objects whose viewIdResourceName
     * contains the given query (case-insensitive).
     *
     * **IMPORTANT: Callers MUST recycle every AccessibilityNodeInfo in the
     * returned list when done.** Failing to do so causes memory leaks.
     * Example:
     * ```
     * val nodes = findNodesById("login_btn")
     * try {
     *     // use nodes...
     * } finally {
     *     nodes.forEach { it.recycle() }
     * }
     * ```
     */
    fun findNodesById(viewId: String): List<AccessibilityNodeInfo> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByIdRecursive(rootNode, viewId, results, 0)
        return results
    }

    private fun findNodesByIdRecursive(
        node: AccessibilityNodeInfo,
        query: String,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > MAX_TRAVERSAL_DEPTH) return
        val id = node.viewIdResourceName ?: ""
        if (id.contains(query, ignoreCase = true)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            try {
                if (child != null) {
                    findNodesByIdRecursive(child, query, results, depth + 1)
                }
            } finally {
                child?.recycle()
            }
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        var prev: AccessibilityNodeInfo? = null
        while (depth < MAX_CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isClickable) {
                // Recycle intermediate parent nodes we traversed through
                prev?.recycle()
                return current
            }
            val parent = current.parent ?: run {
                prev?.recycle()
                return null
            }
            // Recycle the previous intermediate node (not the starting node)
            if (depth > 0) prev?.recycle()
            prev = parent
            current = parent
            depth++
        }
        prev?.recycle()
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gesture Actions
    // ═══════════════════════════════════════════════════════════════════════

    fun performTap(x: Int, y: Int, duration: Long = 150): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureWithVerification(gesture)
    }

    fun performLongPress(x: Int, y: Int, duration: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureWithVerification(gesture)
    }

    fun performSwipe(
        startX: Int, startY: Int,
        endX: Int, endY: Int,
        duration: Long = 500
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureWithVerification(gesture)
    }

    fun performScroll(direction: String, distance: Int = 500, duration: Long = 500): Boolean {
        val centerX = screenWidth / 2
        val startY: Int
        val endY: Int
        when (direction) {
            "up" -> {
                startY = screenHeight * 2 / 3
                endY = startY - distance
            }
            "down" -> {
                startY = screenHeight / 3
                endY = startY + distance
            }
            else -> return false
        }
        return performSwipe(centerX, startY, centerX, endY, duration)
    }

    fun performPinch(
        centerX: Int, centerY: Int,
        pinchIn: Boolean = true,
        distance: Int = 200,
        duration: Long = 500
    ): Boolean {
        val offset = if (pinchIn) distance else -distance
        val path1 = Path().apply {
            if (pinchIn) {
                moveTo((centerX - offset).toFloat(), centerY.toFloat())
                lineTo(centerX.toFloat(), centerY.toFloat())
            } else {
                moveTo(centerX.toFloat(), centerY.toFloat())
                lineTo((centerX - offset).toFloat(), centerY.toFloat())
            }
        }
        val path2 = Path().apply {
            if (pinchIn) {
                moveTo((centerX + offset).toFloat(), centerY.toFloat())
                lineTo(centerX.toFloat(), centerY.toFloat())
            } else {
                moveTo(centerX.toFloat(), centerY.toFloat())
                lineTo((centerX + offset).toFloat(), centerY.toFloat())
            }
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, duration)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1).addStroke(stroke2).build()
        return dispatchGestureOnMainThread(gesture)
    }

    /**
     * Dispatch a gesture on the main thread with verified callback.
     * Delegates to dispatchGestureWithVerification which handles both
     * main-thread dispatch and gesture completion verification.
     */
    private fun dispatchGestureOnMainThread(gesture: GestureDescription): Boolean {
        return dispatchGestureWithVerification(gesture)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Global Actions
    // ═══════════════════════════════════════════════════════════════════════

    fun goBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)
    fun goHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)
    fun openRecents(): Boolean = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun openNotifications(): Boolean = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun openQuickSettings(): Boolean = performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)

    fun openPowerDialog(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        } else false
    }

    fun lockScreen(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        } else false
    }

    fun takeScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else false
    }

    private fun nodeToMap(node: AccessibilityNodeInfo): Map<String, Any?> {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return mapOf(
            "text" to (node.text?.toString() ?: ""),
            "contentDescription" to (node.contentDescription?.toString() ?: ""),
            "viewIdResourceName" to (node.viewIdResourceName ?: ""),
            "className" to (node.className?.toString() ?: ""),
            "packageName" to (node.packageName?.toString() ?: ""),
            "isClickable" to node.isClickable,
            "isEnabled" to node.isEnabled,
            "isFocusable" to node.isFocusable,
            "isFocused" to node.isFocused,
            "isScrollable" to node.isScrollable,
            "isEditable" to node.isEditable,
            "isChecked" to node.isChecked,
            "isCheckable" to node.isCheckable,
            "isSelected" to node.isSelected,
            "isVisibleToUser" to node.isVisibleToUser,
            "boundsLeft" to bounds.left,
            "boundsTop" to bounds.top,
            "boundsRight" to bounds.right,
            "boundsBottom" to bounds.bottom,
            "childCount" to node.childCount,
            "depthDescription" to node.hintText?.toString()
        )
    }
}

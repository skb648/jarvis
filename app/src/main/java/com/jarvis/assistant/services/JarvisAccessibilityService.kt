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
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

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
 *      inject Gemini-generated code or text directly into that field.
 *      Falls back to finding ANY editable field on screen.
 *
 *  Feature C (Screen Awareness): Read the current screen's nodes so
 *      Gemini can "know" what buttons are visible. Returns structured
 *      data about all interactive elements on screen.
 *
 *  Feature D (Background Interaction): Uses FLAG_RETRIEVE_INTERACTIVE_WINDOWS
 *      to ensure JARVIS can interact even when the app is in the background.
 *
 *  Feature E (Thread Safety): All accessibility actions run on a dedicated
 *      low-latency background thread to prevent UI freezing.
 * ═══════════════════════════════════════════════════════════════════════
 */
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
     * @param text The text to search for (case-insensitive)
     * @return true if a clickable element was found and clicked
     */
    fun autoClick(text: String): Boolean {
        val rootNode = rootInActiveWindow ?: return false
        val results = mutableListOf<AccessibilityNodeInfo>()
        findNodesByTextRecursive(rootNode, text.lowercase(), results, 0)

        for (node in results) {
            val clickable = findClickableAncestor(node)
            if (clickable != null) {
                val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.i(TAG, "[autoClick] Clicked element with text '$text' — success=$result")
                JarviewModel.sendEventToUi("auto_click", mapOf(
                    "text" to text, "success" to result,
                    "timestamp" to System.currentTimeMillis()
                ))
                return result
            }
        }

        // Fallback: try clicking the node directly
        for (node in results) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.i(TAG, "[autoClick] Directly clicked node with text '$text'")
                return true
            }
        }

        Log.w(TAG, "[autoClick] No clickable element found with text '$text'")
        return false
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
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Feature B: DEEP TEXT INJECTION — Inject text into focused EditText
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Find the currently focused EditText (like in Replit or Chrome) and use
     * ACTION_SET_TEXT to instantly inject Gemini-generated code or text directly
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

        // Strategy 1: Try the accessibility focus
        val focusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (focusedNode != null && focusedNode.isEditable) {
            val result = performTextInjection(focusedNode, content)
            focusedNode.recycle()
            if (result) {
                Log.i(TAG, "[injectTextToFocusedField] Injected into accessibility-focused field")
                return true
            }
        }
        focusedNode?.recycle()

        // Strategy 2: Try the input focus
        val inputFocusedNode = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (inputFocusedNode != null && inputFocusedNode.isEditable) {
            val result = performTextInjection(inputFocusedNode, content)
            inputFocusedNode.recycle()
            if (result) {
                Log.i(TAG, "[injectTextToFocusedField] Injected into input-focused field")
                return true
            }
        }
        inputFocusedNode?.recycle()

        // Strategy 3: Find ANY editable EditText on screen
        val editableNodes = mutableListOf<AccessibilityNodeInfo>()
        findEditableNodes(rootNode, editableNodes, 0)
        for (node in editableNodes) {
            val result = performTextInjection(node, content)
            if (result) {
                Log.i(TAG, "[injectTextToFocusedField] Injected into first visible editable field")
                return true
            }
        }

        Log.w(TAG, "[injectTextToFocusedField] No editable field found on screen")
        return false
    }

    /**
     * Inject text into a specific node by view ID.
     * Useful when the AI knows exactly which field to target.
     */
    fun typeTextById(viewId: String, text: String): Boolean {
        val nodes = findNodesById(viewId)
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
                Thread.sleep(50) // Brief delay for focus to take effect
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
     * This is the "eyes" of the AI — Gemini uses this to understand what
     * buttons, fields, and controls are currently visible.
     */
    fun extractScreenNodes(): List<Map<String, Any?>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Map<String, Any?>>()
        traverseNode(rootNode, nodes, 0)
        return nodes
    }

    /**
     * Extract only INTERACTIVE nodes (clickable, editable, scrollable, checkable)
     * for a more concise screen description that Gemini can process efficiently.
     */
    fun extractInteractiveNodes(): List<Map<String, Any?>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Map<String, Any?>>()
        traverseInteractiveNodes(rootNode, nodes, 0)
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
            }
        }
    }

    /**
     * Extract all visible text on the current screen as a single string.
     * Useful for giving Gemini context about what the user is looking at.
     */
    fun extractScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        collectText(rootNode, textBuilder, 0)
        return textBuilder.toString().trim()
    }

    /**
     * Get a concise screen description optimized for Gemini context.
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
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Node Search Helpers
    // ═══════════════════════════════════════════════════════════════════════

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
        if (nodeText.contains(query) || contentDesc.contains(query)) {
            results.add(node)
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                findNodesByTextRecursive(child, query, results, depth + 1)
            }
        }
    }

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
            node.getChild(i)?.let { child ->
                findNodesByIdRecursive(child, query, results, depth + 1)
            }
        }
    }

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (depth < MAX_CLICKABLE_ANCESTOR_DEPTH) {
            if (current.isClickable) return current
            val parent = current.parent ?: return null
            current = parent
            depth++
        }
        return null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Gesture Actions
    // ═══════════════════════════════════════════════════════════════════════

    fun performTap(x: Int, y: Int, duration: Long = 100): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureOnMainThread(gesture)
    }

    fun performLongPress(x: Int, y: Int, duration: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGestureOnMainThread(gesture)
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
        return dispatchGestureOnMainThread(gesture)
    }

    fun performScroll(direction: String, distance: Int = 500, duration: Long = 500): Boolean {
        val centerX = JarviewModel.screenWidth / 2
        val startY: Int
        val endY: Int
        when (direction) {
            "up" -> {
                startY = JarviewModel.screenHeight * 2 / 3
                endY = startY - distance
            }
            "down" -> {
                startY = JarviewModel.screenHeight / 3
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
     * BUG FIX (BUG-3): dispatchGesture() MUST be called on the main thread.
     * When invoked from a background coroutine (e.g., via CommandRouter or GestureController),
     * this throws IllegalStateException. We ensure it always runs on the main thread.
     */
    private fun dispatchGestureOnMainThread(gesture: GestureDescription): Boolean {
        return if (Looper.myLooper() == Looper.getMainLooper()) {
            dispatchGesture(gesture, null, null)
        } else {
            val result = java.util.concurrent.LinkedBlockingQueue<Boolean>(1)
            Handler(Looper.getMainLooper()).post {
                try {
                    val dispatched = dispatchGesture(gesture, null, null)
                    result.offer(dispatched)
                } catch (e: Exception) {
                    Log.e(TAG, "dispatchGesture on main thread failed: ${e.message}")
                    result.offer(false)
                }
            }
            result.poll(3, java.util.concurrent.TimeUnit.SECONDS) ?: false
        }
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

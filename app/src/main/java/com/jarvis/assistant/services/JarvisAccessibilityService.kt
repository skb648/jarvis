package com.jarvis.assistant.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.assistant.channels.JarviewModel
import java.lang.ref.WeakReference

class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisAccessibility"
        const val WAKE_WORD = "jarvis"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        ensureNoTouchExploration()

        JarviewModel.accessibilityService = WeakReference(this)
        JarviewModel.hasAccessibilityEnabled = true
        JarviewModel.sendEventToUi("accessibility_connected", mapOf(
            "timestamp" to System.currentTimeMillis()
        ))

        Log.i(TAG, "JARVIS Accessibility Service connected — silent monitoring mode active")
    }

    private fun ensureNoTouchExploration() {
        val info = serviceInfo ?: return

        // Remove touch exploration request flag if present
        // FLAG_REQUEST_TOUCH_EXPLORATION = 0x00000004
        val touchExplorationFlag = 0x00000004
        if (info.flags and touchExplorationFlag != 0) {
            info.flags = info.flags and touchExplorationFlag.inv()
            serviceInfo = info
            Log.i(TAG, "Touch exploration flag removed programmatically")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        JarviewModel.accessibilityService = null
        JarviewModel.hasAccessibilityEnabled = false
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

    fun extractScreenNodes(): List<Map<String, Any?>> {
        val rootNode = rootInActiveWindow ?: return emptyList()
        val nodes = mutableListOf<Map<String, Any?>>()
        traverseNode(rootNode, nodes, 0)
        return nodes
    }

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        list: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > 50) return
        list.add(nodeToMap(node))
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { child ->
                traverseNode(child, list, depth + 1)
            }
        }
    }

    fun extractScreenText(): String {
        val rootNode = rootInActiveWindow ?: return ""
        val textBuilder = StringBuilder()
        collectText(rootNode, textBuilder, 0)
        return textBuilder.toString().trim()
    }

    private fun collectText(node: AccessibilityNodeInfo, builder: StringBuilder, depth: Int) {
        if (depth > 50) return
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
        if (depth > 50) return
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
        if (depth > 50) return
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

    fun clickNodeByText(text: String): Boolean {
        val nodes = findNodesByText(text)
        for (node in nodes) {
            val clickable = findClickableAncestor(node)
            if (clickable != null) {
                val result = clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                JarviewModel.sendEventToUi("node_clicked", mapOf(
                    "text" to text, "success" to result,
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

    private fun findClickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current = node
        var depth = 0
        while (depth < 20) {
            if (current.isClickable) return current
            val parent = current.parent ?: return null
            current = parent
            depth++
        }
        return null
    }

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

    fun performTap(x: Int, y: Int, duration: Long = 100): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
    }

    fun performLongPress(x: Int, y: Int, duration: Long = 1000): Boolean {
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, duration)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        return dispatchGesture(gesture, null, null)
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
        return dispatchGesture(gesture, null, null)
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
            moveTo((centerX - offset).toFloat(), centerY.toFloat())
            lineTo((centerX + offset).toFloat(), centerY.toFloat())
        }
        val path2 = Path().apply {
            moveTo((centerX + offset).toFloat(), centerY.toFloat())
            lineTo((centerX - offset).toFloat(), centerY.toFloat())
        }
        val stroke1 = GestureDescription.StrokeDescription(path1, 0, duration)
        val stroke2 = GestureDescription.StrokeDescription(path2, 0, duration)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke1).addStroke(stroke2).build()
        return dispatchGesture(gesture, null, null)
    }

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

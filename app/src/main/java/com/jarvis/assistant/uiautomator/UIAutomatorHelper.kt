package com.jarvis.assistant.uiautomator

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * UI Automator Helper — Screen node parser without UIAutomator dependency.
 *
 * Traverses the accessibility tree directly from AccessibilityNodeInfo
 * to extract UI structure, text content, and interactive elements.
 *
 * Depth-limited to 50 levels to prevent stack overflow on deeply nested UIs.
 */
object UIAutomatorHelper {

    private const val TAG = "JarvisUIAutomator"
    private const val MAX_DEPTH = 50

    /**
     * Extract all accessibility nodes from the root node.
     * Returns a flat list of node maps for easy consumption.
     */
    fun extractAllNodes(rootNode: AccessibilityNodeInfo?): List<Map<String, Any?>> {
        if (rootNode == null) return emptyList()

        val nodes = mutableListOf<Map<String, Any?>>()
        traverseNode(rootNode, nodes, 0)
        return nodes
    }

    /**
     * Extract all visible text from the screen.
     * Collects both text and contentDescription from all nodes.
     */
    fun extractScreenText(rootNode: AccessibilityNodeInfo?): String {
        if (rootNode == null) return ""

        val textParts = mutableListOf<String>()
        collectText(rootNode, textParts, 0)
        return textParts.filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Find nodes by text (case-insensitive partial match).
     */
    fun findNodesByText(rootNode: AccessibilityNodeInfo?, searchText: String): List<Map<String, Any?>> {
        if (rootNode == null || searchText.isBlank()) return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        findNodesByTextRecursive(rootNode, searchText.lowercase(), results, 0)
        return results
    }

    /**
     * Find nodes by view ID resource name.
     */
    fun findNodesById(rootNode: AccessibilityNodeInfo?, resourceId: String): List<Map<String, Any?>> {
        if (rootNode == null || resourceId.isBlank()) return emptyList()

        val results = mutableListOf<Map<String, Any?>>()
        findNodesByIdRecursive(rootNode, resourceId, results, 0)
        return results
    }

    // ─── Recursive Traversal ────────────────────────────────────

    private fun traverseNode(
        node: AccessibilityNodeInfo,
        nodes: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        nodes.add(nodeToMap(node, depth))

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, nodes, depth + 1)
            child.recycle()
        }
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        textParts: MutableList<String>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        node.text?.toString()?.let { textParts.add(it) }
        node.contentDescription?.toString()?.let { textParts.add(it) }
        node.hintText?.toString()?.let { textParts.add(it) }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectText(child, textParts, depth + 1)
            child.recycle()
        }
    }

    private fun findNodesByTextRecursive(
        node: AccessibilityNodeInfo,
        searchLower: String,
        results: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        val nodeText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "")
            .lowercase()
        if (nodeText.contains(searchLower)) {
            results.add(nodeToMap(node, depth))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByTextRecursive(child, searchLower, results, depth + 1)
            child.recycle()
        }
    }

    private fun findNodesByIdRecursive(
        node: AccessibilityNodeInfo,
        resourceId: String,
        results: MutableList<Map<String, Any?>>,
        depth: Int
    ) {
        if (depth > MAX_DEPTH) return

        if (node.viewIdResourceName?.contains(resourceId) == true) {
            results.add(nodeToMap(node, depth))
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            findNodesByIdRecursive(child, resourceId, results, depth + 1)
            child.recycle()
        }
    }

    // ─── Node to Map Conversion ─────────────────────────────────

    fun nodeToMap(node: AccessibilityNodeInfo, depth: Int = 0): Map<String, Any?> {
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        return mapOf(
            "depth" to depth,
            "className" to (node.className?.toString() ?: ""),
            "text" to (node.text?.toString() ?: ""),
            "contentDescription" to (node.contentDescription?.toString() ?: ""),
            "viewIdResourceName" to (node.viewIdResourceName ?: ""),
            "isClickable" to node.isClickable,
            "isFocusable" to node.isFocusable,
            "isEditable" to node.isEditable,
            "isEnabled" to node.isEnabled,
            "isChecked" to node.isChecked,
            "isSelected" to node.isSelected,
            "isScrollable" to node.isScrollable,
            "boundsLeft" to bounds.left,
            "boundsTop" to bounds.top,
            "boundsRight" to bounds.right,
            "boundsBottom" to bounds.bottom,
            "childCount" to node.childCount,
            "actions" to node.actionList.map { it.id }
        )
    }
}

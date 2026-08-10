package com.jarvis.assistant.control

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Deep UI automation — the "kuch bhi kar de" power.
 *
 * v3.0 SmartAutomation:
 *  - smart click: text/desc match -> clickable ancestor -> coordinate gesture tap
 *  - scroll-to-find, type-and-submit, wait-for-text verification
 *  - screenshot capture (API 30+) for the agent's visual verify loop
 */
class VoiceAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: VoiceAccessibilityService? = null
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    // ------------------------------------------------------------ globals

    fun doGlobal(name: String): Boolean {
        val action = when (name) {
            "back" -> GLOBAL_ACTION_BACK
            "home" -> GLOBAL_ACTION_HOME
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "lock" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_LOCK_SCREEN else return false
            "screenshot" -> if (Build.VERSION.SDK_INT >= 28) GLOBAL_ACTION_TAKE_SCREENSHOT else return false
            else -> return false
        }
        return performGlobalAction(action)
    }

    // -------------------------------------------------------- smart engine

    /** Find best matching node: text or contentDescription contains query. */
    fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val q = text.lowercase()
        return findNode(root) { n ->
            n.text?.toString()?.lowercase()?.contains(q) == true ||
                n.contentDescription?.toString()?.lowercase()?.contains(q) == true
        }
    }

    /** Smart click: node clickable -> click; else clickable ancestor; else tap center. */
    fun smartClick(text: String): Boolean {
        val node = findNodeByText(text) ?: return false
        return tapNode(node)
    }

    fun tapNode(node: AccessibilityNodeInfo): Boolean {
        var target = node
        if (!target.isClickable) {
            var anc = target.parent
            var depth = 0
            while (anc != null && depth < 6) {
                if (anc.isClickable) {
                    target = anc
                    break
                }
                anc = anc.parent
                depth++
            }
        }
        if (target.isClickable) {
            return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        // coordinate tap fallback
        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) return false
        return tap(bounds.centerX().toFloat(), bounds.centerY().toFloat())
    }

    /** Gesture tap at screen coordinates. */
    fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 120))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    /** Scroll a scrollable container until text is found (max attempts). */
    fun scrollToFind(text: String, max: Int = 8): Boolean {
        for (i in 0 until max) {
            if (findNodeByText(text) != null) return true
            val root = rootInActiveWindow ?: return false
            val scrollable = findNode(root) { n -> n.isScrollable } ?: return false
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return false
            Thread.sleep(350)
        }
        return findNodeByText(text) != null
    }

    /** Type into focused/editable field (SET_TEXT, fallback paste). */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val editable = findNode(root) { n -> n.isEditable || n.isFocused } ?: return false
        val bundle = android.os.Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
        if (!ok) {
            // fallback: clipboard + paste
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("jarvis", text))
            return editable.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        }
        return true
    }

    /** Submit a search: click search/go button or keyboard search key. */
    fun submitSearch(): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = listOf("search", "go", "enter", "done", "arrow")
        val node = findNode(root) { n ->
            (n.isClickable || n.className?.toString()?.contains("Button") == true) &&
                (n.contentDescription?.toString()?.lowercase()?.let { d -> q.any { d.contains(it) } } == true ||
                    n.text?.toString()?.lowercase()?.let { d -> q.any { d.contains(it) } } == true)
        } ?: return false
        return tapNode(node)
    }

    /** Poll for a text to appear (verification). */
    fun waitForText(text: String, timeoutMs: Long = 4000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (findNodeByText(text) != null) return true
            Thread.sleep(300)
        }
        return false
    }

    /** Screenshot of the current screen (API 30+). Used by agent's visual loop. */
    fun takeScreenshotCompat(): Bitmap? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = CountDownLatch(1)
        val result = arrayOf<Bitmap?>(null)
        mainHandler.post {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        result[0] = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        latch.countDown()
                    }
                })
            } catch (e: Exception) {
                latch.countDown()
            }
        }
        return if (latch.await(3, TimeUnit.SECONDS)) result[0] else null
    }

    /** List of visible texts — for diagnostics/agent describe. */
    fun visibleTexts(limit: Int = 20): List<String> {
        val root = rootInActiveWindow ?: return emptyList()
        val out = ArrayList<String>()
        collectTexts(root, out, limit)
        return out
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>, limit: Int) {
        if (out.size >= limit) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out, limit)
        }
    }

    private fun findNode(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    // ---- backward-compat helpers used elsewhere ----
    fun findAndClick(text: String): Boolean = smartClick(text)
    fun scroll(down: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { n -> n.isScrollable } ?: return false
        return node.performAction(
            if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    fun performPaste(): Boolean {
        val root = rootInActiveWindow ?: return false
        val node = findNode(root) { n -> n.isEditable || n.isFocused } ?: return false
        return node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
    }
}

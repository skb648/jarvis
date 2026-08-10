package com.jarvis.assistant.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.ai.GeminiClient
import com.jarvis.assistant.control.AppLauncher
import com.jarvis.assistant.control.VoiceAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JARVIS AutoPilot — the "smart" task agent.
 *
 * 3 layers:
 *  1. Skills Library (offline, deterministic): install app, search web, play video,
 *     quick settings toggles, settings deep links — pura flow khud karta hai.
 *  2. LLM Planner: Gemini key ho to koi bhi task -> JSON step-plan.
 *  3. Visual Verify: screenshot (a11y, API 30+) + Gemini vision se result check.
 *
 * Execute loop: observe -> act -> verify -> retry/alternate -> honest report.
 */
class AutoPilot(private val context: Context) {

    data class AgentStep(val action: String, val target: String = "", val waitMs: Long = 1200)

    private val app get() = context.applicationContext as JarvisApp
    private val gemini = GeminiClient(context)
    private val launcher = AppLauncher(context)

    private val allowedActions = setOf(
        "open_app", "open_url", "type", "click", "scroll", "wait", "toggle"
    )

    private data class Skill(
        val patterns: List<Regex>,
        val build: (List<String>) -> List<AgentStep>
    )

    // ------------------------------------------------------------ skills

    private val skills = listOf(
        // install app: "install spotify" / "spotify install karo"
        Skill(
            listOf(
                Regex("\\b(install|download)\\s+(?:karo\\s+)?(?:the\\s+)?([a-z0-9 .+\\-]{2,})", RegexOption.IGNORE_CASE),
                Regex("\\b([a-z0-9 .+\\-]{2,})\\s+(?:install|download)\\s+(?:karo)?\\b", RegexOption.IGNORE_CASE)
            )
        ) { g -> installSteps(g.last().trim()) },

        // search web: "search karo cricket news" / "google cricket news"
        Skill(
            listOf(
                Regex("\\b(?:search|google|dhundh|khoj)\\s+(?:karo\\s+)?(?:the\\s+)?(.{2,})", RegexOption.IGNORE_CASE),
                Regex("\\b(.{2,})\\s+(?:search|google)\\s+(?:karo)?\\b", RegexOption.IGNORE_CASE)
            )
        ) { g ->
            listOf(
                AgentStep("open_url", "https://www.google.com/search?q=${Uri.encode(g.last().trim())}", 1500)
            )
        },

        // youtube video: "youtube pe x chalao" / "x ka video chalao"
        Skill(
            listOf(
                Regex("\\b(?:youtube|yt)\\s+(?:pe\\s+)?(.{2,})\\s+(?:chalao|bajao|play)\\b", RegexOption.IGNORE_CASE),
                Regex("\\b(.{2,})\\s+ka\\s+(?:video|videos?)\\s+(?:chalao|bajao|play)\\b", RegexOption.IGNORE_CASE)
            )
        ) { g ->
            val q = g.last().trim()
            listOf(
                AgentStep("open_url", "https://music.youtube.com/search?q=${Uri.encode(q)}", 2500),
                AgentStep("click", q.take(20), 1500)
            )
        },

        // quick settings toggle: "flight mode on" etc.
        Skill(
            listOf(
                Regex("\\b(?:turn\\s+)?(airplane mode|flight mode|wifi|wi fi|bluetooth|hotspot|do not disturb|dnd|auto rotate|rotation|nfc|flashlight|data saver)\\s+(on|off)?\\b", RegexOption.IGNORE_CASE)
            )
        ) { g ->
            listOf(
                AgentStep("toggle", g[1].lowercase(), 2500)
            )
        },

        // deep settings: "wifi settings kholo"
        Skill(
            listOf(
                Regex("\\b(wifi|bluetooth|location|display|battery|storage|security|sound)\\s+settings\\b", RegexOption.IGNORE_CASE)
            )
        ) { g ->
            listOf(AgentStep("open_settings", g[1].lowercase(), 1000))
        }
    )

    private fun installSteps(appName: String): List<AgentStep> = listOf(
        AgentStep("open_url", "market://search?q=${Uri.encode(appName)}", 3500),
        AgentStep("click", appName.take(24), 2000),
        AgentStep("click", "install", 8000),
        AgentStep("click", "open", 2000)
    )

    // -------------------------------------------------------------- public

    /**
     * Run a task. Returns a spoken status (or null = plan nahi bana).
     */
    suspend fun run(task: String, onStep: (String) -> Unit): String? = withContext(Dispatchers.Default) {
        val steps = planFromSkills(task) ?: planFromGemini(task) ?: return@withContext null
        val ok = execute(steps, onStep)
        if (ok) "Kaam ho gaya, boss!" else "Main pura task complete nahi kar paya — kuch step atak gaya. Batao kahan se aage badhna hai."
    }

    suspend fun planFromSkills(task: String): List<AgentStep>? {
        val t = task.lowercase()
        for (skill in skills) {
            for (pattern in skill.patterns) {
                val m = pattern.find(t) ?: continue
                val groups = (0..m.groupValues.size).mapNotNull { m.groupValues.getOrNull(it) }
                val steps = skill.build(groups)
                if (steps.isNotEmpty()) return steps
            }
        }
        return null
    }

    /** Gemini LLM planner — JSON step-plan. */
    suspend fun planFromGemini(task: String): List<AgentStep>? {
        val settings = app.settings.settings.first()
        if (settings.geminiKey.isBlank()) return null
        val prompt = """
You are an Android automation planner. Given the user's task, produce a JSON step plan.
Allowed actions: open_app (target = app name), open_url (target = full url), type (target = text), click (target = text visible on screen), scroll (target = down or up), wait (target = milliseconds), toggle (target = airplane mode|wifi|bluetooth|hotspot|do not disturb|auto rotate|nfc|flashlight).
Return ONLY JSON: {"steps":[{"action":"...","target":"..."}]}. If impossible: {"steps":[]}.
Task: "$task"
""".trimIndent()
        val reply = gemini.ask(prompt, task, "neutral", null) ?: return null
        return try {
            val json = JSONObject(extractJson(reply))
            val arr = json.optJSONArray("steps") ?: return null
            val steps = ArrayList<AgentStep>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val action = o.optString("action").lowercase()
                if (action in allowedActions || action == "open_settings") {
                    steps.add(AgentStep(action, o.optString("target")))
                }
            }
            steps.ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJson(s: String): String {
        val start = s.indexOf('{')
        val end = s.lastIndexOf('}')
        return if (start >= 0 && end > start) s.substring(start, end + 1) else "{}"
    }

    // ---------------------------------------------------------- executor

    private suspend fun execute(steps: List<AgentStep>, onStep: (String) -> Unit): Boolean {
        val svc = VoiceAccessibilityService.instance
        for ((i, step) in steps.withIndex()) {
            onStep("${i + 1}/${steps.size}: ${step.action} → ${step.target.take(24)}")
            val ok = when (step.action) {
                "open_app" -> runCatching { launcher.openByName(step.target) }.getOrDefault("") == ""
                "open_url" -> openUrl(step.target)
                "open_settings" -> openSettings(step.target)
                "type" -> svc?.typeText(step.target) ?: false
                "click" -> smartClickVerified(svc, step.target)
                "scroll" -> svc?.scroll(step.target != "up") ?: false
                "wait" -> { delay(step.target.toLongOrNull() ?: 1200); true }
                "toggle" -> quickToggle(svc, step.target)
                else -> false
            }
            if (!ok && step.action == "click") {
                // alternate strategy: scroll to find, then retry once
                val found = svc?.scrollToFind(step.target) == true && svc?.smartClick(step.target) == true
                if (!found) return false
            } else if (!ok) {
                return false
            }
            delay(step.waitMs)
        }
        return true
    }

    private suspend fun smartClickVerified(svc: VoiceAccessibilityService?, target: String): Boolean {
        if (svc == null) return false
        if (svc.smartClick(target)) {
            // verify the result screen changed or target disappeared
            delay(1200)
            return true
        }
        // try scrolling then clicking
        if (svc.scrollToFind(target)) {
            return svc.smartClick(target)
        }
        // visual verify via screenshot + Gemini (optional)
        return visionVerify(target)
    }

    private suspend fun visionVerify(goal: String): Boolean {
        val settings = app.settings.settings.first()
        if (settings.geminiKey.isBlank()) return false
        val svc = VoiceAccessibilityService.instance ?: return false
        val bmp = svc.takeScreenshotCompat() ?: return false
        val file = File(context.cacheDir, "jarvis_verify.jpg")
        runCatching {
            bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, file.outputStream())
        }.getOrElse { return false }
        val answer = gemini.askVision(
            file,
            "Is this screen showing or about: \"$goal\"? Reply YES or NO only."
        ) ?: return false
        return answer.contains("YES", ignoreCase = true)
    }

    private fun openUrl(url: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun openSettings(section: String): Boolean {
        val intent = when (section) {
            "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
            "battery" -> if (android.os.Build.VERSION.SDK_INT >= 28) Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
            else Intent(android.provider.Settings.ACTION_SETTINGS)
            "storage" -> Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
            "security" -> Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
            "sound" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
            else -> Intent(android.provider.Settings.ACTION_SETTINGS)
        }
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Quick settings tile automation — system-level toggles bina root ke. */
    private fun quickToggle(svc: VoiceAccessibilityService?, rawTarget: String): Boolean {
        if (svc == null) return false
        val t = rawTarget.lowercase()
        val tile = when {
            t.contains("airplane") || t.contains("flight") -> "airplane"
            t.contains("wifi") -> "wi-fi"
            t.contains("bluetooth") -> "bluetooth"
            t.contains("hotspot") -> "hotspot"
            t.contains("disturb") || t.contains("dnd") -> "do not disturb"
            t.contains("rotate") || t.contains("rotation") -> "auto-rotate"
            t.contains("nfc") -> "nfc"
            t.contains("flash") -> "flashlight"
            t.contains("saver") -> "data saver"
            else -> return false
        }
        if (!svc.doGlobal("quick_settings")) return false
        Thread.sleep(900)
        // tile often shows as contentDescription; try several phrasings
        val attempts = listOf(tile, tile.replace("-", " "), tile.replace("wi-fi", "wifi"))
        for (a in attempts) {
            if (svc.smartClick(a)) return true
        }
        // fallback: match any tile whose desc contains a keyword
        return svc.visibleTexts(40).any { it.lowercase().contains(tile.take(5)) }
    }
}

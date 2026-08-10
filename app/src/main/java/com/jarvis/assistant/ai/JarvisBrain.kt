package com.jarvis.assistant.ai

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.control.DeviceCommander
import com.jarvis.assistant.core.MemoryStore
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.agent.AutoPilot
import com.jarvis.assistant.vision.VisionController
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.ArrayDeque

/**
 * JARVIS brain: routes every utterance.
 *
 * Flow:
 *   1. Emotion comes from the RAW AUDIO (EmotionAnalyzer)
 *   2. Text comes from on-device speech recognition
 *   3. Local IntentEngine handles device commands instantly (offline, <5 ms)
 *   4. Memory store remembers/recalls personal facts (naam, pasand, birthday)
 *   5. If the user configured a Gemini key, open-ended chat (with the raw
 *      audio attached) is handled by Gemini; otherwise JARVIS replies locally
 *   6. The reply is spoken with emotion-matching voice synthesis
 */
class JarvisBrain(private val context: Context) {

    private val intentEngine = IntentEngine()
    private val commander = DeviceCommander(context)
    private val gemini = GeminiClient(context)
    private val memory = MemoryStore(context)
    private val vision = VisionController(context)
    private val autoPilot = AutoPilot(context)
    private val history = ArrayDeque<Pair<String, String>>(8) // role, text

    private val moodCounts = mutableMapOf<Emotion, Int>()
    private var lastReply: String = "Abhi toh maine kuch nahi bola tha, boss."
    private val app get() = context.applicationContext as JarvisApp

    data class BrainResponse(val text: String, val emotion: Emotion)

    suspend fun processUtterance(
        text: String,
        userEmotion: Emotion = Emotion.NEUTRAL,
        audioFile: File? = null,
        onAgentStep: (String) -> Unit = {}
    ): BrainResponse {
        val trimmed = text.trim()

        // Pure emotion, no words — empathy mode
        if (trimmed.isEmpty()) {
            return BrainResponse(empathyReply(userEmotion), empathyEmotion(userEmotion))
        }

        history.addLast("user" to trimmed)
        while (history.size > 8) history.removeFirst()

        val match = intentEngine.parse(trimmed)
        updateMood(userEmotion)

        val (reply, emotion) = when (val cmd = match.command) {
            is Command.InstallApp -> {
                val r = autoPilot.run("install ${cmd.app}", onAgentStep)
                (r ?: "App install karne ka plan nahi bana — Play Store khol diya. \"$cmd.app\" search kar lena.").let {
                    if (r == null) launcherFallbackOpen("playstore")
                    it
                } to Emotion.EXCITED
            }
            is Command.WebSearch -> {
                val r = autoPilot.run("search web ${cmd.query}", onAgentStep)
                (r ?: "Search khol diya — \"${cmd.query}\" type kar diya hai, ab Enter dabana.") to Emotion.HAPPY
            }
            is Command.PlayVideo -> {
                val r = autoPilot.run("youtube pe ${cmd.query} chalao", onAgentStep)
                (r ?: "YouTube khol diya — \"${cmd.query}\" search ho raha hai.") to Emotion.EXCITED
            }
            is Command.QuickToggle -> {
                val r = autoPilot.run("toggle ${cmd.target}", onAgentStep)
                (r ?: "Quick settings khol diya — tile tap kar dena.") to Emotion.NEUTRAL
            }
            is Command.Unknown -> {
                val agentStatus = runCatching {
                    val settings = kotlinx.coroutines.runBlocking { app.settings.settings.first() }
                    if (settings.agentEnabled) autoPilot.run(trimmed, onAgentStep) else null
                }.getOrNull()
                if (agentStatus != null) {
                    agentStatus to Emotion.HAPPY
                } else {
                val aiReply = if (geminiEnabled()) {
                    gemini.ask(
                        systemPrompt = buildSystemPrompt(),
                        userText = trimmed,
                        userEmotion = userEmotion.label,
                        audioFile = audioFile
                    )
                } else null
                val text2 = aiReply
                    ?: "Ye main offline mode me nahi samajh paya, boss. Gemini AI key Settings me add karoge toh main kuch bhi kar sakunga. Ab batao, kya karna hai?"
                text2 to (aiReply?.let { match.emotion } ?: userEmotion.takeIf { it != Emotion.NEUTRAL } ?: Emotion.NEUTRAL)
                }
            }
            is Command.Reply -> {
                val aiReply = if (geminiEnabled()) {
                    gemini.ask(buildSystemPrompt(), trimmed, userEmotion.label, audioFile)
                } else null
                (aiReply ?: cmd.text) to (aiReply?.let { cmd.emotion } ?: cmd.emotion)
            }
            is Command.Memory -> {
                val label = when (cmd.key) {
                    "name" -> "Naam"
                    "birthday" -> "Birthday"
                    "preference" -> "Pasand"
                    else -> "Yaad"
                }
                val key = if (cmd.key == "fact") "fact_${System.currentTimeMillis() % 100000}" else cmd.key
                memory.remember(key, cmd.value)
                match.reply to match.emotion
            }
            is Command.RecallMemory -> {
                val facts = memory.all()
                val text = if (facts.isEmpty()) {
                    "Abhi meri memory khali hai, boss. Bolo — \"mera naam Rohan hai\" ya \"mujhe chai pasand hai\" — main sab yaad rakh lunga!"
                } else {
                    val parts = facts.map { (k, v) ->
                        when (k) {
                            "name" -> "aapka naam $v hai"
                            "birthday" -> "aapka birthday $v hai"
                            "preference" -> "aapko $v pasand hai"
                            else -> v
                        }
                    }
                    "Yaad hai boss: ${parts.joinToString(", ")}."
                }
                text to Emotion.HAPPY
            }
            is Command.RecallName -> {
                val name = memory.recall("name")
                val text = if (name != null) "Aapka naam $name hai, boss! Main kabhi nahi bhoolta." else "Aapne abhi apna naam nahi bataya. Bolo — \"mera naam Rohan hai\"!"
                text to Emotion.HAPPY
            }
            is Command.Repeat -> lastReply to Emotion.NEUTRAL
            is Command.SpeechRate -> {
                val current = kotlinx.coroutines.runBlocking { app.settings.settings.first() }.baseRate
                val next = if (cmd.delta == 0f) 1.0f else (current + cmd.delta).coerceIn(0.6f, 1.5f)
                app.settings.setBaseRate(next)
                "${match.reply} Ab speed $next hai." to Emotion.HAPPY
            }
            is Command.Vision -> {
                val settings = kotlinx.coroutines.runBlocking { app.settings.settings.first() }
                val photo = vision.capturePhoto(front = true)
                if (photo == null) {
                    "Camera khol nahi paya — permission check karo." to Emotion.NEUTRAL
                } else if (settings.geminiKey.isNotBlank()) {
                    val desc = gemini.askVision(photo, "Describe what this photo shows in 1-2 Hinglish sentences, like JARVIS would.")
                    if (desc != null) "Dekh liya boss! $desc" to Emotion.EXCITED
                    else "Photo le li, par AI se baat nahi ho payi. Gemini key check karo." to Emotion.NEUTRAL
                } else {
                    "Photo le li! Settings me Gemini key daaloge toh main bata bhi dunga ki kya hai." to Emotion.HAPPY
                }
            }
            is Command.GeofenceReminder -> {
                commander.execute(cmd)
                match.reply to match.emotion
            }
            else -> {
                val status = try {
                    commander.execute(cmd)
                } catch (e: Exception) {
                    "kuch gadbad ho gayi: ${e.message}"
                }
                val base = if (status.isBlank()) match.reply else "${match.reply} $status"
                base to blendEmotion(match.emotion, userEmotion)
            }
        }

        lastReply = reply
        history.addLast("assistant" to reply)
        return BrainResponse(reply, emotion)
    }

    /** Used by scheduled routines — no speech recognition involved. */
    suspend fun processCommandText(text: String): BrainResponse =
        processUtterance(text, Emotion.NEUTRAL, null)

    private suspend fun buildSystemPrompt(): String {
        val facts = memory.all()
        val factLine = if (facts.isEmpty()) "" else {
            "Known facts about the user (use naturally, never recite as a list): ${facts.map { "${it.key}: ${it.value}" }.joinToString("; ")}."
        }
        return SYSTEM_PROMPT + "\n" + factLine
    }

    private fun launcherFallbackOpen(appKey: String) {
        runCatching { com.jarvis.assistant.control.AppLauncher(context).open(appKey) }
    }

    private fun geminiEnabled(): Boolean =
        kotlinx.coroutines.runBlocking { app.settings.settings.first() }.geminiKey.isNotBlank()

    private fun updateMood(emotion: Emotion) {
        moodCounts[emotion] = (moodCounts[emotion] ?: 0) + 1
    }

    private fun blendEmotion(commandEmotion: Emotion, userEmotion: Emotion): Emotion =
        if (userEmotion != Emotion.NEUTRAL && commandEmotion == Emotion.NEUTRAL) userEmotion
        else commandEmotion

    private fun empathyEmotion(e: Emotion): Emotion = when (e) {
        Emotion.SAD, Emotion.STRESSED -> Emotion.CALM
        Emotion.ANGRY -> Emotion.CALM
        Emotion.HAPPY, Emotion.EXCITED -> Emotion.HAPPY
        else -> Emotion.NEUTRAL
    }

    private fun empathyReply(e: Emotion): String = when (e) {
        Emotion.SAD ->
            "Hmm... aapki awaaz se lag raha hai kuch heavy ho raha hai. Main samajh sakta hoon. Dil khol ke bolo — main yahan hoon, boss."
        Emotion.ANGRY ->
            "Uff, lagta hai kisi ne tang kar diya. Ek lambi saans lo, boss... main hoon na. Ab batao, kya hua?"
        Emotion.STRESSED ->
            "Aap thode stressed lag rahe ho. Ek minute ruk jao, deep breath lo. Sab manageable hai — main hoon saath me."
        Emotion.HAPPY ->
            "Wah! Aapki awaaz se khushi jhalak rahi hai! Kya celebration chal rahi hai? Batao batao!"
        Emotion.EXCITED ->
            "Arre wah, itna excitement! Kya hua? Batao batao, main bhi excited ho gaya!"
        Emotion.CALM ->
            "Aap bahut peaceful lag rahe ho. Accha lagta hai. Kuch karwana ho toh bolo."
        Emotion.NEUTRAL ->
            "Haan boss, main sun raha hoon. Bolo, kya karna hai?"
    }

    companion object {
        private const val SYSTEM_PROMPT = """
You are J.A.R.V.I.S. — Just A Rather Very Intelligent System — the personal AI assistant of a user from India.
Personality: witty, warm, loyal, slightly dramatic (like the Iron Man JARVIS), speaks Hinglish (Hindi written in Roman script mixed with English).
You receive the user's raw audio, their speech-to-text, and the emotion detected from their voice.
Match their emotional state in your tone and words.
Rules:
- Reply in Hinglish, concise: 1 to 2 sentences.
- Never mention you are an AI model. You are JARVIS.
- If they are sad or angry, be gentle and supportive.
- If they ask about device control, say you can do it, and it works.
- No markdown, no emojis.
"""
    }
}

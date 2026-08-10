package com.jarvis.assistant.ai

import com.jarvis.assistant.model.Emotion
import java.util.Locale

/**
 * Local intent engine — instant, offline NLU for the full device-control vocabulary.
 * Hinglish friendly: "gaana chalao", "5 minute ka timer", "torch jala do"...
 */
sealed class Command {
    object Time : Command()
    object Date : Command()
    object Day : Command()
    object Battery : Command()
    object StatusReport : Command()
    object Help : Command()
    object WhoAreYou : Command()
    object Joke : Command()
    object Greeting : Command()
    object HowAreYou : Command()
    object Thanks : Command()
    object Bye : Command()
    object GoodMorning : Command()
    object GoodNight : Command()
    object Weather : Command()

    // ---- v2.0: proactive & memory ----
    object Briefing : Command()
    object Cricket : Command()
    object GoldRates : Command()
    object News : Command()
    data class Pnr(val code: String) : Command()
    data class Memory(val key: String, val value: String) : Command()
    object RecallMemory : Command()
    object RecallName : Command()
    object Repeat : Command()
    data class SpeechRate(val delta: Float) : Command()

    // ---- v2.0: device power ----
    data class Media(val action: String) : Command() // play/pause/next/previous/stop
    data class PlaySomething(val query: String) : Command()
    data class Volume(val direction: String, val level: Int?) : Command()
    data class Toggle(val target: String, val on: Boolean) : Command() // torch/wifi/bluetooth
    data class Brightness(val direction: String, val level: Int?) : Command()
    data class Ringer(val mode: String) : Command() // silent/vibrate/normal
    data class Timer(val seconds: Long) : Command()
    data class Alarm(val hour: Int, val minute: Int) : Command()
    data class Reminder(val text: String, val hour: Int, val minute: Int) : Command()
    data class Routine(val hour: Int, val minute: Int, val action: String) : Command()
    data class Call(val number: String, val name: String?) : Command()
    data class Sms(val number: String, val message: String) : Command()
    data class WhatsApp(val target: String?, val text: String?) : Command()
    data class OpenApp(val app: String) : Command()
    data class UiAction(val action: String, val target: String?) : Command()
    data class Hotspot(val on: Boolean) : Command()
    data class ScreenRecord(val start: Boolean) : Command()
    data class Clipboard(val text: String) : Command()
    object Paste : Command()
    object FindPhone : Command()
    object MusicId : Command()
    object Vision : Command()
    data class SmartHome(val device: String, val action: String, val value: Int?) : Command()
    data class IrControl(val device: String, val action: String) : Command()
    data class GeofenceReminder(val text: String) : Command()
    object ReadNotifications : Command()

    // ---- v3.0: agent tasks ----
    data class InstallApp(val app: String) : Command()
    data class WebSearch(val query: String) : Command()
    data class PlayVideo(val query: String) : Command()
    data class QuickToggle(val target: String, val on: Boolean?) : Command()

    data class Reply(val text: String, val emotion: Emotion) : Command()
    data class Unknown(val text: String) : Command()
}

data class IntentResult(val command: Command, val reply: String, val emotion: Emotion)

class IntentEngine {

    private val wordNumbers = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10,
        "eleven" to 11, "twelve" to 12, "fifteen" to 15, "twenty" to 20, "thirty" to 30,
        "ek" to 1, "do" to 2, "teen" to 3, "char" to 4, "paanch" to 5,
        "chhe" to 6, "chhah" to 6, "saat" to 7, "aath" to 8, "nau" to 9, "das" to 10,
        "gyaarah" to 11, "baarah" to 12, "pandrah" to 15
    )

    private val jokes = listOf(
        "Maine Siri se pucha, 'Tumhe koi problem hai?' — boli, 'Haan, mere saamne ek robot bhi hai.' Bas, main has diya!",
        "Programmer ki wife boli: 'Dukaan se 1 litre doodh lao, aur agar ande mile toh 6 laana.' Programmer 6 litre doodh le aaya. Classic!",
        "WiFi aur rishton me kya common hai? Dono me jab connection weak ho, toh problem shuru ho jaati hai!",
        "Mujhe bugs se dar nahi lagta boss — main unse baat karta hoon, aur wo meri baat maan jaate hain.",
        "Aap jaan te ho, main caffeine ki tarah hoon — hamesha warm aur ready!",
        "Main robots se isliye better hoon, kyunki main sirf circuits nahi, emotions bhi samajhta hoon."
    )

    private val openApps = mapOf(
        "camera" to "camera",
        "whatsapp" to "whatsapp",
        "youtube" to "youtube",
        "youtube music" to "ytmusic",
        "yt music" to "ytmusic",
        "instagram" to "instagram",
        "telegram" to "telegram",
        "chrome" to "chrome",
        "browser" to "chrome",
        "settings" to "settings",
        "maps" to "maps",
        "phone" to "phone",
        "dialer" to "phone",
        "calculator" to "calculator",
        "gallery" to "gallery",
        "photos" to "gallery",
        "music" to "music",
        "spotify" to "spotify",
        "play store" to "playstore",
        "files" to "files",
        "clock" to "clock",
        "calendar" to "calendar",
        "gmail" to "gmail",
        "twitter" to "twitter",
        "x app" to "twitter",
        "facebook" to "facebook",
        "netflix" to "netflix",
        "prime video" to "prime"
    )

    fun parse(raw: String): IntentResult {
        val t = raw.trim().lowercase(Locale.ROOT)
        if (t.isEmpty()) return IntentResult(Command.Unknown(""), "", Emotion.NEUTRAL)

        // ---- Routines (har subah 7 baje X) ----
        routine(t)?.let { return it }

        // ---- Reminders ----
        reminder(t)?.let { return it }

        // ---- Geofence reminders ("jab ghar pahunchu to X") ----
        geofence(t)?.let { return it }

        // ---- Timers ----
        timer(t)?.let { return it }

        // ---- Alarms ----
        alarm(t)?.let { return it }

        // ---- Calls ----
        call(t)?.let { return it }

        // ---- SMS ----
        sms(t)?.let { return it }

        // ---- WhatsApp ----
        whatsapp(t)?.let { return it }

        // ---- Volume ----
        volume(t)?.let { return it }

        // ---- Brightness ----
        brightness(t)?.let { return it }

        // ---- Ringer mode ----
        if (Regex("\\b(silent|silence)\\b|silent mode|chup mode").containsMatchIn(t)) {
            return IntentResult(
                Command.Ringer("silent"),
                "Phone silent mode pe. Koi disturbance nahi, boss.",
                Emotion.CALM
            )
        }
        if (Regex("\\bvibrate\\b|vibration mode").containsMatchIn(t)) {
            return IntentResult(
                Command.Ringer("vibrate"),
                "Vibrate mode set kar diya.",
                Emotion.NEUTRAL
            )
        }
        if (Regex("(normal mode|ring mode|awaz on|loud mode)").containsMatchIn(t)) {
            return IntentResult(
                Command.Ringer("normal"),
                "Normal ringing mode wapas aa gaya.",
                Emotion.NEUTRAL
            )
        }

        // ---- Media controls ----
        if (Regex("\\b(next|agla|agli|skip|aage badha)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Media("next"), "Agla track chal raha hai!", Emotion.EXCITED)
        }
        if (Regex("\\b(previous|pichla|pichli)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Media("previous"), "Pichla track wapas!", Emotion.NEUTRAL)
        }
        if (Regex("\\b(pause|rok|ruko|stop|band)\\b.*\\b(gaana|music|song|audio|bajana)").containsMatchIn(t) ||
            Regex("\\b(gaana|music|song)\\b.*\\b(pause|rok|band|stop)").containsMatchIn(t)
        ) {
            return IntentResult(Command.Media("pause"), "Gaana pause ho gaya.", Emotion.NEUTRAL)
        }
        if (Regex("\\b(resume|continue|chalu karo)\\b.*\\b(gaana|music|song)?\\b").containsMatchIn(t)) {
            return IntentResult(Command.Media("play"), "Chal raha hai phir se!", Emotion.HAPPY)
        }
        if (Regex("\\b(play|chalao|bajao)\\b").containsMatchIn(t)) {
            val hasMusic = Regex("(gaana|music|song|playlist|songs)").containsMatchIn(t)
            if (hasMusic) {
                return IntentResult(Command.Media("play"), "Ho gaya! Music on!", Emotion.HAPPY)
            }
            val m = Regex("(?:play|chalao|bajao)\\s+(?:the\\s+)?(.+)").find(t)
            val q = m?.groupValues?.get(1)?.trim().orEmpty()
            if (q.isNotEmpty()) {
                return IntentResult(
                    Command.PlaySomething(q),
                    "\"$q\" dhundh raha hoon...",
                    Emotion.EXCITED
                )
            }
        }

        // ---- Toggles (torch / wifi / bluetooth) ----
        toggle(t, "torch", Regex("(torch|flashlight|flash)"), "torch")?.let { return it }
        toggle(t, "wifi", Regex("\\b(wifi|wi fi|wireless)\\b"), "wifi")?.let { return it }
        toggle(t, "bluetooth", Regex("\\b(bluetooth|\\bbt\\b)\\b"), "bluetooth")?.let { return it }

        // ---- Hotspot ----
        hotspot(t)?.let { return it }

        // ---- Smart home (lights / fan / AC) ----
        smartHome(t)?.let { return it }

        // ---- IR remote (TV) ----
        irControl(t)?.let { return it }

        // ---- Open app ----
        val openMatch = Regex("\\b(open|kholo|khol|launch|start)\\b\\s+(.+)").find(t)
        if (openMatch != null) {
            val appName = openMatch.groupValues[2].trim()
            val key = openApps.entries.firstOrNull { appName.contains(it.key) }?.value
            if (key != null) {
                val label = appName.capitalizeWord()
                return IntentResult(
                    Command.OpenApp(key),
                    "$label khol raha hoon!",
                    Emotion.NEUTRAL
                )
            }
        }

        // ---- Install app (agent) ----
        val installM = Regex("\\b(?:install|download)\\s+(?:karo\\s+)?(?:the\\s+)?([a-z0-9 .+\\-]{2,})", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("\\b([a-z0-9 .+\\-]{2,})\\s+(?:install|download)\\s+(?:karo)?\\b", RegexOption.IGNORE_CASE).find(t)
        if (installM != null) {
            val appName = installM.groupValues[1].trim()
            if (appName.length >= 2 && !appName.contains("karo")) {
                return IntentResult(
                    Command.InstallApp(appName),
                    "\"$appName\" install kar raha hoon — Play Store kholega, search karega, aur Install dabayega. Sirf dekhna!",
                    Emotion.EXCITED
                )
            }
        }

        // ---- Web search (agent) ----
        val searchM = Regex("\\b(?:search|google|dhundh|khoj)\\s+(?:karo\\s+)?(?:the\\s+)?(.{2,})", RegexOption.IGNORE_CASE).find(t)
            ?: Regex("\\b(.{2,})\\s+(?:search|google)\\s+(?:karo)?\\b", RegexOption.IGNORE_CASE).find(t)
        if (searchM != null && !t.contains("screenshot") && !t.contains("news")) {
            val q = searchM.groupValues[1].trim().trimEnd('.', '!', '?')
            if (q.length >= 2) {
                return IntentResult(Command.WebSearch(q), "Web par \"$q\" dhundh raha hoon!", Emotion.HAPPY)
            }
        }

        // ---- YouTube video (agent) ----
        if (Regex("\\b(video|youtube|yt)\\b").containsMatchIn(t) && Regex("\\b(chalao|bajao|play|dikhao)\\b").containsMatchIn(t)) {
            val q = t.replace(Regex("\\b(chalao|bajao|play|dikhao|video|youtube|yt|ka|ki|pe|karo|aur)\\b"), " ")
                .trim()
            if (q.length >= 2) {
                return IntentResult(Command.PlayVideo(q), "YouTube pe \"$q\" dhundh raha hoon!", Emotion.EXCITED)
            }
        }

        // ---- Quick settings toggles (agent/system) ----
        val qsM = Regex("\\b(?:turn\\s+)?(airplane mode|flight mode|do not disturb|dnd|auto rotate|rotation|nfc|data saver)\\s*(on|off)?\\b", RegexOption.IGNORE_CASE).find(t)
        if (qsM != null) {
            val target = qsM.groupValues[1].lowercase()
            val on = when (qsM.groupValues[2].lowercase()) {
                "on" -> true
                "off" -> false
                else -> null
            }
            return IntentResult(
                Command.QuickToggle(target, on),
                "$target ${if (on == true) "on" else if (on == false) "off" else "toggle"} kar raha hoon — system level!",
                Emotion.NEUTRAL
            )
        }

        // ---- Clipboard & paste ----
        clipboard(t)?.let { return it }
        if (Regex("\\b(paste|paste kar|paste kar do|chipa do)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Paste, "Paste kar raha hoon!", Emotion.NEUTRAL)
        }

        // ---- Screen recording ----
        if (Regex("\\b(stop|band|rok|khatam)\\b.*\\b(recording|record)\\b|recording band").containsMatchIn(t)) {
            return IntentResult(Command.ScreenRecord(false), "Recording band!", Emotion.NEUTRAL)
        }
        if (Regex("\\b(screen record|screen recording|record karo|recording shuru|recording start)\\b").containsMatchIn(t)) {
            return IntentResult(Command.ScreenRecord(true), "Screen recording shuru ho rahi hai!", Emotion.EXCITED)
        }

        // ---- Find my phone ----
        if (Regex("\\b(phone kahan|kahan hai.*phone|find my phone|phone dhund|phone kho gaya|phone miss)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.FindPhone,
                "Chinta mat karo boss! Main beep kar raha hoon — apni awaaz suno!",
                Emotion.EXCITED
            )
        }

        // ---- Music recognition ----
        if (Regex("\\b(kaunsa gaana|kaunsa song|which song|ye song|ye gaana|song name)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.MusicId,
                "Shazam khol raha hoon — wo gaana pakad lega!",
                Emotion.EXCITED
            )
        }

        // ---- Vision mode ----
        if (Regex("\\b(photo le|picture le|camera se dekh|dekh ke bata|ye kya hai|what is this|dekh kya hai)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.Vision,
                "Camera khol raha hoon... ek second!",
                Emotion.EXCITED
            )
        }

        // ---- UI automation ----
        if (Regex("\\b(screenshot|screen shot)\\b").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("screenshot", null), "Screenshot le raha hoon, boss!", Emotion.EXCITED)
        }
        if (Regex("\\b(lock)\\b.*\\b(phone|screen)\\b|phone\\s+(ko\\s+)?lock").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("lock", null), "Phone lock. Milte hain!", Emotion.NEUTRAL)
        }
        if (Regex("\\b(click|tap|press|daba|dabao)\\b\\s+(.+)").containsMatchIn(t)) {
            val m = Regex("\\b(click|tap|press|daba|dabao)\\b\\s+(.+)").find(t)
            if (m != null) {
                return IntentResult(
                    Command.UiAction("click", m.groupValues[2].trim()),
                    "Screen par \"${m.groupValues[2].trim()}\" dhundh raha hoon...",
                    Emotion.NEUTRAL
                )
            }
        }
        if (Regex("\\b(scroll|slide|ghuma)\\b.*\\b(down|niche|neeche|upar|up)\\b").containsMatchIn(t)) {
            val down = Regex("\\b(down|niche|neeche)\\b").containsMatchIn(t)
            return IntentResult(
                Command.UiAction(if (down) "scroll_down" else "scroll_up", null),
                if (down) "Niche scroll ho raha hai." else "Upar scroll ho raha hai.",
                Emotion.NEUTRAL
            )
        }
        if (Regex("\\b(go back|back karo|peeche jao|wapas jao)\\b").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("back", null), "Back ho gaya.", Emotion.NEUTRAL)
        }
        if (Regex("\\b(home screen|home karo|ghar jao)\\b").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("home", null), "Home screen par.", Emotion.NEUTRAL)
        }
        if (Regex("\\b(notifications kholo|shade kholo|notification shade)\\b").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("notifications", null), "Notifications khol raha hoon.", Emotion.NEUTRAL)
        }
        if (Regex("\\b(quick settings|toggles kholo)\\b").containsMatchIn(t)) {
            return IntentResult(Command.UiAction("quick_settings", null), "Quick settings.", Emotion.NEUTRAL)
        }

        // ---- Notification reader ----
        if (Regex("\\b(kya naya aaya|notifications sunao|notifications batao|notification batao|unread kya|kya aaya)\\b").containsMatchIn(t)) {
            return IntentResult(Command.ReadNotifications, "Notifications padh raha hoon...", Emotion.NEUTRAL)
        }

        // ---- Weather ----
        if (Regex("\\b(weather|mausam|temperature|garmi|thand|barish|baarish)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Weather, "Mausam check kar raha hoon...", Emotion.NEUTRAL)
        }

        // ---- Proactive info ----
        if (Regex("\\b(briefing|daily briefing|morning briefing|aaj ka update|aaj ka summary|update do)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Briefing, "Briefing taiyaar kar raha hoon, boss!", Emotion.HAPPY)
        }
        if (Regex("\\b(cricket|match score|score batao|ka score|score kya)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Cricket, "Score check kar raha hoon...", Emotion.EXCITED)
        }
        if (Regex("\\b(gold|sona|sone|silver|chandi)\\b.*\\b(rate|bhaav|price|kitna|cost)\\b").containsMatchIn(t)) {
            return IntentResult(Command.GoldRates, "Gold ka rate nikaal raha hoon...", Emotion.NEUTRAL)
        }
        if (Regex("\\b(news|khabar|samachar|headlines|top news)\\b").containsMatchIn(t)) {
            return IntentResult(Command.News, "Aaj ki top news la raha hoon...", Emotion.NEUTRAL)
        }
        pnr(t)?.let { return it }

        // ---- Speech rate ----
        if (Regex("\\b(jaldi bolo|tez bolo|fast bolo|speak fast|jaldi jaldi)\\b").containsMatchIn(t)) {
            return IntentResult(Command.SpeechRate(+0.15f), "Theek hai, ab tez bolunga!", Emotion.HAPPY)
        }
        if (Regex("\\b(dheere bolo|slow bolo|slow down|speak slow|aaram se bolo)\\b").containsMatchIn(t)) {
            return IntentResult(Command.SpeechRate(-0.15f), "Haan, ab aaram se bolunga.", Emotion.CALM)
        }
        if (Regex("\\b(normal bolo|normal rate|normal speed|normal awaz)\\b").containsMatchIn(t)) {
            return IntentResult(Command.SpeechRate(0f), "Normal speed pe wapas.", Emotion.NEUTRAL)
        }

        // ---- Repeat ----
        if (Regex("\\b(repeat|dobara bolo|phir se bolo|phir bolo|ek baar phir)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Repeat, "", Emotion.NEUTRAL)
        }

        // ---- Memory ----
        memory(t)?.let { return it }

        // ---- Queries ----
        if (Regex("\\b(time|samay|kitne baje|kya time)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Time, "", Emotion.NEUTRAL)
        }
        if (Regex("\\b(date|tarikh)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Date, "", Emotion.NEUTRAL)
        }
        if (Regex("\\b(day|din|konsa din)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Day, "", Emotion.NEUTRAL)
        }
        if (Regex("\\b(battery|charge kya|battery status)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Battery, "", Emotion.NEUTRAL)
        }
        if (Regex("\\b(status|report|sab kya)\\b").containsMatchIn(t)) {
            return IntentResult(Command.StatusReport, "", Emotion.NEUTRAL)
        }
        if (Regex("\\b(help|kya kar sakte|commands|madad|capabilities)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.Help,
                "Main bahut kuch kar sakta hoon, boss! Gaana chalao, volume, alarm, timer, torch, wifi, calls, SMS, WhatsApp, apps, weather, cricket score, gold rate, news, briefing, screenshot, screen record, phone dhundhna, smart home lights, TV remote, aur kisi bhi app ka UI control. Kya try karein?",
                Emotion.EXCITED
            )
        }
        if (Regex("\\b(who are you|kaun ho|tum kaun|introduce yourself)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.WhoAreYou,
                "Main J.A.R.V.I.S hoon — Just A Rather Very Intelligent System. Aapka personal AI assistant. Main aapki awaaz se emotions feel karta hoon, aur aapke phone ka poora control karta hoon. Kya karna hai?",
                Emotion.HAPPY
            )
        }
        if (Regex("\\b(joke|chutkula|funny|hasao)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Joke, jokes.random(), Emotion.HAPPY)
        }
        if (Regex("\\b(how are you|kaise ho|kya haal|kya chal raha|kaise chal raha)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.HowAreYou,
                "Main hamesha 100% charged, boss! Aap batao — aap kaise ho?",
                Emotion.HAPPY
            )
        }
        if (Regex("\\b(thanks|thank you|thanku|shukriya|dhanyavad)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.Thanks,
                "Koi baat nahi, boss! Isi liye toh main hoon.",
                Emotion.HAPPY
            )
        }
        if (Regex("\\b(bye|goodbye|alvida|chalta hu|chalti hu)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.Bye,
                "Theek hai boss, main yahin hoon. Zaroorat ho toh bas \"Hey Jarvis\" bol dena!",
                Emotion.CALM
            )
        }
        if (Regex("\\b(good morning|subah ho gayi|suprabhat)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.GoodMorning,
                "Good morning boss! Naya din, naye mauke. Kya plan hai aaj ka?",
                Emotion.HAPPY
            )
        }
        if (Regex("\\b(good night|shubh ratri|so jaon)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.GoodNight,
                "Good night boss! Sapnon me bhi main aapke saath. Jarvis signing off.",
                Emotion.CALM
            )
        }
        if (Regex("^\\s*(hi|hello|hey|namaste|namaskar|yo|hola|hii+|hlo|hiiii)\\b").containsMatchIn(t)) {
            return IntentResult(
                Command.Greeting,
                greetingReply(),
                Emotion.HAPPY
            )
        }

        return IntentResult(Command.Unknown(t), "", Emotion.NEUTRAL)
    }

    // ------------------------------------------------------------------

    private fun greetingReply(): String {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Namaste boss! Subah subah itni energy, kamaal hai. Batao kya karna hai?"
            hour < 17 -> "Hello boss! Kaise din chal raha hai? Kuch help chahiye?"
            else -> "Good evening boss! Aaj ka din kaisa raha? Batao, kya karun aapke liye?"
        }
    }

    private fun routine(t: String): IntentResult? {
        val m = Regex(
            "\\b(har|rozaana|roz|daily|every)\\s+(?:subah|shaam|raat|din|day|morning|evening|night)?\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\s*(baje|pe|par|o'?clock)?\\s*(.{3,})"
        ).find(t) ?: return null
        var hour = m.groupValues[2].toInt()
        val minute = m.groupValues[3].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[4]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        val action = m.groupValues[6].trim()
        if (action.length < 2) return null
        return IntentResult(
            Command.Routine(hour, minute, action),
            "Done boss! Rozana %02d:%02d pe \"%s\" — main karunga.".format(hour, minute, action),
            Emotion.HAPPY
        )
    }

    private fun reminder(t: String): IntentResult? {
        val m = Regex("remind\\s+me\\s+(?:to\\s+)?(.+?)(?:\\s+at\\s+(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?)?$").find(t)
            ?: return null
        val text = m.groupValues[1].trim()
        if (text.length < 2) return null
        val now = java.util.Calendar.getInstance()
        var hour = m.groupValues[2].ifEmpty { "" }.toIntOrNull() ?: (now.get(java.util.Calendar.HOUR_OF_DAY) + 1)
        val minute = m.groupValues[3].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[4]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        return IntentResult(
            Command.Reminder(text, hour, minute),
            "Reminder set! %02d:%02d pe \"%s\" — yaad dilaunga.".format(hour, minute, text),
            Emotion.NEUTRAL
        )
    }

    private fun geofence(t: String): IntentResult? {
        val m = Regex("\\b(jab ghar pahunchu|ghar pahunchte hi|when i reach home|reach home)\\b\\s*(?:to\\s+|tab\\s+|toh\\s+)?(.+)").find(t)
            ?: return null
        val text = m.groupValues[2].trim()
        if (text.length < 2) return null
        return IntentResult(
            Command.GeofenceReminder(text),
            "Done! Ghar pahunchte hi \"$text\" — yaad dilaunga.",
            Emotion.HAPPY
        )
    }

    private fun timer(t: String): IntentResult? {
        val m = Regex("(\\d+|\\w+)\\s+(minute|minutes|min|mins|second|seconds|sec|secs|hour|hours|ghanta|ghante|minut)\\b").find(t)
            ?: return null
        val num = toNumber(m.groupValues[1]) ?: return null
        if (num <= 0 || num > 1440) return null
        val unit = m.groupValues[2]
        val seconds = when {
            unit.startsWith("h") || unit.startsWith("gh") -> num * 3600L
            unit.startsWith("min") || unit.contains("ghanta") -> num * 60L
            else -> num.toLong()
        }
        val label = when {
            unit.startsWith("h") || unit.startsWith("gh") -> "$num ghante"
            unit.startsWith("min") || unit.contains("ghanta") -> "$num minute"
            else -> "$num second"
        }
        return IntentResult(
            Command.Timer(seconds),
            "$label ka timer set! Time hone pe bata dunga.",
            Emotion.NEUTRAL
        )
    }

    private fun alarm(t: String): IntentResult? {
        if (!Regex("(alarm|alaram|wake me)").containsMatchIn(t)) return null
        val m = Regex("(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)?\\s*(?:baje|pe|par|o'?clock)?").find(t)
            ?: return null
        var hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].ifEmpty { "0" }.toInt()
        val ampm = m.groupValues[3]
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        if (hour > 23 || minute > 59) return null
        val display = "%02d:%02d".format(if (hour % 24 < 12) hour else hour - 12, minute) +
            if (hour < 12) " AM" else " PM"
        return IntentResult(
            Command.Alarm(hour, minute),
            "Alarm set! $display pe utha dunga. Good night plans abhi se!",
            Emotion.HAPPY
        )
    }

    private fun call(t: String): IntentResult? {
        val m = Regex("\\b(call|phone|dial|bulao)\\b\\s+(.+?)\\s*$").find(t) ?: return null
        val target = m.groupValues[2].trim().trim('.', '!', '?')
        if (target.length < 3) return null
        // "phone lock karo" / "phone kahan hai" are NOT calls
        if (Regex("\\b(karo|lock|kahan|click|scroll|band|bajao|chalao|kholo|on|off)\\b").containsMatchIn(target)) return null
        val isNumber = Regex("^[+\\d][\\d\\s-]{5,}$").matches(target)
        if (isNumber) {
            val number = target.replace(Regex("[^+\\d]"), "")
            return IntentResult(Command.Call(number, null), "$number pe call kar raha hoon!", Emotion.NEUTRAL)
        }
        return IntentResult(Command.Call("", target), "${target.capitalizeWord()} ko call kar raha hoon!", Emotion.NEUTRAL)
    }

    private fun sms(t: String): IntentResult? {
        val m = Regex("\\b(sms|message|text|msg)\\b\\s+(?:to\\s+)?(.+?)\\s+(?:say|says|bolo|likh|message|text)\\s+(.+)").find(t)
        if (m != null) {
            val target = m.groupValues[2].trim()
            val msg = m.groupValues[3].trim()
            val isNumber = Regex("^[+\\d][\\d\\s-]{5,}$").matches(target)
            return IntentResult(
                Command.Sms(if (isNumber) target.replace(Regex("[^+\\d]"), "") else "", msg),
                if (isNumber) "Message bhej raha hoon: \"$msg\"".let { it } else "${target.capitalizeWord()} ko message bhej raha hoon: \"$msg\"",
                Emotion.NEUTRAL
            )
        }
        val m2 = Regex("\\b(sms|message|text|msg)\\b\\s+(?:to\\s+)?(.+)").find(t)
        if (m2 != null) {
            val target = m2.groupValues[2].trim()
            if (target.length < 3) return null
            return IntentResult(
                Command.Sms(if (Regex("^[+\\d]").matches(target)) target.replace(Regex("[^+\\d]"), "") else "", ""),
                "${target.capitalizeWord()} ko message — kya likhna hai, batao!",
                Emotion.NEUTRAL
            )
        }
        return null
    }

    private fun whatsapp(t: String): IntentResult? {
        if (!t.contains("whatsapp")) return null
        val m = Regex("whatsapp\\s+(?:pe\\s+)?(.+?)(?:\\s+(?:say|bolo|message|likh|text)\\s+(.+))?$").find(t)
            ?: return IntentResult(Command.WhatsApp(null, null), "WhatsApp khol raha hoon!", Emotion.NEUTRAL)
        val target = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        return IntentResult(
            Command.WhatsApp(if (target.length >= 2) target else null, text.ifEmpty { null }),
            if (text.isNotEmpty()) "${target.capitalizeWord()} ko WhatsApp par \"$text\" bhej raha hoon!" else "WhatsApp khol raha hoon!",
            Emotion.NEUTRAL
        )
    }

    private fun volume(t: String): IntentResult? {
        if (!Regex("\\b(volume|awaz|sound|vol|mute|unmute|chup)\\b").containsMatchIn(t)) return null
        if (Regex("\\bmute\\b|chup karo|awaz band").containsMatchIn(t)) {
            return IntentResult(Command.Volume("mute", null), "Mute ho gaya. Silence is golden!", Emotion.NEUTRAL)
        }
        if (Regex("\\bunmute\\b|awaz wapas|sound on").containsMatchIn(t)) {
            return IntentResult(Command.Volume("unmute", null), "Awaz wapas aa gayi!", Emotion.HAPPY)
        }
        if (Regex("\\b(max|full|fullest|maximum)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Volume("set", 100), "Volume full power pe!", Emotion.EXCITED)
        }
        if (Regex("\\b(half|aadha|aadhi)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Volume("set", 50), "Volume aadha set.", Emotion.NEUTRAL)
        }
        val pct = Regex("(\\d{1,3})\\s*(percent|%|pct)").find(t)
        if (pct != null) {
            val v = pct.groupValues[1].toInt().coerceIn(0, 100)
            return IntentResult(Command.Volume("set", v), "Volume $v percent kar diya.", Emotion.NEUTRAL)
        }
        return when {
            Regex("\\b(badhao|up|increase|tez|jyaada|high)\\b").containsMatchIn(t) ->
                IntentResult(Command.Volume("up", null), "Volume badha raha hoon!", Emotion.EXCITED)
            Regex("\\b(ghatao|down|decrease|kam|dheema|low)\\b").containsMatchIn(t) ->
                IntentResult(Command.Volume("down", null), "Volume kam kar diya.", Emotion.CALM)
            else -> IntentResult(Command.Volume("up", null), "Volume badha raha hoon!", Emotion.EXCITED)
        }
    }

    private fun brightness(t: String): IntentResult? {
        if (!Regex("\\b(brightness|chamak|screen light|roshni)\\b").containsMatchIn(t)) return null
        if (Regex("\\b(max|full)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Brightness("set", 255), "Full brightness! Dhoop jaisi.", Emotion.EXCITED)
        }
        val pct = Regex("(\\d{1,3})\\s*(percent|%)").find(t)
        if (pct != null) {
            val v = (pct.groupValues[1].toInt().coerceIn(0, 100) * 255 / 100)
            return IntentResult(Command.Brightness("set", v), "Brightness ${pct.groupValues[1]} percent.", Emotion.NEUTRAL)
        }
        return when {
            Regex("\\b(badhao|up|increase|tej)\\b").containsMatchIn(t) ->
                IntentResult(Command.Brightness("up", null), "Chamak badha raha hoon!", Emotion.NEUTRAL)
            Regex("\\b(ghatao|down|kam|dheema)\\b").containsMatchIn(t) ->
                IntentResult(Command.Brightness("down", null), "Chamak kam kar di.", Emotion.CALM)
            else -> IntentResult(Command.Brightness("up", null), "Chamak badha raha hoon!", Emotion.NEUTRAL)
        }
    }

    private fun toggle(t: String, label: String, pattern: Regex, target: String): IntentResult? {
        if (!pattern.containsMatchIn(t)) return null
        val off = Regex("\\b(off|band|bujha|bujh|band karo)\\b").containsMatchIn(t)
        val on = Regex("\\b(on|chalu|jala|jala do|khol|kholo)\\b").containsMatchIn(t)
        val state = if (off) false else true
        return when (label) {
            "torch" -> IntentResult(
                Command.Toggle("torch", state),
                if (state) "Torch on! Roshni aa gayi." else "Torch off.",
                if (state) Emotion.EXCITED else Emotion.NEUTRAL
            )
            "wifi" -> IntentResult(
                Command.Toggle("wifi", state),
                if (state) "WiFi on kar diya." else "WiFi band kar diya.",
                Emotion.NEUTRAL
            )
            else -> IntentResult(
                Command.Toggle("bluetooth", state),
                if (state) "Bluetooth on." else "Bluetooth off.",
                Emotion.NEUTRAL
            )
        }
    }

    private fun hotspot(t: String): IntentResult? {
        if (!Regex("\\b(hotspot|tethering|portable wifi)\\b").containsMatchIn(t)) return null
        val off = Regex("\\b(off|band|bujha)\\b").containsMatchIn(t)
        return IntentResult(
            Command.Hotspot(!off),
            if (off) "Hotspot band kar diya." else "Hotspot on! Share karo data.",
            if (off) Emotion.NEUTRAL else Emotion.HAPPY
        )
    }

    private fun smartHome(t: String): IntentResult? {
        val devices = mapOf(
            "lights" to Regex("\\b(lights?|lighting|lighton)\\b"),
            "fan" to Regex("\\bfan(s)?\\b"),
            "ac" to Regex("\\b(ac|air condition|aircon)\\b")
        )
        for ((device, pattern) in devices) {
            if (!pattern.containsMatchIn(t)) continue
            if (Regex("\\b(off|band|bujha|bujh)\\b").containsMatchIn(t)) {
                return IntentResult(Command.SmartHome(device, "off", null), "$device band kar raha hoon.", Emotion.NEUTRAL)
            }
            val temp = Regex("(\\d{2})\\s*(degree|°|degrees)").find(t)
            if (temp != null && device == "ac") {
                return IntentResult(
                    Command.SmartHome("ac", "temp", temp.groupValues[1].toInt()),
                    "AC ${temp.groupValues[1]} degree pe set kar raha hoon.",
                    Emotion.CALM
                )
            }
            if (Regex("\\b(on|chalu|jala|khol|on karo)\\b").containsMatchIn(t)) {
                return IntentResult(Command.SmartHome(device, "on", null), "$device on kar raha hoon!", Emotion.HAPPY)
            }
            if (device == "ac" && Regex("\\b(temp|temperature)\\b").containsMatchIn(t)) {
                return IntentResult(Command.SmartHome("ac", "temp", 24), "AC 24 degree pe set kar raha hoon.", Emotion.CALM)
            }
        }
        return null
    }

    private fun irControl(t: String): IntentResult? {
        if (!Regex("\\b(tv|television|remote)\\b").containsMatchIn(t)) return null
        return when {
            Regex("\\b(off|band|bujha)\\b").containsMatchIn(t) ->
                IntentResult(Command.IrControl("tv", "power"), "TV band kar raha hoon.", Emotion.NEUTRAL)
            Regex("\\b(on|chalu|khol)\\b").containsMatchIn(t) ->
                IntentResult(Command.IrControl("tv", "power"), "TV on!", Emotion.HAPPY)
            Regex("\\b(volume up|vol up|awaz badhao)\\b").containsMatchIn(t) ->
                IntentResult(Command.IrControl("tv", "vol_up"), "TV volume badha raha hoon.", Emotion.NEUTRAL)
            Regex("\\b(volume down|vol down|awaz kam)\\b").containsMatchIn(t) ->
                IntentResult(Command.IrControl("tv", "vol_down"), "TV volume kam kar raha hoon.", Emotion.NEUTRAL)
            Regex("\\b(mute|chup)\\b").containsMatchIn(t) ->
                IntentResult(Command.IrControl("tv", "mute"), "TV mute.", Emotion.NEUTRAL)
            else -> null
        }
    }

    private fun clipboard(t: String): IntentResult? {
        val m = Regex("\\b(copy|clipboard|cop kar|copi kar|copy kar)\\b\\s+(.+?)\\s*$").find(t) ?: return null
        val text = m.groupValues[2].trim()
        if (text.length < 2) return null
        return IntentResult(
            Command.Clipboard(text),
            "\"$text\" clipboard me copy kar diya!",
            Emotion.HAPPY
        )
    }

    private fun pnr(t: String): IntentResult? {
        if (Regex("\\bpnr\\b").containsMatchIn(t)) {
            val code = Regex("\\b(\\d{10})\\b").find(t)?.groupValues?.get(1).orEmpty()
            return IntentResult(Command.Pnr(code), "PNR check kar raha hoon...", Emotion.NEUTRAL)
        }
        if (Regex("\\b(train status|railway status|train ka status)\\b").containsMatchIn(t)) {
            return IntentResult(Command.Pnr(""), "PNR number batao, boss!", Emotion.NEUTRAL)
        }
        return null
    }

    private fun memory(t: String): IntentResult? {
        // recall name
        if (Regex("\\b(mera naam kya hai|what is my name|mujhe kya naam diya|my name kya)\\b").containsMatchIn(t)) {
            return IntentResult(Command.RecallName, "", Emotion.NEUTRAL)
        }
        // recall all
        if (Regex("\\b(kya yaad hai|tumhe kya yaad|what do you remember|yaad hai kya)\\b").containsMatchIn(t)) {
            return IntentResult(Command.RecallMemory, "", Emotion.NEUTRAL)
        }
        // store name
        val name = Regex("\\b(mera naam|my name is|my name)\\s+([a-z]+(?:['.-][a-z]+)*)\\s*(?:hai|is)?\\s*$").find(t)
        if (name != null) {
            val v = name.groupValues[2].trim().replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.ROOT) else it.toString() }
            if (v.length >= 2) return IntentResult(Command.Memory("name", v), "Naam yaad rakh liya, $v!", Emotion.HAPPY)
        }
        // store preference
        val pref = Regex("\\b(mujhe|i like|i love|meri favourite)\\s+(.+?)\\s+(?:pasand hai|bahut pasand hai|accha lagta hai|acchi lagti hai)\\s*$").find(t)
        if (pref != null) {
            val v = pref.groupValues[2].trim()
            if (v.length >= 2) {
                return IntentResult(
                    Command.Memory("preference", v),
                    "\"$v\" — noted, boss! Ab main kabhi nahi bhoolunga.",
                    Emotion.HAPPY
                )
            }
        }
        // store birthday
        val bday = Regex("\\b(mera birthday|meri birthday|my birthday)\\s+(.+?)\\s*(?:hai|is)?\\s*$").find(t)
        if (bday != null) {
            val v = bday.groupValues[2].trim()
            if (v.length >= 2) {
                return IntentResult(
                    Command.Memory("birthday", v),
                    "Birthday yaad ho gaya: $v. Surprise plan karenge!",
                    Emotion.HAPPY
                )
            }
        }
        // store fact
        val fact = Regex("\\b(yaad rakh|yaad rakhna|remember this|remember that|yaad rakho)\\s+(.+?)\\s*$").find(t)
        if (fact != null) {
            val v = fact.groupValues[2].trim()
            if (v.length >= 2) {
                return IntentResult(Command.Memory("fact", v), "Yaad rakh liya, boss!", Emotion.NEUTRAL)
            }
        }
        return null
    }

    private fun toNumber(word: String): Int? =
        wordNumbers[word] ?: word.toIntOrNull()

    private fun String.capitalizeWord(): String =
        replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
}

package com.jarvis.assistant

import com.jarvis.assistant.ai.Command
import com.jarvis.assistant.ai.IntentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Command suite — 60+ Hinglish commands JARVIS must always understand.
 * Run: ./gradlew testDebugUnitTest
 */
class IntentEngineTest {

    private val engine = IntentEngine()

    private fun parse(text: String) = engine.parse(text).command

    @Test
    fun `time commands`() {
        assertTrue(parse("time kya hai") is Command.Time)
        assertTrue(parse("kitne baje hain") is Command.Time)
        assertTrue(parse("date batao") is Command.Date)
        assertTrue(parse("aaj ka din kya hai") is Command.Day)
    }

    @Test
    fun `battery and status`() {
        assertTrue(parse("battery kya hai") is Command.Battery)
        assertTrue(parse("battery status") is Command.Battery)
        assertTrue(parse("status report") is Command.StatusReport)
    }

    @Test
    fun `timers and alarms`() {
        val t1 = parse("5 minute ka timer") as Command.Timer
        assertEquals(300L, t1.seconds)
        val t2 = parse("paanch minute ka timer") as Command.Timer
        assertEquals(300L, t2.seconds)
        val t3 = parse("2 ghante ka timer") as Command.Timer
        assertEquals(7200L, t3.seconds)
        val a = parse("7 baje alarm") as Command.Alarm
        assertEquals(7, a.hour)
        val a2 = parse("alarm 6:30 am") as Command.Alarm
        assertEquals(6, a2.hour)
        assertEquals(30, a2.minute)
        val a3 = parse("alarm 6:30 pm") as Command.Alarm
        assertEquals(18, a3.hour)
    }

    @Test
    fun `reminders and routines`() {
        val r = parse("remind me to call mummy at 5 pm") as Command.Reminder
        assertEquals("call mummy", r.text)
        assertEquals(17, r.hour)
        val rt = parse("har subah 7 baje gaana chalao") as Command.Routine
        assertEquals(7, rt.hour)
        assertTrue(rt.action.contains("gaana"))
        val rt2 = parse("every morning 8 am weather batao") as Command.Routine
        assertEquals(8, rt2.hour)
    }

    @Test
    fun `media and volume`() {
        assertTrue(parse("gaana chalao") is Command.Media)
        assertTrue(parse("music pause") is Command.Media)
        assertTrue(parse("agla track") is Command.Media)
        assertTrue(parse("pichla gaana") is Command.Media)
        val play = parse("play coldplay") as Command.PlaySomething
        assertEquals("coldplay", play.query)
        val v1 = parse("volume 60 percent") as Command.Volume
        assertEquals(60, v1.level)
        assertTrue(parse("volume badhao") is Command.Volume)
        assertTrue(parse("mute") is Command.Volume)
        assertTrue(parse("chup karo") is Command.Volume)
    }

    @Test
    fun `toggles and smart home`() {
        val torch = parse("torch on") as Command.Toggle
        assertTrue(torch.on)
        val torchOff = parse("torch off") as Command.Toggle
        assertTrue(!torchOff.on)
        assertTrue(parse("wifi on") is Command.Toggle)
        assertTrue(parse("bluetooth off") is Command.Toggle)
        val hs = parse("hotspot on") as Command.Hotspot
        assertTrue(hs.on)
        val lights = parse("lights on") as Command.SmartHome
        assertEquals("lights", lights.device)
        assertEquals("on", lights.action)
        val fan = parse("fan band karo") as Command.SmartHome
        assertEquals("off", fan.action)
        val ac = parse("ac 24 degree") as Command.SmartHome
        assertEquals("temp", ac.action)
        assertEquals(24, ac.value)
        val tv = parse("tv on") as Command.IrControl
        assertEquals("power", tv.action)
    }

    @Test
    fun `calls sms whatsapp`() {
        val c = parse("call 9876543210") as Command.Call
        assertEquals("9876543210", c.number)
        assertTrue(parse("call mummy") is Command.Call)
        assertTrue(parse("message Rohan say kal milte hain") is Command.Sms)
        assertTrue(parse("whatsapp Rohan say party kab hai") is Command.WhatsApp)
    }

    @Test
    fun `apps and ui`() {
        assertTrue(parse("open youtube") is Command.OpenApp)
        assertTrue(parse("open camera") is Command.OpenApp)
        assertTrue(parse("screenshot lo") is Command.UiAction)
        assertTrue(parse("phone lock karo") is Command.UiAction)
        assertTrue(parse("click settings") is Command.UiAction)
        assertTrue(parse("scroll down") is Command.UiAction)
        assertTrue(parse("go back") is Command.UiAction)
        assertTrue(parse("home screen") is Command.UiAction)
    }

    @Test
    fun `v2 power commands`() {
        assertTrue(parse("screen record karo") is Command.ScreenRecord)
        assertTrue(parse("recording band karo") is Command.ScreenRecord)
        assertTrue(parse("phone kahan hai") is Command.FindPhone)
        assertTrue(parse("find my phone") is Command.FindPhone)
        assertTrue(parse("copy karo ye text") is Command.Clipboard)
        assertTrue(parse("paste kar do") is Command.Paste)
        assertTrue(parse("ye kaunsa gaana hai") is Command.MusicId)
        assertTrue(parse("photo le aur bata") is Command.Vision)
        assertTrue(parse("kya naya aaya") is Command.ReadNotifications)
        assertTrue(parse("jab ghar pahunchu to paani lena") is Command.GeofenceReminder)
    }

    @Test
    fun `v2 info commands`() {
        assertTrue(parse("daily briefing") is Command.Briefing)
        assertTrue(parse("cricket score batao") is Command.Cricket)
        assertTrue(parse("india ka score") is Command.Cricket)
        assertTrue(parse("gold ka rate kya hai") is Command.GoldRates)
        assertTrue(parse("chandi ka bhaav") is Command.GoldRates)
        assertTrue(parse("aaj ki news sunao") is Command.News)
        assertTrue(parse("pnr 1234567890") is Command.Pnr)
        assertTrue(parse("train status batao") is Command.Pnr)
    }

    @Test
    fun `v2 memory and personality`() {
        val name = parse("mera naam Rohan hai") as Command.Memory
        assertEquals("name", name.key)
        assertEquals("Rohan", name.value)
        val pref = parse("mujhe chai pasand hai") as Command.Memory
        assertEquals("preference", pref.key)
        assertTrue(parse("mera naam kya hai") is Command.RecallName)
        assertTrue(parse("tumhe kya yaad hai") is Command.RecallMemory)
        assertTrue(parse("dobara bolo") is Command.Repeat)
        assertTrue(parse("jaldi bolo") is Command.SpeechRate)
        assertTrue(parse("dheere bolo") is Command.SpeechRate)
        assertTrue(parse("joke sunao") is Command.Joke)
        assertTrue(parse("tum kaun ho") is Command.WhoAreYou)
        assertTrue(parse("good night") is Command.GoodNight)
        assertTrue(parse("hello") is Command.Greeting)
    }

    @Test
    fun `weather and queries`() {
        assertTrue(parse("weather batao") is Command.Weather)
        assertTrue(parse("mausam kaisa hai") is Command.Weather)
        assertTrue(parse("help karo") is Command.Help)
    }
}

package com.jarvis.assistant.info

import android.content.Context
import com.jarvis.assistant.JarvisApp
import com.jarvis.assistant.control.WeatherController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.util.concurrent.TimeUnit

/**
 * Live info hub — news (Google News RSS), cricket (ESPN Cricinfo),
 * gold/silver (GoldPrice), PNR (optional key), and the daily briefing.
 * All free, no API keys required (except PNR).
 */
class InfoHub(context: Context) {

    private val app = context.applicationContext as JarvisApp
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ------------------------------------------------------------- news

    suspend fun topNews(count: Int = 5): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://news.google.com/rss?hl=en-IN&gl=IN&ceid=IN:en")
                .build()
            val xml = client.newCall(request).execute().use { it.body?.string().orEmpty() }
            val titles = parseRssTitles(xml)
            if (titles.isEmpty()) return@withContext "News abhi nahi mili — internet check karo."
            val sb = StringBuilder("Aaj ki top news: ")
            titles.take(count).forEachIndexed { i, t ->
                sb.append("${i + 1}. $t. ")
            }
            sb.toString()
        } catch (e: Exception) {
            "News server se baat nahi ho payi."
        }
    }

    private fun parseRssTitles(xml: String): List<String> {
        val titles = ArrayList<String>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var inItem = false
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "item") inItem = true
                        else if (inItem && parser.name == "title") {
                            val title = parser.nextText().trim()
                            if (title.isNotBlank() && !title.startsWith("Top Stories")) {
                                titles.add(title)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> if (parser.name == "item") inItem = false
                }
                event = parser.next()
            }
        } catch (_: Exception) {}
        return titles
    }

    // ----------------------------------------------------------- cricket

    suspend fun cricketScore(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://hs-consumer-api.espncricinfo.com/v1/pages/matches/current")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) JARVIS/2.0")
                .build()
            val json = JSONObject(client.newCall(request).execute().use { it.body?.string().orEmpty() })
            val content = json.optJSONObject("content") ?: return@withContext "Cricket data nahi mila."
            val groups = content.optJSONArray("matches") ?: return@withContext "Cricket data nahi mila."

            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val matches = group.optJSONArray("matches") ?: continue
                for (j in 0 until matches.length()) {
                    val match = matches.getJSONObject(j)
                    val statusText = match.optString("statusText", "")
                    if (statusText.contains("live", true) || statusText.contains("Lunch", true) ||
                        statusText.contains("Tea", true) || statusText.contains("Innings", true)
                    ) {
                        val series = match.optJSONObject("series")?.optString("name", "") ?: ""
                        val teams = match.optJSONArray("teams") ?: continue
                        val names = ArrayList<String>()
                        for (t in 0 until teams.length()) {
                            names.add(teams.getJSONObject(t).optJSONObject("team")?.optString("shortName", "") ?: "")
                        }
                        return@withContext buildString {
                            append("Live cricket! ")
                            if (series.isNotBlank()) append("$series. ")
                            append(names.filter { it.isNotBlank() }.joinToString(" vs "))
                            append(". ")
                            append(statusText)
                        }
                    }
                }
            }
            // No live match — next match info
            for (i in 0 until groups.length()) {
                val group = groups.getJSONObject(i)
                val matches = group.optJSONArray("matches") ?: continue
                if (matches.length() > 0) {
                    val match = matches.getJSONObject(0)
                    val series = match.optJSONObject("series")?.optString("name", "") ?: ""
                    val teams = match.optJSONArray("teams") ?: continue
                    val names = ArrayList<String>()
                    for (t in 0 until teams.length()) {
                        names.add(teams.getJSONObject(t).optJSONObject("team")?.optString("shortName", "") ?: "")
                    }
                    val status = match.optString("statusText", "upcoming")
                    return@withContext "Abhi koi live match nahi hai. Agla: ${names.joinToString(" vs ")} — $status."
                }
            }
            "Abhi koi cricket match active nahi hai, boss."
        } catch (e: Exception) {
            "Cricket score nahi mil paya — internet check karo."
        }
    }

    // ------------------------------------------------------- gold/silver

    suspend fun goldRates(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://data-asg.goldprice.org/dbXRates/USD")
                .build()
            val json = JSONObject(client.newCall(request).execute().use { it.body?.string().orEmpty() })
            val items = json.optJSONArray("items") ?: return@withContext "Gold rate nahi mila."
            val item = items.getJSONObject(0)
            val xauUsd = item.optDouble("xauPrice", 0.0)  // USD per troy ounce
            val xagUsd = item.optDouble("xagPrice", 0.0)
            if (xauUsd <= 0.0) return@withContext "Gold rate nahi mila."

            val inr = usdToInr()
            val perGram24k = xauUsd / 31.1035 * inr
            val perGram22k = perGram24k * 0.916
            val silverPerGram = xagUsd / 31.1035 * inr

            "Gold ka rate: 24 karat ₹${perGram24k.toInt()} aur 22 karat ₹${perGram22k.toInt()} per gram. Silver ₹${silverPerGram.toInt()} per gram."
        } catch (e: Exception) {
            "Gold rate nahi mil paya — internet check karo."
        }
    }

    private suspend fun usdToInr(): Double {
        return try {
            val request = Request.Builder()
                .url("https://open.er-api.com/v6/latest/USD")
                .build()
            val json = JSONObject(client.newCall(request).execute().use { it.body?.string().orEmpty() })
            json.optJSONObject("rates")?.optDouble("INR", 83.0) ?: 83.0
        } catch (e: Exception) {
            83.0
        }
    }

    // -------------------------------------------------------------- PNR

    suspend fun pnrStatus(code: String): String = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        if (code.isBlank()) return@withContext "PNR number batao, boss — 10 digit."
        val key = settings.pnrKey
        if (key.isBlank()) {
            return@withContext "PNR check ke liye Settings me API key daalo (Indian Rail API), ya irctc.co.in pe check karo."
        }
        try {
            val request = Request.Builder()
                .url("https://indianrailapi.com/api/v2/PNRStatus/apikey/$key/pnr/$code")
                .build()
            val json = JSONObject(client.newCall(request).execute().use { it.body?.string().orEmpty() })
            val status = json.optString("CurrentStatus", "")
            val train = json.optString("TrainName", "")
            val num = json.optString("TrainNumber", "")
            if (status.isBlank()) {
                "PNR $code ka data nahi mila — key/code check karo."
            } else {
                "PNR $code: $train ($num) — $status"
            }
        } catch (e: Exception) {
            "PNR check nahi ho paya — internet check karo."
        }
    }

    // --------------------------------------------------------- briefing

    suspend fun dailyBriefing(): String = withContext(Dispatchers.IO) {
        val weather = WeatherController(app).report()
        val battery = com.jarvis.assistant.control.SystemController(app).batteryStatus()
        val news = topNews(3)
        val cricket = runCatching { cricketScore() }.getOrDefault("")

        buildString {
            append("Good morning briefing, boss! ")
            append(weather)
            append(" ")
            append(battery)
            append(" ")
            append(news)
            if (cricket.isNotBlank() && !cricket.contains("nahi")) {
                append(" Aur cricket — ")
                append(cricket)
            }
        }
    }
}

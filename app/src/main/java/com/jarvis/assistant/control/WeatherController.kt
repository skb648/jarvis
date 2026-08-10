package com.jarvis.assistant.control

import android.content.Context
import com.jarvis.assistant.JarvisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Weather via Open-Meteo — free, no API key.
 * Location from Settings (default: Jaipur) or GPS permission when granted.
 */
class WeatherController(context: Context) {

    private val app = context.applicationContext as JarvisApp
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun report(): String = withContext(Dispatchers.IO) {
        val settings = app.settings.settings.first()
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${settings.weatherLat}" +
            "&longitude=${settings.weatherLon}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m"
        try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext "Mausam server se baat nahi ho payi — thodi der baad try karo."
                val json = JSONObject(resp.body?.string().orEmpty())
                val current = json.optJSONObject("current") ?: return@withContext "Mausam data nahi mila."
                val temp = current.optInt("temperature_2m", 0)
                val code = current.optInt("weather_code", 0)
                val wind = current.optInt("wind_speed_10m", 0)
                val humidity = current.optInt("relative_humidity_2m", 0)
                "Abhi $temp degree Celsius, ${describe(code)}, hawa $wind kilometer per hour, humidity $humidity percent."
            }
        } catch (e: Exception) {
            "Mausam check nahi ho paya. Internet check karo."
        }
    }

    private fun describe(code: Int): String = when (code) {
        0 -> "saaf aasmaan"
        1, 2 -> "halke baadal"
        3 -> "badal chhaye hue"
        45, 48 -> "kohra chhaya hai"
        51, 53, 55, 56, 57 -> "halki boondabaandi"
        61, 63, 65, 66, 67 -> "baarish ho rahi hai"
        71, 73, 75, 77 -> "baarish ho rahi hai"
        80, 81, 82 -> "jhoom kar baarish"
        95, 96, 99 -> "toofan chal raha hai"
        else -> "normal mausam"
    }
}

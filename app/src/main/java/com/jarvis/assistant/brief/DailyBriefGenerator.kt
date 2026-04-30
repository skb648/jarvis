package com.jarvis.assistant.brief

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.jarvis.assistant.data.local.JarvisDatabase
import com.jarvis.assistant.memory.ConversationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * DailyBriefGenerator — Generates a conversational morning brief.
 *
 * "Good morning Sir, aaj 3 meetings hain, weather 28°C hai, koi important email nahi"
 *
 * Components:
 *   - Calendar events (today's meetings/appointments via CalendarContract)
 *   - Weather (via OpenMeteo free API — no key needed)
 *   - Unread notifications count (via NotificationReaderService)
 *   - Battery status
 *   - Pending tasks from yesterday's conversations (via Memory system)
 *
 * The brief is generated using the Gemini API to make it conversational
 * and natural, rather than a raw data dump.
 */
object DailyBriefGenerator {

    private const val TAG = "DailyBriefGenerator"
    private const val CHANNEL_BRIEF = "jarvis_daily_brief"
    private const val BRIEF_NOTIFICATION_ID = 2001

    // Work IDs
    private const val MORNING_BRIEF_WORK = "jarvis_morning_brief_v2"

    /**
     * Generate the daily brief as a conversational string.
     *
     * @param context Android context
     * @param apiKey Gemini API key for natural language generation
     * @return Conversational brief string
     */
    suspend fun generateBrief(context: Context, apiKey: String): String {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "[generateBrief] Starting daily brief generation")

                // Collect all components
                val calendarEvents = getCalendarEvents(context)
                val weather = getWeather(context)
                val batteryStatus = getBatteryStatus(context)
                val pendingTasks = getPendingTasks(context)
                val greeting = getGreeting()

                // Build raw data string
                val rawData = buildString {
                    append("Greeting: $greeting\n")
                    append("Calendar: $calendarEvents\n")
                    append("Weather: $weather\n")
                    append("Battery: $batteryStatus\n")
                    if (pendingTasks.isNotBlank()) {
                        append("Pending tasks: $pendingTasks\n")
                    }
                }

                Log.d(TAG, "[generateBrief] Raw data: $rawData")

                // Use Gemini to make it conversational
                if (apiKey.isNotBlank()) {
                    val conversationalBrief = generateConversationalBrief(rawData, apiKey)
                    if (conversationalBrief.isNotBlank()) {
                        return@withContext conversationalBrief
                    }
                }

                // Fallback: return raw data formatted nicely
                formatRawBrief(greeting, calendarEvents, weather, batteryStatus, pendingTasks)

            } catch (e: Exception) {
                Log.e(TAG, "[generateBrief] Error: ${e.message}")
                "Good morning Sir. I had trouble preparing your daily brief. ${e.message?.take(100)}"
            }
        }
    }

    /**
     * Schedule a morning brief notification at a specific time.
     * Uses WorkManager with an initial delay to approximate the time.
     */
    fun scheduleMorningBrief(context: Context, hour: Int, minute: Int) {
        try {
            val now = Calendar.getInstance()
            val scheduled = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If the time has already passed today, schedule for tomorrow
            if (scheduled.before(now)) {
                scheduled.add(Calendar.DAY_OF_YEAR, 1)
            }

            val delayMs = scheduled.timeInMillis - now.timeInMillis

            val inputData = Data.Builder()
                .putString("api_key", "") // Will be loaded from settings at execution time
                .build()

            val request = OneTimeWorkRequestBuilder<DailyBriefWorker>()
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .setInputData(inputData)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .addTag(MORNING_BRIEF_WORK)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    MORNING_BRIEF_WORK,
                    ExistingWorkPolicy.REPLACE,
                    request
                )

            Log.i(TAG, "[scheduleMorningBrief] Scheduled for $hour:$minute (delay=${delayMs}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "[scheduleMorningBrief] Error: ${e.message}")
        }
    }

    /**
     * Cancel any scheduled morning brief.
     */
    fun cancelMorningBrief(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(MORNING_BRIEF_WORK)
        Log.i(TAG, "[cancelMorningBrief] Cancelled")
    }

    // ─── Component Collectors ──────────────────────────────────────────

    private fun getGreeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hour < 12 -> "Good morning"
            hour < 17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    private fun getCalendarEvents(context: Context): String {
        return try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return "Calendar permission not granted"
            }

            val events = mutableListOf<String>()
            val startTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis

            val endTime = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
            }.timeInMillis

            val projection = arrayOf(
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )

            val selection = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} <= ?"
            val selectionArgs = arrayOf(startTime.toString(), endTime.toString())

            val cursor = context.contentResolver.query(
                CalendarContract.Events.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                "${CalendarContract.Events.DTSTART} ASC"
            )

            cursor?.use {
                val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val locationIdx = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)

                while (it.moveToNext()) {
                    val title = it.getString(titleIdx) ?: "Untitled"
                    val startTime = it.getLong(startIdx)
                    val location = it.getString(locationIdx) ?: ""

                    val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
                    val timeStr = sdf.format(Date(startTime))

                    val eventStr = if (location.isNotBlank()) {
                        "$title at $timeStr ($location)"
                    } else {
                        "$title at $timeStr"
                    }
                    events.add(eventStr)
                }
            }

            if (events.isEmpty()) {
                "No events today"
            } else {
                "${events.size} events: " + events.take(5).joinToString("; ")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[getCalendarEvents] Error: ${e.message}")
            "Could not read calendar"
        }
    }

    /**
     * Get current weather using OpenMeteo free API (no API key needed).
     * Falls back to wttr.in if OpenMeteo fails.
     */
    private suspend fun getWeather(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                // Try OpenMeteo (no API key needed, IP-based location)
                val url = URL("https://api.open-meteo.com/v1/forecast?latitude=28.6&longitude=77.2&current_weather=true&timezone=auto")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000

                val code = connection.responseCode
                if (code == 200) {
                    val response = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()

                    val json = JSONObject(response)
                    val current = json.optJSONObject("current_weather")
                    if (current != null) {
                        val temp = current.optDouble("temperature", 0.0)
                        val windspeed = current.optDouble("windspeed", 0.0)
                        val weatherCode = current.optInt("weathercode", 0)
                        val description = weatherCodeToDescription(weatherCode)

                        return@withContext "${temp}°C, $description, wind ${windspeed}km/h"
                    }
                }
                connection.disconnect()

                // Fallback to wttr.in
                try {
                    val wttrUrl = URL("https://wttr.in/?format=%t+%C+%w")
                    val wttrConn = wttrUrl.openConnection() as HttpURLConnection
                    wttrConn.requestMethod = "GET"
                    wttrConn.connectTimeout = 5_000
                    wttrConn.readTimeout = 5_000
                    wttrConn.setRequestProperty("User-Agent", "curl/7.68.0")
                    if (wttrConn.responseCode == 200) {
                        val result = wttrConn.inputStream.bufferedReader().readText().trim()
                        wttrConn.disconnect()
                        return@withContext result
                    }
                    wttrConn.disconnect()
                } catch (_: Exception) {}

                "Weather unavailable"
            } catch (e: Exception) {
                Log.e(TAG, "[getWeather] Error: ${e.message}")
                "Weather unavailable"
            }
        }
    }

    private fun weatherCodeToDescription(code: Int): String {
        return when (code) {
            0 -> "Clear sky"
            1, 2, 3 -> "Partly cloudy"
            45, 48 -> "Foggy"
            51, 53, 55 -> "Drizzle"
            61, 63, 65 -> "Rainy"
            71, 73, 75 -> "Snowy"
            80, 81, 82 -> "Showers"
            95, 96, 99 -> "Thunderstorm"
            else -> "Mixed"
        }
    }

    private fun getBatteryStatus(context: Context): String {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val isCharging = bm.isCharging
            if (isCharging) {
                "Battery $level% (charging)"
            } else {
                "Battery $level%"
            }
        } catch (e: Exception) {
            "Battery status unknown"
        }
    }

    /**
     * Get pending tasks from yesterday's conversation memory.
     * Searches for "task" tagged memories.
     */
    private suspend fun getPendingTasks(context: Context): String {
        return withContext(Dispatchers.IO) {
            try {
                val db = JarvisDatabase.getInstance(context)
                val memoryDao = db.memoryDao()
                val tasks = memoryDao.searchByKeyword("task", 3)
                if (tasks.isEmpty()) {
                    ""
                } else {
                    tasks.joinToString("; ") { it.keyword }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[getPendingTasks] Error: ${e.message}")
                ""
            }
        }
    }

    // ─── Conversational Generation via Gemini ──────────────────────────

    private fun generateConversationalBrief(rawData: String, apiKey: String): String {
        return try {
            val prompt = """You are JARVIS, Tony Stark's AI assistant. Generate a concise, conversational morning brief based on this data. Use Hinglish (Hindi+English mix) naturally. Address the user as "Sir". Keep it under 3 sentences. Be witty but informative.

Data:
$rawData

Generate the brief:"""

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("parts", JSONArray().put(
                            JSONObject().put("text", prompt)
                        ))
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 200)
                })
            }.toString()

            val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=${apiKey.trim()}")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000

            connection.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == 200) {
                val responseBody = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                val root = JSONObject(responseBody)
                val candidates = root.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val content = firstCandidate?.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                val firstPart = parts?.optJSONObject(0)
                firstPart?.optString("text", "") ?: ""
            } else {
                connection.disconnect()
                ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "[generateConversationalBrief] Error: ${e.message}")
            ""
        }
    }

    private fun formatRawBrief(
        greeting: String,
        calendar: String,
        weather: String,
        battery: String,
        tasks: String
    ): String {
        val sb = StringBuilder()
        sb.append("$greeting Sir. ")
        sb.append(calendar.replace("No events today", "Aaj koi meeting nahi hai")).append(". ")
        sb.append("Weather $weather hai. ")
        sb.append(battery).append(". ")
        if (tasks.isNotBlank()) {
            sb.append("Pending tasks: $tasks.")
        }
        return sb.toString()
    }

    // ─── Notification Posting ──────────────────────────────────────────

    /**
     * Post the daily brief as a notification.
     */
    fun postBriefNotification(context: Context, brief: String) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Create channel
            val channel = NotificationChannel(
                CHANNEL_BRIEF,
                "JARVIS Daily Brief",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Morning brief from JARVIS"
            }
            nm.createNotificationChannel(channel)

            // Build notification
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                context.packageManager.getLaunchIntentForPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_BRIEF)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("JARVIS Daily Brief")
                .setContentText(brief.take(100))
                .setStyle(NotificationCompat.BigTextStyle().bigText(brief))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            nm.notify(BRIEF_NOTIFICATION_ID, notification)
            Log.i(TAG, "[postBriefNotification] Notification posted")
        } catch (e: Exception) {
            Log.e(TAG, "[postBriefNotification] Error: ${e.message}")
        }
    }

    // ─── WorkManager Worker ────────────────────────────────────────────

    class DailyBriefWorker(
        context: Context,
        workerParams: WorkerParameters
    ) : CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result {
            return try {
                Log.i(TAG, "[DailyBriefWorker] Executing morning brief")

                // Get API key from DataStore
                val context = applicationContext
                val apiKey = try {
                    val prefs = context.getSharedPreferences("jarvis_settings_apikey_cache", Context.MODE_PRIVATE)
                    prefs.getString("gemini_api_key", "") ?: ""
                } catch (_: Exception) { "" }

                val brief = generateBrief(context, apiKey)

                // Post notification
                postBriefNotification(context, brief)

                // Also try TTS via the foreground service if running
                try {
                    val ttsIntent = Intent("com.jarvis.assistant.SPEAK_TEXT").apply {
                        putExtra("text", brief)
                        setPackage(context.packageName)
                    }
                    context.sendBroadcast(ttsIntent)
                } catch (_: Exception) {}

                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "[DailyBriefWorker] Error", e)
                Result.retry()
            }
        }
    }
}

package com.jarvis.assistant.router

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * SystemActionExecutor — Executes device system actions like alarms, timers, reminders,
 * image generation, and settings control.
 *
 * Provides real, working implementations for common device operations
 * that users expect from a full AI assistant.
 */
object SystemActionExecutor {

    private const val TAG = "SystemAction"

    /**
     * Set an alarm on the device.
     */
    fun setAlarm(context: Context, hour: Int, minute: Int, message: String = ""): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                if (message.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[setAlarm] Alarm set for $hour:${minute.toString().padStart(2, '0')} - $message")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[setAlarm] Failed: ${e.message}")
            false
        }
    }

    /**
     * Set a timer on the device.
     */
    fun setTimer(context: Context, durationSeconds: Int, message: String = ""): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds)
                if (message.isNotBlank()) {
                    putExtra(AlarmClock.EXTRA_MESSAGE, message)
                }
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[setTimer] Timer set for ${durationSeconds}s - $message")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[setTimer] Failed: ${e.message}")
            false
        }
    }

    /**
     * Open the clock app to show alarms.
     */
    fun showAlarms(context: Context): Boolean {
        return try {
            val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "[showAlarms] Failed: ${e.message}")
            false
        }
    }

    /**
     * Set a calendar event/reminder.
     */
    fun setReminder(context: Context, title: String, description: String = "", beginTimeMs: Long? = null): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                type = "vnd.android.cursor.item/event"
                putExtra("title", title)
                if (description.isNotBlank()) putExtra("description", description)
                if (beginTimeMs != null) putExtra("beginTime", beginTimeMs)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "[setReminder] Reminder set: $title")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[setReminder] Failed: ${e.message}")
            false
        }
    }

    /**
     * Open Android Settings for a specific section.
     */
    fun openSettings(context: Context, section: String = ""): Boolean {
        return try {
            val intent = when (section.lowercase()) {
                "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                "location" -> Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                "sound", "volume" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                "display", "brightness" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                "battery" -> Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS)
                "apps", "applications" -> Intent(android.provider.Settings.ACTION_APPLICATION_SETTINGS)
                "storage" -> Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS)
                "about" -> Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS)
                "accessibility" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                "security" -> Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)
                "notifications" -> Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                }
                else -> Intent(android.provider.Settings.ACTION_SETTINGS)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            Log.i(TAG, "[openSettings] Opened settings: ${section.ifBlank { "main" }}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "[openSettings] Failed: ${e.message}")
            false
        }
    }

    /**
     * Generate an image using Gemini Imagen API.
     */
    suspend fun generateImage(prompt: String, apiKey: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = org.json.JSONObject().apply {
                    put("instances", org.json.JSONArray().put(
                        org.json.JSONObject().apply {
                            put("prompt", prompt)
                        }
                    ))
                    put("parameters", org.json.JSONObject().apply {
                        put("sampleCount", 1)
                    })
                }.toString()

                val url = URL("https://generativelanguage.googleapis.com/v1beta/models/imagen-3.0-generate-002:predict?key=${apiKey.trim()}")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000

                connection.outputStream.use { os ->
                    os.write(requestBody.toByteArray(Charsets.UTF_8))
                }

                val responseCode = connection.responseCode
                if (responseCode == 200) {
                    val responseBody = connection.inputStream.bufferedReader().readText()
                    connection.disconnect()

                    val root = com.google.gson.JsonParser.parseString(responseBody).asJsonObject
                    val predictions = root.getAsJsonArray("predictions")
                    if (predictions != null && predictions.size() > 0) {
                        val firstPrediction = predictions[0].asJsonObject
                        val bytesBase64 = firstPrediction.get("bytesBase64Encoded")?.asString
                        if (bytesBase64 != null) {
                            Log.i(TAG, "[generateImage] Image generated successfully")
                            return@withContext bytesBase64
                        }
                    }
                    Log.w(TAG, "[generateImage] No image data in response")
                    null
                } else {
                    val errorBody = connection.errorStream?.bufferedReader()?.readText() ?: ""
                    Log.e(TAG, "[generateImage] HTTP $responseCode: ${errorBody.take(300)}")
                    connection.disconnect()
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "[generateImage] Error: ${e.message}")
                null
            }
        }
    }
}

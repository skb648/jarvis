package com.jarvis.assistant.monitor

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * SelfDiagnosticManager — JARVIS's ability to read its own crash logs.
 *
 * Uses `Runtime.getRuntime().exec("logcat -d -v time -m 200")` to fetch
 * the app's own recent crash logs and warnings. This is exposed to Gemini
 * as the `diagnose_system` tool.
 *
 * ═══════════════════════════════════════════════════════════════════════
 * WHY THIS MATTERS:
 *
 * If the user says "Why is the mic failing?", JARVIS can:
 *   1. Call diagnose_system()
 *   2. Read the logcat for stack traces / error messages
 *   3. Analyze the root cause (e.g., "AudioRecord died because the
 *      foreground service was killed by battery optimization")
 *   4. Reply with the exact fix ("Disable battery optimization for JARVIS")
 *
 * This is the core of JARVIS's self-awareness — it can diagnose its
 * own bugs without the user needing to connect ADB or read logs manually.
 * ═══════════════════════════════════════════════════════════════════════
 */
object SelfDiagnosticManager {

    private const val TAG = "JarvisSelfDiag"

    /**
     * Read the app's recent logcat entries.
     *
     * Uses `logcat -d -v time` with:
     *   -d: Dump mode (read existing logs, don't follow)
     *   -v time: Show timestamps
     *   -m 200: Limit to last 200 entries
     *   --pid=<PID>: Only our app's logs
     *
     * @param maxLines Maximum number of log lines to return (default 200)
     * @param filterLevel Minimum log level: "V"=Verbose, "D"=Debug, "I"=Info, "W"=Warn, "E"=Error
     * @return A string containing the filtered logcat output, or an error message
     */
    fun readAppLogs(maxLines: Int = 200, filterLevel: String = "W"): String {
        return try {
            val pid = android.os.Process.myPid()
            val command = "logcat -d -v time --pid=$pid -m $maxLines *:$filterLevel"
            
            Log.d(TAG, "[readAppLogs] Running: $command")
            
            val process = Runtime.getRuntime().exec(command)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            
            val output = StringBuilder()
            output.appendLine("JARVIS Self-Diagnostic — Logcat (last $maxLines lines, level >= $filterLevel)")
            output.appendLine("PID: $pid | Time: ${System.currentTimeMillis()}")
            output.appendLine("═".repeat(60))
            
            // Read stdout
            var line: String?
            var lineCount = 0
            while (reader.readLine().also { line = it } != null && lineCount < maxLines) {
                // Filter for our app's PID or package name
                val logLine = line ?: continue
                if (logLine.contains(pid.toString()) || 
                    logLine.contains("Jarvis") || 
                    logLine.contains("jarvis") ||
                    logLine.contains("AudioEngine") ||
                    logLine.contains("AccessibilityService") ||
                    logLine.contains("AndroidRuntime") ||
                    logLine.contains("FATAL") ||
                    logLine.contains("Process died")) {
                    output.appendLine(logLine)
                    lineCount++
                }
            }
            
            reader.close()
            errorReader.close()
            process.destroy()
            
            if (lineCount == 0) {
                output.appendLine("No relevant log entries found at level $filterLevel or above.")
                output.appendLine("Try lowering the filter level (e.g., 'I' for Info, 'D' for Debug).")
            }
            
            output.appendLine("═".repeat(60))
            output.appendLine("Total relevant entries: $lineCount")
            
            val result = output.toString()
            Log.d(TAG, "[readAppLogs] Collected $lineCount log entries")
            result
            
        } catch (e: Exception) {
            Log.e(TAG, "[readAppLogs] Failed to read logcat: ${e.message}")
            "ERROR: Could not read logcat — ${e.message}\n" +
            "This usually means JARVIS doesn't have READ_LOGS permission.\n" +
            "On Android 11+, apps can only read their own logs via logcat."
        }
    }

    /**
     * Get a focused diagnostic for a specific issue.
     *
     * @param issueType The type of issue to diagnose: "mic", "accessibility", 
     *                  "crash", "network", "battery", or "general"
     * @return A diagnostic string with relevant logs and suggestions
     */
    fun diagnoseIssue(issueType: String): String {
        val sb = StringBuilder()
        sb.appendLine("JARVIS Diagnostic — Issue: $issueType")
        sb.appendLine("═".repeat(60))
        
        when (issueType.lowercase()) {
            "mic", "microphone", "audio" -> {
                sb.appendLine("Checking microphone / audio issues...")
                sb.appendLine()
                val logs = readAppLogs(100, "W")
                // Filter for audio-related entries
                val audioLogs = logs.lines().filter { line ->
                    line.contains("Audio", ignoreCase = true) ||
                    line.contains("mic", ignoreCase = true) ||
                    line.contains("AudioRecord", ignoreCase = true) ||
                    line.contains("AudioEngine", ignoreCase = true) ||
                    line.contains("RECORD_AUDIO", ignoreCase = true) ||
                    line.contains("ForegroundService", ignoreCase = true) ||
                    line.contains("Microphone", ignoreCase = true)
                }
                if (audioLogs.isNotEmpty()) {
                    sb.appendLine("Audio-related log entries:")
                    audioLogs.forEach { sb.appendLine("  $it") }
                } else {
                    sb.appendLine("No audio-related errors in recent logs.")
                }
                sb.appendLine()
                sb.appendLine("Common causes:")
                sb.appendLine("  1. Microphone permission revoked — check Settings > Apps > JARVIS > Permissions")
                sb.appendLine("  2. Foreground service killed — check Battery Optimization")
                sb.appendLine("  3. Another app holding the microphone — close other voice apps")
                sb.appendLine("  4. AudioRecord initialization failed — restart the app")
            }
            
            "accessibility", "a11y" -> {
                sb.appendLine("Checking accessibility service issues...")
                sb.appendLine()
                val logs = readAppLogs(100, "W")
                val a11yLogs = logs.lines().filter { line ->
                    line.contains("Accessibility", ignoreCase = true) ||
                    line.contains("a11y", ignoreCase = true) ||
                    line.contains("click", ignoreCase = true) ||
                    line.contains("gesture", ignoreCase = true)
                }
                if (a11yLogs.isNotEmpty()) {
                    sb.appendLine("Accessibility-related log entries:")
                    a11yLogs.forEach { sb.appendLine("  $it") }
                } else {
                    sb.appendLine("No accessibility errors in recent logs.")
                }
                sb.appendLine()
                sb.appendLine("Common causes:")
                sb.appendLine("  1. Accessibility Service not enabled — Settings > Accessibility > JARVIS")
                sb.appendLine("  2. Service was killed by OS — enable Battery Optimization bypass")
                sb.appendLine("  3. Touch exploration interfering — JARVIS removes this automatically on connect")
            }
            
            "crash" -> {
                sb.appendLine("Checking for recent crashes...")
                sb.appendLine()
                val logs = readAppLogs(200, "E")
                val crashLogs = logs.lines().filter { line ->
                    line.contains("FATAL", ignoreCase = true) ||
                    line.contains("AndroidRuntime", ignoreCase = true) ||
                    line.contains("Process died", ignoreCase = true) ||
                    line.contains("Exception", ignoreCase = true) ||
                    line.contains("Stacktrace", ignoreCase = true)
                }
                if (crashLogs.isNotEmpty()) {
                    sb.appendLine("CRASH LOGS FOUND:")
                    crashLogs.take(50).forEach { sb.appendLine("  $it") }
                } else {
                    sb.appendLine("No crash entries found in recent logs.")
                }
            }
            
            "network" -> {
                sb.appendLine("Checking network connectivity...")
                sb.appendLine()
                val logs = readAppLogs(100, "W")
                val networkLogs = logs.lines().filter { line ->
                    line.contains("network", ignoreCase = true) ||
                    line.contains("HTTP", ignoreCase = true) ||
                    line.contains("connect", ignoreCase = true) ||
                    line.contains("timeout", ignoreCase = true) ||
                    line.contains("Gemini", ignoreCase = true) ||
                    line.contains("API", ignoreCase = true)
                }
                if (networkLogs.isNotEmpty()) {
                    sb.appendLine("Network-related log entries:")
                    networkLogs.forEach { sb.appendLine("  $it") }
                } else {
                    sb.appendLine("No network errors in recent logs.")
                }
            }
            
            "battery" -> {
                sb.appendLine("Checking battery optimization issues...")
                sb.appendLine()
                sb.appendLine("Common causes of battery-related kills:")
                sb.appendLine("  1. Battery Optimization NOT bypassed — this is the #1 cause of service kills")
                sb.appendLine("  2. App is swiped from recents — Foreground service should survive this")
                sb.appendLine("  3. Doze mode — disable optimization in Settings > Battery > Unrestricted")
                sb.appendLine("  4. Manufacturer-specific battery managers (Xiaomi MIUI, Samsung, Huawei)")
            }
            
            else -> {
                sb.appendLine("Running general diagnostic...")
                sb.appendLine()
                sb.appendLine(readAppLogs(150, "W"))
            }
        }
        
        return sb.toString()
    }
}

package com.jarvis.assistant.ui

import android.accessibilityservice.AccessibilityService
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.audio.WakeWordDetector
import com.jarvis.assistant.core.Settings as JarvisSettings
import com.jarvis.assistant.control.SmartHomeController
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.service.FloatingBubbleService
import com.jarvis.assistant.ui.theme.JarvisCyan
import com.jarvis.assistant.ui.theme.JarvisGold
import com.jarvis.assistant.ui.theme.JarvisPanel
import com.jarvis.assistant.ui.theme.JarvisPanelHi
import com.jarvis.assistant.ui.theme.JarvisTextDim
import com.jarvis.assistant.ui.theme.accentColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    viewModel: MainViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by viewModel.jarvisApp.settings.settings.collectAsState(initial = JarvisSettings())
    val tts = viewModel.jarvisApp.tts
    val app = viewModel.jarvisApp

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // header
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = JarvisCyan)
            }
            Text(
                "SETTINGS",
                color = JarvisGold,
                fontSize = 18.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 2.sp
            )
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp)
                .padding(bottom = 30.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            SectionCard("POWER") {
                Row {
                    Column(Modifier.weight(1f)) {
                        Text("JARVIS Service", color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
                        Text("Wake word + always listening", color = JarvisTextDim, fontSize = 12.sp)
                    }
                    Button(
                        onClick = viewModel::activate,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color(0xFF00202A))
                    ) { Text("ACTIVATE") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = viewModel::deactivate,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = JarvisTextDim)
                    ) { Text("STOP") }
                }
            }

            SectionCard("PERMISSIONS") {
                PermissionRow(
                    "Core permissions (mic/calls/SMS/camera/location)",
                    "Voice, calls, messages, torch, geofence, vision",
                    hasAllCorePermissions(context)
                ) { onRequestPermissions() }
                PermissionRow(
                    "Accessibility (JARVIS UI Control)",
                    "Click/scroll/paste/screenshot/lock — kisi bhi app me",
                    isAccessibilityEnabled(context),
                    actionLabel = if (isAccessibilityEnabled(context)) "Enabled" else "Enable"
                ) {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                PermissionRow(
                    "Notification access",
                    "Media control + notification reader (\"kya naya aaya\")",
                    isNotificationListenerEnabled(context)
                ) {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                PermissionRow(
                    "Exact alarms",
                    "Timers/alarms exact time pe",
                    canScheduleExactAlarms(context)
                ) {
                    if (Build.VERSION.SDK_INT >= 31) {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM))
                    } else {
                        onRequestPermissions()
                    }
                }
                PermissionRow(
                    "Battery optimization off",
                    "Background me hamesha sunne ke liye",
                    !isBatteryOptimized(context)
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
                PermissionRow(
                    "Write settings",
                    "Brightness control ke liye",
                    Settings.System.canWrite(context)
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
                PermissionRow(
                    "Overlay (floating bubble)",
                    "Kisi bhi app ke upar JARVIS bubble",
                    Settings.canDrawOverlays(context)
                ) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                            .setData(Uri.parse("package:${context.packageName}"))
                    )
                }
            }

            SectionCard("WAKE WORD (ON-DEVICE, OFFLINE)") {
                Text(
                    "Apni awaaz se custom wake word train karo — bina Google ke, bina internet ke, 200ms me detect. MFCC + DTW template matching, sab phone me hi.",
                    color = JarvisTextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                WakeWordCard(settings.wakeTrained, app, scope)
            }

            SectionCard("AI BRAIN (OPTIONAL — PRO MODE)") {
                Text(
                    "Bina key ke sab on-device chalta hai. Gemini key = open-ended baat + audio understanding + vision. ElevenLabs = almost real-human voice.",
                    color = JarvisTextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(10.dp))
                SettingsTextField(
                    value = settings.geminiKey,
                    onValueChange = { scope.launch { app.settings.setGeminiKey(it) } },
                    label = "Gemini API key",
                    placeholder = "AIzaSy..."
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.geminiModel,
                    onValueChange = { scope.launch { app.settings.setGeminiModel(it) } },
                    label = "Gemini model",
                    placeholder = "gemini-2.5-flash"
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.elevenLabsKey,
                    onValueChange = { scope.launch { app.settings.setElevenLabsKey(it) } },
                    label = "ElevenLabs API key (optional)",
                    placeholder = "sk_..."
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.elevenLabsVoice,
                    onValueChange = { scope.launch { app.settings.setElevenLabsVoice(it) } },
                    label = "ElevenLabs voice ID",
                    placeholder = "EXAVITQu4vr4xnSDxMaL"
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.pnrKey,
                    onValueChange = { scope.launch { app.settings.setPnrKey(it) } },
                    label = "PNR status API key (optional)",
                    placeholder = "Indian Rail API key"
                )
            }

            SectionCard("VOICE") {
                LanguagePicker(settings.language) {
                    scope.launch { app.settings.setLanguage(it) }
                }
                Spacer(Modifier.height(10.dp))
                VoicePicker(settings.preferredVoice, tts) {
                    scope.launch { app.settings.setPreferredVoice(it) }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "Base speaking rate: ${"%.2f".format(settings.baseRate)}x",
                    color = JarvisTextDim,
                    fontSize = 12.sp
                )
                Slider(
                    value = settings.baseRate,
                    onValueChange = { scope.launch { app.settings.setBaseRate(it) } },
                    valueRange = 0.6f..1.5f,
                    colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
                )
                ToggleRow("Whisper mode", "Raat ko dheemi, soft, halki awaaz", settings.whisperMode) {
                    scope.launch { app.settings.setWhisper(it) }
                }
                Button(
                    onClick = {
                        tts.speak(
                            "Namaste boss! Main JARVIS hoon. Aaj kaise lag raha hoon? Happy, excited, ya calm?",
                            Emotion.HAPPY
                        )
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisGold, contentColor = Color(0xFF332800))
                ) { Text("🎧 Test voice (emotion ke saath)") }
            }

            SectionCard("SMART HOME (HOME ASSISTANT)") {
                Text(
                    "\"lights on\", \"fan off\", \"AC 24 degree\" — Home Assistant me apna URL + token daalo.",
                    color = JarvisTextDim,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.haUrl,
                    onValueChange = { scope.launch { app.settings.setHaUrl(it) } },
                    label = "Home Assistant URL",
                    placeholder = "http://192.168.1.10:8123"
                )
                Spacer(Modifier.height(8.dp))
                SettingsTextField(
                    value = settings.haToken,
                    onValueChange = { scope.launch { app.settings.setHaToken(it) } },
                    label = "Long-lived token",
                    placeholder = "eyJ..."
                )
                Spacer(Modifier.height(8.dp))
                var haTest by remember { mutableStateOf("") }
                Button(
                    onClick = {
                        scope.launch { haTest = SmartHomeController(context).testConnection() }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = JarvisCyan)
                ) { Text("Test connection") }
                if (haTest.isNotBlank()) {
                    Text(haTest, color = JarvisCyan, fontSize = 12.sp)
                }
            }

            SectionCard("MEMORY (JARVIS YAD RAKHTA HAI)") {
                var memoryFacts by remember { mutableStateOf(emptyMap<String, String>()) }
                LaunchedEffect(Unit) {
                    memoryFacts = app.memory.all()
                }
                if (memoryFacts.isEmpty()) {
                    Text(
                        "Abhi kuch nahi yaad hai. Try: \"mera naam Rohan hai\", \"mujhe chai pasand hai\", \"yaad rakh mera pincode 313001 hai\".",
                        color = JarvisTextDim,
                        fontSize = 12.sp
                    )
                } else {
                    memoryFacts.forEach { (k, v) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(JarvisPanelHi, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                when (k) {
                                    "name" -> "👤"
                                    "birthday" -> "🎂"
                                    "preference" -> "❤️"
                                    else -> "📌"
                                },
                                fontSize = 14.sp
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(v, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Button(
                        onClick = { scope.launch { app.memory.clear() } },
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = Color(0xFFFF5C5C))
                    ) { Text("Clear memory") }
                }
            }

            SectionCard("CONVERSATION HISTORY") {
                ToggleRow("Save history", "Baatein phone pe save hoti hain", settings.saveHistory) {
                    scope.launch { app.settings.setSaveHistory(it) }
                }
                Button(
                    onClick = {
                        scope.launch {
                            val file = com.jarvis.assistant.core.ConversationStore.export(context)
                            if (file != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    file
                                )
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/json"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(share, "JARVIS history export"))
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = JarvisCyan)
                ) { Text("📤 Export history (JSON)") }
                Button(
                    onClick = { viewModel.clearHistory() },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = Color(0xFFFF5C5C))
                ) { Text("🗑 Clear history") }
            }

            SectionCard("BEHAVIOR") {
                ToggleRow("Wake word", "Bina touch kiye activate", settings.wakeWordEnabled) {
                    scope.launch { app.settings.setWakeWord(it) }
                }
                ToggleRow("Auto-listen", "Jawab ke baad turant dobara suno", settings.autoListen) {
                    scope.launch { app.settings.setAutoListen(it) }
                }
                ToggleRow("Mood watch", "Stressed/angry ho to JARVIS notice kare", settings.moodWatch) {
                    scope.launch { app.settings.setMoodWatch(it) }
                }
                ToggleRow("Activation beep", "HUD jaisa chhota beep", settings.chimeEnabled) {
                    scope.launch { app.settings.setChime(it) }
                }
                ToggleRow("Mic compatibility mode", "ASR + raw mic conflict ho to ON karo", settings.strictMicMode) {
                    scope.launch { app.settings.setStrictMic(it) }
                }
                ToggleRow("Floating bubble", "Kisi bhi app ke upar JARVIS bubble", settings.bubbleEnabled) {
                    val enable = it
                    scope.launch {
                        app.settings.setBubble(enable)
                        if (enable && Settings.canDrawOverlays(context)) {
                            context.startForegroundService(
                                Intent(context, FloatingBubbleService::class.java)
                            )
                        } else if (!enable) {
                            context.stopService(Intent(context, FloatingBubbleService::class.java))
                        }
                    }
                }
                AccentPicker(settings.accent) {
                    scope.launch { app.settings.setAccent(it) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Auto-sleep: ${settings.autoSleepMinutes} min",
                    color = JarvisTextDim,
                    fontSize = 12.sp
                )
                Slider(
                    value = settings.autoSleepMinutes.toFloat(),
                    onValueChange = { scope.launch { app.settings.setAutoSleepMinutes(it.toInt()) } },
                    valueRange = 5f..60f,
                    steps = 10,
                    colors = SliderDefaults.colors(thumbColor = JarvisCyan, activeTrackColor = JarvisCyan)
                )
            }

            SectionCard("WEATHER LOCATION") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SettingsTextField(
                        value = settings.weatherLat.toString(),
                        onValueChange = {},
                        label = "Latitude",
                        modifier = Modifier.weight(1f)
                    )
                    SettingsTextField(
                        value = settings.weatherLon.toString(),
                        onValueChange = {},
                        label = "Longitude",
                        modifier = Modifier.weight(1f)
                    )
                }
                Text(
                    "Default: Jaipur (26.91, 75.79). \"weather batao\" aur daily briefing me use hota hai.",
                    color = JarvisTextDim,
                    fontSize = 11.sp
                )
            }

            SectionCard("ROUTINES (ROZANA AUTOMATION)") {
                if (settings.routines.isEmpty()) {
                    Text("Koi routine nahi. Try: \"har subah 7 baje gaana chalao\"", color = JarvisTextDim, fontSize = 12.sp)
                } else {
                    settings.routines.sorted().forEach { entry ->
                        val parts = entry.split("|")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(JarvisPanelHi, RoundedCornerShape(10.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(parts.getOrElse(0) { entry }, color = JarvisGold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                Text(parts.getOrElse(1) { "" }, color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
                            }
                            Text(
                                "DELETE",
                                color = Color(0xFFFF5C5C),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        scope.launch { app.settings.removeRoutine(entry) }
                                    }
                                    .padding(6.dp)
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
            }

            Text(
                "JARVIS AI v2.0.0 — Jetpack Compose • Emotion engine on-device • Wake word on-device • Memory • Smart home • Built with ❤ for boss",
                color = JarvisTextDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}

// ------------------------------------------------------- wake word trainer

@Composable
private fun WakeWordCard(
    trained: Boolean,
    app: com.jarvis.assistant.JarvisApp,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val context = LocalContext.current
    var training by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    Text(
        if (trained) "✅ Custom wake word trained — on-device detection active!"
        else "❌ Not trained — abhi Google ASR based wake word chalta hai.",
        color = if (trained) Color(0xFF69F0AE) else JarvisTextDim,
        fontSize = 12.sp
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                if (training) return@Button
                training = true
                status = "Silence ke liye 1 second ruko... phir bolo \"Hey Jarvis\" 2 second tak..."
                scope.launch {
                    app.capturer.beginUtterance()
                    delay(1200)
                    status = "🎙️ AB BOLO: \"Hey Jarvis\"... 2 second!"
                    delay(2600)
                    val file = File(context.cacheDir, "jarvis_wake_train.wav")
                    val ok = app.capturer.endUtterance(file)
                    if (ok && file.length() > 4000) {
                        val detector = WakeWordDetector()
                        val samples = readWavPcm(file)
                        if (detector.train(samples) &&
                            detector.saveTemplate(File(context.filesDir, "jarvis_wake_template.bin"))
                        ) {
                            app.settings.setWakeTrained(true)
                            status = "✅ Done! \"Hey Jarvis\" bolo — ab main turant pehchaan lunga."
                        } else {
                            status = "❌ Awaaz clear nahi aayi — dobara try karo (thoda loud bolo)."
                        }
                    } else {
                        status = "❌ Recording khaali hai — dobara try karo."
                    }
                    training = false
                }
            },
            enabled = !training,
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = JarvisCyan, contentColor = Color(0xFF00202A))
        ) { Text(if (training) "Recording..." else "🎙 Train wake word") }
        if (trained) {
            Button(
                onClick = {
                    scope.launch {
                        app.settings.setWakeTrained(false)
                        File(context.filesDir, "jarvis_wake_template.bin").delete()
                        status = "Reset ho gaya."
                    }
                },
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = JarvisPanelHi, contentColor = Color(0xFFFF5C5C))
            ) { Text("Reset") }
        }
    }
    if (status.isNotBlank()) {
        Text(status, color = JarvisGold, fontSize = 11.sp)
    }
}

private fun readWavPcm(file: File): ShortArray {
    val bytes = file.readBytes()
    val pcm = ByteArray(bytes.size - 44)
    System.arraycopy(bytes, 44, pcm, 0, pcm.size)
    val samples = ShortArray(pcm.size / 2)
    for (i in samples.indices) {
        samples[i] = ((pcm[i * 2].toInt() and 0xFF) or (pcm[i * 2 + 1].toInt() shl 8)).toShort()
    }
    return samples
}

// ---------------------------------------------------------------- pickers

@Composable
private fun LanguagePicker(current: String, onChange: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Text("Language", color = JarvisTextDim, fontSize = 12.sp)
    Box {
        OutlinedTextField(
            value = current,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            listOf("en-IN", "en-US", "en-GB", "hi-IN", "auto").forEach { lang ->
                DropdownMenuItem(
                    text = { Text(if (lang == "auto") "auto (Hinglish mix)" else lang) },
                    onClick = { onChange(lang); menuOpen = false }
                )
            }
        }
    }
}

@Composable
private fun VoicePicker(current: String, tts: com.jarvis.assistant.tts.EmotionalTtsEngine, onChange: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val voices by remember { mutableStateOf(tts.availableVoices()) }
    Text("TTS Voice", color = JarvisTextDim, fontSize = 12.sp)
    Box {
        OutlinedTextField(
            value = if (current.isBlank()) "Auto (recommended)" else current,
            onValueChange = {},
            readOnly = true,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { menuOpen = true },
            shape = RoundedCornerShape(12.dp),
            colors = fieldColors()
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Auto (recommended)") },
                onClick = { onChange(""); menuOpen = false }
            )
            voices.forEach { v ->
                DropdownMenuItem(
                    text = { Text("${v.name} · ${if (v.offline) "offline" else "online"}") },
                    onClick = { onChange(v.id); menuOpen = false }
                )
            }
        }
    }
    Text(
        "Offline voices best hain — bina internet ke, fast. Online neural voices aur natural.",
        color = JarvisTextDim,
        fontSize = 10.sp
    )
}

@Composable
private fun AccentPicker(current: String, onChange: (String) -> Unit) {
    Text("Accent color", color = JarvisTextDim, fontSize = 12.sp)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("cyan", "gold", "green", "purple", "red").forEach { name ->
            val color = accentColor(name)
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color)
                    .border(
                        if (current == name) 3.dp else 1.dp,
                        if (current == name) Color.White else Color.Transparent,
                        RoundedCornerShape(10.dp)
                    )
                    .clickable { onChange(name) },
                contentAlignment = Alignment.Center
            ) {
                if (current == name) {
                    Text("✓", color = Color(0xFF00202A), fontSize = 14.sp, fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- helpers

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(JarvisPanel, RoundedCornerShape(16.dp))
            .border(1.dp, JarvisCyan.copy(alpha = 0.15f), RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        content()
    }
}

@Composable
private fun ToggleRow(label: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = JarvisTextDim, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF00202A),
                checkedTrackColor = JarvisCyan,
                uncheckedThumbColor = JarvisTextDim,
                uncheckedTrackColor = JarvisPanelHi
            )
        )
    }
}

@Composable
private fun PermissionRow(
    label: String,
    desc: String,
    enabled: Boolean,
    actionLabel: String = if (enabled) "Enabled" else "Enable",
    onAction: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = JarvisTextDim, fontSize = 11.sp)
        }
        Button(
            onClick = onAction,
            enabled = !enabled,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (enabled) JarvisPanelHi else JarvisCyan,
                contentColor = if (enabled) Color(0xFF4CAF50) else Color(0xFF00202A),
                disabledContainerColor = JarvisPanelHi,
                disabledContentColor = Color(0xFF4CAF50)
            )
        ) { Text(actionLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label, color = JarvisTextDim, fontSize = 12.sp) },
        placeholder = { Text(placeholder, color = JarvisTextDim, fontSize = 12.sp) },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(),
        singleLine = true
    )
}

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = JarvisCyan,
    unfocusedBorderColor = JarvisPanelHi,
    focusedContainerColor = JarvisPanelHi,
    unfocusedContainerColor = JarvisPanelHi,
    cursorColor = JarvisCyan,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
)

// ------------------------------------------------------------- status fns

private fun hasAllCorePermissions(context: Context): Boolean {
    val perms = mutableListOf(android.Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= 33) perms.add(android.Manifest.permission.POST_NOTIFICATIONS)
    return perms.all {
        context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
    val expected = ComponentName(context, com.jarvis.assistant.control.VoiceAccessibilityService::class.java)
    return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        .any { it.resolveInfo.serviceInfo.packageName == expected.packageName && it.resolveInfo.serviceInfo.name == expected.className }
}

private fun isNotificationListenerEnabled(context: Context): Boolean {
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
    return flat.split(':').any {
        it.contains(context.packageName) && it.contains("MediaNotificationListener")
    }
}

private fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

private fun isBatteryOptimized(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return !pm.isIgnoringBatteryOptimizations(context.packageName)
}

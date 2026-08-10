package com.jarvis.assistant.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsCricket
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.model.Emotion
import com.jarvis.assistant.service.JarvisEvents
import com.jarvis.assistant.ui.components.ArcReactor
import com.jarvis.assistant.ui.theme.JarvisGold
import com.jarvis.assistant.ui.theme.JarvisPanel
import com.jarvis.assistant.ui.theme.JarvisPanelHi
import com.jarvis.assistant.ui.theme.JarvisTextDim

@Composable
fun MainScreen(
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = { BottomBar(viewModel, state) }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Header(state, onOpenSettings, viewModel)
            ArcReactor(
                emotionColor = state.currentEmotion.color,
                emotionEmoji = state.currentEmotion.emoji,
                emotionLabel = state.currentEmotion.label,
                confidence = state.confidence,
                waveform = state.waveform,
                state = state.serviceState,
                accent = state.accent,
                modifier = Modifier
                    .padding(top = 14.dp, bottom = 6.dp)
                    .size(230.dp)
                    .align(Alignment.CenterHorizontally)
            )
            MoodTrendRow(state.moodTrend, Modifier.align(Alignment.CenterHorizontally))
            ChatList(state, listState, Modifier.weight(1f))
        }
    }
}

/** 48-dot emotion history strip — aapke mood ka live graph. */
@Composable
private fun MoodTrendRow(trend: List<Emotion>, modifier: Modifier = Modifier) {
    if (trend.isEmpty()) return
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(JarvisPanel.copy(alpha = 0.7f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        trend.forEach { e ->
            Box(
                Modifier
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(e.color.copy(alpha = 0.85f))
            )
        }
    }
}

// ------------------------------------------------------------------ header

@Composable
private fun Header(
    state: MainViewModel.UiState,
    onOpenSettings: () -> Unit,
    viewModel: MainViewModel
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                "J.A.R.V.I.S.",
                color = JarvisGold,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp
            )
            Text(
                "Just A Rather Very Intelligent System",
                color = JarvisTextDim,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
        Spacer(Modifier.weight(1f))
        StatusPill(state.serviceState, state.accent)
        Spacer(Modifier.width(8.dp))
        if (state.serviceState == JarvisEvents.JarvisState.IDLE) {
            Button(
                onClick = viewModel::activate,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = state.accent,
                    contentColor = Color(0xFF00202A)
                )
            ) { Text("ACTIVATE", fontWeight = FontWeight.Bold) }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Filled.Settings, "Settings", tint = JarvisTextDim)
        }
    }
}

@Composable
private fun StatusPill(state: JarvisEvents.JarvisState, accent: Color) {
    val (color, label) = when (state) {
        JarvisEvents.JarvisState.WAKE -> accent to "WAKE"
        JarvisEvents.JarvisState.LISTENING -> JarvisGold to "LISTENING"
        JarvisEvents.JarvisState.THINKING -> Color(0xFFB388FF) to "THINKING"
        JarvisEvents.JarvisState.SPEAKING -> Color(0xFF69F0AE) to "SPEAKING"
        JarvisEvents.JarvisState.IDLE -> JarvisTextDim to "OFFLINE"
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(JarvisPanelHi)
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

// ------------------------------------------------------------------ chat

@Composable
private fun ChatList(
    state: MainViewModel.UiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(state.messages, key = { it.id }) { msg ->
            MessageBubble(msg)
        }
    }
}

@Composable
private fun MessageBubble(msg: MainViewModel.Message) {
    val isUser = msg.role == MainViewModel.Role.USER
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Row(
            Modifier.widthIn(max = 320.dp),
            horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
        ) {
            if (!isUser) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(JarvisPanelHi)
                        .border(1.5.dp, com.jarvis.assistant.ui.theme.JarvisCyan.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("J", color = com.jarvis.assistant.ui.theme.JarvisCyan, fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.width(8.dp))
            }
            Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                Text(
                    msg.text,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    textAlign = if (isUser) TextAlign.End else TextAlign.Start,
                    modifier = Modifier
                        .clip(bubbleShape(isUser))
                        .background(
                            if (isUser) com.jarvis.assistant.ui.theme.JarvisCyan.copy(alpha = 0.12f)
                            else JarvisPanel.copy(alpha = 0.9f)
                        )
                        .border(
                            1.dp,
                            if (isUser) com.jarvis.assistant.ui.theme.JarvisCyan.copy(alpha = 0.35f)
                            else JarvisPanelHi,
                            bubbleShape(isUser)
                        )
                        .padding(horizontal = 12.dp, vertical = 9.dp)
                )
                Spacer(Modifier.height(3.dp))
                if (!isUser) {
                    Text(msg.emotion.emoji, fontSize = 10.sp)
                }
            }
        }
    }
}

private fun bubbleShape(isUser: Boolean) = RoundedCornerShape(
    topStart = if (isUser) 14.dp else 4.dp,
    topEnd = if (isUser) 4.dp else 14.dp,
    bottomStart = 14.dp,
    bottomEnd = 14.dp
)

// ------------------------------------------------------------------ bottom

@Composable
private fun BottomBar(viewModel: MainViewModel, state: MainViewModel.UiState) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(JarvisPanel)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        // quick action chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip("Timer", Icons.Filled.Timer, state.accent) { viewModel.quickAction("timer") }
            QuickChip("Torch", Icons.Filled.FlashOn, state.accent) { viewModel.quickAction("torch") }
            QuickChip("Weather", Icons.Filled.WbSunny, state.accent) { viewModel.quickAction("weather") }
            QuickChip("Music", Icons.Filled.MusicNote, state.accent) { viewModel.quickAction("music") }
            QuickChip("Cricket", Icons.Filled.SportsCricket, state.accent) { viewModel.quickAction("cricket") }
        }

        if (state.partial.isNotBlank()) {
            Text(
                "\u201C${state.partial}\u201D",
                color = JarvisGold,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp)
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            var typed by remember { mutableStateOf("") }
            OutlinedTextField(
                value = typed,
                onValueChange = { typed = it },
                placeholder = { Text("Type karke bhi bol sakte ho...", color = JarvisTextDim) },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = state.accent,
                    unfocusedBorderColor = JarvisPanelHi,
                    focusedContainerColor = JarvisPanelHi,
                    unfocusedContainerColor = JarvisPanelHi,
                    cursorColor = state.accent,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
            Spacer(Modifier.width(8.dp))
            IconButton(
                onClick = { viewModel.sendText(typed); typed = "" },
                enabled = typed.isNotBlank(),
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(JarvisPanelHi)
            ) {
                Icon(Icons.Filled.Send, "Send", tint = state.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))

            // big mic button
            val listening = state.serviceState == JarvisEvents.JarvisState.LISTENING
            val busy = state.serviceState == JarvisEvents.JarvisState.THINKING ||
                state.serviceState == JarvisEvents.JarvisState.SPEAKING
            Box(
                Modifier
                    .size(58.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            if (listening) listOf(Color(0xFFFF5C5C), Color(0xFFB71C1C))
                            else listOf(state.accent, Color(0xFF0086A8))
                        )
                    )
                    .border(2.dp, JarvisGold.copy(alpha = 0.7f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (busy) {
                    CircularProgressIndicator(
                        color = JarvisGold,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    IconButton(
                        onClick = { if (listening) viewModel.stopListening() else viewModel.talk() }
                    ) {
                        Icon(
                            if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = "Talk",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuickChip(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(JarvisPanelHi)
            .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = accent, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, fontSize = 11.sp)
    }
}

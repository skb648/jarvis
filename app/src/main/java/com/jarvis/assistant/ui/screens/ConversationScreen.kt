package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Data model for a conversation message.
 */
data class ChatMessage(
    val id: Long,
    val content: String,
    val isFromUser: Boolean,
    val emotion: String = "neutral",
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Data model for a persisted chat session.
 *
 * Each session captures a complete conversation exchange, identified by
 * its first user message (as title/preview) and a timestamp. Sessions
 * are displayed in the ModalNavigationDrawer for quick switching, like
 * the official Gemini app.
 */
data class ChatSession(
    val id: Long,
    val title: String,
    val preview: String,
    val timestamp: Long,
    val messages: List<ChatMessage>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    messages: List<ChatMessage>,
    isTyping: Boolean,
    isVoiceMode: Boolean,
    onSendMessage: (String) -> Unit,
    onToggleVoice: () -> Unit,
    // ── Chat history drawer parameters ────────────────────────────────────
    chatSessions: List<ChatSession> = emptyList(),
    currentSessionId: Long = -1L,
    onLoadSession: (ChatSession) -> Unit = {},
    onNewChat: () -> Unit = {},
    onClearHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatHistoryDrawerContent(
                chatSessions = chatSessions,
                currentSessionId = currentSessionId,
                onLoadSession = { session ->
                    onLoadSession(session)
                    // Close drawer after selecting a session
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    onNewChat()
                    scope.launch { drawerState.close() }
                },
                onClearHistory = {
                    onClearHistory()
                    scope.launch { drawerState.close() }
                }
            )
        },
        modifier = modifier
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // ── Top App Bar with drawer toggle ─────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Hamburger menu button to open drawer
                IconButton(
                    onClick = { scope.launch { drawerState.open() } },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Menu,
                        contentDescription = "Chat history",
                        tint = TextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Text(
                    text = if (currentSessionId >= 0) "Chat" else "New Chat",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = TextPrimary,
                        fontFamily = FontFamily.Monospace
                    ),
                    modifier = Modifier.weight(1f)
                )

                // Spacer to balance the row
                Spacer(modifier = Modifier.size(44.dp))
            }

            // ── Message List ───────────────────────────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                items(items = messages, key = { it.id }) { message ->
                    MessageBubble(message = message)
                }

                // Typing indicator
                if (isTyping) {
                    item {
                        TypingIndicator()
                    }
                }
            }

            // ── Input Bar ──────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Voice toggle
                FilledIconButton(
                    onClick = onToggleVoice,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isVoiceMode) JarvisGreen else GlassBackground,
                        contentColor = if (isVoiceMode) DeepNavy else TextSecondary
                    )
                ) {
                    Icon(
                        imageVector = if (isVoiceMode) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = if (isVoiceMode) "Voice on" else "Voice off",
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Text input
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (isVoiceMode) "Listening..." else "Type a message...",
                            color = TextTertiary
                        )
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan.copy(alpha = 0.5f),
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceNavy,
                        unfocusedContainerColor = SurfaceNavy,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = JarvisCyan
                    ),
                    maxLines = 4,
                    enabled = !isVoiceMode
                )

                // Send button
                FilledIconButton(
                    onClick = {
                        if (inputText.isNotBlank()) {
                            onSendMessage(inputText.trim())
                            inputText = ""
                        }
                    },
                    modifier = Modifier.size(44.dp),
                    enabled = inputText.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = JarvisCyan,
                        contentColor = DeepNavy,
                        disabledContainerColor = SurfaceNavyLight,
                        disabledContentColor = TextTertiary
                    )
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHAT HISTORY DRAWER CONTENT
//
// A ModalNavigationDrawer content that displays past chat sessions in a
// list, like the official Gemini app. Includes:
//   - "New Chat" button at the top
//   - Scrollable list of past sessions (GlassmorphicCard styling)
//   - "Clear All History" button at the bottom
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChatHistoryDrawerContent(
    chatSessions: List<ChatSession>,
    currentSessionId: Long,
    onLoadSession: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onClearHistory: () -> Unit
) {
    ModalDrawerSheet(
        drawerContainerColor = DeepNavy,
        drawerContentColor = TextPrimary,
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 16.dp)
        ) {
            // ── Drawer header ──────────────────────────────────────────────
            Text(
                text = "Chat History",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = JarvisCyan,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(bottom = 16.dp, start = 8.dp)
            )

            // ── New Chat button ────────────────────────────────────────────
            GlassmorphicCardSimple(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                backgroundColor = JarvisCyan.copy(alpha = 0.12f),
                borderColor = JarvisCyan.copy(alpha = 0.3f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNewChat() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "New chat",
                        tint = JarvisCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "New Chat",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = JarvisCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            // ── Divider ────────────────────────────────────────────────────
            HorizontalDivider(
                color = GlassBorder,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // ── Session list ───────────────────────────────────────────────
            if (chatSessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No past sessions",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(items = chatSessions, key = { it.id }) { session ->
                        ChatSessionItem(
                            session = session,
                            isSelected = session.id == currentSessionId,
                            onClick = { onLoadSession(session) }
                        )
                    }
                }
            }

            // ── Divider ────────────────────────────────────────────────────
            HorizontalDivider(
                color = GlassBorder,
                thickness = 1.dp,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // ── Clear All History button ───────────────────────────────────
            GlassmorphicCardSimple(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                backgroundColor = JarvisRedPink.copy(alpha = 0.08f),
                borderColor = JarvisRedPink.copy(alpha = 0.25f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onClearHistory() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteSweep,
                        contentDescription = "Clear all history",
                        tint = JarvisRedPink,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "Clear All History",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = JarvisRedPink,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHAT SESSION ITEM
//
// A single row in the drawer's session list. Shows:
//   - Title (first user message, truncated)
//   - Preview (last message snippet)
//   - Relative timestamp
//   - Active indicator (cyan left border) for the current session
//
// Uses GlassmorphicCardSimple for consistent glass styling.
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ChatSessionItem(
    session: ChatSession,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isSelected) {
        JarvisCyan.copy(alpha = 0.10f)
    } else {
        GlassBackground
    }

    val borderColor = if (isSelected) {
        JarvisCyan.copy(alpha = 0.5f)
    } else {
        GlassBorder
    }

    GlassmorphicCardSimple(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        backgroundColor = bgColor,
        borderColor = borderColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Active indicator bar
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(40.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .then(
                            Modifier.clip(RoundedCornerShape(2.dp))
                        )
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(JarvisCyan)
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Session title — first user message
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = if (isSelected) JarvisCyan else TextPrimary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                // Preview — last message snippet
                Text(
                    text = session.preview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextTertiary
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Timestamp
            Text(
                text = formatRelativeTime(session.timestamp),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace
                )
            )
        }
    }
}

/**
 * Formats a timestamp into a human-readable relative time string.
 *
 * Examples: "Just now", "5m ago", "2h ago", "Yesterday", "Jan 15"
 */
private fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 60_000L      -> "Just now"
        diff < 3_600_000L   -> "${diff / 60_000L}m ago"
        diff < 86_400_000L  -> "${diff / 3_600_000L}h ago"
        diff < 172_800_000L -> "Yesterday"
        else -> {
            val sdf = SimpleDateFormat("MMM dd", Locale.getDefault())
            sdf.format(Date(timestamp))
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val emotionColor = when (message.emotion.lowercase()) {
        "happy", "joy"      -> JarvisGreen
        "sad"               -> Color(0xFF5588FF)
        "angry"             -> JarvisRedPink
        "surprised"         -> JarvisCyan
        "fearful"           -> Color(0xFFFF8800)
        "disgusted"         -> Color(0xFF88AA00)
        "neutral"           -> TextSecondary
        else                -> JarvisPurple
    }

    val borderColor = if (message.isFromUser) {
        JarvisCyan.copy(alpha = 0.4f)
    } else {
        emotionColor.copy(alpha = 0.4f)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start
    ) {
        // Emotion label above assistant messages
        if (!message.isFromUser && message.emotion != "neutral") {
            Text(
                text = message.emotion.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall.copy(
                    color = emotionColor,
                    fontFamily = FontFamily.Monospace
                ),
                modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)
            )
        }

        GlassmorphicCardSimple(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .then(
                    Modifier.clip(RoundedCornerShape(16.dp))
                ),
            backgroundColor = if (message.isFromUser) {
                JarvisCyan.copy(alpha = 0.08f)
            } else {
                GlassBackground
            },
            borderColor = borderColor
        ) {
            Text(
                text = message.content,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = TextPrimary
                )
            )
        }
    }
}

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing-anim")

    Row(
        modifier = Modifier
            .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val pulse by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = 500,
                        delayMillis = index * 150,
                        easing = EaseInOutSine
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "dot-$index"
            )
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(
                    color = JarvisCyan.copy(alpha = pulse),
                    radius = size.minDimension / 2f * (0.6f + pulse * 0.4f)
                )
            }
        }
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "thinking",
            style = MaterialTheme.typography.labelSmall.copy(
                color = TextTertiary,
                fontFamily = FontFamily.Monospace
            )
        )
    }
}

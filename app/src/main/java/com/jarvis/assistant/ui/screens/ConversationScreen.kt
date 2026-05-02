package com.jarvis.assistant.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Face
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Message status enum for delivery/read indicators.
 */
enum class MessageStatus {
    SENDING,    // Clock icon
    SENT,       // Single check
    DELIVERED,  // Double check
    READ        // Double check (colored)
}

/**
 * Data model for a conversation message.
 */
data class ChatMessage(
    val id: Long,
    val content: String,
    val isFromUser: Boolean,
    val emotion: String = "neutral",
    val timestamp: Long = System.currentTimeMillis(),
    val status: MessageStatus = if (isFromUser) MessageStatus.SENT else MessageStatus.READ,
    val isVoiceMessage: Boolean = false
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

// ─── Suggested prompts shown when conversation is empty ────────────────────
private val SuggestedPrompts = listOf(
    "What can you do?",
    "Tell me a joke",
    "What's the weather?",
    "Set a reminder",
    "Open YouTube"
)

// ─── Reaction emojis available on messages ─────────────────────────────────
private val ReactionOptions = listOf("👍", "❤️", "😂", "🤔", "👏")

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
    val focusRequester = remember { FocusRequester() }
    var isInputFocused by remember { mutableStateOf(false) }

    // ── Scroll-to-bottom FAB visibility ───────────────────────────────────
    // Show FAB when user has scrolled up more than 5 items from the bottom
    val showScrollToBottom by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            totalItems > 6 && lastVisibleIndex < totalItems - 5
        }
    }

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

            // ── Message List with Scroll-to-Bottom FAB ────────────────────
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) {
                    items(items = messages, key = { it.id }) { message ->
                        // ── Animated message entry ─────────────────────────
                        AnimatedVisibility(
                            visible = true,
                            enter = fadeIn(
                                animationSpec = tween(durationMillis = 350, easing = EaseOut)
                            ) + slideInVertically(
                                animationSpec = tween(durationMillis = 350, easing = EaseOutCubic),
                                initialOffsetY = { it / 3 }
                            )
                        ) {
                            MessageBubble(message = message)
                        }
                    }

                    // Typing indicator
                    if (isTyping) {
                        item {
                            AnimatedVisibility(
                                visible = true,
                                enter = fadeIn(tween(300)) + slideInVertically(
                                    tween(300),
                                    initialOffsetY = { it / 4 }
                                )
                            ) {
                                TypingIndicator()
                            }
                        }
                    }
                }

                // ── Scroll-to-Bottom FAB ───────────────────────────────────
                AnimatedVisibility(
                    visible = showScrollToBottom,
                    enter = fadeIn(tween(200)),
                    exit = androidx.compose.animation.fadeOut(tween(200)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 8.dp)
                ) {
                    GlassmorphicCardSimple(
                        backgroundColor = DeepNavy.copy(alpha = 0.85f),
                        borderColor = JarvisCyan.copy(alpha = 0.4f),
                        cornerRadius = 18.dp
                    ) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    if (messages.isNotEmpty()) {
                                        listState.animateScrollToItem(messages.size)
                                    }
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "Scroll to bottom",
                                tint = JarvisCyan,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }

            // ── Quick Actions Row (always visible) ───────────────────────────
            QuickActionsRow(onSendMessage = onSendMessage)

            // ── Suggested Prompts Section (only when no messages) ──────────
            if (messages.isEmpty()) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp)
                ) {
                    items(SuggestedPrompts) { prompt ->
                        FilterChip(
                            selected = false,
                            onClick = { onSendMessage(prompt) },
                            label = {
                                Text(
                                    text = prompt,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        color = TextSecondary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                )
                            },
                            border = FilterChipDefaults.filterChipBorder(
                                borderColor = JarvisCyan.copy(alpha = 0.4f),
                                selectedBorderColor = JarvisCyan,
                                enabled = true,
                                selected = false,
                                borderWidth = 1.dp
                            ),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = Color.Transparent,
                                selectedContainerColor = JarvisCyan.copy(alpha = 0.1f)
                            ),
                            shape = RoundedCornerShape(20.dp)
                        )
                    }
                }
            }

            // ── Enhanced Input Bar ────────────────────────────────────────
            // Animated glow border when focused
            val glowTransition = rememberInfiniteTransition(label = "input-glow")
            val glowAlpha by glowTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 0.8f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "glow-alpha"
            )

            val borderBrush = if (isInputFocused) {
                Brush.horizontalGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = glowAlpha),
                        JarvisPurple.copy(alpha = glowAlpha),
                        JarvisCyan.copy(alpha = glowAlpha)
                    )
                )
            } else {
                Brush.horizontalGradient(
                    colors = listOf(GlassBorder, GlassBorder)
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .drawBehind {
                        if (isInputFocused) {
                            // Subtle glow effect behind the input area
                            drawRoundRect(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(
                                        JarvisCyan.copy(alpha = glowAlpha * 0.15f),
                                        JarvisPurple.copy(alpha = glowAlpha * 0.10f),
                                        JarvisCyan.copy(alpha = glowAlpha * 0.15f)
                                    )
                                ),
                                cornerRadius = CornerRadius(28.dp.toPx())
                            )
                        }
                    },
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

                // Text input with animated glow border
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(28.dp))
                        .then(
                            if (isInputFocused) {
                                Modifier.background(
                                    brush = borderBrush,
                                    shape = RoundedCornerShape(28.dp)
                                )
                            } else {
                                Modifier.background(
                                    color = GlassBorder,
                                    shape = RoundedCornerShape(28.dp)
                                )
                            }
                        )
                        .padding(1.5.dp)
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .onFocusChanged { focusState ->
                                isInputFocused = focusState.isFocused
                            },
                        placeholder = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // ── Voice mode pulsing red dot ──────────
                                if (isVoiceMode) {
                                    PulsingRedDot()
                                }
                                Text(
                                    text = if (isVoiceMode) "Listening..." else "Type a message...",
                                    color = TextTertiary
                                )
                            }
                        },
                        shape = RoundedCornerShape(26.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                            focusedContainerColor = SurfaceNavy,
                            unfocusedContainerColor = SurfaceNavy,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            cursorColor = JarvisCyan
                        ),
                        maxLines = 4,
                        enabled = !isVoiceMode
                    )
                }

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

            // ── Voice Activity Indicator Bar (when voice mode is active) ──
            if (isVoiceMode) {
                VoiceActivityWaveform()
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// PULSING RED DOT — Voice mode indicator
//
// A small pulsing red circle that appears next to the "Listening..."
// placeholder when voice mode is active.
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun PulsingRedDot(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "red-dot-pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "red-dot-alpha"
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "red-dot-scale"
    )

    Box(modifier = modifier.size(8.dp), contentAlignment = Alignment.Center) {
        // Glow
        Canvas(modifier = Modifier.size(12.dp)) {
            drawCircle(
                color = JarvisRedPink.copy(alpha = pulseAlpha * 0.3f),
                radius = size.minDimension / 2f * pulseScale
            )
        }
        // Core dot
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(
                color = JarvisRedPink.copy(alpha = pulseAlpha),
                radius = size.minDimension / 2f
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CHAT HISTORY DRAWER CONTENT
//
// A ModalNavigationDrawer content that displays past chat sessions in a
// list, like the official Gemini app. Includes:
//   - Animated gradient header
//   - "New Chat" button at the top
//   - Scrollable list of past sessions (GlassmorphicCard styling)
//     with message count and chat bubble icon
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
            // ── Animated gradient header ───────────────────────────────
            val headerTransition = rememberInfiniteTransition(label = "drawer-header")
            val headerShift by headerTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "header-shift"
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .drawBehind {
                        drawRect(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    JarvisCyan.copy(alpha = 0.15f + headerShift * 0.10f),
                                    JarvisPurple.copy(alpha = 0.10f + (1f - headerShift) * 0.10f),
                                    JarvisCyan.copy(alpha = 0.12f + headerShift * 0.08f)
                                )
                            )
                        )
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp)
            ) {
                Column {
                    Text(
                        text = "Chat History",
                        style = MaterialTheme.typography.titleMedium.copy(
                            color = JarvisCyan,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "${chatSessions.size} session${if (chatSessions.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

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
//   - Chat bubble preview icon
//   - Title (first user message, truncated)
//   - Preview (last message snippet)
//   - Message count (e.g., "12 messages")
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
                ) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawRect(JarvisCyan)
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
            }

            // ── Chat bubble preview icon ───────────────────────────────
            Icon(
                imageVector = Icons.Filled.ChatBubbleOutline,
                contentDescription = "Chat session",
                tint = if (isSelected) JarvisCyan else TextTertiary,
                modifier = Modifier.size(18.dp)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
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
                Spacer(modifier = Modifier.height(2.dp))
                // ── Message count ──────────────────────────────────────
                Text(
                    text = "${session.messages.size} message${if (session.messages.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = TextTertiary.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    )
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

// ═══════════════════════════════════════════════════════════════════════════════
// MESSAGE BUBBLE — Enhanced with:
//   - Gradient backgrounds on user messages
//   - Message status indicators (sent, delivered, read)
//   - Voice waveform visualization for audio messages
//   - Reaction emoji bar
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageBubble(message: ChatMessage) {
    val emotionColor = when (message.emotion.lowercase()) {
        "happy", "joy"      -> JarvisGreen
        "sad"               -> Color(0xFF5588FF)
        "angry"             -> JarvisRedPink
        "surprised"         -> JarvisCyan
        "fearful"           -> JarvisOrange
        "disgusted"         -> Color(0xFF88AA00)
        "neutral"           -> TextSecondary
        else                -> JarvisPurple
    }

    val borderColor = if (message.isFromUser) {
        JarvisCyan.copy(alpha = 0.4f)
    } else {
        emotionColor.copy(alpha = 0.4f)
    }

    // ── Gradient backgrounds ─────────────────────────────────────────
    // User messages: dark cyan tint
    val userBgBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF002A33).copy(alpha = 0.8f),  // Dark cyan tint
            Color(0xFF001A2E).copy(alpha = 0.6f)   // Darker cyan-blue tint
        )
    )
    // AI messages: dark purple tint
    val aiBgBrush = Brush.verticalGradient(
        colors = listOf(
            Color(0xFF1A0A33).copy(alpha = 0.8f),  // Dark purple tint
            Color(0xFF120828).copy(alpha = 0.6f)   // Deeper purple tint
        )
    )

    // ── Reaction state ───────────────────────────────────────────────
    var selectedReaction by remember { mutableStateOf<String?>(null) }
    var showReactions by remember { mutableStateOf(false) }

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

        // ── AI avatar icon next to AI messages ──────────────────────
        if (!message.isFromUser) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // AI Avatar
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(JarvisPurple.copy(alpha = 0.2f))
                        .border(1.dp, JarvisPurple.copy(alpha = 0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "JARVIS AI",
                        tint = JarvisPurple.copy(alpha = 0.8f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Message bubble with gradient background
                MessageBubbleContent(
                    message = message,
                    aiBgBrush = aiBgBrush,
                    showReactions = showReactions,
                    onToggleReactions = { showReactions = !showReactions }
                )
            }
        } else {
            // User message bubble (no avatar)
            MessageBubbleContent(
                message = message,
                userBgBrush = userBgBrush,
                showReactions = showReactions,
                onToggleReactions = { showReactions = !showReactions }
            )
        }

        // ── Border overlay (drawn separately for gradient bg) ────────
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .offset(y = if (message.isVoiceMessage) (-40).dp else (-40).dp)
        ) {}

        // ── Reaction emoji bar ───────────────────────────────────────
        if (showReactions) {
            Row(
                modifier = Modifier
                    .padding(top = 4.dp, start = if (!message.isFromUser) 8.dp else 0.dp, end = if (message.isFromUser) 8.dp else 0.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceNavy.copy(alpha = 0.9f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                ReactionOptions.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(
                                if (selectedReaction == emoji) JarvisCyan.copy(alpha = 0.2f) else Color.Transparent
                            )
                            .clickable {
                                selectedReaction = if (selectedReaction == emoji) null else emoji
                                showReactions = false
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }

        // ── Selected reaction display ────────────────────────────────
        if (selectedReaction != null && !showReactions) {
            Text(
                text = selectedReaction!!,
                fontSize = 12.sp,
                modifier = Modifier.padding(
                    start = if (!message.isFromUser) 8.dp else 0.dp,
                    end = if (message.isFromUser) 8.dp else 0.dp,
                    top = 2.dp
                )
            )
        }

        // ── Message timestamp + status indicator ─────────────────────
        Row(
            modifier = Modifier
                .padding(
                    start = if (message.isFromUser) 0.dp else 8.dp,
                    end = if (message.isFromUser) 8.dp else 0.dp,
                    top = 2.dp
                )
                .align(if (message.isFromUser) Alignment.End else Alignment.Start),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = formatRelativeTime(message.timestamp),
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            )
            // ── Status indicator for user messages ────────────────────
            if (message.isFromUser) {
                MessageStatusIndicator(status = message.status)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MESSAGE BUBBLE CONTENT — Extracted helper for bubble rendering
// Supports both user and AI gradient backgrounds
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageBubbleContent(
    message: ChatMessage,
    userBgBrush: Brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF002A33).copy(alpha = 0.8f), Color(0xFF001A2E).copy(alpha = 0.6f))
    ),
    aiBgBrush: Brush = Brush.verticalGradient(
        colors = listOf(Color(0xFF1A0A33).copy(alpha = 0.8f), Color(0xFF120828).copy(alpha = 0.6f))
    ),
    showReactions: Boolean,
    onToggleReactions: () -> Unit
) {
    Box(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .clip(RoundedCornerShape(16.dp))
            .drawBehind {
                drawRoundRect(
                    brush = if (message.isFromUser) userBgBrush else aiBgBrush,
                    cornerRadius = CornerRadius(16.dp.toPx())
                )
            }
            .clickable { onToggleReactions() }
    ) {
        Column {
            if (message.isVoiceMessage) {
                // ── Voice message with animated equalizer bars ─────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Play button icon
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Play",
                        tint = JarvisCyan,
                        modifier = Modifier.size(28.dp)
                    )
                    // Animated equalizer bars
                    AnimatedEqualizerBars(
                        modifier = Modifier.weight(1f)
                    )
                }
            } else {
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
}

// ═══════════════════════════════════════════════════════════════════════════════
// ANIMATED EQUALIZER BARS — Animated bars next to voice messages
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun AnimatedEqualizerBars(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    val barCount = 5

    Row(
        modifier = modifier.height(28.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val barHeight by infiniteTransition.animateFloat(
                initialValue = 0.2f + (index % 3) * 0.1f,
                targetValue = 0.8f - (index % 2) * 0.15f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 800
                        0.2f at 0
                        0.9f at (100 + index * 80L)
                        0.3f at (300 + index * 60L)
                        0.7f at (500 + index * 70L)
                        0.2f at 800
                    },
                    repeatMode = RepeatMode.Restart
                ),
                label = "eq-bar-$index"
            )

            Canvas(modifier = Modifier.width(3.dp).fillMaxHeight()) {
                val barHeightPx = size.height * barHeight.coerceIn(0.15f, 1f)
                drawRoundRect(
                    color = if (index % 2 == 0) JarvisCyan.copy(alpha = 0.7f) else JarvisPurple.copy(alpha = 0.6f),
                    topLeft = Offset(0f, (size.height - barHeightPx) / 2f),
                    size = Size(size.width, barHeightPx),
                    cornerRadius = CornerRadius(size.width / 2f, size.width / 2f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MESSAGE STATUS INDICATOR — Shows sent/delivered/read state
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageStatusIndicator(status: MessageStatus) {
    when (status) {
        MessageStatus.SENDING -> {
            // Clock-like indicator (small dot)
            Canvas(modifier = Modifier.size(10.dp)) {
                drawCircle(
                    color = TextTertiary.copy(alpha = 0.5f),
                    radius = size.minDimension / 4f,
                    center = Offset(size.width / 2f, size.height / 2f)
                )
            }
        }
        MessageStatus.SENT -> {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Sent",
                tint = TextTertiary,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.DELIVERED -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Delivered",
                tint = TextTertiary,
                modifier = Modifier.size(12.dp)
            )
        }
        MessageStatus.READ -> {
            Icon(
                imageVector = Icons.Filled.DoneAll,
                contentDescription = "Read",
                tint = JarvisCyan,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VOICE WAVEFORM — Animated waveform visualization for audio messages
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun VoiceWaveform(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveform")
    val waveTime by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wave-time"
    )

    Canvas(modifier = modifier.height(32.dp)) {
        val barCount = 24
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barCount * barWidth) / (barCount - 1)
        val centerY = size.height / 2f

        for (i in 0 until barCount) {
            val progress = i.toFloat() / barCount
            // Create a realistic waveform pattern with animation
            val baseHeight = 4.dp.toPx() +
                (kotlin.math.sin(progress * Math.PI.toFloat()) * size.height * 0.35f)
            val animOffset = kotlin.math.sin(waveTime * 0.1f + i * 0.5f).toFloat() * 4.dp.toPx()
            val barHeight = (baseHeight + animOffset).coerceIn(4.dp.toPx(), size.height * 0.8f)

            val x = i * (barWidth + gap)
            drawRoundRect(
                color = JarvisCyan.copy(alpha = 0.6f + progress * 0.4f),
                topLeft = Offset(x, centerY - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TYPING INDICATOR — Three bouncing dots with orb pulse
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing-anim")

    // ── Orb pulse animation ───────────────────────────────────────────
    val orbPulse by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "orb-pulse"
    )

    // ── Bounce animation for dots ─────────────────────────────────────
    val bounceTransition = rememberInfiniteTransition(label = "bounce")

    Column(
        modifier = Modifier
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // ── Animated orb dot (pulsing circle) ─────────────────────
            Box(contentAlignment = Alignment.Center) {
                // Glow behind orb
                Canvas(modifier = Modifier.size(16.dp)) {
                    drawCircle(
                        color = JarvisCyan.copy(alpha = orbPulse * 0.2f),
                        radius = size.minDimension / 2f
                    )
                }
                // Core orb
                Canvas(modifier = Modifier.size(8.dp)) {
                    drawCircle(
                        color = JarvisPurple.copy(alpha = orbPulse),
                        radius = size.minDimension / 2f * (0.7f + orbPulse * 0.3f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(2.dp))

            // ── Three animated bouncing dots with glow ────────────────
            repeat(3) { index ->
                val bounceY by bounceTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = -6f,
                    animationSpec = infiniteRepeatable(
                        animation = keyframes {
                            durationMillis = 600
                            0f at 0
                            -6f at 150 + index * 100L
                            0f at 300 + index * 100L
                            0f at 600
                        },
                        repeatMode = RepeatMode.Restart
                    ),
                    label = "bounce-$index"
                )

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

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.offset(y = with(androidx.compose.ui.platform.LocalDensity.current) { bounceY.toDp() })
                ) {
                    // Glow behind dot
                    Canvas(modifier = Modifier.size(14.dp)) {
                        drawCircle(
                            color = JarvisCyan.copy(alpha = pulse * 0.15f),
                            radius = size.minDimension / 2f
                        )
                    }
                    // Core dot
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(
                            color = JarvisCyan.copy(alpha = pulse),
                            radius = size.minDimension / 2f * (0.6f + pulse * 0.4f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            // ── "JARVIS is typing" label ───────────────────────────────
            Text(
                text = "thinking...",
                style = MaterialTheme.typography.labelSmall.copy(
                    color = TextTertiary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp
                )
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VOICE ACTIVITY WAVEFORM — Horizontal animated waveform when voice is active
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun VoiceActivityWaveform(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "voice-waveform")
    val barCount = 32
    val timeOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 100f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ), label = "waveform-time"
    )

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(24.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val barWidth = size.width / (barCount * 1.5f)
        val maxHeight = size.height * 0.8f
        val minHeight = size.height * 0.15f

        for (i in 0 until barCount) {
            val phase = timeOffset * 0.15f + i * 0.4f
            val amplitude = 0.3f + 0.7f * (0.5f + 0.5f * kotlin.math.sin(phase.toDouble()).toFloat())
            val barHeight = minHeight + (maxHeight - minHeight) * amplitude
            val x = i * (barWidth * 1.5f)
            val y = (size.height - barHeight) / 2f

            drawRoundRect(
                color = JarvisGreen.copy(alpha = 0.3f + amplitude * 0.5f),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// QUICK ACTIONS ROW — Preset voice commands above input field
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun QuickActionsRow(onSendMessage: (String) -> Unit) {
    val quickActions = listOf(
        "What time is it?" to Icons.Filled.Schedule,
        "Set timer" to Icons.Filled.Timer,
        "Take screenshot" to Icons.Filled.PhotoCamera,
        "Tell me a joke" to Icons.Filled.Face
    )

    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(horizontal = 4.dp)
    ) {
        items(quickActions) { (label, icon) ->
            FilterChip(
                selected = false,
                onClick = { onSendMessage(label) },
                label = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(icon, contentDescription = label, modifier = Modifier.size(12.dp), tint = JarvisCyan)
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = TextSecondary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        )
                    }
                },
                border = FilterChipDefaults.filterChipBorder(
                    borderColor = JarvisCyan.copy(alpha = 0.3f),
                    selectedBorderColor = JarvisCyan,
                    enabled = true,
                    selected = false,
                    borderWidth = 1.dp
                ),
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = Color.Transparent,
                    selectedContainerColor = JarvisCyan.copy(alpha = 0.1f)
                ),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

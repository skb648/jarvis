package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.components.GlassmorphicCardSimple
import com.jarvis.assistant.ui.theme.*

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    messages: List<ChatMessage>,
    isTyping: Boolean,
    isVoiceMode: Boolean,
    onSendMessage: (String) -> Unit,
    onToggleVoice: () -> Unit,
    modifier: Modifier = Modifier
) {
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new messages arrive
    LaunchedEffect(messages.size, isTyping) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // ── Message List ────────────────────────────────────────────
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

        // ── Input Bar ───────────────────────────────────────────────
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
                    imageVector = Icons.Filled.Send,
                    contentDescription = "Send",
                    modifier = Modifier.size(20.dp)
                )
            }
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

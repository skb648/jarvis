package com.jarvis.assistant.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.*
import java.util.UUID

// ─── Data Model ──────────────────────────────────────────────────────────────

data class QuickNote(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val colorTag: Color = JarvisCyan
)

// ─── QuickNotesScreen ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickNotesScreen(
    modifier: Modifier = Modifier
) {
    var notes by remember { mutableStateOf(listOf<QuickNote>()) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var expandedNoteId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavy)
    ) {
        // ── Top Bar ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "QUICK NOTES",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = JarvisCyan,
                        letterSpacing = 2.sp
                    )
                )
                Text(
                    text = "${notes.size} note${if (notes.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary
                    )
                )
            }

            // Add Note FAB
            FilledIconButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = JarvisCyan,
                    contentColor = DeepNavy
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add note",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // ── Voice Hint ─────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Mic,
                contentDescription = "Voice",
                tint = JarvisCyan.copy(alpha = 0.6f),
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Say 'Note: buy groceries' to create via voice",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiary,
                    fontSize = 11.sp
                )
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── Notes List or Empty State ──────────────────────────────────────
        if (notes.isEmpty()) {
            // Empty state with pulsing icon
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Pulsing note icon
                    val infiniteTransition = rememberInfiniteTransition(label = "empty-note-pulse")
                    val pulseAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 0.9f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "empty-pulse-alpha"
                    )
                    val pulseScale by infiniteTransition.animateFloat(
                        initialValue = 0.95f,
                        targetValue = 1.05f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(1500, easing = EaseInOutSine),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "empty-pulse-scale"
                    )

                    Icon(
                        imageVector = Icons.Filled.NoteAlt,
                        contentDescription = "No notes",
                        tint = JarvisCyan.copy(alpha = pulseAlpha),
                        modifier = Modifier.size(64.dp)
                    )
                    Text(
                        text = "No notes yet",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextSecondary
                        )
                    )
                    Text(
                        text = "Tap + to create your first note",
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary,
                            textAlign = TextAlign.Center
                        )
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(items = notes, key = { it.id }) { note ->
                    NoteCard(
                        note = note,
                        isExpanded = expandedNoteId == note.id,
                        onClick = {
                            expandedNoteId = if (expandedNoteId == note.id) null else note.id
                        },
                        onLongPress = {
                            notes = notes.filterNot { it.id == note.id }
                        }
                    )
                }
            }
        }
    }

    // ── Create Note Dialog ─────────────────────────────────────────────────
    if (showCreateDialog) {
        NoteCreateDialog(
            onDismiss = { showCreateDialog = false },
            onSave = { title, content, colorTag ->
                notes = notes + QuickNote(
                    title = title,
                    content = content,
                    colorTag = colorTag
                )
                showCreateDialog = false
            }
        )
    }
}

// ─── Note Card ───────────────────────────────────────────────────────────────

@Composable
private fun NoteCard(
    note: QuickNote,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    // Staggered entry animation
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)) + slideInVertically(
            tween(400, easing = EaseOutCubic),
            initialOffsetY = { it / 3 }
        ),
        exit = fadeOut(tween(200))
    ) {
        // Glassmorphic card with color tag indicator on the left
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            SurfaceNavyLight.copy(alpha = 0.7f),
                            SurfaceNavy.copy(alpha = 0.5f)
                        )
                    )
                )
                .border(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            note.colorTag.copy(alpha = 0.3f),
                            GlassBorder.copy(alpha = 0.5f)
                        )
                    ),
                    shape = RoundedCornerShape(16.dp)
                )
                .clickable { onClick() }
        ) {
            // Color tag indicator on the left
            Canvas(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (isExpanded) 120.dp else 80.dp)
            ) {
                drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            note.colorTag,
                            note.colorTag.copy(alpha = 0.4f)
                        )
                    )
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(14.dp)
            ) {
                // Title row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Canvas(modifier = Modifier.size(8.dp)) {
                        drawCircle(color = note.colorTag)
                    }
                    Text(
                        text = note.title.ifBlank { "Untitled" },
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = note.colorTag
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (isExpanded) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = JarvisRedPink.copy(alpha = 0.7f),
                            modifier = Modifier
                                .size(18.dp)
                                .clickable { onLongPress() }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Content
                Text(
                    text = note.content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = TextSecondary,
                        lineHeight = 16.sp
                    ),
                    maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Timestamp
                val sdf = java.text.SimpleDateFormat("MMM dd, HH:mm", java.util.Locale.getDefault())
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Schedule,
                        contentDescription = "Time",
                        tint = TextTertiary.copy(alpha = 0.5f),
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = sdf.format(java.util.Date(note.timestamp)),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary,
                            fontSize = 9.sp
                        )
                    )
                }

                // Long-press hint when expanded
                if (isExpanded) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Tap delete icon to remove",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = TextTertiary.copy(alpha = 0.5f),
                            fontSize = 9.sp
                        )
                    )
                }
            }
        }
    }
}

// ─── Note Create Dialog ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoteCreateDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Color) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var selectedColor by remember { mutableStateOf(JarvisCyan) }

    val colorOptions = listOf(
        JarvisCyan to "Cyan",
        JarvisPurple to "Purple",
        JarvisGreen to "Green",
        WarningAmber to "Amber",
        JarvisRedPink to "Pink"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceNavy,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.EditNote,
                    contentDescription = null,
                    tint = JarvisCyan,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = "New Note",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = JarvisCyan
                    )
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Title input
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = {
                        Text(
                            text = "Title",
                            color = TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceNavyLight,
                        unfocusedContainerColor = SurfaceNavyLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = JarvisCyan
                    ),
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Title,
                            null,
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                // Content input
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = {
                        Text(
                            text = "Content",
                            color = TextTertiary,
                            fontFamily = FontFamily.Monospace
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = JarvisCyan,
                        unfocusedBorderColor = GlassBorder,
                        focusedContainerColor = SurfaceNavyLight,
                        unfocusedContainerColor = SurfaceNavyLight,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = JarvisCyan
                    ),
                    leadingIcon = {
                        Icon(
                            Icons.Filled.Notes,
                            null,
                            tint = TextTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                )

                // Color tag selector
                Text(
                    text = "Color Tag",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextSecondary
                    )
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colorOptions.forEach { (color, label) ->
                        val isSelected = selectedColor == color
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) color.copy(alpha = 0.25f)
                                    else Color.Transparent
                                )
                                .border(
                                    width = if (isSelected) 2.dp else 1.dp,
                                    color = if (isSelected) color else GlassBorder,
                                    shape = RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedColor = color },
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(14.dp)) {
                                drawCircle(
                                    color = color,
                                    alpha = if (isSelected) 1f else 0.5f
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (title.isNotBlank() || content.isNotBlank()) {
                        onSave(
                            title.ifBlank { "Quick Note" },
                            content,
                            selectedColor
                        )
                    }
                },
                enabled = title.isNotBlank() || content.isNotBlank()
            ) {
                Text(
                    text = "Save",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        color = if (title.isNotBlank() || content.isNotBlank()) JarvisCyan else TextTertiary
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = "Cancel",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary
                    )
                )
            }
        }
    )
}

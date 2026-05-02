package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.components.GlassmorphicCard
import com.jarvis.assistant.ui.theme.*

// ─── AI Status Constants ────────────────────────────────────────────────────

private const val STATUS_IDLE = "IDLE"
private const val STATUS_SEEING = "SEEING"
private const val STATUS_ACTING = "ACTING"
private const val STATUS_DONE = "DONE"

// ─── ComputerUseScreen ──────────────────────────────────────────────────────

/**
 * AI Computer Use Screen — JARVIS Edition
 *
 * Like OpenAI Codex / Claw Computer Use: the AI can control a virtual
 * mouse cursor on the screen, see what's on screen, and interact with apps.
 *
 * Features:
 *  - Large canvas area showing "Screen Mirror" placeholder
 *  - Floating AI-controlled cursor with pulsing cyan glow
 *  - Status indicators: AI Active, Screen Capture, Cursor Mode
 *  - Command input bar for natural-language instructions
 *  - "Take Control" / "Stop" buttons
 *  - Action log showing AI's performed actions
 *  - AI status: IDLE, SEEING, ACTING, DONE
 *  - Animated cursor with smooth movement
 *  - Glassmorphic panels and JARVIS sci-fi theme
 */
@Composable
fun ComputerUseScreen(
    modifier: Modifier = Modifier,
    isAiActive: Boolean = false,
    cursorX: Float = 0.5f,
    cursorY: Float = 0.5f,
    aiStatus: String = STATUS_IDLE,
    actionLog: List<String> = emptyList(),
    onCommand: (String) -> Unit = {},
    onTakeControl: () -> Unit = {},
    onStop: () -> Unit = {}
) {
    // ── Internal command text state ─────────────────────────────────────────
    var commandText by remember { mutableStateOf("") }

    // ── Animated cursor position (smooth interpolation) ─────────────────────
    val animatedCursorX by animateFloatAsState(
        targetValue = cursorX,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "cursor-x"
    )
    val animatedCursorY by animateFloatAsState(
        targetValue = cursorY,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "cursor-y"
    )

    // ── Pulse animation for cursor glow ─────────────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "cursor-pulse")
    val cursorPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursor-pulse-val"
    )

    // ── Scan line animation ─────────────────────────────────────────────────
    val scanLineProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(3500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan-line-progress"
    )

    // ── Status color mapping ────────────────────────────────────────────────
    val statusColor = when (aiStatus) {
        STATUS_IDLE -> TextTertiary
        STATUS_SEEING -> JarvisPurple
        STATUS_ACTING -> JarvisCyan
        STATUS_DONE -> JarvisGreen
        else -> TextTertiary
    }

    val statusIcon = when (aiStatus) {
        STATUS_IDLE -> Icons.Filled.HourglassEmpty
        STATUS_SEEING -> Icons.Filled.Visibility
        STATUS_ACTING -> Icons.Filled.TouchApp
        STATUS_DONE -> Icons.Filled.CheckCircle
        else -> Icons.Filled.HourglassEmpty
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(DeepNavy, GradientMid, DeepNavy)
                )
            )
    ) {
        // ═══ TOP BAR: Title + Status ═══════════════════════════════════════
        TopBar(
            isAiActive = isAiActive,
            aiStatus = aiStatus,
            statusColor = statusColor,
            statusIcon = statusIcon
        )

        // ═══ SCREEN MIRROR CANVAS ══════════════════════════════════════════
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            ScreenMirrorCanvas(
                isAiActive = isAiActive,
                animatedCursorX = animatedCursorX,
                animatedCursorY = animatedCursorY,
                cursorPulse = cursorPulse,
                scanLineProgress = scanLineProgress,
                aiStatus = aiStatus,
                statusColor = statusColor
            )
        }

        // ═══ STATUS INDICATORS ROW ════════════════════════════════════════
        StatusIndicatorsRow(
            isAiActive = isAiActive,
            aiStatus = aiStatus,
            statusColor = statusColor
        )

        // ═══ ACTION LOG ═══════════════════════════════════════════════════
        if (actionLog.isNotEmpty()) {
            ActionLogPanel(actionLog = actionLog)
        }

        // ═══ BOTTOM BAR: Control buttons + Command input ═════════════════
        BottomControlBar(
            isAiActive = isAiActive,
            commandText = commandText,
            onCommandTextChange = { commandText = it },
            onCommand = {
                if (commandText.isNotBlank()) {
                    onCommand(commandText.trim())
                    commandText = ""
                }
            },
            onTakeControl = onTakeControl,
            onStop = onStop
        )
    }
}

// ─── Top Bar ────────────────────────────────────────────────────────────────

@Composable
private fun TopBar(
    isAiActive: Boolean,
    aiStatus: String,
    statusColor: Color,
    statusIcon: androidx.compose.ui.graphics.vector.ImageVector
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Title
        Column {
            Text(
                text = "COMPUTER USE",
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = JarvisCyan,
                    letterSpacing = 3.sp
                )
            )
            Text(
                text = "AI-Controlled Interface",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiary
                )
            )
        }

        // Status badge
        GlassmorphicCard(
            backgroundColor = statusColor.copy(alpha = 0.08f),
            borderColor = statusColor.copy(alpha = 0.25f),
            cornerRadius = 12.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = "AI Status",
                    tint = statusColor,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = aiStatus,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = statusColor,
                        letterSpacing = 1.sp
                    )
                )
            }
        }
    }

    // Divider
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .padding(horizontal = 16.dp)
    ) {
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    JarvisCyan.copy(alpha = 0.25f),
                    JarvisPurple.copy(alpha = 0.35f),
                    JarvisCyan.copy(alpha = 0.25f),
                    Color.Transparent
                )
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1f
        )
    }
}

// ─── Screen Mirror Canvas ───────────────────────────────────────────────────

@Composable
private fun ScreenMirrorCanvas(
    isAiActive: Boolean,
    animatedCursorX: Float,
    animatedCursorY: Float,
    cursorPulse: Float,
    scanLineProgress: Float,
    aiStatus: String,
    statusColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceNavyDeep)
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = 0.15f),
                        GlassBorder,
                        JarvisPurple.copy(alpha = 0.15f)
                    )
                ),
                shape = RoundedCornerShape(16.dp)
            ),
        contentAlignment = Alignment.Center
    ) {
        // Grid background canvas
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSpacing = 40.dp.toPx()
            val gridAlpha = 0.06f

            // Draw grid lines
            for (x in 0..(size.width / gridSpacing).toInt()) {
                drawLine(
                    color = JarvisCyan.copy(alpha = gridAlpha),
                    start = Offset(x * gridSpacing, 0f),
                    end = Offset(x * gridSpacing, size.height),
                    strokeWidth = 0.5f
                )
            }
            for (y in 0..(size.height / gridSpacing).toInt()) {
                drawLine(
                    color = JarvisCyan.copy(alpha = gridAlpha),
                    start = Offset(0f, y * gridSpacing),
                    end = Offset(size.width, y * gridSpacing),
                    strokeWidth = 0.5f
                )
            }

            // Scan line
            val scanY = scanLineProgress * size.height
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisCyan.copy(alpha = 0.15f),
                        JarvisPurple.copy(alpha = 0.2f),
                        JarvisCyan.copy(alpha = 0.15f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = size.width
                ),
                start = Offset(0f, scanY),
                end = Offset(size.width, scanY),
                strokeWidth = 2.dp.toPx()
            )
            // Scan line glow
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisCyan.copy(alpha = 0.03f),
                        Color.Transparent
                    ),
                    startY = scanY - 15.dp.toPx(),
                    endY = scanY + 15.dp.toPx()
                ),
                topLeft = Offset(0f, scanY - 15.dp.toPx()),
                size = Size(size.width, 30.dp.toPx())
            )

            // Corner brackets
            val bracketLen = 30.dp.toPx()
            val bracketStroke = 2.dp.toPx()
            val bracketColor = JarvisCyan.copy(alpha = 0.2f)
            val margin = 16.dp.toPx()

            // Top-left
            drawLine(bracketColor, Offset(margin, margin), Offset(margin + bracketLen, margin), bracketStroke)
            drawLine(bracketColor, Offset(margin, margin), Offset(margin, margin + bracketLen), bracketStroke)
            // Top-right
            drawLine(bracketColor, Offset(size.width - margin, margin), Offset(size.width - margin - bracketLen, margin), bracketStroke)
            drawLine(bracketColor, Offset(size.width - margin, margin), Offset(size.width - margin, margin + bracketLen), bracketStroke)
            // Bottom-left
            drawLine(bracketColor, Offset(margin, size.height - margin), Offset(margin + bracketLen, size.height - margin), bracketStroke)
            drawLine(bracketColor, Offset(margin, size.height - margin), Offset(margin, size.height - margin - bracketLen), bracketStroke)
            // Bottom-right
            drawLine(bracketColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin - bracketLen, size.height - margin), bracketStroke)
            drawLine(bracketColor, Offset(size.width - margin, size.height - margin), Offset(size.width - margin, size.height - margin - bracketLen), bracketStroke)
        }

        // Placeholder text when AI is not active
        if (!isAiActive) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Monitor,
                    contentDescription = "Screen Mirror",
                    tint = TextTertiary.copy(alpha = 0.4f),
                    modifier = Modifier.size(64.dp)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "SCREEN MIRROR",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary.copy(alpha = 0.5f),
                        letterSpacing = 3.sp
                    )
                )
                Text(
                    text = "Tap \"Take Control\" to begin",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextTertiary.copy(alpha = 0.35f)
                    )
                )
            }
        }

        // ── AI Cursor Overlay ───────────────────────────────────────────────
        if (isAiActive) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cx = animatedCursorX * size.width
                val cy = animatedCursorY * size.height

                // Outer glow ring
                val outerR = 28.dp.toPx() * (0.8f + cursorPulse * 0.4f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.15f * cursorPulse),
                            JarvisCyan.copy(alpha = 0.05f * cursorPulse),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = outerR
                    ),
                    radius = outerR,
                    center = Offset(cx, cy)
                )

                // Middle glow ring
                val midR = 16.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.3f * cursorPulse),
                            JarvisCyan.copy(alpha = 0.1f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = midR
                    ),
                    radius = midR,
                    center = Offset(cx, cy)
                )

                // Core dot
                val coreR = 5.dp.toPx()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.95f),
                            JarvisCyan.copy(alpha = 0.9f),
                            JarvisCyan.copy(alpha = 0.5f)
                        ),
                        center = Offset(cx, cy),
                        radius = coreR
                    ),
                    radius = coreR,
                    center = Offset(cx, cy)
                )

                // Crosshair lines
                val crossLen = 22.dp.toPx()
                val crossAlpha = 0.35f * cursorPulse
                drawLine(
                    color = JarvisCyan.copy(alpha = crossAlpha),
                    start = Offset(cx - crossLen, cy),
                    end = Offset(cx - coreR - 2.dp.toPx(), cy),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = JarvisCyan.copy(alpha = crossAlpha),
                    start = Offset(cx + coreR + 2.dp.toPx(), cy),
                    end = Offset(cx + crossLen, cy),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = JarvisCyan.copy(alpha = crossAlpha),
                    start = Offset(cx, cy - crossLen),
                    end = Offset(cx, cy - coreR - 2.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
                drawLine(
                    color = JarvisCyan.copy(alpha = crossAlpha),
                    start = Offset(cx, cy + coreR + 2.dp.toPx()),
                    end = Offset(cx, cy + crossLen),
                    strokeWidth = 1.dp.toPx()
                )

                // Rotating arc around cursor (when ACTING)
                if (aiStatus == STATUS_ACTING) {
                    val arcR = 20.dp.toPx()
                    val rotationAngle = (cursorPulse * 360f) % 360f
                    drawArc(
                        color = JarvisCyan.copy(alpha = 0.5f * cursorPulse),
                        startAngle = rotationAngle,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(cx - arcR, cy - arcR),
                        size = Size(arcR * 2, arcR * 2),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    drawArc(
                        color = JarvisPurple.copy(alpha = 0.4f * cursorPulse),
                        startAngle = rotationAngle + 180f,
                        sweepAngle = 60f,
                        useCenter = false,
                        topLeft = Offset(cx - arcR, cy - arcR),
                        size = Size(arcR * 2, arcR * 2),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                }

                // Coordinate label
                val coordX = (animatedCursorX * 1080).toInt()
                val coordY = (animatedCursorY * 1920).toInt()
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = JarvisCyan.copy(alpha = 0.6f).toArgb()
                        textSize = 9.dp.toPx()
                        typeface = android.graphics.Typeface.MONOSPACE
                    }
                    drawText("($coordX, $coordY)", cx + 14.dp.toPx(), cy - 8.dp.toPx(), paint)
                }
            }
        }
    }
}

// ─── Status Indicators Row ──────────────────────────────────────────────────

@Composable
private fun StatusIndicatorsRow(
    isAiActive: Boolean,
    aiStatus: String,
    statusColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // AI Active indicator
        StatusChip(
            icon = if (isAiActive) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            label = if (isAiActive) "AI Active" else "AI Inactive",
            color = if (isAiActive) JarvisGreen else TextTertiary,
            isActive = isAiActive
        )

        // Screen Capture indicator
        StatusChip(
            icon = Icons.Filled.Screenshot,
            label = if (isAiActive) "Capture: ON" else "Capture: OFF",
            color = if (isAiActive) JarvisCyan else TextTertiary,
            isActive = isAiActive
        )

        // Cursor Mode indicator
        StatusChip(
            icon = Icons.Filled.Mouse,
            label = "Cursor: Auto",
            color = if (isAiActive) JarvisPurple else TextTertiary,
            isActive = isAiActive
        )
    }
}

@Composable
private fun StatusChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    color: Color,
    isActive: Boolean
) {
    GlassmorphicCard(
        backgroundColor = if (isActive) color.copy(alpha = 0.06f) else SurfaceNavyLight.copy(alpha = 0.4f),
        borderColor = if (isActive) color.copy(alpha = 0.2f) else GlassBorder.copy(alpha = 0.3f),
        cornerRadius = 10.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color.copy(alpha = if (isActive) 0.9f else 0.5f),
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = color.copy(alpha = if (isActive) 0.9f else 0.5f),
                    fontSize = 9.sp,
                    letterSpacing = 0.5.sp
                )
            )
        }
    }
}

// ─── Action Log Panel ───────────────────────────────────────────────────────

@Composable
private fun ActionLogPanel(actionLog: List<String>) {
    GlassmorphicCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        backgroundColor = SurfaceNavyDeep.copy(alpha = 0.6f),
        borderColor = JarvisCyan.copy(alpha = 0.12f),
        cornerRadius = 12.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "Action Log",
                    tint = JarvisCyan.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = "ACTION LOG",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = JarvisCyan.copy(alpha = 0.6f),
                        letterSpacing = 1.sp,
                        fontSize = 9.sp
                    )
                )
            }

            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 80.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(actionLog.takeLast(6)) { logEntry ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Canvas(modifier = Modifier.size(4.dp)) {
                            drawCircle(JarvisCyan.copy(alpha = 0.5f))
                        }
                        Text(
                            text = logEntry,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextSecondary,
                                fontSize = 9.sp
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

// ─── Bottom Control Bar ─────────────────────────────────────────────────────

@Composable
private fun BottomControlBar(
    isAiActive: Boolean,
    commandText: String,
    onCommandTextChange: (String) -> Unit,
    onCommand: () -> Unit,
    onTakeControl: () -> Unit,
    onStop: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Control buttons row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (!isAiActive) {
                // Take Control button
                Button(
                    onClick = onTakeControl,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = JarvisCyan.copy(alpha = 0.15f),
                        contentColor = JarvisCyan
                    ),
                    border = BorderStroke(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(JarvisCyan, JarvisPurple)
                        )
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = "Take Control",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "TAKE CONTROL",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    )
                }
            } else {
                // Stop button
                OutlinedButton(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = JarvisRedPink
                    ),
                    border = BorderStroke(
                        width = 1.5.dp,
                        brush = Brush.horizontalGradient(
                            colors = listOf(JarvisRedPink, JarvisOrange)
                        )
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "STOP",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )
                    )
                }
            }

            // Reset button (always visible)
            GlassmorphicCard(
                backgroundColor = SurfaceNavyLight.copy(alpha = 0.6f),
                borderColor = GlassBorder,
                cornerRadius = 12.dp
            ) {
                IconButton(
                    onClick = onStop,
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Reset",
                        tint = TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Command input bar
        GlassmorphicCard(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = SurfaceNavyLight.copy(alpha = 0.5f),
            borderColor = if (isAiActive) JarvisCyan.copy(alpha = 0.2f) else GlassBorder.copy(alpha = 0.3f),
            cornerRadius = 14.dp,
            animatedBorder = isAiActive
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Command icon
                Icon(
                    imageVector = Icons.Filled.Terminal,
                    contentDescription = "Command",
                    tint = if (isAiActive) JarvisCyan.copy(alpha = 0.7f) else TextTertiary,
                    modifier = Modifier
                        .size(18.dp)
                        .padding(start = 8.dp)
                )

                Spacer(Modifier.width(4.dp))

                // Text input
                OutlinedTextField(
                    value = commandText,
                    onValueChange = onCommandTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        Text(
                            text = if (isAiActive) "e.g. click on Settings, scroll down, tap Install"
                            else "Activate AI to enter commands...",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = TextTertiary.copy(alpha = 0.5f)
                            ),
                            maxLines = 1
                        )
                    },
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = TextPrimary
                    ),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onCommand() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = JarvisCyan
                    ),
                    enabled = isAiActive
                )

                // Send button
                IconButton(
                    onClick = onCommand,
                    enabled = isAiActive && commandText.isNotBlank(),
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = "Send",
                        tint = if (isAiActive && commandText.isNotBlank())
                            JarvisCyan
                        else
                            TextTertiary.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

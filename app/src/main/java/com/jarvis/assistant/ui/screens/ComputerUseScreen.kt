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
import androidx.compose.material.icons.automirrored.filled.Send
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
import com.jarvis.assistant.automation.AutonomousAgentEngine
import com.jarvis.assistant.channels.JarviewModel
import com.jarvis.assistant.ui.components.GlassmorphicCard
import com.jarvis.assistant.ui.theme.*
import kotlinx.coroutines.delay

// ─── AI Status Constants ────────────────────────────────────────────────────

private const val STATUS_IDLE = "IDLE"
private const val STATUS_SEEING = "SEEING"
private const val STATUS_THINKING = "THINKING"
private const val STATUS_ACTING = "ACTING"
private const val STATUS_OBSERVING = "OBSERVING"
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
 *  - AI status: IDLE, SEEING, THINKING, ACTING, OBSERVING, DONE
 *  - Animated cursor with smooth movement, connected to OverlayCursorService
 *  - Agent state display from AutonomousAgentEngine
 *  - Action log merged from both external source and AutonomousAgentEngine
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
    onStop: () -> Unit = {},
    screenTextData: String = "",
    aiThinkingText: String = "",
    currentRound: Int = 0,
    maxRounds: Int = AutonomousAgentEngine.MAX_AUTONOMOUS_ROUNDS
) {
    // ── Internal command text state ─────────────────────────────────────────
    var commandText by remember { mutableStateOf("") }

    // ── Real cursor position from overlay service ──────────────────────────
    var realCursorX by remember { mutableStateOf(JarviewModel.cursorX) }
    var realCursorY by remember { mutableStateOf(JarviewModel.cursorY) }

    // Update from JarviewModel events
    DisposableEffect(Unit) {
        val removeListener = JarviewModel.addEventListener { event, data ->
            if (event == "cursor_moved") {
                realCursorX = (data["x"] as? Int) ?: realCursorX
                realCursorY = (data["y"] as? Int) ?: realCursorY
            }
        }
        onDispose { removeListener() }
    }

    // ── Observe AutonomousAgentEngine state ─────────────────────────────────
    var agentState by remember {
        mutableStateOf(AutonomousAgentEngine.AgentState.IDLE)
    }
    LaunchedEffect(Unit) {
        while (true) {
            agentState = AutonomousAgentEngine.getState()
            delay(200)
        }
    }

    // ── Normalize real cursor position to 0-1 for Canvas ───────────────────
    val effectiveCursorX = if (JarviewModel.screenWidth > 0 && JarviewModel.computerUseActive)
        realCursorX.toFloat() / JarviewModel.screenWidth.toFloat() else cursorX
    val effectiveCursorY = if (JarviewModel.screenHeight > 0 && JarviewModel.computerUseActive)
        realCursorY.toFloat() / JarviewModel.screenHeight.toFloat() else cursorY

    // ── Animated cursor position (smooth interpolation) ─────────────────────
    val animatedCursorX by animateFloatAsState(
        targetValue = effectiveCursorX,
        animationSpec = tween(durationMillis = 600, easing = EaseInOutCubic),
        label = "cursor-x"
    )
    val animatedCursorY by animateFloatAsState(
        targetValue = effectiveCursorY,
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
        STATUS_SEEING -> InfoBlue
        STATUS_THINKING -> JarvisPurple
        STATUS_ACTING -> JarvisCyan
        STATUS_OBSERVING -> WarningAmber
        STATUS_DONE -> JarvisGreen
        else -> TextTertiary
    }

    val statusIcon = when (aiStatus) {
        STATUS_IDLE -> Icons.Filled.HourglassEmpty
        STATUS_SEEING -> Icons.Filled.Visibility
        STATUS_THINKING -> Icons.Filled.Psychology
        STATUS_ACTING -> Icons.Filled.TouchApp
        STATUS_OBSERVING -> Icons.Filled.RemoveRedEye
        STATUS_DONE -> Icons.Filled.CheckCircle
        else -> Icons.Filled.HourglassEmpty
    }

    // ── Agent state display color ────────────────────────────────────────────
    val agentStateColor = when (agentState) {
        AutonomousAgentEngine.AgentState.SEEING -> InfoBlue
        AutonomousAgentEngine.AgentState.THINKING -> JarvisPurple
        AutonomousAgentEngine.AgentState.ACTING -> JarvisCyan
        AutonomousAgentEngine.AgentState.OBSERVING -> WarningAmber
        AutonomousAgentEngine.AgentState.COMPLETED -> JarvisGreen
        AutonomousAgentEngine.AgentState.FAILED -> JarvisRedPink
        AutonomousAgentEngine.AgentState.IDLE -> TextTertiary
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
                statusColor = statusColor,
                screenTextData = screenTextData,
                aiThinkingText = aiThinkingText,
                currentRound = currentRound,
                maxRounds = maxRounds
            )
        }

        // ═══ STATUS INDICATORS ROW ════════════════════════════════════════
        StatusIndicatorsRow(
            isAiActive = isAiActive,
            aiStatus = aiStatus,
            statusColor = statusColor,
            agentState = agentState,
            agentStateColor = agentStateColor
        )

        // ═══ ACTION LOG ═══════════════════════════════════════════════════
        val agentActionLog = remember(agentState) {
            AutonomousAgentEngine.actionLog.takeLast(10).map {
                "[R${it.round}] ${it.toolName}: ${it.result.take(60)}"
            }
        }
        val combinedLog = (actionLog + agentActionLog).takeLast(10)
        if (combinedLog.isNotEmpty()) {
            ActionLogPanel(actionLog = combinedLog)
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
            onTakeControl = {
                onTakeControl()
                // Also start the real cursor overlay
                val svc = JarviewModel.overlayCursorService?.get()
                svc?.setAiControlMode(true)
                svc?.showCursor()
            },
            onStop = {
                onStop()
                val svc = JarviewModel.overlayCursorService?.get()
                svc?.setAiControlMode(false)
            }
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
    statusColor: Color,
    screenTextData: String,
    aiThinkingText: String,
    currentRound: Int,
    maxRounds: Int
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

            // Scan line — CYAN + PURPLE gradient, 3dp thick with enhanced glow
            val scanY = scanLineProgress * size.height
            val scanStrokeW = 3.dp.toPx()
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisCyan.copy(alpha = 0.6f),
                        JarvisPurple.copy(alpha = 0.7f),
                        JarvisCyan.copy(alpha = 0.6f),
                        Color.Transparent
                    ),
                    startX = 0f,
                    endX = size.width
                ),
                start = Offset(0f, scanY),
                end = Offset(size.width, scanY),
                strokeWidth = scanStrokeW
            )
            // Scan line glow — wide outer glow for visibility
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisCyan.copy(alpha = 0.12f),
                        JarvisPurple.copy(alpha = 0.08f),
                        JarvisCyan.copy(alpha = 0.12f),
                        Color.Transparent
                    ),
                    startY = scanY - 36.dp.toPx(),
                    endY = scanY + 36.dp.toPx()
                ),
                topLeft = Offset(0f, scanY - 36.dp.toPx()),
                size = Size(size.width, 72.dp.toPx())
            )
            // Inner bright glow behind scan line
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.White.copy(alpha = 0.08f),
                        Color.White.copy(alpha = 0.12f),
                        Color.White.copy(alpha = 0.08f),
                        Color.Transparent
                    ),
                    startY = scanY - 12.dp.toPx(),
                    endY = scanY + 12.dp.toPx()
                ),
                topLeft = Offset(0f, scanY - 12.dp.toPx()),
                size = Size(size.width, 24.dp.toPx())
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

        // ── Live Screen Text Summary (LazyColumn with monospace lines) ──
        if (isAiActive) {
            GlassmorphicCard(
                backgroundColor = SurfaceNavyDeep.copy(alpha = 0.80f),
                borderColor = JarvisCyan.copy(alpha = 0.18f),
                cornerRadius = 10.dp,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .align(Alignment.TopCenter)
                    .padding(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Visibility,
                            contentDescription = "Screen",
                            tint = InfoBlue,
                            modifier = Modifier.size(10.dp)
                        )
                        Text(
                            text = "SCREEN CONTENT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = InfoBlue,
                                fontSize = 8.sp,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    if (screenTextData.isBlank()) {
                        // Show "Reading screen..." when no data yet
                        Text(
                            text = "Reading screen...",
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                color = JarvisCyan.copy(alpha = 0.5f),
                                fontSize = 8.sp,
                                lineHeight = 10.sp
                            )
                        )
                    } else {
                        // LazyColumn with line-by-line screen text
                        val lines = screenTextData.lines().take(12)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 72.dp),
                            verticalArrangement = Arrangement.spacedBy(0.dp)
                        ) {
                            items(lines) { line ->
                                Text(
                                    text = line.ifBlank { " " },
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        color = JarvisCyan.copy(alpha = 0.55f),
                                        fontSize = 8.sp,
                                        lineHeight = 10.sp
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

        // ── AI Thinking Overlay (bottom-left) ────────────────────────────
        if (isAiActive && aiThinkingText.isNotBlank()) {
            GlassmorphicCard(
                backgroundColor = JarvisPurple.copy(alpha = 0.08f),
                borderColor = JarvisPurple.copy(alpha = 0.25f),
                cornerRadius = 8.dp,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(10.dp)
                    .fillMaxWidth(0.7f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "Thinking",
                        tint = JarvisPurple,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = aiThinkingText.take(120),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = JarvisPurple.copy(alpha = 0.9f),
                            fontSize = 9.sp
                        ),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        // ── Coordinates Badge (top-right) ────────────────────────────────
        if (isAiActive) {
            val coordX = (animatedCursorX * JarviewModel.screenWidth).toInt()
            val coordY = (animatedCursorY * JarviewModel.screenHeight).toInt()
            GlassmorphicCard(
                backgroundColor = JarvisCyan.copy(alpha = 0.08f),
                borderColor = JarvisCyan.copy(alpha = 0.2f),
                cornerRadius = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MyLocation,
                        contentDescription = "Coords",
                        tint = JarvisCyan,
                        modifier = Modifier.size(9.dp)
                    )
                    Text(
                        text = "X:$coordX Y:$coordY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = JarvisCyan,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
        }

        // ── Round Counter Badge (top-left corner) ────────────────────
        if (isAiActive && currentRound > 0) {
            GlassmorphicCard(
                backgroundColor = WarningAmber.copy(alpha = 0.08f),
                borderColor = WarningAmber.copy(alpha = 0.2f),
                cornerRadius = 6.dp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Round",
                        tint = WarningAmber,
                        modifier = Modifier.size(9.dp)
                    )
                    Text(
                        text = "R$currentRound/$maxRounds",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = WarningAmber,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
            }
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

                // Coordinate label — drawn as a small overlay text via drawStyle
                // Using drawText would require TextMeasurer; instead we draw a simple
                // coordinate indicator using drawRect markers for the position
                val coordX = (animatedCursorX * 1080).toInt()
                val coordY = (animatedCursorY * 1920).toInt()
                // Draw a small tick mark at the label position instead of native text
                drawLine(
                    color = JarvisCyan.copy(alpha = 0.4f),
                    start = Offset(cx + 8.dp.toPx(), cy - 8.dp.toPx()),
                    end = Offset(cx + 8.dp.toPx(), cy - 4.dp.toPx()),
                    strokeWidth = 1.dp.toPx()
                )
            }
        }
    }
}

// ─── Status Indicators Row ──────────────────────────────────────────────────

@Composable
private fun StatusIndicatorsRow(
    isAiActive: Boolean,
    aiStatus: String,
    statusColor: Color,
    agentState: AutonomousAgentEngine.AgentState,
    agentStateColor: Color
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

        // Agent State indicator
        if (isAiActive && agentState != AutonomousAgentEngine.AgentState.IDLE) {
            StatusChip(
                icon = when (agentState) {
                    AutonomousAgentEngine.AgentState.SEEING -> Icons.Filled.Visibility
                    AutonomousAgentEngine.AgentState.THINKING -> Icons.Filled.Psychology
                    AutonomousAgentEngine.AgentState.ACTING -> Icons.Filled.TouchApp
                    AutonomousAgentEngine.AgentState.OBSERVING -> Icons.Filled.RemoveRedEye
                    AutonomousAgentEngine.AgentState.COMPLETED -> Icons.Filled.CheckCircle
                    AutonomousAgentEngine.AgentState.FAILED -> Icons.Filled.Error
                    AutonomousAgentEngine.AgentState.IDLE -> Icons.Filled.HourglassEmpty
                },
                label = agentState.name,
                color = agentStateColor,
                isActive = true
            )
        }
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
                    .heightIn(max = 120.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items(actionLog.takeLast(10)) { logEntry ->
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
                        imageVector = Icons.AutoMirrored.Filled.Send,
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

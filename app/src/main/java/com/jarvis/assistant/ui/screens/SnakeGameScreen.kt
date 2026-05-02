package com.jarvis.assistant.ui.screens

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.*

// ─── Game Constants ───────────────────────────────────────────────────────────
private const val GRID_SIZE = 20          // 20x20 grid
private const val INITIAL_SPEED = 180L    // ms per tick
private const val SPEED_INCREMENT = 3L    // ms faster per food eaten
private const val MIN_SPEED = 70L         // fastest possible

// ─── Direction Enum ───────────────────────────────────────────────────────────
enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    fun opposite(): Direction = when (this) {
        UP -> DOWN; DOWN -> UP; LEFT -> RIGHT; RIGHT -> LEFT
    }
}

// ─── Data Classes ─────────────────────────────────────────────────────────────
data class Position(val x: Int, val y: Int)

data class Particle(
    val x: Float, val y: Float,
    val vx: Float, val vy: Float,
    val life: Float, val maxLife: Float,
    val size: Float
)

data class TrailDot(
    val x: Float, val y: Float,
    val alpha: Float, val radius: Float
)

// ─── SnakeGameScreen ──────────────────────────────────────────────────────────
/**
 * Bubble-Type 3D Cool Snake Game — JARVIS Edition
 *
 * A visually stunning snake game that matches the JARVIS holographic
 * aesthetic with bubble-style snake body, 3D depth effects, glowing
 * food orbs, particle explosions, and trailing afterglow.
 *
 * Features:
 * - Bubble-style snake with radial gradients (cyan core → purple edge)
 * - 3D tunnel illusion: bubbles shrink and fade toward the tail
 * - Glowing orb food items (mini JARVIS hologram orbs)
 * - Dark navy grid background with scan lines
 * - Particle burst effects when food is eaten
 * - Fading trail effect behind the snake
 * - Swipe gesture controls
 * - Voice command integration hint
 * - Score counter with JARVIS styling
 * - Game over screen with restart
 */
@Composable
fun SnakeGameScreen(
    modifier: Modifier = Modifier,
    onVoiceDirection: Direction? = null  // External voice command input
) {
    // ── Game State ────────────────────────────────────────────────────────
    var snake by remember { mutableStateOf(listOf(Position(10, 10), Position(9, 10), Position(8, 10))) }
    var direction by remember { mutableStateOf(Direction.RIGHT) }
    var nextDirection by remember { mutableStateOf(Direction.RIGHT) }
    var food by remember { mutableStateOf(Position(15, 10)) }
    var score by remember { mutableIntStateOf(0) }
    var highScore by remember { mutableIntStateOf(0) }
    var isGameOver by remember { mutableStateOf(false) }
    var isPaused by remember { mutableStateOf(false) }
    var speed by remember { mutableLongStateOf(INITIAL_SPEED) }
    var particles by remember { mutableStateOf(listOf<Particle>()) }
    var trailDots by remember { mutableStateOf(listOf<TrailDot>()) }
    var foodPulse by remember { mutableFloatStateOf(0f) }
    var justAte by remember { mutableStateOf(false) }

    // ── Infinite transitions for animations ───────────────────────────────
    val infiniteTransition = rememberInfiniteTransition(label = "snake-anim")

    val foodGlow by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "food-glow"
    )

    val scanLineOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "scan-line"
    )

    val gridPulse by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "grid-pulse"
    )

    // ── Voice command handler ─────────────────────────────────────────────
    LaunchedEffect(onVoiceDirection) {
        onVoiceDirection?.let {
            if (it != direction.opposite()) {
                nextDirection = it
            }
        }
    }

    // ── Spawn food at random empty position ───────────────────────────────
    fun spawnFood(): Position {
        val occupied = snake.toSet()
        val available = mutableListOf<Position>()
        for (x in 0 until GRID_SIZE) {
            for (y in 0 until GRID_SIZE) {
                if (Position(x, y) !in occupied) {
                    available.add(Position(x, y))
                }
            }
        }
        return if (available.isNotEmpty()) available.random() else Position(0, 0)
    }

    // ── Create particle burst at position ─────────────────────────────────
    fun createParticles(gridX: Int, gridY: Int) {
        val newParticles = mutableListOf<Particle>()
        for (i in 0 until 16) {
            val angle = (i * 22.5f + (Math.random() * 15f - 7.5f)) * (PI / 180f)
            val speed = 1.5f + (Math.random() * 3f).toFloat()
            newParticles.add(
                Particle(
                    x = gridX.toFloat(), y = gridY.toFloat(),
                    vx = (speed * cos(angle)).toFloat(),
                    vy = (speed * sin(angle)).toFloat(),
                    life = 1f, maxLife = 1f,
                    size = 2f + (Math.random() * 3f).toFloat()
                )
            )
        }
        particles = particles + newParticles
    }

    // ── Game loop ─────────────────────────────────────────────────────────
    LaunchedEffect(isGameOver, isPaused) {
        while (!isGameOver && !isPaused) {
            delay(speed)

            direction = nextDirection

            val head = snake.first()
            val newHead = Position(
                (head.x + direction.dx + GRID_SIZE) % GRID_SIZE,
                (head.y + direction.dy + GRID_SIZE) % GRID_SIZE
            )

            // Collision check
            if (newHead in snake) {
                isGameOver = true
                if (score > highScore) highScore = score
                break
            }

            val ate = newHead == food
            justAte = ate

            val newSnake = mutableListOf(newHead)
            newSnake.addAll(snake)
            if (!ate) {
                newSnake.removeAt(newSnake.lastIndex)
            } else {
                score += 10
                speed = (speed - SPEED_INCREMENT).coerceAtLeast(MIN_SPEED)
                createParticles(food.x, food.y)
                food = spawnFood()
            }

            snake = newSnake
        }
    }

    // ── Particle animation loop ───────────────────────────────────────────
    LaunchedEffect(particles.isNotEmpty()) {
        while (true) {
            delay(33) // ~30fps for particles
            if (particles.isNotEmpty()) {
                particles = particles.mapNotNull { p ->
                    val newLife = p.life - 0.04f
                    if (newLife > 0f) {
                        p.copy(
                            x = p.x + p.vx * 0.05f,
                            y = p.y + p.vy * 0.05f,
                            life = newLife
                        )
                    } else null
                }
            }
            // Fade trail dots
            trailDots = trailDots.mapNotNull { dot ->
                val newAlpha = dot.alpha - 0.03f
                if (newAlpha > 0f) dot.copy(alpha = newAlpha) else null
            }
        }
    }

    // ── Update trail ──────────────────────────────────────────────────────
    LaunchedEffect(snake.first()) {
        val head = snake.first()
        trailDots = trailDots + TrailDot(
            x = head.x.toFloat(), y = head.y.toFloat(),
            alpha = 0.4f, radius = 0.35f
        )
        // Limit trail length
        if (trailDots.size > 100) {
            trailDots = trailDots.takeLast(100)
        }
    }

    // ── Swipe gesture handler ─────────────────────────────────────────────
    fun handleSwipe(dx: Float, dy: Float) {
        if (isGameOver) return
        if (abs(dx) > abs(dy)) {
            if (dx > 0 && direction != Direction.LEFT) nextDirection = Direction.RIGHT
            else if (dx < 0 && direction != Direction.RIGHT) nextDirection = Direction.LEFT
        } else {
            if (dy > 0 && direction != Direction.UP) nextDirection = Direction.DOWN
            else if (dy < 0 && direction != Direction.DOWN) nextDirection = Direction.UP
        }
    }

    // ── Restart game ──────────────────────────────────────────────────────
    fun restart() {
        snake = listOf(Position(10, 10), Position(9, 10), Position(8, 10))
        direction = Direction.RIGHT
        nextDirection = Direction.RIGHT
        food = spawnFood()
        score = 0
        speed = INITIAL_SPEED
        isGameOver = false
        particles = emptyList()
        trailDots = emptyList()
    }

    // ── UI ────────────────────────────────────────────────────────────────
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(DeepNavy)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    handleSwipe(dragAmount.x, dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Score Bar ──────────────────────────────────────────────
            ScoreBar(score = score, highScore = highScore)

            // ── Game Canvas ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                GameCanvas(
                    snake = snake,
                    food = food,
                    particles = particles,
                    trailDots = trailDots,
                    foodGlow = foodGlow,
                    scanLineOffset = scanLineOffset,
                    gridPulse = gridPulse,
                    isGameOver = isGameOver,
                    gridSize = GRID_SIZE
                )
            }

            // ── Controls hint ──────────────────────────────────────────
            VoiceControlHint()

            Spacer(Modifier.height(8.dp))
        }

        // ── Game Over Overlay ─────────────────────────────────────────
        if (isGameOver) {
            GameOverOverlay(
                score = score,
                highScore = highScore,
                onRestart = { restart() }
            )
        }
    }
}

// ─── Score Bar ────────────────────────────────────────────────────────────────
@Composable
private fun ScoreBar(score: Int, highScore: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = "SCORE",
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Text(
                text = "$score",
                color = JarvisCyan,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Center JARVIS badge
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(8.dp)) {
                drawCircle(JarvisCyan, radius = size.minDimension / 2f)
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "SNAKE",
                color = JarvisPurple,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 3.sp
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "HIGH SCORE",
                color = TextTertiary,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Text(
                text = "$highScore",
                color = JarvisGreen,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    // Divider line
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
                    JarvisCyan.copy(alpha = 0.3f),
                    JarvisPurple.copy(alpha = 0.5f),
                    JarvisCyan.copy(alpha = 0.3f),
                    Color.Transparent
                )
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, 0f),
            strokeWidth = 1f
        )
    }
}

// ─── Voice Control Hint ──────────────────────────────────────────────────────
@Composable
private fun VoiceControlHint() {
    val infinite = rememberInfiniteTransition(label = "hint-pulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "hint-pulse-val"
    )

    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Canvas(modifier = Modifier.size(6.dp)) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        JarvisGreen.copy(alpha = pulse),
                        JarvisGreen.copy(alpha = pulse * 0.3f),
                        Color.Transparent
                    )
                ),
                radius = size.minDimension / 2f
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "Say 'UP', 'DOWN', 'LEFT', 'RIGHT' to control!",
            color = JarvisGreen.copy(alpha = 0.5f + pulse * 0.3f),
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 1.sp
        )
    }
}

// ─── Main Game Canvas ────────────────────────────────────────────────────────
@Composable
private fun GameCanvas(
    snake: List<Position>,
    food: Position,
    particles: List<Particle>,
    trailDots: List<TrailDot>,
    foodGlow: Float,
    scanLineOffset: Float,
    gridPulse: Float,
    isGameOver: Boolean,
    gridSize: Int
) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val cellW = size.width / gridSize
        val cellH = size.height / gridSize
        val cellSize = minOf(cellW, cellH)
        val offsetX = (size.width - cellSize * gridSize) / 2f
        val offsetY = (size.height - cellSize * gridSize) / 2f

        // ═══ BACKGROUND: Dark navy grid with scan lines ═══════════════════
        // Grid lines
        for (i in 0..gridSize) {
            val lineAlpha = if (i % 5 == 0) gridPulse else gridPulse * 0.3f
            drawLine(
                color = JarvisCyan.copy(alpha = lineAlpha),
                start = Offset(offsetX + i * cellSize, offsetY),
                end = Offset(offsetX + i * cellSize, offsetY + gridSize * cellSize),
                strokeWidth = if (i % 5 == 0) 1f else 0.5f
            )
            drawLine(
                color = JarvisCyan.copy(alpha = lineAlpha),
                start = Offset(offsetX, offsetY + i * cellSize),
                end = Offset(offsetX + gridSize * cellSize, offsetY + i * cellSize),
                strokeWidth = if (i % 5 == 0) 1f else 0.5f
            )
        }

        // Scan line moving across the field
        val scanY = offsetY + scanLineOffset * gridSize * cellSize
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Transparent,
                    JarvisCyan.copy(alpha = 0.12f),
                    JarvisPurple.copy(alpha = 0.15f),
                    JarvisCyan.copy(alpha = 0.12f),
                    Color.Transparent
                ),
                startX = offsetX,
                endX = offsetX + gridSize * cellSize
            ),
            start = Offset(offsetX, scanY),
            end = Offset(offsetX + gridSize * cellSize, scanY),
            strokeWidth = 2.dp.toPx()
        )

        // Subtle scan line glow
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color.Transparent,
                    JarvisCyan.copy(alpha = 0.03f),
                    Color.Transparent
                ),
                startY = scanY - 20.dp.toPx(),
                endY = scanY + 20.dp.toPx()
            ),
            topLeft = Offset(offsetX, scanY - 20.dp.toPx()),
            size = Size(gridSize * cellSize, 40.dp.toPx())
        )

        // ═══ TRAIL EFFECT: Fading trail behind the snake ══════════════════
        trailDots.forEach { dot ->
            val dotX = offsetX + dot.x * cellSize + cellSize / 2f
            val dotY = offsetY + dot.y * cellSize + cellSize / 2f
            val dotR = cellSize * dot.radius
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = dot.alpha * 0.5f),
                        JarvisPurple.copy(alpha = dot.alpha * 0.2f),
                        Color.Transparent
                    ),
                    center = Offset(dotX, dotY),
                    radius = dotR
                ),
                radius = dotR,
                center = Offset(dotX, dotY)
            )
        }

        // ═══ FOOD: Glowing JARVIS-style orb ═══════════════════════════════
        val foodCx = offsetX + food.x * cellSize + cellSize / 2f
        val foodCy = offsetY + food.y * cellSize + cellSize / 2f
        val foodR = cellSize * 0.38f

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    JarvisGreen.copy(alpha = 0.3f * foodGlow),
                    JarvisGreen.copy(alpha = 0.1f * foodGlow),
                    Color.Transparent
                ),
                center = Offset(foodCx, foodCy),
                radius = foodR * 3f
            ),
            radius = foodR * 3f,
            center = Offset(foodCx, foodCy)
        )

        // Main orb body
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.9f * foodGlow),
                    JarvisGreen.copy(alpha = 0.8f),
                    JarvisGreen.copy(alpha = 0.3f),
                    Color.Transparent
                ),
                center = Offset(foodCx - foodR * 0.2f, foodCy - foodR * 0.2f),
                radius = foodR * 1.2f
            ),
            radius = foodR,
            center = Offset(foodCx, foodCy)
        )

        // Food rim
        drawCircle(
            color = JarvisGreen.copy(alpha = 0.7f * foodGlow),
            radius = foodR,
            center = Offset(foodCx, foodCy),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Inner bright core
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color.White.copy(alpha = 0.95f * foodGlow),
                    JarvisGreen.copy(alpha = 0.6f),
                    Color.Transparent
                ),
                center = Offset(foodCx, foodCy),
                radius = foodR * 0.4f
            ),
            radius = foodR * 0.25f,
            center = Offset(foodCx, foodCy)
        )

        // Food orbiting ring
        val orbitR = foodR * 1.3f
        for (i in 0 until 6) {
            val arcAngle = i * 60f
            drawArc(
                color = JarvisGreen.copy(alpha = 0.4f * foodGlow),
                startAngle = arcAngle,
                sweepAngle = 25f,
                useCenter = false,
                topLeft = Offset(foodCx - orbitR, foodCy - orbitR),
                size = Size(orbitR * 2, orbitR * 2),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // ═══ SNAKE: Bubble-style with 3D depth effect ═════════════════════
        snake.forEachIndexed { index, segment ->
            val segCx = offsetX + segment.x * cellSize + cellSize / 2f
            val segCy = offsetY + segment.y * cellSize + cellSize / 2f

            // 3D depth: size and alpha decrease toward the tail
            val depthFactor = 1f - (index.toFloat() / snake.size) * 0.55f
            val bubbleR = cellSize * 0.40f * depthFactor
            val bubbleAlpha = (0.95f - index.toFloat() / snake.size * 0.5f).coerceIn(0.3f, 1f)

            // Color interpolation: head is cyan, tail fades to purple
            val colorLerp = index.toFloat() / snake.size

            if (index == 0) {
                // ═══ HEAD: Extra large, bright, with glow ══════════════════
                val headR = cellSize * 0.44f

                // Head outer glow
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.35f),
                            JarvisCyan.copy(alpha = 0.12f),
                            Color.Transparent
                        ),
                        center = Offset(segCx, segCy),
                        radius = headR * 2.5f
                    ),
                    radius = headR * 2.5f,
                    center = Offset(segCx, segCy)
                )

                // Head main body
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.6f),
                            JarvisCyan.copy(alpha = 0.95f),
                            JarvisCyan.copy(alpha = 0.6f),
                            JarvisPurple.copy(alpha = 0.2f)
                        ),
                        center = Offset(segCx - headR * 0.25f, segCy - headR * 0.25f),
                        radius = headR * 1.3f
                    ),
                    radius = headR,
                    center = Offset(segCx, segCy)
                )

                // Head specular highlight
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.7f),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        center = Offset(segCx - headR * 0.3f, segCy - headR * 0.3f),
                        radius = headR * 0.35f
                    ),
                    radius = headR * 0.35f,
                    center = Offset(segCx - headR * 0.3f, segCy - headR * 0.3f)
                )

                // Head rim
                drawCircle(
                    color = JarvisCyan.copy(alpha = 0.8f),
                    radius = headR,
                    center = Offset(segCx, segCy),
                    style = Stroke(width = 2.dp.toPx())
                )

                // Head bright core
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.9f),
                            JarvisCyan.copy(alpha = 0.7f),
                            Color.Transparent
                        ),
                        center = Offset(segCx, segCy),
                        radius = headR * 0.35f
                    ),
                    radius = headR * 0.2f,
                    center = Offset(segCx, segCy)
                )

                // Head orbiting ring
                val headOrbitR = headR * 1.25f
                for (i in 0 until 8) {
                    drawArc(
                        color = JarvisCyan.copy(alpha = 0.3f),
                        startAngle = i * 45f,
                        sweepAngle = 20f,
                        useCenter = false,
                        topLeft = Offset(segCx - headOrbitR, segCy - headOrbitR),
                        size = Size(headOrbitR * 2, headOrbitR * 2),
                        style = Stroke(width = 1.dp.toPx())
                    )
                }
            } else {
                // ═══ BODY BUBBLE: Gradient from cyan to purple ════════════

                // Subtle glow per bubble
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JarvisCyan.copy(alpha = 0.12f * bubbleAlpha),
                            Color.Transparent
                        ),
                        center = Offset(segCx, segCy),
                        radius = bubbleR * 2f
                    ),
                    radius = bubbleR * 2f,
                    center = Offset(segCx, segCy)
                )

                // Main bubble body — radial gradient cyan core → purple edge
                val coreColor = lerp(JarvisCyan, JarvisPurple, colorLerp)
                val edgeColor = lerp(JarvisCyan.copy(alpha = 0.3f), JarvisPurple.copy(alpha = 0.5f), colorLerp)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.3f * bubbleAlpha),
                            coreColor.copy(alpha = 0.9f * bubbleAlpha),
                            coreColor.copy(alpha = 0.6f * bubbleAlpha),
                            edgeColor,
                            Color.Transparent
                        ),
                        center = Offset(segCx - bubbleR * 0.2f, segCy - bubbleR * 0.2f),
                        radius = bubbleR * 1.3f
                    ),
                    radius = bubbleR,
                    center = Offset(segCx, segCy)
                )

                // Specular highlight
                val specAlpha = (0.4f * depthFactor * bubbleAlpha).coerceIn(0f, 0.5f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = specAlpha),
                            Color.White.copy(alpha = 0.0f)
                        ),
                        center = Offset(segCx - bubbleR * 0.25f, segCy - bubbleR * 0.25f),
                        radius = bubbleR * 0.3f
                    ),
                    radius = bubbleR * 0.3f,
                    center = Offset(segCx - bubbleR * 0.25f, segCy - bubbleR * 0.25f)
                )

                // Bubble rim
                drawCircle(
                    color = coreColor.copy(alpha = 0.5f * bubbleAlpha),
                    radius = bubbleR,
                    center = Offset(segCx, segCy),
                    style = Stroke(width = (1f + depthFactor).dp.toPx() * 0.5f)
                )
            }
        }

        // ═══ PARTICLES: Burst effects when food is eaten ══════════════════
        particles.forEach { p ->
            val pX = offsetX + p.x * cellSize + cellSize / 2f + p.vx * cellSize
            val pY = offsetY + p.y * cellSize + cellSize / 2f + p.vy * cellSize
            val pR = p.size * (p.life / p.maxLife) * cellSize * 0.1f

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = p.life * 0.9f),
                        JarvisCyan.copy(alpha = p.life * 0.7f),
                        JarvisGreen.copy(alpha = p.life * 0.3f),
                        Color.Transparent
                    ),
                    center = Offset(pX, pY),
                    radius = pR * 3f
                ),
                radius = pR,
                center = Offset(pX, pY)
            )
        }

        // ═══ BORDER: Glowing game border ══════════════════════════════════
        val borderAlpha = 0.3f + gridPulse * 0.2f
        drawRoundRect(
            color = JarvisCyan.copy(alpha = borderAlpha),
            topLeft = Offset(offsetX, offsetY),
            size = Size(gridSize * cellSize, gridSize * cellSize),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Corner accents
        val cornerLen = cellSize * 1.5f
        val corners = listOf(
            Offset(offsetX, offsetY) to listOf(Offset(cornerLen, 0f), Offset(0f, cornerLen)),
            Offset(offsetX + gridSize * cellSize, offsetY) to listOf(Offset(-cornerLen, 0f), Offset(0f, cornerLen)),
            Offset(offsetX, offsetY + gridSize * cellSize) to listOf(Offset(cornerLen, 0f), Offset(0f, -cornerLen)),
            Offset(offsetX + gridSize * cellSize, offsetY + gridSize * cellSize) to listOf(Offset(-cornerLen, 0f), Offset(0f, -cornerLen))
        )
        corners.forEach { (corner, dirs) ->
            dirs.forEach { dir ->
                drawLine(
                    color = JarvisPurple.copy(alpha = 0.6f),
                    start = corner,
                    end = Offset(corner.x + dir.x, corner.y + dir.y),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }
    }
}

// ─── Game Over Overlay ───────────────────────────────────────────────────────
@Composable
private fun GameOverOverlay(score: Int, highScore: Int, onRestart: () -> Unit) {
    val infinite = rememberInfiniteTransition(label = "gameover")
    val pulse by infinite.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "gameover-pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0E21).copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Glowing circle indicator
            Canvas(modifier = Modifier.size(60.dp)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            JarvisRedPink.copy(alpha = 0.5f * pulse),
                            JarvisRedPink.copy(alpha = 0.15f * pulse),
                            Color.Transparent
                        ),
                        center = center,
                        radius = size.minDimension / 2f
                    ),
                    radius = size.minDimension / 2f,
                    center = center
                )
                drawCircle(
                    color = JarvisRedPink.copy(alpha = 0.8f),
                    radius = size.minDimension / 2f,
                    center = center,
                    style = Stroke(width = 2.dp.toPx())
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "GAME OVER",
                color = JarvisRedPink.copy(alpha = pulse),
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 4.sp
            )

            Spacer(Modifier.height(8.dp))

            Canvas(modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(1.dp)
            ) {
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            JarvisRedPink.copy(alpha = 0.5f),
                            JarvisPurple.copy(alpha = 0.5f),
                            JarvisRedPink.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "SCORE: $score",
                color = JarvisCyan,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(8.dp))

            Text(
                text = "BEST: $highScore",
                color = TextSecondary,
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(Modifier.height(24.dp))

            // Restart button
            OutlinedButton(
                onClick = onRestart,
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 2.dp,
                    brush = Brush.horizontalGradient(
                        colors = listOf(JarvisCyan, JarvisPurple)
                    )
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = JarvisCyan
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Restart",
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "RESTART",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp
                )
            }

            Spacer(Modifier.height(16.dp))

            Text(
                text = "SWIPE TO PLAY AGAIN",
                color = TextTertiary,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
        }
    }
}

// ─── Utility: Color interpolation ────────────────────────────────────────────
private fun lerp(start: Color, end: Color, fraction: Float): Color {
    return Color(
        red = start.red + (end.red - start.red) * fraction,
        green = start.green + (end.green - start.green) * fraction,
        blue = start.blue + (end.blue - start.blue) * fraction,
        alpha = start.alpha + (end.alpha - start.alpha) * fraction
    )
}

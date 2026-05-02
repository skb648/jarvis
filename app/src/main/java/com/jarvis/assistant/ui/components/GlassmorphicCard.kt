package com.jarvis.assistant.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.ui.theme.*

/**
 * A reusable composable that renders a glassmorphic card.
 *
 * Features:
 *  - Semi-transparent background with blur-like effect (simulated via layered alpha)
 *  - Rounded corners (16 dp)
 *  - Subtle border (1 dp, semi-transparent white)
 *  - Optional gradient overlay drawn behind content
 *  - Optional shimmer/highlight effect on the top edge
 *  - Optional animated gradient border
 *  - Optional depth shadow with color tint
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    gradientOverlay: Brush? = null,
    backgroundColor: Color = GlassBackground,
    borderColor: Color = GlassBorder,
    cornerRadius: Dp = 16.dp,
    borderWidth: Dp = 1.dp,
    showShimmer: Boolean = false,
    animatedBorder: Boolean = false,
    depthShadowColor: Color = Color.Transparent,
    content: @Composable ColumnScope.() -> Unit
) {
    val shimmerAlpha by animateFloatAsState(
        targetValue = if (showShimmer) 0.12f else 0f,
        animationSpec = tween(800, easing = EaseInOutSine),
        label = "shimmer-alpha"
    )

    // ── Animated border gradient ──────────────────────────────────────
    val borderTransition = rememberInfiniteTransition(label = "border-grad")
    val borderShift by borderTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "border-shift"
    )

    val animatedBorderColor = if (animatedBorder) {
        // Shift between cyan and purple for the border
        val r = JarvisCyan.red + (JarvisPurple.red - JarvisCyan.red) * borderShift
        val g = JarvisCyan.green + (JarvisPurple.green - JarvisCyan.green) * borderShift
        val b = JarvisCyan.blue + (JarvisPurple.blue - JarvisCyan.blue) * borderShift
        Color(r, g, b, alpha = GlassBorder.alpha)
    } else {
        borderColor
    }

    val cardModifier = modifier.drawBehind {
        // ── Depth shadow with color tint ──────────────────────────────
        if (depthShadowColor != Color.Transparent) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        depthShadowColor.copy(alpha = 0.12f)
                    )
                ),
                topLeft = Offset(0f, size.height * 0.7f),
                size = size.copy(height = size.height * 0.3f)
            )
        }

        if (gradientOverlay != null) {
            drawRect(brush = gradientOverlay, alpha = 0.15f)
        }
        if (showShimmer && shimmerAlpha > 0.01f) {
            // Top-edge shimmer highlight
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = shimmerAlpha),
                        Color.Transparent
                    )
                ),
                size = this.size.copy(height = this.size.height * 0.3f)
            )
        }

        // ── Animated gradient glow along top border ───────────────────
        if (animatedBorder) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        JarvisCyan.copy(alpha = 0.0f),
                        Color(r, g, b, alpha = 0.15f),
                        JarvisPurple.copy(alpha = 0.0f),
                        Color(r, g, b, alpha = 0.10f),
                        JarvisCyan.copy(alpha = 0.0f)
                    ),
                    startX = borderShift * size.width - size.width * 0.3f,
                    endX = borderShift * size.width + size.width * 0.7f
                ),
                size = this.size.copy(height = 4.dp.toPx())
            )
        }
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(
            width = borderWidth,
            color = animatedBorderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (depthShadowColor != Color.Transparent) 4.dp else 0.dp
        ),
        content = content
    )
}

/**
 * Simplified glassmorphic card without gradient overlay.
 * Now includes an optional top-edge highlight effect.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicCardSimple(
    modifier: Modifier = Modifier,
    backgroundColor: Color = GlassBackground,
    borderColor: Color = GlassBorder,
    cornerRadius: Dp = 16.dp,
    showHighlight: Boolean = false,
    highlightColor: Color = Color.White.copy(alpha = 0.06f),
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (showHighlight) {
        modifier.drawBehind {
            // Top-edge highlight — simulates light reflection on glass
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        highlightColor,
                        Color.Transparent
                    )
                ),
                size = this.size.copy(height = this.size.height * 0.25f)
            )
            // Subtle inner glow at top
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.03f),
                        Color.Transparent
                    )
                ),
                topLeft = Offset(0f, 0f),
                size = this.size.copy(height = this.size.height * 0.5f)
            )
        }
    } else {
        modifier
    }

    Card(
        modifier = cardModifier,
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/**
 * Glassmorphic button wrapper — minimal card used for icon buttons.
 * Now includes hover-like pressed state support.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicButtonCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    accentColor: Color = JarvisCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier.drawBehind {
            // Subtle accent-colored glow at the bottom
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        accentColor.copy(alpha = 0.05f)
                    )
                ),
                topLeft = Offset(0f, this.size.height * 0.6f),
                size = this.size.copy(height = this.size.height * 0.4f)
            )
        },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = GlassBackground
        ),
        border = BorderStroke(
            width = 1.dp,
            color = GlassBorder
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/**
 * Animated glassmorphic card with a sweeping shimmer effect.
 * The shimmer moves from left to right across the card.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicShimmerCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = GlassBackground,
    borderColor: Color = GlassBorder,
    cornerRadius: Dp = 16.dp,
    shimmerColor: Color = Color.White.copy(alpha = 0.08f),
    content: @Composable ColumnScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer-card")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer-progress"
    )

    Card(
        modifier = modifier.drawBehind {
            // Sweeping shimmer band
            val shimmerWidth = size.width * 0.3f
            val shimmerX = shimmerProgress * size.width
            if (shimmerX in -shimmerWidth..(size.width + shimmerWidth)) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            shimmerColor,
                            Color.Transparent
                        ),
                        startX = shimmerX - shimmerWidth / 2f,
                        endX = shimmerX + shimmerWidth / 2f
                    )
                )
            }
        },
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(
            width = 1.dp,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        content = content
    )
}

/**
 * Featured glassmorphic card with a golden animated gradient border.
 * Used for premium/promoted content or key action cards.
 *
 * Features:
 *  - Animated golden gradient border that shifts colors
 *  - Depth shadow with gold tint
 *  - Top-edge golden highlight shimmer
 *  - Sweep shimmer effect
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicFeaturedCard(
    modifier: Modifier = Modifier,
    backgroundColor: Color = GlassBackground,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "featured-card")

    // ── Animated border color shift (gold → orange → gold) ───────────
    val borderShift by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "featured-border-shift"
    )

    val featuredBorderColor = run {
        val r = JarvisGold.red + (JarvisOrange.red - JarvisGold.red) * borderShift
        val g = JarvisGold.green + (JarvisOrange.green - JarvisGold.green) * borderShift
        val b = JarvisGold.blue + (JarvisOrange.blue - JarvisGold.blue) * borderShift
        Color(r, g, b, alpha = 0.6f)
    }

    // ── Sweep shimmer ────────────────────────────────────────────────
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "featured-shimmer"
    )

    Card(
        modifier = modifier.drawBehind {
            // ── Gold depth shadow at bottom ────────────────────────────
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        JarvisGold.copy(alpha = 0.08f)
                    )
                ),
                topLeft = Offset(0f, size.height * 0.7f),
                size = size.copy(height = size.height * 0.3f)
            )

            // ── Top golden glow line ──────────────────────────────────
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        JarvisGold.copy(alpha = 0.0f),
                        featuredBorderColor.copy(alpha = 0.3f),
                        JarvisOrange.copy(alpha = 0.0f),
                        featuredBorderColor.copy(alpha = 0.2f),
                        JarvisGold.copy(alpha = 0.0f)
                    ),
                    startX = borderShift * size.width - size.width * 0.3f,
                    endX = borderShift * size.width + size.width * 0.7f
                ),
                size = size.copy(height = 3.dp.toPx())
            )

            // ── Sweeping gold shimmer ─────────────────────────────────
            val shimmerWidth = size.width * 0.25f
            val shimmerX = shimmerProgress * size.width
            if (shimmerX in -shimmerWidth..(size.width + shimmerWidth)) {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            JarvisGold.copy(alpha = 0.06f),
                            Color.Transparent
                        ),
                        startX = shimmerX - shimmerWidth / 2f,
                        endX = shimmerX + shimmerWidth / 2f
                    )
                )
            }
        },
        shape = RoundedCornerShape(cornerRadius),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(
            width = 1.5.dp,
            color = featuredBorderColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        content = content
    )
}

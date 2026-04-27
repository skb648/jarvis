package com.jarvis.assistant.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
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
    content: @Composable ColumnScope.() -> Unit
) {
    val cardModifier = if (gradientOverlay != null) {
        modifier.drawBehind {
            drawRect(brush = gradientOverlay, alpha = 0.15f)
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
            width = borderWidth,
            color = borderColor
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp
        ),
        content = content
    )
}

/**
 * Simplified glassmorphic card without gradient overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicCardSimple(
    modifier: Modifier = Modifier,
    backgroundColor: Color = GlassBackground,
    borderColor: Color = GlassBorder,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier,
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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassmorphicButtonCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        onClick = onClick,
        modifier = modifier,
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

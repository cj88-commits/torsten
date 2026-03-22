package com.torsten.app.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Returns a left-to-right sweeping shimmer [Brush] suitable for skeleton loading placeholders.
 * Animate background with this brush in any Box/Spacer to produce a pulsing shimmer effect.
 */
@Composable
fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color(0xFF1A1A1A),
        Color(0xFF282828),
        Color(0xFF1A1A1A),
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val startX by transition.animateFloat(
        initialValue = -800f,
        targetValue = 800f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_x",
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start = Offset(startX, 0f),
        end = Offset(startX + 600f, 0f),
    )
}

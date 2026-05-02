package com.example.familysafety.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Shape
import com.example.familysafety.ui.theme.CardShape
import com.example.familysafety.ui.theme.Surface1
import com.example.familysafety.ui.theme.Surface2

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    shape: Shape = CardShape
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val animatedValue by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_offset"
    )

    val brush = Brush.linearGradient(
        colors = listOf(Surface1, Surface2, Surface1),
        start = Offset(animatedValue * 1000f, 0f),
        end = Offset(animatedValue * 1000f + 300f, 0f)
    )

    Box(modifier = modifier.background(brush = brush, shape = shape))
}

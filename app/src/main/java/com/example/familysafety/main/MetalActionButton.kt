package com.example.familysafety.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.ChipShape
import com.example.familysafety.ui.theme.Spacing

@Composable
fun MetalActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Add
) {
    val colorScheme = MaterialTheme.colorScheme
    val topSheen = colorScheme.surfaceVariant.copy(alpha = 0.96f)
    val base = colorScheme.surface.copy(alpha = 0.96f)
    val lowerShade = colorScheme.primary.copy(alpha = 0.10f)

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.95f))
    ) {
        Box(
            modifier = Modifier
                .clip(ChipShape)
                .background(
                    Brush.verticalGradient(
                        0f to topSheen,
                        0.48f to base,
                        1f to lowerShade
                    )
                )
                .padding(horizontal = Spacing.md, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = colorScheme.onSurface
                )
            }
        }
    }
}

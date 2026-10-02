package com.example.familysafety.main

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.ButtonShape
import com.example.familysafety.ui.theme.Spacing

/**
 * The labelled floating action at the bottom of a list screen ("Invite someone", "Add a zone").
 *
 * Flat on purpose. It used to paint a three-stop vertical gradient under the label to look
 * like brushed metal: decoration that said nothing, and one of the recognisable marks of a
 * generated UI. What it needs is to read as floating above the list, which the raised surface
 * colour, the hairline and the shadow already do.
 */
@Composable
fun FloatingActionLabelButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Add
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ButtonShape,
        color = colorScheme.surfaceContainerHigh,
        contentColor = colorScheme.onSurface,
        shadowElevation = 6.dp,
        border = BorderStroke(1.dp, colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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

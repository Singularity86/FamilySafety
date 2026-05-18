package com.example.familysafety.main

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.AmberWarning
import com.example.familysafety.ui.theme.ChipShape
import com.example.familysafety.ui.theme.RedDanger
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.TealPrimary

@Composable
fun ConnectionBadge(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val mode by viewModel.connectionMode.collectAsState()

    val label: String
    val tint: Color
    when (mode) {
        MainViewModel.ConnectionMode.LAN     -> { label = "LAN";     tint = TealPrimary  }
        MainViewModel.ConnectionMode.RELAY   -> { label = "Relay";   tint = AmberWarning }
        MainViewModel.ConnectionMode.OFFLINE -> { label = "Offline"; tint = RedDanger    }
    }

    Row(
        modifier = modifier
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, ChipShape)
            .background(
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = ChipShape
            )
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConnectionIcon(mode = mode, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint
        )
    }
}

/**
 * Icon that shows a checkmark as negative space on connected states so "OK" is instantly obvious.
 * RELAY uses CloudDone (checkmark baked into the icon geometry).
 * LAN uses Wifi with a same-color-as-background Check overlaid, creating a cut-out effect.
 * OFFLINE shows WifiOff with no checkmark.
 */
@Composable
private fun ConnectionIcon(
    mode: MainViewModel.ConnectionMode,
    tint: Color,
    modifier: Modifier = Modifier
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    when (mode) {
        MainViewModel.ConnectionMode.LAN -> Box(modifier = modifier) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.fillMaxSize()
            )
            // Overlay a check in the chip's background color — it reads as negative space cut
            // out of the Wifi arcs, signalling the connection is healthy.
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = surfaceColor,
                modifier = Modifier
                    .size(8.dp)
                    .align(Alignment.Center)
                    .offset(y = 2.dp)
            )
        }

        MainViewModel.ConnectionMode.RELAY -> Icon(
            imageVector = Icons.Default.CloudDone,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        MainViewModel.ConnectionMode.OFFLINE -> Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

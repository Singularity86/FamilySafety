package com.example.familysafety.main

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.familysafety.ui.theme.AmberWarning
import com.example.familysafety.ui.theme.RedDanger
import com.example.familysafety.ui.theme.TealPrimary

@Composable
fun ConnectionBadge(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val mode by viewModel.connectionMode.collectAsState()

    val label: String
    val tint: Color
    val containerColor: Color
    val borderColor: Color

    when (mode) {
        MainViewModel.ConnectionMode.LAN -> {
            label = "LAN"
            tint = TealPrimary
            containerColor = TealPrimary.copy(alpha = 0.12f)
            borderColor = TealPrimary.copy(alpha = 0.34f)
        }

        MainViewModel.ConnectionMode.RELAY -> {
            label = "Relay"
            tint = AmberWarning
            containerColor = AmberWarning.copy(alpha = 0.12f)
            borderColor = AmberWarning.copy(alpha = 0.34f)
        }

        MainViewModel.ConnectionMode.OFFLINE -> {
            label = "Offline"
            tint = RedDanger
            containerColor = RedDanger.copy(alpha = 0.10f)
            borderColor = RedDanger.copy(alpha = 0.28f)
        }
    }

    StatusPill(
        modifier = modifier,
        containerColor = containerColor,
        borderColor = borderColor,
        contentColor = tint
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

@Composable
private fun ConnectionIcon(
    mode: MainViewModel.ConnectionMode,
    tint: Color,
    modifier: Modifier = Modifier
) {
    when (mode) {
        MainViewModel.ConnectionMode.LAN -> Box(modifier = modifier) {
            Icon(
                imageVector = Icons.Default.Wifi,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp)
            )
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.surface,
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

package com.example.familysafety.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
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
    val routeHealth by viewModel.routeHealth.collectAsState()

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

        MainViewModel.ConnectionMode.MIXED -> {
            label = "Mixed"
            tint = MaterialTheme.colorScheme.onSurface
            containerColor = Color.Transparent
            borderColor = TealPrimary.copy(alpha = 0.34f)
        }

        MainViewModel.ConnectionMode.OFFLINE -> {
            label = "Offline"
            tint = RedDanger
            containerColor = RedDanger.copy(alpha = 0.10f)
            borderColor = RedDanger.copy(alpha = 0.28f)
        }
    }

    if (mode == MainViewModel.ConnectionMode.MIXED) {
        ChipInfoPopup(
            title = "Mixed route",
            message = routeSummary(
                leading = "Working. Some family devices are nearby on this network, and others are being reached through relay.",
                routeHealth = routeHealth
            ),
            modifier = modifier
        ) {
            MixedConnectionPill()
        }
        return
    }

    ChipInfoPopup(
        title = "Connection route: $label",
        message = when (mode) {
            MainViewModel.ConnectionMode.LAN ->
                routeSummary(
                    leading = "Working locally. FamilySafety can reach a family device directly on this network.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.RELAY ->
                routeSummary(
                    leading = "Working through relay. Updates can still move between devices even when they are not on the same network.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.OFFLINE ->
                routeSummary(
                    leading = "Needs attention. FamilySafety does not currently have a local device or relay route.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.MIXED ->
                routeSummary(
                    leading = "Working. Some family devices are local and others are using relay.",
                    routeHealth = routeHealth
                )
        },
        modifier = modifier,
    ) {
        StatusPill(
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
}

private fun routeSummary(
    leading: String,
    routeHealth: MainViewModel.RouteHealth
): String {
    val relay = if (routeHealth.hasRelay) "connected" else "not connected"
    val notLocal = (routeHealth.totalPeerCount - routeHealth.localPeerCount).coerceAtLeast(0)
    return "$leading Local: ${routeHealth.localPeerCount}/${routeHealth.totalPeerCount}. Relay: $relay. Not local: $notLocal."
}

@Composable
private fun MixedConnectionPill(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, TealPrimary.copy(alpha = 0.34f))
    ) {
        Box(
            modifier = Modifier
                .clip(ChipShape)
                .drawBehind {
                    val tealPath = Path().apply {
                        moveTo(0f, 0f)
                        lineTo(size.width, 0f)
                        lineTo(0f, size.height)
                        close()
                    }
                    val amberPath = Path().apply {
                        moveTo(size.width, 0f)
                        lineTo(size.width, size.height)
                        lineTo(0f, size.height)
                        close()
                    }
                    drawPath(tealPath, TealPrimary.copy(alpha = 0.12f))
                    drawPath(amberPath, AmberWarning.copy(alpha = 0.12f))
                }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Mixed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
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

        MainViewModel.ConnectionMode.MIXED -> Unit

        MainViewModel.ConnectionMode.OFFLINE -> Icon(
            imageVector = Icons.Default.WifiOff,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

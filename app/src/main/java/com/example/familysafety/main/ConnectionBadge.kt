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
            details = connectionDetails(MainViewModel.ConnectionMode.MIXED),
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
                    leading = "Working locally. Jibaro Family Safety can reach a family device directly on this network.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.RELAY ->
                routeSummary(
                    leading = "Working through relay. Updates can still move between devices even when they are not on the same network.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.OFFLINE ->
                routeSummary(
                    leading = "Needs attention. Jibaro Family Safety does not currently have a local device or relay route.",
                    routeHealth = routeHealth
                )
            MainViewModel.ConnectionMode.MIXED ->
                routeSummary(
                    leading = "Working. Some family devices are local and others are using relay.",
                    routeHealth = routeHealth
                )
        },
        details = connectionDetails(mode),
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

private fun connectionDetails(mode: MainViewModel.ConnectionMode): String = when (mode) {
    MainViewModel.ConnectionMode.LAN ->
        "Jibaro Family Safety always looks for family devices on your local network first, since that's the " +
            "fastest and most private route — updates travel directly between phones without touching the " +
            "internet. Right now at least one family device is reachable that way. If a device leaves this " +
            "network, the app automatically falls back to relay so updates keep flowing."
    MainViewModel.ConnectionMode.RELAY ->
        "No family device is reachable on your local network right now, so updates are going through an " +
            "encrypted relay server instead. The relay only ever sees locked messages it can't open — it just " +
            "passes them along between devices, wherever they are. This is normal whenever family members " +
            "aren't on the same network."
    MainViewModel.ConnectionMode.MIXED ->
        "Some family devices are on the same local network as you, so updates to them travel directly. " +
            "Others are elsewhere, so their updates go through an encrypted relay server instead, which can't " +
            "read them either way. Seeing a mix like this is normal for a family that isn't all in one place."
    MainViewModel.ConnectionMode.OFFLINE ->
        "Jibaro Family Safety can't currently reach any family device — not on this network, and not through " +
            "relay. Check your internet connection. Updates you make will be queued and sent automatically " +
            "once a route becomes available again."
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

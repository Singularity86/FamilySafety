package com.example.familysafety.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.MqttTransport

private val GreenOk = Color(0xFF4CAF50)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecurityScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val mqttState by viewModel.mqttState.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    val deviceNetworkAvailable by viewModel.deviceNetworkAvailable.collectAsState()
    val routeHealth by viewModel.routeHealth.collectAsState()
    val groupDef by viewModel.groupDefinition.collectAsState()
    val members by viewModel.familyMembers.collectAsState()
    val decryptFailures by viewModel.decryptFailures.collectAsState()
    val keySyncRequestState by viewModel.keySyncRequestState.collectAsState()
    val memberRouteStatuses by viewModel.memberRouteStatuses.collectAsState()
    val myMemberId = viewModel.myMemberId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Security") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SecurityVerdictCard(
                mqttState = mqttState,
                syncState = syncState,
                deviceNetworkAvailable = deviceNetworkAvailable,
                routeHealth = routeHealth,
                decryptFailures = decryptFailures,
                keySyncRequestState = keySyncRequestState
            )
            NetworkStatusCard(
                mqttState = mqttState,
                syncState = syncState,
                deviceNetworkAvailable = deviceNetworkAvailable,
                routeHealth = routeHealth
            )
            groupDef?.let {
                GroupIntegrityCard(
                    groupDef = it,
                    keySyncRequestState = keySyncRequestState,
                    viewModel = viewModel
                )
            }
            MemberKeysCard(
                members = members.filter { it.memberId != myMemberId },
                decryptFailures = decryptFailures,
                memberRouteStatuses = memberRouteStatuses,
                onClearRecovered = viewModel::clearRecoveredDecryptFailures
            )
        }
    }
}

@Composable
private fun SecurityVerdictCard(
    mqttState: MqttTransport.ConnectionState,
    syncState: GroupSyncManager.SyncState,
    deviceNetworkAvailable: Boolean,
    routeHealth: MainViewModel.RouteHealth,
    decryptFailures: Map<String, SecurityEventRepository.DecryptStats>,
    keySyncRequestState: MainViewModel.KeySyncRequestState
) {
    val activeKeyIssues = decryptFailures.values.any { it.hasActiveFailure }
    val waitingForSync = keySyncRequestState is MainViewModel.KeySyncRequestState.Sending ||
        keySyncRequestState is MainViewModel.KeySyncRequestState.Requested ||
        syncState is GroupSyncManager.SyncState.Syncing
    val hasRoute = routeHealth.localPeerCount > 0 || routeHealth.hasRelay
    val needsAttention = activeKeyIssues ||
        !deviceNetworkAvailable && !hasRoute ||
        syncState is GroupSyncManager.SyncState.Conflict ||
        syncState is GroupSyncManager.SyncState.Error ||
        mqttState is MqttTransport.ConnectionState.Error ||
        keySyncRequestState is MainViewModel.KeySyncRequestState.Error

    val (title, body, color) = when {
        needsAttention -> Triple(
            "Needs attention",
            "Something is blocking a clean family update path. The details below show what to check.",
            MaterialTheme.colorScheme.error
        )
        waitingForSync -> Triple(
            "Waiting for devices",
            "Jibaro Family Safety is checking the family list or waiting for other devices to answer.",
            Color(0xFFFF9800)
        )
        else -> Triple(
            "All clear",
            "Jibaro Family Safety has a working route and no current key issues.",
            GreenOk
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(color, CircleShape)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(2.dp))
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun NetworkStatusCard(
    mqttState: MqttTransport.ConnectionState,
    syncState: GroupSyncManager.SyncState,
    deviceNetworkAvailable: Boolean,
    routeHealth: MainViewModel.RouteHealth
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            StatusRow(
                color = if (deviceNetworkAvailable) GreenOk else MaterialTheme.colorScheme.error,
                label = "Device internet: ${if (deviceNetworkAvailable) "Available" else "Unavailable"}"
            )

            Spacer(Modifier.height(6.dp))

            val (mqttColor, mqttLabel) = when (mqttState) {
                is MqttTransport.ConnectionState.Connected   -> GreenOk to "Connected"
                is MqttTransport.ConnectionState.Connecting  -> Color(0xFFFF9800) to "Connecting…"
                is MqttTransport.ConnectionState.Error       -> MaterialTheme.colorScheme.error to "Error"
                else                                         -> Color(0xFF9E9E9E) to "Disconnected"
            }
            StatusRow(mqttColor, "Relay: $mqttLabel")

            Spacer(Modifier.height(6.dp))

            val localColor = when {
                routeHealth.totalPeerCount == 0 -> Color(0xFF9E9E9E)
                routeHealth.localPeerCount > 0 -> GreenOk
                else -> Color(0xFF9E9E9E)
            }
            StatusRow(
                localColor,
                "Local peers: ${routeHealth.localPeerCount}/${routeHealth.totalPeerCount}"
            )

            Spacer(Modifier.height(6.dp))

            val routeLabel = when {
                routeHealth.localPeerCount > 0 &&
                    routeHealth.localPeerCount < routeHealth.totalPeerCount &&
                    routeHealth.hasRelay -> "Mixed local + relay"
                routeHealth.localPeerCount > 0 -> "Local"
                routeHealth.hasRelay -> "Relay"
                else -> "No active route"
            }
            val routeColor = when (routeLabel) {
                "Local" -> GreenOk
                "Mixed local + relay" -> Color(0xFFFF9800)
                "Relay" -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.error
            }
            StatusRow(routeColor, "App route: $routeLabel")

            Spacer(Modifier.height(6.dp))

            val (syncColor, syncLabel) = when (val s = syncState) {
                is GroupSyncManager.SyncState.Synced   -> GreenOk to "Synced v${s.version}"
                is GroupSyncManager.SyncState.Syncing  -> Color(0xFFFF9800) to "Syncing…"
                is GroupSyncManager.SyncState.Conflict -> MaterialTheme.colorScheme.error to "Conflict detected"
                is GroupSyncManager.SyncState.Error    -> MaterialTheme.colorScheme.error to "Error: ${s.message}"
                else                                   -> Color(0xFF9E9E9E) to "Idle"
            }
            StatusRow(syncColor, "Family list: $syncLabel")
        }
    }
}

@Composable
private fun StatusRow(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GroupIntegrityCard(
    groupDef: GroupDefinition,
    keySyncRequestState: MainViewModel.KeySyncRequestState,
    viewModel: MainViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Family List", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            IntegrityRow("Version", "${groupDef.version}")
            Spacer(Modifier.height(4.dp))
            IntegrityRow("State hash", groupDef.computeStateHash().take(8))

            Spacer(Modifier.height(14.dp))
            KeySyncStatus(keySyncRequestState)
            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = { viewModel.requestGroupStateRefresh() },
                enabled = keySyncRequestState !is MainViewModel.KeySyncRequestState.Sending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (keySyncRequestState is MainViewModel.KeySyncRequestState.Sending) {
                        "Checking..."
                    } else {
                        "Check Family List"
                    }
                )
            }
        }
    }
}

@Composable
private fun KeySyncStatus(state: MainViewModel.KeySyncRequestState) {
    val (color, text) = when (state) {
        MainViewModel.KeySyncRequestState.Idle ->
            Color(0xFF9E9E9E) to "Ready to ask family devices for their latest member list."
        MainViewModel.KeySyncRequestState.Sending ->
            Color(0xFFFF9800) to "Sending sync request..."
        is MainViewModel.KeySyncRequestState.Requested ->
            Color(0xFFFF9800) to "Asked ${state.peerCount} device${if (state.peerCount != 1) "s" else ""}. ${state.sentCount} reachable, ${state.failedCount} no response so far."
        is MainViewModel.KeySyncRequestState.Updated ->
            GreenOk to "Updated family list from v${state.fromVersion} to v${state.toVersion}."
        is MainViewModel.KeySyncRequestState.NoNewerUpdate ->
            GreenOk to "No newer family list came back. ${state.sentCount} reachable, ${state.failedCount} no response. This device is on v${state.version}."
        MainViewModel.KeySyncRequestState.NoPeers ->
            Color(0xFF9E9E9E) to "No other family devices are in this family yet."
        is MainViewModel.KeySyncRequestState.Error ->
            MaterialTheme.colorScheme.error to state.message
    }

    StatusRow(color = color, label = text)
}

@Composable
private fun IntegrityRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun MemberKeysCard(
    members: List<com.example.familysafety.group.FamilyMember>,
    decryptFailures: Map<String, SecurityEventRepository.DecryptStats>,
    memberRouteStatuses: Map<String, MainViewModel.MemberRouteStatus>,
    onClearRecovered: () -> Unit
) {
    if (members.isEmpty()) return

    val activeCount = decryptFailures.values.count { it.hasActiveFailure }
    val recoveredCount = decryptFailures.values.count {
        !it.hasActiveFailure && it.failureCount > 0
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Member Keys", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                text = when {
                    activeCount > 0 -> "$activeCount member${if (activeCount != 1) "s" else ""} need attention now."
                    recoveredCount > 0 -> "No current key issues. $recoveredCount recovered issue${if (recoveredCount != 1) "s" else ""} kept as history."
                    else -> "No current key issues."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (activeCount > 0) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (recoveredCount > 0) {
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = onClearRecovered,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Text("Clear recovered history")
                }
            }
            Spacer(Modifier.height(12.dp))

            members.forEachIndexed { index, member ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                val stats = decryptFailures[member.memberId]
                val hasFailures = (stats?.failureCount ?: 0) > 0
                val hasActiveFailure = stats?.hasActiveFailure == true
                val hasSuccess = (stats?.lastSuccessMs ?: 0L) > 0L
                val routeStatus = memberRouteStatuses[member.memberId]

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Top
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            member.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            buildMemberStatusLine(routeStatus),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            member.ed25519PublicKey.take(16).chunked(4).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (stats != null && hasActiveFailure) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "Current issue: this device could not open the latest update from this member. Last tried ${formatTimeAgo(stats.lastFailureMs)}.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        if (stats != null && !hasActiveFailure && (hasFailures || hasSuccess)) {
                            Spacer(Modifier.height(2.dp))
                            DecryptHealthText(
                                stats = stats,
                                hasActiveFailure = hasActiveFailure,
                                hasFailures = hasFailures,
                                hasSuccess = hasSuccess
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (hasActiveFailure) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Key issue",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = "OK",
                            tint = GreenOk,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DecryptHealthText(
    stats: SecurityEventRepository.DecryptStats,
    hasActiveFailure: Boolean,
    hasFailures: Boolean,
    hasSuccess: Boolean
) {
    val statusText = when {
        hasActiveFailure ->
            "${stats.failureCount} failed update${if (stats.failureCount != 1) "s" else ""} - ${formatTimeAgo(stats.lastFailureMs)}"
        hasFailures ->
            "Recovered after ${stats.failureCount} failed update${if (stats.failureCount != 1) "s" else ""}. Last good update ${formatTimeAgo(stats.lastSuccessMs)}."
        hasSuccess ->
            "Healthy. Last good update ${formatTimeAgo(stats.lastSuccessMs)}."
        else -> null
    }
    statusText?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = if (hasActiveFailure) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun formatTimeAgo(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000       -> "just now"
        diff < 3_600_000    -> "${diff / 60_000}m ago"
        diff < 86_400_000   -> "${diff / 3_600_000}h ago"
        else                -> "${diff / 86_400_000}d ago"
    }
}

private fun buildMemberStatusLine(status: MainViewModel.MemberRouteStatus?): String {
    if (status == null) return "Waiting for contact"
    val seenAt = status.lastLocationUpdateMs ?: status.lastSeenMs
    val seenText = if (seenAt != null) {
        "Seen ${formatTimeAgo(seenAt)}"
    } else {
        "Not seen yet"
    }
    return "$seenText · ${status.routeLabel}"
}

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
    val groupDef by viewModel.groupDefinition.collectAsState()
    val members by viewModel.familyMembers.collectAsState()
    val decryptFailures by viewModel.decryptFailures.collectAsState()
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
            NetworkStatusCard(mqttState, syncState)
            groupDef?.let { GroupIntegrityCard(it, viewModel) }
            MemberKeysCard(
                members = members.filter { it.memberId != myMemberId },
                decryptFailures = decryptFailures
            )
        }
    }
}

@Composable
private fun NetworkStatusCard(
    mqttState: MqttTransport.ConnectionState,
    syncState: GroupSyncManager.SyncState
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            val (mqttColor, mqttLabel) = when (mqttState) {
                is MqttTransport.ConnectionState.Connected   -> GreenOk to "Connected"
                is MqttTransport.ConnectionState.Connecting  -> Color(0xFFFF9800) to "Connecting…"
                is MqttTransport.ConnectionState.Error       -> MaterialTheme.colorScheme.error to "Error"
                else                                         -> Color(0xFF9E9E9E) to "Disconnected"
            }
            StatusRow(mqttColor, "MQTT: $mqttLabel")

            Spacer(Modifier.height(6.dp))

            val (syncColor, syncLabel) = when (val s = syncState) {
                is GroupSyncManager.SyncState.Synced   -> GreenOk to "Synced v${s.version}"
                is GroupSyncManager.SyncState.Syncing  -> Color(0xFFFF9800) to "Syncing…"
                is GroupSyncManager.SyncState.Conflict -> MaterialTheme.colorScheme.error to "Conflict detected"
                is GroupSyncManager.SyncState.Error    -> MaterialTheme.colorScheme.error to "Error: ${s.message}"
                else                                   -> Color(0xFF9E9E9E) to "Idle"
            }
            StatusRow(syncColor, "Group sync: $syncLabel")
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
private fun GroupIntegrityCard(groupDef: GroupDefinition, viewModel: MainViewModel) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Group Integrity", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            IntegrityRow("Version", "${groupDef.version}")
            Spacer(Modifier.height(4.dp))
            IntegrityRow("State hash", groupDef.computeStateHash().take(8))

            Spacer(Modifier.height(14.dp))

            OutlinedButton(
                onClick = { viewModel.requestGroupStateRefresh() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("Request Key Sync")
            }
        }
    }
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
    decryptFailures: Map<String, SecurityEventRepository.DecryptStats>
) {
    if (members.isEmpty()) return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Member Keys", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            members.forEachIndexed { index, member ->
                if (index > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                val stats = decryptFailures[member.memberId]
                val hasFailures = (stats?.failureCount ?: 0) > 0

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
                            member.ed25519PublicKey.take(16).chunked(4).joinToString(" "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (hasFailures && stats != null) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "${stats.failureCount} decrypt failure${if (stats.failureCount != 1) "s" else ""}" +
                                    " · ${formatTimeAgo(stats.lastFailureMs)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    if (hasFailures) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = "Decrypt failures",
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

private fun formatTimeAgo(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    return when {
        diff < 60_000       -> "just now"
        diff < 3_600_000    -> "${diff / 60_000}m ago"
        diff < 86_400_000   -> "${diff / 3_600_000}h ago"
        else                -> "${diff / 86_400_000}d ago"
    }
}

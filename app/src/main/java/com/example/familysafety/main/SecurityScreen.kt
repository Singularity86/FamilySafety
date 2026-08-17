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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.familysafety.BuildConfig
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.MqttTransport
import com.example.familysafety.ui.theme.AmberWarning
import com.example.familysafety.ui.theme.ColorSuccess

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
            ThisDeviceCard(
                memberId = myMemberId,
                displayName = members.find { it.memberId == myMemberId }?.displayName,
                ed25519PublicKey = members.find { it.memberId == myMemberId }?.ed25519PublicKey,
                // Built on demand rather than per recomposition — it stamps relative
                // timestamps, so it must reflect the moment the button is pressed.
                buildDiagnostics = {
                    buildDiagnosticsReport(
                        memberId = myMemberId,
                        displayName = members.find { it.memberId == myMemberId }?.displayName,
                        ed25519PublicKey = members.find { it.memberId == myMemberId }?.ed25519PublicKey,
                        groupDef = groupDef,
                        members = members,
                        mqttState = mqttState,
                        syncState = syncState,
                        deviceNetworkAvailable = deviceNetworkAvailable,
                        routeHealth = routeHealth,
                        memberRouteStatuses = memberRouteStatuses,
                        decryptFailures = decryptFailures
                    )
                }
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
            AmberWarning
        )
        else -> Triple(
            "All clear",
            "Jibaro Family Safety has a working route and no current key issues.",
            ColorSuccess
        )
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Network", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            StatusRow(
                color = if (deviceNetworkAvailable) ColorSuccess else MaterialTheme.colorScheme.error,
                label = "Device internet: ${if (deviceNetworkAvailable) "Available" else "Unavailable"}"
            )

            Spacer(Modifier.height(6.dp))

            val (mqttColor, mqttLabel) = when (mqttState) {
                is MqttTransport.ConnectionState.Connected   -> ColorSuccess to "Connected"
                is MqttTransport.ConnectionState.Connecting  -> AmberWarning to "Connecting…"
                is MqttTransport.ConnectionState.Error       -> MaterialTheme.colorScheme.error to "Error"
                else                                         -> MaterialTheme.colorScheme.onSurfaceVariant to "Disconnected"
            }
            StatusRow(mqttColor, "Relay: $mqttLabel")

            Spacer(Modifier.height(6.dp))

            val localColor = when {
                routeHealth.totalPeerCount == 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                routeHealth.localPeerCount > 0 -> ColorSuccess
                else -> MaterialTheme.colorScheme.onSurfaceVariant
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
                "Local" -> ColorSuccess
                "Mixed local + relay" -> AmberWarning
                "Relay" -> AmberWarning
                else -> MaterialTheme.colorScheme.error
            }
            StatusRow(routeColor, "App route: $routeLabel")

            Spacer(Modifier.height(6.dp))

            val (syncColor, syncLabel) = when (val s = syncState) {
                is GroupSyncManager.SyncState.Synced   -> ColorSuccess to "Synced v${s.version}"
                is GroupSyncManager.SyncState.Syncing  -> AmberWarning to "Syncing…"
                is GroupSyncManager.SyncState.Conflict -> MaterialTheme.colorScheme.error to "Conflict detected"
                is GroupSyncManager.SyncState.Error    -> MaterialTheme.colorScheme.error to "Error: ${s.message}"
                else                                   -> MaterialTheme.colorScheme.onSurfaceVariant to "Idle"
            }
            StatusRow(syncColor, "Family list: $syncLabel")
        }
    }
}

/**
 * Identity of this device. Every other card describes *other* members, so without this
 * there was no way to learn your own member ID from inside the app — which made it
 * impossible to tell which member you are when reading broker traffic or comparing
 * notes with someone else's device.
 */
@Composable
private fun ThisDeviceCard(
    memberId: String,
    displayName: String?,
    ed25519PublicKey: String?,
    buildDiagnostics: () -> String
) {
    val clipboard = LocalClipboardManager.current
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("This Device", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            if (!displayName.isNullOrBlank()) {
                IntegrityRow("Name", displayName)
                Spacer(Modifier.height(8.dp))
            }

            // Full ID, not a prefix: it is matched against topic names and other
            // devices' records, where a truncated value is worse than useless.
            CopyableValue(
                label = "Member ID",
                value = memberId.ifBlank { "not available until onboarding completes" },
                onCopy = if (memberId.isNotBlank()) {
                    { clipboard.setText(AnnotatedString(memberId)) }
                } else null
            )

            if (!ed25519PublicKey.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                // Same 16-char grouped form the Member Keys card uses for everyone
                // else, so it can be read straight across from another device.
                CopyableValue(
                    label = "Signing key",
                    value = ed25519PublicKey.take(16).chunked(4).joinToString(" "),
                    onCopy = { clipboard.setText(AnnotatedString(ed25519PublicKey)) }
                )
            }

            Spacer(Modifier.height(8.dp))
            IntegrityRow("App version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")

            Spacer(Modifier.height(14.dp))
            var copied by remember { mutableStateOf(false) }
            LaunchedEffect(copied) {
                if (copied) {
                    kotlinx.coroutines.delay(2_000)
                    copied = false
                }
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(buildDiagnostics()))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(if (copied) "Copied to clipboard" else "Copy diagnostics")
            }
            Text(
                "Includes member IDs, names, routes and sync state for everyone in the " +
                    "family. No locations or message contents.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/**
 * One pasteable snapshot of everything this screen knows. Deliberately excludes
 * coordinates and message contents — it is meant to be shareable when asking for help,
 * and location data is the one thing this app exists to keep private.
 */
private fun buildDiagnosticsReport(
    memberId: String,
    displayName: String?,
    ed25519PublicKey: String?,
    groupDef: GroupDefinition?,
    members: List<com.example.familysafety.group.FamilyMember>,
    mqttState: MqttTransport.ConnectionState,
    syncState: GroupSyncManager.SyncState,
    deviceNetworkAvailable: Boolean,
    routeHealth: MainViewModel.RouteHealth,
    memberRouteStatuses: Map<String, MainViewModel.MemberRouteStatus>,
    decryptFailures: Map<String, SecurityEventRepository.DecryptStats>
): String = buildString {
    appendLine("=== Jibaro Family Safety diagnostics ===")
    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine()

    appendLine("[This device]")
    appendLine("  Name: ${displayName ?: "(unknown)"}")
    appendLine("  Member ID: ${memberId.ifBlank { "(not set)" }}")
    appendLine("  Signing key: ${ed25519PublicKey ?: "(not set)"}")
    appendLine()

    appendLine("[Network]")
    appendLine("  Device internet: ${if (deviceNetworkAvailable) "available" else "unavailable"}")
    appendLine("  Relay: " + when (mqttState) {
        is MqttTransport.ConnectionState.Connected  -> "connected"
        is MqttTransport.ConnectionState.Connecting -> "connecting"
        is MqttTransport.ConnectionState.Error      -> "error"
        else                                        -> "disconnected"
    })
    appendLine("  Local peers: ${routeHealth.localPeerCount}/${routeHealth.totalPeerCount}")
    appendLine("  Has relay: ${routeHealth.hasRelay}")
    appendLine("  Sync: " + when (val s = syncState) {
        is GroupSyncManager.SyncState.Synced   -> "synced v${s.version}"
        is GroupSyncManager.SyncState.Syncing  -> "syncing"
        is GroupSyncManager.SyncState.Conflict -> "conflict"
        is GroupSyncManager.SyncState.Error    -> "error: ${s.message}"
        else                                    -> "idle"
    })
    appendLine()

    appendLine("[Family list]")
    if (groupDef == null) {
        appendLine("  (no group)")
    } else {
        appendLine("  Group ID: ${groupDef.groupId}")
        appendLine("  Version: ${groupDef.version}")
        appendLine("  State hash: ${groupDef.computeStateHash()}")
        appendLine("  Members: ${groupDef.members.size}")
        // Presence or absence only — never the key itself. This report is meant to be pasted
        // into a message to somebody, and a report that leaks the family's document key would
        // be a worse hole than the one it is here to diagnose.
        appendLine(
            "  Document key: " +
                if (groupDef.fileEncryptionKey.isNullOrBlank()) {
                    "ABSENT (family predates per-family keys; files use the derivable key)"
                } else {
                    "present"
                }
        )
    }
    appendLine()

    appendLine("[Members]")
    if (members.isEmpty()) {
        appendLine("  (none)")
    }
    members.forEach { member ->
        val isMe = member.memberId == memberId
        val status = memberRouteStatuses[member.memberId]
        val stats = decryptFailures[member.memberId]
        appendLine("  ${member.displayName}${if (isMe) " (this device)" else ""}")
        appendLine("    id: ${member.memberId}")
        appendLine("    route: ${status?.routeLabel ?: "unknown"}")
        appendLine("    last seen: ${status?.lastSeenMs?.let { formatTimeAgo(it) } ?: "never"}")
        appendLine("    last location: ${status?.lastLocationUpdateMs?.let { formatTimeAgo(it) } ?: "never"}")
        appendLine("    decrypt: " + when {
            stats == null -> "no data"
            stats.hasActiveFailure ->
                "FAILING (${stats.failureCount} failures, last ${formatTimeAgo(stats.lastFailureMs)})"
            stats.failureCount > 0 ->
                "recovered after ${stats.failureCount}, last ok ${formatTimeAgo(stats.lastSuccessMs)}"
            stats.lastSuccessMs > 0L -> "healthy, last ok ${formatTimeAgo(stats.lastSuccessMs)}"
            else -> "nothing received yet"
        })
    }
}

/**
 * Label above a monospace value, with an optional copy action. Long hex values get their
 * own line because [IntegrityRow]'s single-line layout squeezes them unreadably.
 */
@Composable
private fun CopyableValue(label: String, value: String, onCopy: (() -> Unit)?) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            if (onCopy != null) {
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy $label",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Family List", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))

            IntegrityRow("Version", "${groupDef.version}")
            Spacer(Modifier.height(4.dp))
            IntegrityRow("State hash", groupDef.computeStateHash().take(8))
            Spacer(Modifier.height(4.dp))
            IntegrityRow(
                "Document protection",
                if (groupDef.fileEncryptionKey.isNullOrBlank()) "Not set up" else "On"
            )

            // A family only ever gets this key at the moment it is created, and there is no
            // upgrade path — so a family that predates the key keeps sharing documents under
            // one that anyone reaching the relay can work out. Nothing in the app said so, and
            // it is not something a user could otherwise find out.
            FamilyKeyStatus(hasKey = !groupDef.fileEncryptionKey.isNullOrBlank())

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

/**
 * Whether this family has its own document key, and what it means if it does not.
 *
 * Deliberately explicit about the cost of fixing it. The fix is for everyone to leave and one
 * person to create the family again, and leaving destroys this device's identity keys — every
 * member ends up with a new recovery phrase and the old ones stop working. Presenting that as
 * a one-tap improvement would be a way to lose people their identities.
 */
@Composable
private fun FamilyKeyStatus(hasKey: Boolean) {
    Spacer(Modifier.height(10.dp))
    if (hasKey) {
        StatusRow(
            color = ColorSuccess,
            label = "This family has its own key. Documents you share, their names, and who is " +
                "online can only be read by this family."
        )
        return
    }

    StatusRow(
        color = AmberWarning,
        label = "This family was started before per-family keys existed, so it never got one."
    )
    Spacer(Modifier.height(8.dp))
    Text(
        "Documents you share, and their file names, can be read by the server that passes " +
            "them between your phones. Everything else — messages, locations — is unaffected, " +
            "and so is the private vault.\n\n" +
            "The only fix is for everyone to leave the family and one person to start it " +
            "again. That gives everyone a new recovery phrase and makes the old ones stop " +
            "working, and shared documents have to be added again afterwards.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun KeySyncStatus(state: MainViewModel.KeySyncRequestState) {
    val (color, text) = when (state) {
        MainViewModel.KeySyncRequestState.Idle ->
            MaterialTheme.colorScheme.onSurfaceVariant to "Ready to ask family devices for their latest member list."
        MainViewModel.KeySyncRequestState.Sending ->
            AmberWarning to "Sending sync request..."
        is MainViewModel.KeySyncRequestState.Requested ->
            AmberWarning to "Asked ${state.peerCount} device${if (state.peerCount != 1) "s" else ""}. ${state.sentCount} reachable, ${state.failedCount} no response so far."
        is MainViewModel.KeySyncRequestState.Updated ->
            ColorSuccess to "Updated family list from v${state.fromVersion} to v${state.toVersion}."
        is MainViewModel.KeySyncRequestState.NoNewerUpdate ->
            ColorSuccess to "No newer family list came back. ${state.sentCount} reachable, ${state.failedCount} no response. This device is on v${state.version}."
        MainViewModel.KeySyncRequestState.NoPeers ->
            MaterialTheme.colorScheme.onSurfaceVariant to "No other family devices are in this family yet."
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

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
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
                            tint = ColorSuccess,
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

/**
 * One-line member status. The route is only appended when it is actually known: an
 * Unknown connection state means the route has not been pinned down, not that anything
 * is wrong, and pairing "Seen just now" with a "Waiting" route made healthy members
 * look broken.
 */
private fun buildMemberStatusLine(status: MainViewModel.MemberRouteStatus?): String {
    if (status == null) return "No contact yet"
    val seenAt = status.lastLocationUpdateMs ?: status.lastSeenMs
    val route = status.routeLabel
    return when {
        seenAt != null && route != null -> "Seen ${formatTimeAgo(seenAt)} · $route"
        seenAt != null -> "Seen ${formatTimeAgo(seenAt)}"
        route != null -> route
        else -> "No contact yet"
    }
}

package com.example.familysafety.main

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.familysafety.sync.GroupSyncManager

@Composable
fun SyncIndicator(
    syncManager: GroupSyncManager,
    modifier: Modifier = Modifier
) {
    val syncState by syncManager.syncState.collectAsState()

    when (val state = syncState) {
        is GroupSyncManager.SyncState.Idle -> Unit
        is GroupSyncManager.SyncState.Syncing -> SyncingIndicator(modifier)
        is GroupSyncManager.SyncState.Synced -> SyncedIndicator(state.version, modifier)
        is GroupSyncManager.SyncState.Conflict -> ConflictIndicator(state, modifier)
        is GroupSyncManager.SyncState.Error -> ErrorIndicator(
            message = state.message,
            modifier = modifier,
            onDismiss = { syncManager.clearError() }
        )
    }
}

@Composable
private fun SyncingIndicator(modifier: Modifier = Modifier) {
    StatusPill(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(12.dp),
            strokeWidth = 1.8.dp,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "Syncing",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun SyncedIndicator(version: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(version) {
        visible = true
        kotlinx.coroutines.delay(2500)
        visible = false
    }

    if (!visible) return

    StatusPill(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f),
        borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f),
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "Synced v$version",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun ConflictIndicator(
    conflict: GroupSyncManager.SyncState.Conflict,
    modifier: Modifier = Modifier
) {
    StatusPill(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.24f),
        contentColor = MaterialTheme.colorScheme.error
    ) {
        Icon(
            imageVector = Icons.Default.WarningAmber,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = "Sync conflict",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = "v${conflict.localVersion} / v${conflict.remoteVersion}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ErrorIndicator(
    message: String,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {}
) {
    var visible by remember { mutableStateOf(true) }

    LaunchedEffect(message) {
        visible = true
        kotlinx.coroutines.delay(5000)
        visible = false
        onDismiss()
    }

    if (!visible) return

    StatusPill(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
        borderColor = MaterialTheme.colorScheme.error.copy(alpha = 0.24f),
        contentColor = MaterialTheme.colorScheme.error
    ) {
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(12.dp)
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

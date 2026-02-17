package com.example.familysafety.main

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import com.example.familysafety.sync.GroupSyncManager

@Composable
fun SyncIndicator(
    syncManager: GroupSyncManager,
    modifier: Modifier = Modifier
) {
    val syncState by syncManager.syncState.collectAsState()
    
    when (val state = syncState) {
        is GroupSyncManager.SyncState.Idle -> {
        }
        
        is GroupSyncManager.SyncState.Syncing -> {
            SyncingIndicator(modifier)
        }
        
        is GroupSyncManager.SyncState.Synced -> {
            SyncedIndicator(state.version, modifier)
        }
        
        is GroupSyncManager.SyncState.Conflict -> {
            ConflictIndicator(state, modifier)
        }
        
        is GroupSyncManager.SyncState.Error -> {
            ErrorIndicator(state.message, modifier)
        }
    }
}

@Composable
private fun SyncingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "sync")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🔄",
                modifier = Modifier.rotate(rotation)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Syncing...",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun SyncedIndicator(version: Int, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(true) }
    
    LaunchedEffect(version) {
        visible = true
        kotlinx.coroutines.delay(3000)
        visible = false
    }
    
    if (visible) {
        Card(
            modifier = modifier,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = "✓")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Synced (v$version)",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ConflictIndicator(
    conflict: GroupSyncManager.SyncState.Conflict,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "⚠️")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sync Conflict",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Local: v${conflict.localVersion}, Remote: v${conflict.remoteVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

@Composable
private fun ErrorIndicator(message: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "❌")
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }
}

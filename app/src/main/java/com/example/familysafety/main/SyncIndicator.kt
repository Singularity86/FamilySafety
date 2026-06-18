package com.example.familysafety.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.ui.theme.AmberWarning
import com.example.familysafety.ui.theme.ChipShape
import com.example.familysafety.ui.theme.RedDanger
import com.example.familysafety.ui.theme.Spacing
import com.example.familysafety.ui.theme.TealPrimary

private val SyncSlotWidth = 86.dp

@Composable
fun SyncIndicator(
    syncManager: GroupSyncManager,
    modifier: Modifier = Modifier
) {
    val syncState by syncManager.syncState.collectAsState()

    when (val state = syncState) {
        is GroupSyncManager.SyncState.Idle -> SyncInfoSlot(
            title = "Sync idle",
            message = "Okay. No family-list sync is running right now.",
            modifier = modifier
        ) {
            SyncPlaceholder()
        }
        is GroupSyncManager.SyncState.Syncing -> SyncInfoSlot(
            title = "Syncing family state",
            message = "Working. FamilySafety is checking the family list, member keys, and group version.",
            modifier = modifier
        ) {
            SyncChip(
                text = "Syncing",
                iconTint = MaterialTheme.colorScheme.primary,
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f),
                icon = Icons.Default.Sync
            )
        }
        is GroupSyncManager.SyncState.Synced -> SyncedChip(
            version = state.version,
            modifier = modifier
        )
        is GroupSyncManager.SyncState.Conflict -> SyncInfoSlot(
            title = "Sync issue",
            message = "Needs attention. This device saw two different family lists. Open Security for details.",
            modifier = modifier
        ) {
            SyncChip(
                text = "Sync issue",
                iconTint = AmberWarning,
                containerColor = AmberWarning.copy(alpha = 0.12f),
                borderColor = AmberWarning.copy(alpha = 0.34f),
                icon = Icons.Default.WarningAmber
            )
        }
        is GroupSyncManager.SyncState.Error -> SyncInfoSlot(
            title = "Sync error",
            message = "Needs attention. FamilySafety could not complete the latest family-list sync.",
            modifier = modifier
        ) {
            SyncChip(
                text = "Sync error",
                iconTint = RedDanger,
                containerColor = RedDanger.copy(alpha = 0.10f),
                borderColor = RedDanger.copy(alpha = 0.28f),
                icon = Icons.Default.Error
            )
        }
    }
}

@Composable
private fun SyncedChip(version: Long, modifier: Modifier = Modifier) {
    var closed by remember(version) { mutableStateOf(false) }
    val shutterProgress = remember(version) { Animatable(0f) }

    LaunchedEffect(version) {
        closed = false
        shutterProgress.snapTo(0f)
        kotlinx.coroutines.delay(2500)
        shutterProgress.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 1200)
        )
        closed = true
    }

    SyncInfoSlot(
        title = "Synced",
        message = "Working. This device has family list version $version, including the current members and their keys.",
        modifier = modifier
    ) {
        if (closed) {
            SyncPlaceholder()
        } else {
            SyncChip(
                text = "Synced v$version",
                iconTint = TealPrimary,
                containerColor = TealPrimary.copy(alpha = 0.10f),
                borderColor = TealPrimary.copy(alpha = 0.24f),
                icon = Icons.Default.Check,
                shutterProgress = shutterProgress.value
            )
        }
    }
}

@Composable
private fun SyncInfoSlot(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    ChipInfoPopup(title = title, message = message, modifier = modifier) {
        content()
    }
}

@Composable
private fun SyncPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.width(SyncSlotWidth),
        shape = ChipShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun SyncChip(
    text: String,
    iconTint: Color,
    containerColor: Color,
    borderColor: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    shutterProgress: Float = 0f
) {
    Surface(
        modifier = modifier.width(SyncSlotWidth),
        shape = ChipShape,
        color = containerColor,
        contentColor = iconTint,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Box(modifier = Modifier.clip(ChipShape)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(Spacing.xs))
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = iconTint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (shutterProgress > 0f) {
                val coverColor = MaterialTheme.colorScheme.surface
                Box(modifier = Modifier.matchParentSize()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(shutterProgress / 2f)
                            .background(coverColor)
                    )
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(shutterProgress / 2f)
                            .background(coverColor)
                    )
                }
            }
        }
    }
}

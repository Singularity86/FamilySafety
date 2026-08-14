package com.example.familysafety.main

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.example.familysafety.files.FileStatus
import com.example.familysafety.files.FileStatusRow
import com.example.familysafety.files.FilesViewModel
import com.example.familysafety.storage.FileTransferLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Every shared file, what state it is in, and how many devices hold it.
 *
 * The reason this screen exists: when a file failed to arrive there was nothing to look at.
 * The library showed a tile that never filled in, and no way to tell "still trying" from
 * "stopped an hour ago". Problems sort to the top, because a board sorted by date buries the
 * one file worth opening the screen for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileStatusBoardScreen(
    onBack: () -> Unit,
    viewModel: FilesViewModel = hiltViewModel()
) {
    val rows by viewModel.boardSorted.collectAsState()
    val log by viewModel.transferLog.collectAsState()
    var showLog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showLog) "Transfer log" else "File status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { showLog = !showLog }) {
                        Text(if (showLog) "Files" else "Log")
                    }
                }
            )
        }
    ) { padding ->
        if (showLog) {
            TransferLogList(log = log, modifier = Modifier.padding(padding))
        } else {
            FileStatusList(
                rows = rows,
                onRetry = viewModel::retryNow,
                onTogglePin = { id, pinned -> viewModel.setEssential(id, pinned) },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun FileStatusList(
    rows: List<FileStatusRow>,
    onRetry: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    if (rows.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "No shared files yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val atRisk = rows.count { it.status == FileStatus.AVAILABLE && !it.isRedundant }
        if (atRisk > 0) {
            item {
                // The point of the whole feature: a document that exists on exactly one phone
                // is one lost phone away from gone, and nothing else in the app says so.
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            "$atRisk file${if (atRisk == 1) " is" else "s are"} only on this " +
                                "phone. If you lose it, they are gone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        items(rows, key = { it.entity.fileId }) { row ->
            FileStatusCard(row = row, onRetry = onRetry, onTogglePin = onTogglePin)
        }
    }
}

@Composable
private fun FileStatusCard(
    row: FileStatusRow,
    onRetry: (String) -> Unit,
    onTogglePin: (String, Boolean) -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = statusIcon(row.status),
                    contentDescription = null,
                    tint = statusColor(row.status),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    row.entity.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (row.entity.isEssential) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Pinned to every device",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Text(
                row.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (row.status == FileStatus.DOWNLOADING || row.status == FileStatus.WAITING_FOR_PEER) {
                LinearProgressIndicator(
                    progress = { row.progressFraction },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                CopiesIndicator(row = row)
                Spacer(Modifier.weight(1f))
                if (row.status == FileStatus.STALLED || row.status == FileStatus.WAITING_FOR_PEER) {
                    TextButton(onClick = { onRetry(row.entity.fileId) }) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Retry now")
                    }
                }
                TextButton(onClick = { onTogglePin(row.entity.fileId, !row.entity.isEssential) }) {
                    Text(if (row.entity.isEssential) "Unpin" else "Pin")
                }
            }
        }
    }
}

/**
 * "On 3 of 4 devices", as dots plus a number.
 *
 * Dots rather than only text because this is the line people should be able to read at a
 * glance without parsing a sentence.
 */
@Composable
private fun CopiesIndicator(row: FileStatusRow) {
    val total = row.totalMembers.coerceAtLeast(1)
    val held = row.totalCopies.coerceIn(0, total)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(total) { index ->
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(
                        if (index < held) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surfaceVariant
                    )
            )
        }
        Spacer(Modifier.width(4.dp))
        Text(
            "on $held of $total",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TransferLogList(log: List<FileTransferLogEntity>, modifier: Modifier = Modifier) {
    if (log.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                "Nothing recorded yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }
    val formatter = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(log, key = { it.id }) { entry ->
            Column {
                Text(
                    "${formatter.format(Date(entry.at))}  ·  ${entry.event.replace('_', ' ')}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
                entry.detail?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
    }
}

private fun statusIcon(status: FileStatus) = when (status) {
    FileStatus.AVAILABLE -> Icons.Default.CheckCircle
    FileStatus.STALLED -> Icons.Default.ErrorOutline
    FileStatus.ON_DEMAND -> Icons.Default.CloudDownload
    else -> Icons.Default.Schedule
}

@Composable
private fun statusColor(status: FileStatus): Color = when (status) {
    FileStatus.AVAILABLE -> MaterialTheme.colorScheme.primary
    FileStatus.STALLED -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

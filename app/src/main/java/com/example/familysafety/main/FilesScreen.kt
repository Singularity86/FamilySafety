package com.example.familysafety.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.example.familysafety.files.FilesViewModel
import com.example.familysafety.files.SharedFileRepository
import com.example.familysafety.storage.SharedFileEntity
import com.example.familysafety.ui.components.AppButton
import com.example.familysafety.ui.components.ButtonState
import com.example.familysafety.ui.components.ShimmerBox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    viewModel: FilesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val files by viewModel.files.collectAsState()
    val totalUsedBytes by viewModel.totalUsedBytes.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val memberNames by viewModel.memberNames.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    var fileToDelete by remember { mutableStateOf<SharedFileEntity?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.uploadFile(it) }
    }

    // Error snackbar
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            if (uploadProgress == null) {
                FloatingActionButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = "Upload file")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Storage usage bar
            StorageBar(usedBytes = totalUsedBytes, maxBytes = SharedFileRepository.MAX_TOTAL_BYTES)

            // Upload progress banner
            uploadProgress?.let { progress ->
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Text(
                    text = "Uploading ${progress.fileName}… ${progress.chunksUploaded}/${progress.totalChunks} chunks",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            if (files.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "No files yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Share a file with your family",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(24.dp))
                        AppButton(
                            label = "Add File",
                            state = if (uploadProgress != null) ButtonState.Loading else ButtonState.Idle,
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(files, key = { it.fileId }) { file ->
                        FileCard(
                            file = file,
                            uploaderName = memberNames[file.uploaderMemberId] ?: "Unknown",
                            onClick = { viewModel.openFile(file, context) },
                            onLongClick = { fileToDelete = file }
                        )
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    fileToDelete?.let { file ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            icon = { Icon(Icons.Default.Delete, contentDescription = null) },
            title = { Text("Delete File") },
            text = { Text("Delete \"${file.name}\" for everyone in the family?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(file.fileId)
                        fileToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StorageBar(usedBytes: Long, maxBytes: Long) {
    val fraction = (usedBytes.toFloat() / maxBytes).coerceIn(0f, 1f)
    val isNearFull = fraction > 0.9f
    val usedMb = usedBytes / (1024 * 1024)
    val maxMb = maxBytes / (1024 * 1024)

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Family Files", style = MaterialTheme.typography.titleMedium)
            Text(
                "$usedMb MB / $maxMb MB",
                style = MaterialTheme.typography.labelMedium,
                color = if (isNearFull) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = if (isNearFull) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
        )
        if (isNearFull) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Storage almost full — delete files to make room",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: SharedFileEntity,
    uploaderName: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isComplete = file.downloadState == "COMPLETE"
    val isDownloading = file.downloadState == "DOWNLOADING"
    val isPending = file.downloadState == "PENDING"

    Card(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = RoundedCornerShape(12.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background layer — shimmer while downloading, thumbnail/icon when ready
            when {
                isDownloading -> ShimmerBox(modifier = Modifier.fillMaxSize())
                file.mimeType.startsWith("image/") && isComplete && file.localPath != null ->
                    AsyncImage(
                        model = File(file.localPath),
                        contentDescription = file.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = mimeTypeIcon(file.mimeType),
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Progress ring overlaid on shimmer — shows actual chunk completion
            if (isDownloading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        progress = { if (file.chunkCount > 0) file.chunksReceived.toFloat() / file.chunkCount else 0f },
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = Color.White
                    )
                }
            }

            // Pending overlay — file known but not yet received
            if (isPending) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "Waiting for download",
                        modifier = Modifier.size(32.dp),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }

            // File name + uploader label at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            ) {
                Column {
                    Text(
                        text = file.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$uploaderName · ${formatDate(file.uploadedAt)}",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.85f
                        ),
                        color = Color.White.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun mimeTypeIcon(mimeType: String): ImageVector = when {
    mimeType.startsWith("image/") -> Icons.Default.Image
    mimeType.startsWith("video/") -> Icons.Default.Videocam
    mimeType.startsWith("audio/") -> Icons.Default.AudioFile
    mimeType == "application/pdf" -> Icons.Default.PictureAsPdf
    mimeType.contains("word") || mimeType.contains("document") -> Icons.Default.Description
    mimeType.contains("sheet") || mimeType.contains("excel") -> Icons.Default.TableChart
    else -> Icons.Default.InsertDriveFile
}

private fun formatDate(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "now"
        diff < 3_600_000 -> "${diff / 60_000}m"
        diff < 86_400_000 -> "${diff / 3_600_000}h"
        diff < 7 * 86_400_000 -> "${diff / 86_400_000}d"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}

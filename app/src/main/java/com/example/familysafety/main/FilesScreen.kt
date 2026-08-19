package com.example.familysafety.main

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import com.example.familysafety.vault.VaultKeyDerivation
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
import com.example.familysafety.ui.theme.AmberWarning
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    onOpenStatusBoard: () -> Unit = {},
    onOpenVault: (String) -> Unit = {},
    viewModel: FilesViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    var query by remember { mutableStateOf("") }
    val allFiles by viewModel.files.collectAsState()
    val files = remember(allFiles, query) {
        val needle = query.trim()
        if (needle.isBlank()) allFiles
        else allFiles.filter { it.name.contains(needle, ignoreCase = true) }
    }
    val totalUsedBytes by viewModel.totalUsedBytes.collectAsState()
    val uploadProgress by viewModel.uploadProgress.collectAsState()
    val memberNames by viewModel.memberNames.collectAsState()
    // Status comes from the shared board model so this grid and the status board can never
    // disagree about what state a file is in.
    val board by viewModel.board.collectAsState()
    val statusByFile = board.associateBy { it.entity.fileId }
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
                MetalActionButton(
                    label = "Add File",
                    icon = Icons.Default.Add,
                    onClick = { filePicker.launch("*/*") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search, and the way into the vault.
            //
            // Typing filters the grid. Submitting from the keyboard tries the text as a vault
            // passphrase instead — which is why there is no "enter passphrase" prompt anywhere
            // in this app: a prompt would prove a vault exists, and the whole design rests on
            // that being unknowable. Any text of at least the minimum length opens a vault, so
            // submitting never distinguishes a real passphrase from anything else.
            //
            // Nothing here hints at the minimum, or at what makes a passphrase good. A hint
            // is a prompt wearing a different hat, and this box has to look like search to
            // someone reading over a shoulder. The one place that guidance can be given is
            // inside the vault, which is only reached by having submitted something.
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search files") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(
                    onGo = {
                        val typed = query.trim()
                        if (typed.length >= VaultKeyDerivation.MIN_PASSPHRASE_LENGTH) {
                            query = ""
                            keyboard?.hide()
                            onOpenVault(typed)
                        }
                    }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Storage usage bar
            StorageBar(usedBytes = totalUsedBytes, maxBytes = SharedFileRepository.MAX_TOTAL_BYTES)

            // Summary line into the status board. Surfaces the one number worth acting on —
            // files that exist on this phone and nowhere else — rather than making someone
            // open a screen to find out whether anything is wrong.
            val needsAttention = board.count {
                it.status == com.example.familysafety.files.FileStatus.STALLED ||
                    it.status == com.example.familysafety.files.FileStatus.WAITING_FOR_PEER
            }
            val onlyHere = board.count { it.isKnownToBeOnlyHere }
            if (board.isNotEmpty()) {
                Surface(
                    onClick = onOpenStatusBoard,
                    color = if (needsAttention > 0 || onlyHere > 0) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = when {
                                needsAttention > 0 -> "$needsAttention file${if (needsAttention == 1) "" else "s"} still arriving"
                                onlyHere > 0 -> "$onlyHere file${if (onlyHere == 1) " is" else "s are"} only on this phone"
                                else -> "All files are on every device"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Details",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

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
                            status = statusByFile[file.fileId],
                            thumbnailProvider = viewModel::thumbnailFile,
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
                color = if (isNearFull) AmberWarning
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
            color = if (isNearFull) AmberWarning
                    else MaterialTheme.colorScheme.primary
        )
        if (isNearFull) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Storage almost full — delete files to make room",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: SharedFileEntity,
    uploaderName: String,
    status: com.example.familysafety.files.FileStatusRow?,
    thumbnailProvider: suspend (SharedFileEntity) -> File?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isComplete = file.downloadState == "COMPLETE"
    val isDownloading = file.downloadState == "DOWNLOADING"
    val isPending = file.downloadState == "PENDING"

    // Documents are stored encrypted, so a thumbnail is a decryption rather than a file path.
    // Done per tile and keyed on the file so only what is actually on screen is decrypted,
    // and the icon stands in until it is ready rather than the tile flashing empty.
    val thumbnail by produceState<File?>(initialValue = null, file.fileId, file.downloadState) {
        value = thumbnailProvider(file)
    }

    OutlinedCard(
        modifier = Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Background layer — shimmer while downloading, thumbnail/icon when ready
            when {
                isDownloading -> ShimmerBox(modifier = Modifier.fillMaxSize())
                file.mimeType.startsWith("image/") && isComplete && thumbnail != null ->
                    AsyncImage(
                        model = thumbnail,
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

            // Status chip and copy count, top-left. Two things a glance should answer:
            // is this file here, and does anyone else have it. The second is the one that
            // matters for an emergency document and nothing used to show it.
            status?.let { row ->
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // A finished file needs a mark, not a sentence. On a three-column grid
                    // the word "Available" ate most of the tile width to say what the
                    // thumbnail already showed, and it crowded out the states that actually
                    // need reading.
                    if (row.status == com.example.familysafety.files.FileStatus.AVAILABLE) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Downloaded",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        StatusPill(
                            containerColor = when (row.status) {
                                com.example.familysafety.files.FileStatus.STALLED ->
                                    MaterialTheme.colorScheme.errorContainer
                                else -> MaterialTheme.colorScheme.tertiaryContainer
                            }
                        ) {
                            Text(
                                row.shortLabel,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                    if (row.entity.isEssential) {
                        Icon(
                            imageVector = Icons.Default.PushPin,
                            contentDescription = "Pinned to every device",
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Copy dots, top-right — filled per device that holds it.
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        // Thumbnails are arbitrary images; a pale scan left white dots
                        // invisible. The scrim makes them readable over anything.
                        .background(
                            Color.Black.copy(alpha = 0.45f),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 5.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    val total = row.totalMembers.coerceAtLeast(1)
                    val held = row.totalCopies.coerceIn(0, total)
                    repeat(total) { index ->
                        Box(
                            Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(
                                    if (index < held) MaterialTheme.colorScheme.primary
                                    else Color.White.copy(alpha = 0.55f)
                                )
                        )
                    }
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

package com.example.familysafety.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.SharedFileEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FilesViewModel @Inject constructor(
    private val fileRepository: SharedFileRepository,
    private val groupStateManager: GroupStateManager
) : ViewModel() {

    val files: StateFlow<List<SharedFileEntity>> = fileRepository
        .observeAllFiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val totalUsedBytes: StateFlow<Long> = fileRepository
        .observeAllFiles()
        .map { list -> list.filter { !it.isDeleted }.sumOf { it.sizeBytes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val uploadProgress: StateFlow<UploadProgress?> = fileRepository.uploadProgress

    /**
     * Every file with its state, how many devices hold it, and what happened last.
     *
     * Combined here rather than in the UI so the grid tile and the status board can never
     * disagree about what a file's state is, and so the status rules live in one testable
     * place ([FileStatusRow]) instead of being re-derived per screen.
     */
    val board: StateFlow<List<FileStatusRow>> = combine(
        fileRepository.observeAllFiles(),
        fileRepository.observeCopyCounts(),
        groupStateManager.groupDefinition,
        fileRepository.observeAnnouncingPeerCount()
    ) { files, copyCounts, group, announcingPeers ->
        val copies = copyCounts.associate { it.fileId to it.copies }
        // Until at least one peer reports its holdings, a copy count of zero means we have
        // not been told — not that no copy exists. Claiming a document is nowhere else on
        // that basis would be alarming and wrong.
        val availabilityKnown = announcingPeers > 0
        val names = group?.members?.associate { it.memberId to it.displayName } ?: emptyMap()
        val memberCount = group?.members?.size ?: 1
        files.filterNot { it.isDeleted }.map { entity ->
            FileStatusRow(
                entity = entity,
                uploaderName = names[entity.uploaderMemberId] ?: "Someone",
                copiesElsewhere = copies[entity.fileId] ?: 0,
                totalMembers = memberCount,
                lastActivityAt = entity.lastAttemptAt,
                lastActivityDetail = entity.lastError,
                availabilityKnown = availabilityKnown
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Problems first, then work in progress, then everything that is fine.
     *
     * A status board sorted by upload date buries the one file that needs attention, which is
     * the only reason to open it.
     */
    val boardSorted: StateFlow<List<FileStatusRow>> = board
        .map { rows ->
            rows.sortedWith(
                compareBy(
                    { statusRank(it.status) },
                    { -(it.entity.uploadedAt) }
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun statusRank(status: FileStatus): Int = when (status) {
        FileStatus.STALLED -> 0
        FileStatus.WAITING_FOR_PEER -> 1
        FileStatus.DOWNLOADING -> 2
        FileStatus.QUEUED -> 3
        FileStatus.UPLOADING -> 4
        FileStatus.ON_DEMAND -> 5
        FileStatus.AVAILABLE -> 6
    }

    val transferLog = fileRepository.observeTransferLog()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setEssential(fileId: String, essential: Boolean) {
        viewModelScope.launch {
            fileRepository.setEssential(fileId, essential).onFailure { error ->
                _errorMessage.value = error.message ?: "Could not change pinning"
            }
        }
    }

    fun retryNow(fileId: String) {
        viewModelScope.launch { fileRepository.retryNow(fileId) }
    }

    /** Map of memberId → displayName for showing who uploaded each file. */
    val memberNames: StateFlow<Map<String, String>> = groupStateManager.groupDefinition
        .map { group -> group?.members?.associate { it.memberId to it.displayName } ?: emptyMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun uploadFile(uri: Uri) {
        viewModelScope.launch {
            fileRepository.uploadFile(uri).onFailure { error ->
                _errorMessage.value = error.message ?: "Upload failed"
                Timber.e(error, "Upload failed")
            }
        }
    }

    fun deleteFile(fileId: String) {
        viewModelScope.launch {
            fileRepository.deleteFile(fileId).onFailure { error ->
                _errorMessage.value = error.message ?: "Delete failed"
            }
        }
    }

    /**
     * Open a document in whatever app handles its type.
     *
     * Documents are stored encrypted, so this decrypts one into the private cache first and
     * hands that file out under a read grant. It is asynchronous for the same reason: a large
     * document has to be streamed and hashed before it can be shown, and doing that on the
     * main thread is how a tap turns into a frozen screen.
     */
    fun openFile(file: SharedFileEntity, context: Context) {
        if (file.downloadState != "COMPLETE") {
            // Opening a file that is not here is a normal, expected action rather than a
            // repair: non-essential files are deliberately left undownloaded until wanted.
            // This asks one peer for exactly the missing pieces, where it used to ask everyone
            // to re-broadcast their entire library.
            _errorMessage.value = "Downloading \"${file.name}\"…"
            viewModelScope.launch { fileRepository.requestDownload(file.fileId) }
            return
        }

        viewModelScope.launch {
            val decrypted = fileRepository.materializeForViewing(file.fileId)
            if (decrypted == null) {
                _errorMessage.value = "Could not open \"${file.name}\""
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    decrypted
                )
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, file.mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                _errorMessage.value = "No app found to open this file"
                Timber.e(e, "Failed to open file")
            }
        }
    }

    /**
     * The decrypted file backing a grid thumbnail, or null while it is being produced.
     *
     * Thumbnails used to point straight at the stored file, which only worked because that
     * file was plaintext. Now the image has to be decrypted first, and the grid asks for it
     * per tile so nothing is decrypted that never comes on screen.
     */
    suspend fun thumbnailFile(file: SharedFileEntity): File? =
        if (file.downloadState == "COMPLETE" && file.mimeType.startsWith("image/")) {
            fileRepository.materializeForViewing(file.fileId)
        } else null

    fun clearError() {
        _errorMessage.value = null
    }
}

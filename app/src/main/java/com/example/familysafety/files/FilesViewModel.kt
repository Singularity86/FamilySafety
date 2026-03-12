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

    fun openFile(file: SharedFileEntity, context: Context) {
        val localPath = file.localPath
        if (localPath == null || !File(localPath).exists()) {
            _errorMessage.value = "File not downloaded yet — requesting from other members…"
            viewModelScope.launch {
                fileRepository.requestFilesFromPeers()
            }
            return
        }
        val f = File(localPath)
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                f
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

    fun clearError() {
        _errorMessage.value = null
    }
}

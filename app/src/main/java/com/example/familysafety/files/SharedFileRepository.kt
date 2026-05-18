package com.example.familysafety.files

import android.content.Context
import android.net.Uri
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.SharedFileDao
import com.example.familysafety.storage.SharedFileEntity
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the family's shared file library.
 *
 * Every file is replicated to every device using MQTT chunked transfer.
 * Files are encrypted at rest with AES-256-GCM using a key derived from the groupId.
 * Total storage across the group is capped at MAX_TOTAL_BYTES (500 MB).
 */
@Singleton
class SharedFileRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sharedFileDao: SharedFileDao,
    private val groupStateManager: GroupStateManager,
    private val transportProvider: com.example.familysafety.transport.TransportProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Wire up the MQTT publisher.
     * Legacy method - no longer used with TransportProvider.
     */
    fun setMqttPublisher(
        publisher: suspend (topic: String, payload: ByteArray, qos: Int, retained: Boolean) -> Boolean
    ) {
        // No-op
    }

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    companion object {
        private const val TAG = "SharedFileRepository"
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024   // 500 MB
        private const val CHUNK_SIZE = 32 * 1024           // 32 KB
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val FILE_KEY_SALT = "familysafety-files-v1"
    }

    // =========================================================================
    // QUERIES
    // =========================================================================

    fun observeAllFiles(): Flow<List<SharedFileEntity>> = sharedFileDao.observeAllFiles()

    suspend fun getTotalUsedBytes(): Long = sharedFileDao.getTotalSizeBytes()

    // =========================================================================
    // UPLOAD
    // =========================================================================

    /**
     * Read a file from [uri], encrypt it, split into chunks, and publish everything over MQTT.
     * The uploader's device marks the file as COMPLETE immediately (no need to receive own chunks).
     */
    suspend fun uploadFile(uri: Uri): Result<Unit> {
        return try {
            val groupId = groupStateManager.groupDefinition.value?.groupId
                ?: return Result.failure(IllegalStateException("No group available"))

            // Read bytes
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return Result.failure(IllegalArgumentException("Cannot read file"))

            // Quota check
            val currentUsed = sharedFileDao.getTotalSizeBytes()
            if (currentUsed + bytes.size > MAX_TOTAL_BYTES) {
                return Result.failure(IllegalStateException("Group storage limit of 500 MB reached"))
            }

            // Metadata
            val contentHash = sha256Hex(bytes)
            val existingFiles = sharedFileDao.getAllFiles()
            if (existingFiles.any { it.contentHash == contentHash && !it.isDeleted }) {
                Timber.d("$TAG: File already in library (duplicate hash), skipping upload")
                return Result.success(Unit)
            }

            val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val fileId = UUID.randomUUID().toString()

            // Derive group file key
            val fileKey = deriveFileKey(groupId)

            // Persist metadata before publishing chunks so receivers can create their
            // pending records from the manifest before chunk messages arrive.
            val entity = SharedFileEntity(
                fileId = fileId,
                name = fileName,
                mimeType = mimeType,
                sizeBytes = bytes.size.toLong(),
                contentHash = contentHash,
                uploaderMemberId = groupStateManager.localMember.value?.memberId ?: "",
                uploadedAt = System.currentTimeMillis(),
                chunkCount = 0,
                localPath = null,
                chunksReceived = 0,
                downloadState = "PENDING"
            )

            // Split into 32 KB chunks, encrypt each
            val chunks = bytes.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }
            val totalChunks = chunks.size

            _uploadProgress.value = UploadProgress(fileId, fileName, 0, totalChunks)

            sharedFileDao.upsert(entity.copy(chunkCount = totalChunks))
            broadcastManifest(groupId)

            chunks.forEachIndexed { index, chunk ->
                val encryptedChunk = encrypt(chunk, fileKey)
                val chunkMsg = FileChunkMessage(
                    fileId = fileId,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    data = Base64.getEncoder().encodeToString(encryptedChunk)
                )
                val topic = MqttConfig.getFileChunkTopic(groupId, fileId, index)
                
                // Use unified transport broadcast
                transportProvider.broadcastMessage(
                    topic = topic,
                    payload = json.encodeToString(chunkMsg).toByteArray(),
                    qos = MqttConfig.QOS_AT_LEAST_ONCE,
                    retained = false
                )
                
                _uploadProgress.value = UploadProgress(fileId, fileName, index + 1, totalChunks)
            }

            // Save file locally (uploader already has the plaintext)
            val localPath = saveToLocalStorage(fileId, bytes)

            sharedFileDao.upsert(
                entity.copy(
                    chunkCount = totalChunks,
                    localPath = localPath,
                    chunksReceived = totalChunks,
                    downloadState = "COMPLETE"
                )
            )

            // Broadcast updated manifest (retained so new members get it immediately)
            broadcastManifest(groupId)

            _uploadProgress.value = null
            Timber.i("$TAG: Uploaded $fileName ($totalChunks chunks)")
            Result.success(Unit)
        } catch (e: Exception) {
            _uploadProgress.value = null
            Timber.e(e, "$TAG: Upload failed")
            Result.failure(e)
        }
    }

    // =========================================================================
    // INCOMING MQTT MESSAGES
    // =========================================================================

    fun handleIncomingManifest(payload: ByteArray) {
        scope.launch {
            try {
                val manifest = json.decodeFromString<FileManifest>(String(payload))
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                if (manifest.groupId != groupId) return@launch

                Timber.d("$TAG: Received manifest with ${manifest.files.size} files")

                manifest.files.forEach { sharedFile ->
                    val existing = sharedFileDao.getFileById(sharedFile.fileId)
                    if (existing == null) {
                        // New file — create a PENDING record to trigger download
                        sharedFileDao.upsert(sharedFile.toEntity())
                        tryAssembleFile(sharedFile.fileId)
                    } else if (sharedFile.isDeleted && !existing.isDeleted) {
                        // Remotely deleted
                        sharedFileDao.markDeleted(
                            sharedFile.fileId,
                            sharedFile.deletedByMemberId ?: "",
                            sharedFile.deletedAt ?: System.currentTimeMillis()
                        )
                        deleteLocalFile(sharedFile.fileId)
                    } else if (existing.downloadState != "COMPLETE") {
                        tryAssembleFile(sharedFile.fileId)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to handle manifest")
            }
        }
    }

    fun handleIncomingChunk(fileId: String, chunkIndex: Int, payload: ByteArray) {
        scope.launch {
            try {
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                val chunkMsg = json.decodeFromString<FileChunkMessage>(String(payload))
                if (chunkMsg.fileId != fileId || chunkMsg.chunkIndex != chunkIndex) {
                    Timber.w("$TAG: Ignoring chunk with mismatched topic metadata for $fileId/$chunkIndex")
                    return@launch
                }

                val entity = sharedFileDao.getFileById(fileId)
                if (entity?.downloadState == "COMPLETE") return@launch

                val encryptedBytes = Base64.getDecoder().decode(chunkMsg.data)
                val fileKey = deriveFileKey(groupId)
                val plainChunk = decrypt(encryptedBytes, fileKey)

                writeTempChunk(fileId, chunkIndex, plainChunk)

                if (entity == null) {
                    Timber.d("$TAG: Stashed chunk $chunkIndex for $fileId before manifest arrived")
                    return@launch
                }

                // Count unique chunk files on disk — naturally handles concurrent coroutines
                // and at-least-once redelivery (same chunk overwrites same file = still 1 count).
                val tmpDir = File(filesDir(), "$fileId/.tmp")
                val received = tmpDir.listFiles()?.size ?: 0

                sharedFileDao.updateDownloadProgress(fileId, "DOWNLOADING", null, received)

                if (received >= entity.chunkCount) {
                    // Re-fetch before assembling to guard against two coroutines both seeing
                    // received == chunkCount simultaneously.
                    val fresh = sharedFileDao.getFileById(fileId) ?: return@launch
                    if (fresh.downloadState == "COMPLETE") return@launch

                    val assembled = assembleChunks(fileId, entity.chunkCount)
                    val hash = sha256Hex(assembled)
                    if (hash != entity.contentHash) {
                        Timber.e("$TAG: Hash mismatch for $fileId — discarding")
                        sharedFileDao.updateDownloadProgress(fileId, "FAILED", null, received)
                        return@launch
                    }
                    val localPath = saveToLocalStorage(fileId, assembled)
                    cleanTempChunks(fileId)
                    sharedFileDao.updateDownloadProgress(fileId, "COMPLETE", localPath, received)
                    Timber.i("$TAG: Assembled file ${entity.name}")
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to handle chunk $chunkIndex for $fileId")
            }
        }
    }

    /** Re-broadcast manifest + all file chunks when a peer requests it (e.g. new member). */
    fun handleFileRequest(payload: ByteArray) {
        scope.launch {
            try {
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                val fileKey = deriveFileKey(groupId)

                broadcastManifest(groupId)

                sharedFileDao.getAllFiles().filter { !it.isDeleted && it.downloadState == "COMPLETE" }.forEach { entity ->
                    val localPath = entity.localPath ?: return@forEach
                    val bytes = File(localPath).takeIf { it.exists() }?.readBytes() ?: return@forEach
                    val chunks = bytes.toList().chunked(CHUNK_SIZE).map { it.toByteArray() }
                    chunks.forEachIndexed { index, chunk ->
                        val encryptedChunk = encrypt(chunk, fileKey)
                        val chunkMsg = FileChunkMessage(
                            fileId = entity.fileId,
                            chunkIndex = index,
                            totalChunks = chunks.size,
                            data = Base64.getEncoder().encodeToString(encryptedChunk)
                        )
                        val topic = MqttConfig.getFileChunkTopic(groupId, entity.fileId, index)
                        transportProvider.broadcastMessage(topic, json.encodeToString(chunkMsg).toByteArray(), MqttConfig.QOS_AT_LEAST_ONCE, false)
                    }
                }
                Timber.i("$TAG: Re-broadcast all files in response to request")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to handle file request")
            }
        }
    }

    // =========================================================================
    // DELETE
    // =========================================================================

    suspend fun deleteFile(fileId: String): Result<Unit> {
        return try {
            val localMemberId = groupStateManager.localMember.value?.memberId
                ?: return Result.failure(IllegalStateException("No local member"))
            val groupId = groupStateManager.groupDefinition.value?.groupId
                ?: return Result.failure(IllegalStateException("No group"))

            val now = System.currentTimeMillis()
            sharedFileDao.markDeleted(fileId, localMemberId, now)
            deleteLocalFile(fileId)
            broadcastManifest(groupId)
            Timber.i("$TAG: Deleted file $fileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to delete file $fileId")
            Result.failure(e)
        }
    }

    // =========================================================================
    // NEW MEMBER SYNC
    // =========================================================================

    /**
     * Ask every other group member to re-broadcast all their file chunks.
     * Used when this device missed chunks because it was offline during an upload.
     * Each peer receives the request on their own file-request topic and responds
     * by re-publishing the manifest + all chunks to the group.
     */
    suspend fun requestFilesFromPeers(): Result<Unit> {
        return try {
            val groupDef = groupStateManager.groupDefinition.value
                ?: return Result.failure(IllegalStateException("No group"))
            val myMemberId = groupStateManager.localMember.value?.memberId
                ?: return Result.failure(IllegalStateException("No local member"))

            val payload = json.encodeToString(FileRequestMessage(requesterId = myMemberId))
                .toByteArray()

            groupDef.members
                .filter { it.memberId != myMemberId }
                .forEach { member ->
                    val topic = MqttConfig.getFileRequestTopic(member.memberId)
                    transportProvider.sendMessage(member.memberId, topic, payload, MqttConfig.QOS_AT_LEAST_ONCE, false)
                }

            Timber.d("$TAG: Requested file re-broadcast from ${groupDef.members.size - 1} peers")
            Result.success(Unit)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to request files from peers")
            Result.failure(e)
        }
    }

    /**
     * Called by AppInitializer when a new member joins.
     * The retained manifest message will be received automatically;
     * we also publish a fresh manifest in case the retained one is stale.
     */
    fun syncNewMember(groupId: String) {
        scope.launch {
            try {
                broadcastManifest(groupId)
                Timber.d("$TAG: Broadcast manifest for new member")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to sync new member")
            }
        }
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private suspend fun broadcastManifest(groupId: String) {
        val files = sharedFileDao.getAllFiles().map { it.toSharedFile() }
        val manifest = FileManifest(groupId, files, System.currentTimeMillis())
        val topic = MqttConfig.getFileManifestTopic(groupId)
        transportProvider.broadcastMessage(
            topic,
            json.encodeToString(manifest).toByteArray(),
            MqttConfig.QOS_AT_LEAST_ONCE,
            true  // retained — new subscribers get it immediately
        )
    }

    private suspend fun tryAssembleFile(fileId: String) {
        val entity = sharedFileDao.getFileById(fileId) ?: return
        if (entity.downloadState == "COMPLETE") return

        val tmpDir = File(filesDir(), "$fileId/.tmp")
        val received = tmpDir.listFiles()?.size ?: 0
        if (received == 0) return

        sharedFileDao.updateDownloadProgress(fileId, "DOWNLOADING", null, received)
        if (received < entity.chunkCount) return

        val fresh = sharedFileDao.getFileById(fileId) ?: return
        if (fresh.downloadState == "COMPLETE") return

        val assembled = assembleChunks(fileId, entity.chunkCount)
        val hash = sha256Hex(assembled)
        if (hash != entity.contentHash) {
            Timber.e("$TAG: Hash mismatch for $fileId, discarding")
            sharedFileDao.updateDownloadProgress(fileId, "FAILED", null, received)
            return
        }

        val localPath = saveToLocalStorage(fileId, assembled)
        cleanTempChunks(fileId)
        sharedFileDao.updateDownloadProgress(fileId, "COMPLETE", localPath, received)
        Timber.i("$TAG: Assembled file ${entity.name}")
    }

    private fun deriveFileKey(groupId: String): ByteArray {
        val material = (groupId + FILE_KEY_SALT).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(material)
    }

    private fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { java.security.SecureRandom().nextBytes(it) }
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val nonce = data.copyOfRange(0, NONCE_BYTES)
        val ciphertext = data.copyOfRange(NONCE_BYTES, data.size)
        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun filesDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "familysafety_files")
            .also { it.mkdirs() }

    private fun saveToLocalStorage(fileId: String, bytes: ByteArray): String {
        val dir = File(filesDir(), fileId).also { it.mkdirs() }
        val file = File(dir, "content")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    private fun deleteLocalFile(fileId: String) {
        File(filesDir(), fileId).deleteRecursively()
    }

    private fun writeTempChunk(fileId: String, chunkIndex: Int, bytes: ByteArray) {
        val tmpDir = File(filesDir(), "$fileId/.tmp").also { it.mkdirs() }
        File(tmpDir, "chunk_$chunkIndex").writeBytes(bytes)
    }

    private fun assembleChunks(fileId: String, chunkCount: Int): ByteArray {
        val tmpDir = File(filesDir(), "$fileId/.tmp")
        val out = java.io.ByteArrayOutputStream()
        for (i in 0 until chunkCount) {
            val chunk = File(tmpDir, "chunk_$i")
            out.write(chunk.readBytes())
        }
        return out.toByteArray()
    }

    private fun cleanTempChunks(fileId: String) {
        File(filesDir(), "$fileId/.tmp").deleteRecursively()
    }

    // =========================================================================
    // ENTITY CONVERTERS
    // =========================================================================

    private fun SharedFileEntity.toSharedFile() = SharedFile(
        fileId = fileId, name = name, mimeType = mimeType, sizeBytes = sizeBytes,
        contentHash = contentHash, uploaderMemberId = uploaderMemberId,
        uploadedAt = uploadedAt, chunkCount = chunkCount,
        isDeleted = isDeleted, deletedByMemberId = deletedByMemberId, deletedAt = deletedAt
    )

    private fun SharedFile.toEntity() = SharedFileEntity(
        fileId = fileId, name = name, mimeType = mimeType, sizeBytes = sizeBytes,
        contentHash = contentHash, uploaderMemberId = uploaderMemberId,
        uploadedAt = uploadedAt, chunkCount = chunkCount,
        isDeleted = isDeleted, deletedByMemberId = deletedByMemberId, deletedAt = deletedAt,
        localPath = null, chunksReceived = 0, downloadState = if (isDeleted) "COMPLETE" else "PENDING"
    )
}

data class UploadProgress(
    val fileId: String,
    val fileName: String,
    val chunksUploaded: Int,
    val totalChunks: Int
) {
    val fraction: Float get() = if (totalChunks == 0) 0f else chunksUploaded.toFloat() / totalChunks
}

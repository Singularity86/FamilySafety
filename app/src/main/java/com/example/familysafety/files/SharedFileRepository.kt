package com.example.familysafety.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.SharedFileDao
import com.example.familysafety.storage.SharedFileEntity
import com.example.familysafety.transport.MqttConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
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

    private val _uploadProgress = MutableStateFlow<UploadProgress?>(null)
    val uploadProgress: StateFlow<UploadProgress?> = _uploadProgress.asStateFlow()

    /**
     * Blob storage plus the streaming paths. Constructed rather than injected: it owns no
     * state beyond per-file locks and needs only the directory, which is resolved lazily
     * because external storage may not be mounted when the repository is built.
     */
    private val fileStore = ChunkedFileStore { filesDir() }

    companion object {
        private const val TAG = "SharedFileRepository"
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024   // 500 MB
        private const val CHUNK_SIZE = 32 * 1024           // 32 KB
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val FILE_KEY_SALT = "familysafety-files-v1"
        /** Grid the encrypted manifest is padded to, so its size hides the file count. */
        private const val MANIFEST_PAD_BYTES = 1024

        /**
         * Delay between chunk publishes. Paho's default in-flight window is 10 unacked
         * messages, and MqttTransport treats a publish exception as a dropped connection —
         * it queues the message, marks itself disconnected and schedules a reconnect. Pacing
         * keeps a large upload from knocking the app off its own broker.
         */
        private const val PUBLISH_PACING_MS = 15L

        /** Free space kept in reserve when staging an upload. */
        private const val STAGING_HEADROOM_BYTES = 16L * 1024 * 1024

        /** Cap on indices in one repair request, so it fits in a single message. */
        const val MAX_MISSING_PER_REQUEST = 256
    }

    /** Bytes one chunk occupies in the blob: nonce + ciphertext + GCM tag. */
    private fun strideBytes(): Int = fileStore.strideFor(CHUNK_SIZE, NONCE_BYTES, GCM_TAG_BITS)

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
        var stagedFileId: String? = null
        return try {
            val groupId = groupStateManager.groupDefinition.value?.groupId
                ?: return Result.failure(IllegalStateException("No group available"))

            val fileName = queryDisplayName(uri)
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val declaredSize = queryDeclaredSize(uri)

            // Quota is checked against the size the provider declares, before a byte is read.
            // The old order read the whole file into memory first and only then asked whether
            // it would fit, so an oversized file was refused only after paying for it.
            val currentUsed = sharedFileDao.getTotalSizeBytes()
            if (declaredSize != null && currentUsed + declaredSize > MAX_TOTAL_BYTES) {
                return Result.failure(IllegalStateException("Group storage limit of 500 MB reached"))
            }
            if (declaredSize != null) {
                val needed = declaredSize + STAGING_HEADROOM_BYTES
                val available = fileStore.usableSpaceBytes()
                if (available in 1 until needed) {
                    return Result.failure(
                        IllegalStateException(
                            "Not enough storage: ${declaredSize / 1024} KB needed, " +
                                "${available / 1024} KB free"
                        )
                    )
                }
            }

            val fileId = UUID.randomUUID().toString()
            stagedFileId = fileId

            // Pass 1: stream the source to our own copy, hashing as it goes. Peak memory is
            // one buffer, not the file. This replaced readBytes() + toList().chunked(), which
            // held the whole file several times over and made large uploads an OOM.
            val (contentHash, sizeBytes) = context.contentResolver.openInputStream(uri)?.use { input ->
                fileStore.copyAndHash(input, fileStore.contentFile(fileId), CHUNK_SIZE)
            } ?: return Result.failure(IllegalArgumentException("Cannot read file"))

            // The hash is only known after streaming, so the duplicate check moves here and
            // has to clean up the copy it just made.
            val existingFiles = sharedFileDao.getAllFiles()
            if (existingFiles.any { it.contentHash == contentHash && !it.isDeleted }) {
                Timber.d("$TAG: File already in library (duplicate hash), skipping upload")
                fileStore.deleteAll(fileId)
                stagedFileId = null
                return Result.success(Unit)
            }

            if (currentUsed + sizeBytes > MAX_TOTAL_BYTES) {
                fileStore.deleteAll(fileId)
                stagedFileId = null
                return Result.failure(IllegalStateException("Group storage limit of 500 MB reached"))
            }

            val (fileKey, fileKeyVersion) = resolveEncryptionKey(groupId)
            val totalChunks = chunkCountFor(sizeBytes)
            val contentFile = fileStore.contentFile(fileId)

            // Persist metadata before publishing chunks so receivers can create their
            // pending records from the manifest before chunk messages arrive. The uploader
            // already holds the plaintext, so it is COMPLETE from the start — there is no
            // window where its own file looks half-downloaded.
            val entity = SharedFileEntity(
                fileId = fileId,
                name = fileName,
                mimeType = mimeType,
                sizeBytes = sizeBytes,
                contentHash = contentHash,
                uploaderMemberId = groupStateManager.localMember.value?.memberId ?: "",
                uploadedAt = System.currentTimeMillis(),
                chunkCount = totalChunks,
                localPath = contentFile.absolutePath,
                chunksReceived = totalChunks,
                downloadState = "COMPLETE",
                chunkBitmap = null,
                blobKeyVersion = fileKeyVersion
            )
            sharedFileDao.upsert(entity)
            broadcastManifest(groupId)

            _uploadProgress.value = UploadProgress(fileId, fileName, 0, totalChunks)

            // Pass 2: read our copy back a chunk at a time, encrypt and publish. Nothing
            // whole-file is ever resident.
            publishChunksFrom(
                source = contentFile,
                groupId = groupId,
                fileId = fileId,
                totalChunks = totalChunks,
                fileKey = fileKey,
                fileKeyVersion = fileKeyVersion
            ) { published ->
                _uploadProgress.value = UploadProgress(fileId, fileName, published, totalChunks)
            }

            broadcastManifest(groupId)

            _uploadProgress.value = null
            stagedFileId = null
            Timber.i("$TAG: Uploaded $fileName ($totalChunks chunks, $sizeBytes bytes)")
            Result.success(Unit)
        } catch (e: Exception) {
            _uploadProgress.value = null
            // A half-written copy is worse than none: it counts against the quota and would be
            // served to peers as if it were the real file.
            stagedFileId?.let { fileStore.deleteAll(it) }
            Timber.e(e, "$TAG: Upload failed")
            Result.failure(e)
        }
    }

    /** Chunks a file of [sizeBytes] splits into. Zero-length files still count as one chunk. */
    private fun chunkCountFor(sizeBytes: Long): Int =
        if (sizeBytes <= 0) 1 else ((sizeBytes + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()

    /**
     * Read [source] a chunk at a time, encrypt each and broadcast it.
     *
     * Publishing is paced. Paho's default in-flight window is 10, and `publishRaw` treats any
     * publish exception as a lost connection — it queues the message, marks the transport
     * disconnected and schedules a reconnect. A tight loop over thousands of chunks therefore
     * disconnects the app from its own broker. The old byte-boxing path was slow enough to
     * hide that; streaming is not.
     */
    private suspend fun publishChunksFrom(
        source: File,
        groupId: String,
        fileId: String,
        totalChunks: Int,
        fileKey: ByteArray,
        fileKeyVersion: Int,
        onProgress: (Int) -> Unit
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        source.inputStream().buffered().use { input ->
            for (index in 0 until totalChunks) {
                var filled = 0
                while (filled < CHUNK_SIZE) {
                    val read = input.read(buffer, filled, CHUNK_SIZE - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0 && index > 0) break

                val plain = if (filled == CHUNK_SIZE) buffer else buffer.copyOf(filled)
                val chunkMsg = FileChunkMessage(
                    fileId = fileId,
                    chunkIndex = index,
                    totalChunks = totalChunks,
                    data = Base64.getEncoder().encodeToString(encrypt(plain, fileKey)),
                    keyVersion = fileKeyVersion
                )
                transportProvider.broadcastMessage(
                    topic = MqttConfig.getFileChunkTopic(groupId, fileId, index),
                    payload = json.encodeToString(chunkMsg).toByteArray(),
                    qos = MqttConfig.QOS_AT_LEAST_ONCE,
                    retained = false
                )
                onProgress(index + 1)
                if (PUBLISH_PACING_MS > 0) delay(PUBLISH_PACING_MS)
            }
        }
    }

    // =========================================================================
    // INCOMING MQTT MESSAGES
    // =========================================================================

    fun handleIncomingManifest(payload: ByteArray) {
        scope.launch {
            try {
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                val manifest = decodeManifest(String(payload), groupId) ?: return@launch
                if (manifest.groupId != groupId) return@launch

                Timber.d("$TAG: Received manifest with ${manifest.files.size} files")

                manifest.files.forEach { sharedFile ->
                    val existing = sharedFileDao.getFileById(sharedFile.fileId)
                    if (existing == null) {
                        // New file — create a PENDING record to trigger download
                        sharedFileDao.upsert(sharedFile.toEntity())
                        reconcileFile(sharedFile.fileId)
                    } else if (sharedFile.isDeleted && !existing.isDeleted) {
                        // Remotely deleted
                        sharedFileDao.markDeleted(
                            sharedFile.fileId,
                            sharedFile.deletedByMemberId ?: "",
                            sharedFile.deletedAt ?: System.currentTimeMillis()
                        )
                        fileStore.deleteAll(sharedFile.fileId)
                    } else if (existing.downloadState != "COMPLETE") {
                        reconcileFile(sharedFile.fileId)
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
                // Use the key the sender tagged the chunk with, not our current one:
                // during the upgrade a group holds files under both key versions.
                val fileKey = keyForVersion(groupId, chunkMsg.keyVersion)
                if (fileKey == null) {
                    Timber.w(
                        "$TAG: chunk $chunkIndex of $fileId needs key version " +
                            "${chunkMsg.keyVersion}, which this device does not have"
                    )
                    return@launch
                }

                // Authenticate before storing. GCM's tag is what proves the chunk is intact
                // and really came from a holder of the group key, so a corrupt or forged chunk
                // never reaches the blob and never sets a bit.
                try {
                    decrypt(encryptedBytes, fileKey)
                } catch (e: Exception) {
                    Timber.w("$TAG: chunk $chunkIndex of $fileId failed authentication — dropping")
                    return@launch
                }

                if (entity == null) {
                    // Nothing to account against yet. Dropping is safe now in a way it was not
                    // before: the file will be re-requested once the manifest lands, whereas
                    // the old code wrote the chunk to disk where nothing ever counted it.
                    Timber.d("$TAG: chunk $chunkIndex for $fileId arrived before its manifest")
                    return@launch
                }

                // Slots hold one key version. A repair chunk under a different version cannot
                // be mixed in, because slots are stored verbatim for retransmission.
                if (entity.blobKeyVersion != 0 && entity.blobKeyVersion != chunkMsg.keyVersion) {
                    Timber.w(
                        "$TAG: chunk $chunkIndex of $fileId is key version " +
                            "${chunkMsg.keyVersion}, blob holds ${entity.blobKeyVersion} — dropping"
                    )
                    return@launch
                }

                val stride = strideBytes()
                val updated = fileStore.writeChunk(
                    fileId = fileId,
                    chunkIndex = chunkIndex,
                    chunkCount = entity.chunkCount,
                    stride = stride,
                    ciphertext = encryptedBytes,
                    currentBitmap = entity.chunkBitmap
                ) ?: return@launch

                val received = ChunkBitmap.count(updated, entity.chunkCount)
                sharedFileDao.updateChunkState(
                    fileId = fileId,
                    bitmap = updated,
                    chunksReceived = received,
                    state = "DOWNLOADING",
                    blobKeyVersion = chunkMsg.keyVersion
                )

                if (ChunkBitmap.isComplete(updated, entity.chunkCount)) {
                    completeFile(fileId)
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
                // Re-encrypted fresh from the local plaintext, so this uses the current
                // key regardless of which one the file originally arrived under. That
                // also quietly migrates legacy files to the group key as they replicate.
                val (fileKey, fileKeyVersion) = resolveEncryptionKey(groupId)

                broadcastManifest(groupId)

                // Streams each file rather than reading it whole. This path is worse than the
                // upload for memory, because it runs over *every* complete file in the library
                // back to back, so one request could hold several files' worth of boxed bytes
                // at once. It stays all-or-nothing only until Phase 2 replaces it with a
                // request for specific missing chunks.
                val complete = sharedFileDao.getAllFiles()
                    .filter { !it.isDeleted && it.downloadState == "COMPLETE" }
                var republished = 0
                complete.forEach { entity ->
                    val source = entity.localPath?.let { File(it) }?.takeIf { it.exists() }
                        ?: return@forEach
                    publishChunksFrom(
                        source = source,
                        groupId = groupId,
                        fileId = entity.fileId,
                        totalChunks = chunkCountFor(source.length()),
                        fileKey = fileKey,
                        fileKeyVersion = fileKeyVersion
                    ) { }
                    republished++
                }
                Timber.i("$TAG: Re-broadcast $republished files in response to request")
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
            fileStore.deleteAll(fileId)
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

        val (fileKey, fileKeyVersion) = resolveEncryptionKey(groupId)
        val payload = if (fileKeyVersion == FILE_KEY_VERSION_GROUP_SECRET) {
            val wrapped = EncryptedFileManifest(
                keyVersion = fileKeyVersion,
                data = Base64.getEncoder().encodeToString(
                    encrypt(json.encodeToString(padManifest(manifest)).toByteArray(), fileKey)
                )
            )
            json.encodeToString(wrapped).toByteArray()
        } else {
            // Legacy group with no shared secret. The only key available is derivable by
            // anyone on the broker, so encrypting with it would be theatre — send the
            // plaintext older peers already expect and leave the exposure recorded
            // (SECURITY_REVIEW.md F7). Recreating the family on 1.12.0+ is the fix.
            json.encodeToString(manifest).toByteArray()
        }

        transportProvider.broadcastMessage(
            topic,
            payload,
            MqttConfig.QOS_AT_LEAST_ONCE,
            true  // retained — new subscribers get it immediately
        )
    }

    /**
     * Decode a manifest that may be encrypted or legacy plaintext.
     *
     * Both shapes appear on the same topic during rollout, and a retained plaintext
     * manifest from before the upgrade can outlive it, so the encrypted form is tried
     * first and plaintext is the fallback. Returns null when neither parses, or when the
     * manifest is encrypted under a key this device does not hold.
     */
    private fun decodeManifest(raw: String, groupId: String): FileManifest? {
        runCatching { json.decodeFromString<EncryptedFileManifest>(raw) }
            .getOrNull()
            ?.let { wrapped ->
                val key = keyForVersion(groupId, wrapped.keyVersion)
                if (key == null) {
                    Timber.w(
                        "$TAG: manifest is encrypted under key version ${wrapped.keyVersion}, " +
                            "which this device does not have"
                    )
                    return null
                }
                return runCatching {
                    json.decodeFromString<FileManifest>(
                        String(decrypt(Base64.getDecoder().decode(wrapped.data), key))
                    )
                }.onFailure { Timber.e(it, "$TAG: failed to decrypt manifest") }.getOrNull()
            }

        return runCatching { json.decodeFromString<FileManifest>(raw) }
            .onFailure { Timber.e(it, "$TAG: failed to parse manifest") }
            .getOrNull()
    }

    /**
     * Pad the manifest so its encrypted size lands on a 1 KiB grid, hiding how many files
     * the family has and how long their names are.
     */
    private fun padManifest(manifest: FileManifest): FileManifest {
        val bare = json.encodeToString(manifest.copy(pad = "")).toByteArray(Charsets.UTF_8).size
        val target = ((bare + MANIFEST_PAD_BYTES - 1) / MANIFEST_PAD_BYTES) * MANIFEST_PAD_BYTES
        // '.' needs no JSON escaping, so N characters add exactly N bytes.
        return manifest.copy(pad = ".".repeat(target - bare))
    }

    /**
     * Turn a fully-received blob into the finished file.
     *
     * Streams the blob out slot by slot, decrypting and hashing in one pass, so assembly never
     * holds more than a chunk. The old path built the whole file in a `ByteArrayOutputStream`,
     * copied it again with `toByteArray()`, hashed that copy and wrote it — four full-size
     * allocations for one file.
     *
     * Writes to a scratch file and renames on success, so a partial assembly can never be
     * mistaken for a complete download.
     */
    private suspend fun completeFile(fileId: String) {
        val entity = sharedFileDao.getFileById(fileId) ?: return
        if (entity.downloadState == "COMPLETE") return
        if (!ChunkBitmap.isComplete(entity.chunkBitmap, entity.chunkCount)) return

        val groupId = groupStateManager.groupDefinition.value?.groupId ?: return
        val fileKey = keyForVersion(groupId, entity.blobKeyVersion) ?: run {
            Timber.w("$TAG: cannot complete $fileId — no key for version ${entity.blobKeyVersion}")
            return
        }

        val scratch = File(fileStore.contentFile(fileId).parentFile, "content.part")
        val hash = scratch.outputStream().buffered().use { out ->
            fileStore.decryptTo(
                fileId = fileId,
                chunkCount = entity.chunkCount,
                stride = strideBytes(),
                out = out
            ) { slot -> decrypt(slot, fileKey) }
        }

        if (hash == null) {
            runCatching { scratch.delete() }
            Timber.w("$TAG: could not assemble $fileId — will retry when more chunks arrive")
            return
        }

        if (hash != entity.contentHash) {
            // Every chunk authenticated individually, so this is not transmission corruption —
            // it means the manifest's hash disagrees with bytes that all verified under the
            // group key. Clear the accounting and let the file be fetched again rather than
            // parking it in a terminal state with its partial data left behind, which is what
            // the old FAILED path did.
            Timber.e("$TAG: Hash mismatch for $fileId — discarding and re-fetching")
            runCatching { scratch.delete() }
            fileStore.discardBlob(fileId)
            sharedFileDao.updateChunkState(fileId, null, 0, "PENDING", 0)
            return
        }

        val target = fileStore.contentFile(fileId)
        runCatching { target.delete() }
        if (!scratch.renameTo(target)) {
            runCatching { scratch.delete() }
            Timber.e("$TAG: could not move assembled $fileId into place")
            return
        }

        fileStore.discardBlob(fileId)
        sharedFileDao.updateDownloadProgress(fileId, "COMPLETE", target.absolutePath, entity.chunkCount)
        Timber.i("$TAG: Assembled file ${entity.name}")
    }

    /**
     * Reconcile a file's recorded accounting against what is actually on disk, then finish it
     * if it turns out to be complete.
     *
     * The bitmap is a cache; the blob is the truth. When the bitmap is missing or the wrong
     * width — an upgraded row, or a process killed between writing a chunk and committing the
     * row — it is rebuilt by testing which slots authenticate. An unwritten slot is zeros and
     * fails the GCM tag, so that test is exact, and it lets the database and the filesystem
     * reconcile without a transaction spanning both.
     */
    private suspend fun reconcileFile(fileId: String) {
        val entity = sharedFileDao.getFileById(fileId) ?: return
        if (entity.downloadState == "COMPLETE" || entity.isDeleted) return
        if (entity.chunkCount <= 0) return

        val expectedWidth = ChunkBitmap.sizeFor(entity.chunkCount)
        val needsRebuild = entity.chunkBitmap == null || entity.chunkBitmap.size != expectedWidth

        val bitmap = if (needsRebuild && fileStore.blobFile(fileId).exists()) {
            val groupId = groupStateManager.groupDefinition.value?.groupId ?: return
            val keyVersion = if (entity.blobKeyVersion != 0) entity.blobKeyVersion else FILE_KEY_VERSION_GROUP_SECRET
            val key = keyForVersion(groupId, keyVersion) ?: return
            fileStore.rebuildBitmap(fileId, entity.chunkCount, strideBytes()) { slot ->
                decrypt(slot, key)
            }.also {
                Timber.d("$TAG: rebuilt bitmap for $fileId from blob")
            }
        } else {
            entity.chunkBitmap
        }

        val received = ChunkBitmap.count(bitmap, entity.chunkCount)
        if (bitmap != null && !bitmap.contentEquals(entity.chunkBitmap)) {
            sharedFileDao.updateChunkState(
                fileId, bitmap, received, entity.downloadState, entity.blobKeyVersion
            )
        }

        if (ChunkBitmap.isComplete(bitmap, entity.chunkCount)) {
            completeFile(fileId)
        }
    }

    /**
     * Chunks this device is still missing for [fileId], ascending.
     *
     * This is the question the old directory-count could not answer, and the reason a single
     * lost chunk stranded a file indefinitely. Phase 2's targeted repair asks a peer for
     * exactly this list instead of triggering a re-broadcast of the entire library.
     */
    suspend fun missingChunks(fileId: String, limit: Int = MAX_MISSING_PER_REQUEST): List<Int> {
        val entity = sharedFileDao.getFileById(fileId) ?: return emptyList()
        if (entity.downloadState == "COMPLETE") return emptyList()
        return ChunkBitmap.missingIndices(entity.chunkBitmap, entity.chunkCount, limit)
    }

    /**
     * Clear storage left by the pre-blob layout and re-check anything unfinished.
     *
     * The `.tmp` directories held plaintext chunks and leaked on every hash mismatch, because
     * cleanup only ran on the success path. Nothing references them now.
     */
    fun reconcileOnStartup() {
        scope.launch {
            try {
                val swept = fileStore.sweepLegacyTempDirs()
                if (swept > 0) Timber.i("$TAG: removed $swept legacy temp chunk directories")
                sharedFileDao.getIncompleteFiles().forEach { reconcileFile(it.fileId) }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: startup reconcile failed")
            }
        }
    }

    /**
     * The key to encrypt new uploads with, and the version tag to publish alongside them.
     *
     * Prefers the group's random fileEncryptionKey. Falls back to the legacy derivation
     * for groups created before that field existed — those stay readable, but remain
     * exposed, because the legacy key is computable from the groupId in the topic name
     * plus a constant in the APK. See SECURITY_REVIEW.md F1.
     */
    private fun resolveEncryptionKey(groupId: String): Pair<ByteArray, Int> {
        val groupKey = groupStateManager.groupDefinition.value?.fileEncryptionKey
        return if (!groupKey.isNullOrBlank()) {
            groupKey.hexToBytes() to FILE_KEY_VERSION_GROUP_SECRET
        } else {
            deriveLegacyFileKey(groupId) to FILE_KEY_VERSION_LEGACY
        }
    }

    /** The key a received chunk says it was encrypted with. */
    private fun keyForVersion(groupId: String, keyVersion: Int): ByteArray? =
        when (keyVersion) {
            FILE_KEY_VERSION_GROUP_SECRET ->
                groupStateManager.groupDefinition.value?.fileEncryptionKey
                    ?.takeIf { it.isNotBlank() }
                    ?.hexToBytes()
            else -> deriveLegacyFileKey(groupId)
        }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /**
     * Legacy key: SHA-256(groupId + constant). Retained only so files shared before the
     * group key existed remain readable. Never use for new uploads — both inputs are
     * public, so this provides no confidentiality against a broker observer.
     */
    private fun deriveLegacyFileKey(groupId: String): ByteArray {
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

    /**
     * The user-facing name of a picked document.
     *
     * This used to be `uri.lastPathSegment?.substringAfterLast('/')`, which is only correct
     * for `file://` URIs. The picker returns Storage Access Framework URIs whose last segment
     * is a provider-internal document ID, so a family sharing "insurance-card.pdf" saw it
     * arrive as something like "1000000042" — no extension, which also breaks the mime icon
     * and the open intent. The display name has to be queried from the provider.
     */
    private fun queryDisplayName(uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull()

        // file:// URIs have no provider to query, so fall back to the path.
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "file"
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun filesDir(): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "familysafety_files")
            .also { it.mkdirs() }

    /** Size the provider declares for a picked document, before anything is read. */
    private fun queryDeclaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && cursor.moveToFirst() && !cursor.isNull(index)) {
                    cursor.getLong(index)
                } else null
            }
    }.getOrNull()?.takeIf { it > 0 }

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

package com.example.familysafety.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.familysafety.core.RateLimiters
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.FileAvailabilityEntity
import com.example.familysafety.storage.FileTransferEvent
import com.example.familysafety.storage.FileTransferLogEntity
import com.example.familysafety.storage.FileCopyCount
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
    private val fileAvailabilityDao: com.example.familysafety.storage.FileAvailabilityDao,
    private val fileTransferLogDao: com.example.familysafety.storage.FileTransferLogDao,
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

    /** Debounce for holdings announcements; see [announceHoldings]. */
    @Volatile
    private var lastAnnouncementAt = 0L

    companion object {
        private const val TAG = "SharedFileRepository"
        const val MAX_TOTAL_BYTES = 500L * 1024 * 1024   // 500 MB
        private const val CHUNK_SIZE = 32 * 1024           // 32 KB
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12
        private const val FILE_KEY_SALT = "familysafety-files-v1"
        /**
         * Grid the encrypted manifest is padded to, so its size hides the file count.
         *
         * Widened from 1 KiB when `isEssential` was added to each file entry: the extra
         * ~19 bytes per file pushed a three-file manifest out of the bucket a one-file
         * manifest sits in, so the two became distinguishable by length again. A unit test
         * caught it. Every byte here is spent on a single retained message, which is a cheap
         * way to buy back the property the padding exists for.
         */
        private const val MANIFEST_PAD_BYTES = 2048

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

        /** Cap on chunks served for one request; the requester re-asks for the rest. */
        private const val MAX_CHUNKS_PER_REPAIR_RESPONSE = 64

        /** Files chased in one background pass. */
        private const val MAX_FILES_PER_REPAIR_PASS = 10

        private const val REPAIR_BASE_BACKOFF_MS = 30_000L
        private const val REPAIR_MAX_BACKOFF_MS = 30 * 60_000L

        /** Minimum gap between holdings announcements. */
        private const val ANNOUNCE_DEBOUNCE_MS = 30_000L

        /** How long transfer-log entries are kept. Diagnostic, not an audit trail. */
        private const val LOG_RETENTION_MS = 14L * 24 * 60 * 60 * 1000

        /**
         * Schedule marker for a file this device is not fetching. Non-essential files sit
         * listed until someone opens them, at which point the schedule is cleared to 0.
         */
        private const val NEVER = Long.MAX_VALUE
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

            log(fileId, FileTransferEvent.UPLOAD_STARTED, "$totalChunks pieces, ${sizeBytes / 1024} KB")
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
            log(fileId, FileTransferEvent.UPLOAD_FINISHED, "sent $totalChunks pieces")
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
                        // New file — record it, then decide whether to chase it. Essential
                        // files start downloading now; the rest wait to be opened.
                        val entity = sharedFile.toEntity()
                        sharedFileDao.upsert(entity)
                        applyFetchPolicy(entity)
                        if (entity.isEssential) reconcileFile(sharedFile.fileId)
                    } else if (sharedFile.isDeleted && !existing.isDeleted) {
                        // Remotely deleted
                        sharedFileDao.markDeleted(
                            sharedFile.fileId,
                            sharedFile.deletedByMemberId ?: "",
                            sharedFile.deletedAt ?: System.currentTimeMillis()
                        )
                        fileStore.deleteAll(sharedFile.fileId)
                    } else if (existing.downloadState != "COMPLETE") {
                        if (existing.isEssential != sharedFile.isEssential) {
                            sharedFileDao.setEssential(sharedFile.fileId, sharedFile.isEssential)
                        }
                        sharedFileDao.getFileById(sharedFile.fileId)?.let { applyFetchPolicy(it) }
                        if (sharedFile.isEssential) reconcileFile(sharedFile.fileId)
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

    // =========================================================================
    // TRANSFER LOG
    // =========================================================================

    fun observeTransferLog(limit: Int = 200) = fileTransferLogDao.observeRecent(limit)

    fun observeTransferLogFor(fileId: String) = fileTransferLogDao.observeForFile(fileId)

    /**
     * Record what just happened to a transfer.
     *
     * Never throws into the caller: a diagnostic that can break the thing it is diagnosing is
     * worse than no diagnostic.
     */
    private suspend fun log(fileId: String, event: String, detail: String? = null) {
        runCatching {
            fileTransferLogDao.insert(
                FileTransferLogEntity(
                    fileId = fileId,
                    at = System.currentTimeMillis(),
                    event = event,
                    detail = detail
                )
            )
        }
    }

    /** Age-based trim, run from the background pass. */
    private suspend fun trimTransferLog() {
        runCatching {
            fileTransferLogDao.deleteOlderThan(System.currentTimeMillis() - LOG_RETENTION_MS)
        }
    }

    // =========================================================================
    // AVAILABILITY
    // =========================================================================

    /** How many peers hold a complete, hash-matching copy of each file. */
    fun observeCopyCounts(): Flow<List<FileCopyCount>> = fileAvailabilityDao.observeCompleteCounts()

    /**
     * How many peers have ever reported their holdings.
     *
     * Zero means no peer is running a build that announces, so a copy count of zero is
     * ignorance rather than absence — and telling someone their insurance card exists only on
     * this phone when it may well be on three others is worse than saying nothing.
     */
    fun observeAnnouncingPeerCount(): Flow<Int> = fileAvailabilityDao.observeAnnouncingPeerCount()

    /**
     * Tell every peer what this device holds.
     *
     * Debounced, because completing a batch of files would otherwise fire one announcement per
     * file. Sent per-recipient and encrypted, not retained — a retained per-recipient message
     * is the pollution problem that left stale join approvals sitting on the broker.
     */
    fun announceHoldings(force: Boolean = false) {
        scope.launch {
            try {
                val now = System.currentTimeMillis()
                if (!force && now - lastAnnouncementAt < ANNOUNCE_DEBOUNCE_MS) return@launch
                lastAnnouncementAt = now

                val groupDef = groupStateManager.groupDefinition.value ?: return@launch
                val myMemberId = groupStateManager.localMember.value?.memberId ?: return@launch

                val holdings = sharedFileDao.getAllFiles()
                    .filter { !it.isDeleted && it.chunkCount > 0 }
                    .map { entity ->
                        FileHolding(
                            fileId = entity.fileId,
                            contentHash = entity.contentHash,
                            state = if (entity.downloadState == "COMPLETE") HOLDING_COMPLETE else HOLDING_PARTIAL,
                            chunksHeld = entity.chunksReceived,
                            chunkCount = entity.chunkCount
                        )
                    }
                if (holdings.isEmpty()) return@launch

                val payload = sealFileMessage(
                    groupDef.groupId,
                    json.encodeToString(
                        FileHoldingsAnnouncement(announcerId = myMemberId, holdings = holdings)
                    )
                ) ?: return@launch

                groupDef.members.filter { it.memberId != myMemberId }.forEach { member ->
                    transportProvider.sendMessage(
                        member.memberId,
                        MqttConfig.getFileAvailabilityTopic(member.memberId),
                        payload,
                        MqttConfig.QOS_AT_LEAST_ONCE,
                        false
                    )
                }
                Timber.d("$TAG: announced ${holdings.size} holdings")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: failed to announce holdings")
            }
        }
    }

    fun handleHoldingsAnnouncement(payload: ByteArray) {
        scope.launch {
            try {
                val groupDef = groupStateManager.groupDefinition.value ?: return@launch
                val plain = openFileMessage(groupDef.groupId, String(payload)) ?: return@launch
                val announcement = json.decodeFromString<FileHoldingsAnnouncement>(plain)

                // Only members count. A departed device's claim must not keep a document
                // looking safer than it is.
                if (groupDef.findMemberById(announcement.announcerId) == null) {
                    Timber.w("$TAG: holdings from non-member ${announcement.announcerId.take(8)}")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val rows = announcement.holdings.map { holding ->
                    FileAvailabilityEntity(
                        fileId = holding.fileId,
                        memberId = announcement.announcerId,
                        contentHash = holding.contentHash,
                        state = holding.state,
                        chunksHeld = holding.chunksHeld,
                        chunkCount = holding.chunkCount,
                        updatedAt = now
                    )
                }
                fileAvailabilityDao.upsertAll(rows)
                Timber.d(
                    "$TAG: ${announcement.announcerId.take(8)} holds ${rows.count { it.state == HOLDING_COMPLETE }} complete"
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: failed to handle holdings announcement")
            }
        }
    }

    /** Drop availability rows for members who have left. */
    suspend fun pruneAvailability(currentMemberIds: List<String>) {
        runCatching { fileAvailabilityDao.deleteForMembersNotIn(currentMemberIds) }
    }

    // =========================================================================
    // TARGETED REPAIR
    // =========================================================================

    /**
     * Ask one peer for the chunks this device is missing of [fileId].
     *
     * One peer per round, not all of them. Asking everyone means every peer answers, so a
     * single gap costs the family N copies of the same chunks — and with the old
     * whole-library re-broadcast it cost N copies of *everything*. Peers are rotated on each
     * attempt so a peer that cannot help does not get asked forever.
     *
     * @return true if a request went out.
     */
    suspend fun requestMissingChunks(fileId: String): Boolean {
        val entity = sharedFileDao.getFileById(fileId) ?: return false
        if (entity.isDeleted || entity.downloadState == "COMPLETE") return false

        val groupDef = groupStateManager.groupDefinition.value ?: return false
        val myMemberId = groupStateManager.localMember.value?.memberId ?: return false
        val missing = ChunkBitmap.missingIndices(
            entity.chunkBitmap, entity.chunkCount, MAX_MISSING_PER_REQUEST
        )
        if (missing.isEmpty()) {
            // Nothing missing but not COMPLETE — the blob is all there and assembly simply has
            // not run yet, most likely because the process died between the two.
            completeFile(fileId)
            return false
        }

        val allPeers = groupDef.members.map { it.memberId }.filter { it != myMemberId }
        if (allPeers.isEmpty()) return false

        // Prefer peers that have announced a complete copy — asking a device that is itself
        // missing the chunk wastes a whole backoff cycle. Falls back to everyone when no
        // announcement has arrived yet, which is also the case for peers on older builds.
        val holders = runCatching { fileAvailabilityDao.getCompleteHolders(fileId) }
            .getOrDefault(emptyList())
            .filter { it in allPeers }
        val peers = holders.ifEmpty { allPeers }

        // Rotate: start after whoever we asked last.
        val startIndex = entity.lastRepairPeerId
            ?.let { peers.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?.let { (it + 1) % peers.size }
            ?: 0
        val peer = peers[startIndex]

        val request = ChunkRepairRequest(
            requestId = UUID.randomUUID().toString(),
            requesterId = myMemberId,
            fileId = fileId,
            contentHash = entity.contentHash,
            totalChunks = entity.chunkCount,
            missing = missing
        )

        val groupId = groupDef.groupId
        val payload = sealFileMessage(groupId, json.encodeToString(request)) ?: return false
        val sent = transportProvider.sendMessage(
            peer,
            MqttConfig.getFileRepairTopic(peer),
            payload,
            MqttConfig.QOS_AT_LEAST_ONCE,
            false
        )

        val now = System.currentTimeMillis()
        sharedFileDao.recordRepairAttempt(
            fileId = fileId,
            attemptAt = now,
            nextAttemptAt = now + backoffFor(entity.attemptCount + 1),
            lastError = if (sent) null else "could not reach ${peer.take(8)}",
            peerId = peer
        )

        log(
            fileId,
            FileTransferEvent.CHUNKS_REQUESTED,
            "asked ${peer.take(8)} for ${missing.size} of ${entity.chunkCount} pieces"
        )
        if (sent) {
            Timber.i(
                "$TAG: asked ${peer.take(8)} for ${missing.size} missing chunks of " +
                    "${entity.name} (${entity.chunksReceived}/${entity.chunkCount} held)"
            )
        }
        return sent
    }

    /**
     * Send a peer exactly the chunks it asked for.
     *
     * Replies go to the ordinary group chunk topic rather than back to the requester alone.
     * That makes a repair reply indistinguishable from an original upload, so it needs no new
     * receive path, and any other device with the same gap picks it up for free.
     */
    fun handleChunkRepairRequest(payload: ByteArray) {
        scope.launch {
            try {
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                val plain = openFileMessage(groupId, String(payload)) ?: return@launch
                val request = json.decodeFromString<ChunkRepairRequest>(plain)

                // One member must not be able to make every device re-upload on demand.
                if (!RateLimiters.fileRepair.allowRequest(request.requesterId)) {
                    Timber.w("$TAG: repair request from ${request.requesterId.take(8)} rate limited")
                    return@launch
                }

                val entity = sharedFileDao.getFileById(request.fileId) ?: return@launch
                if (entity.isDeleted) return@launch
                if (entity.contentHash != request.contentHash) {
                    // Different bytes under the same id. Sending would corrupt their blob
                    // rather than repair it; the manifest will reconcile the disagreement.
                    Timber.w("$TAG: repair request for ${request.fileId} has a different contentHash")
                    return@launch
                }
                if (entity.downloadState != "COMPLETE") {
                    Timber.d("$TAG: asked for chunks of ${request.fileId} we do not hold in full")
                    return@launch
                }
                val source = entity.localPath?.let { File(it) }?.takeIf { it.exists() } ?: return@launch

                val (fileKey, fileKeyVersion) = resolveEncryptionKey(groupId)
                val wanted = request.missing.filter { it in 0 until entity.chunkCount }
                    .distinct()
                    .sorted()
                    .take(MAX_CHUNKS_PER_REPAIR_RESPONSE)
                if (wanted.isEmpty()) return@launch

                serveChunks(source, groupId, entity.fileId, entity.chunkCount, wanted, fileKey, fileKeyVersion)
                log(
                    entity.fileId,
                    FileTransferEvent.CHUNKS_SERVED,
                    "sent ${wanted.size} pieces to ${request.requesterId.take(8)}"
                )
                Timber.i(
                    "$TAG: served ${wanted.size} chunks of ${entity.name} to " +
                        request.requesterId.take(8)
                )
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to handle chunk repair request")
            }
        }
    }

    /** Read just the requested chunk indices from [source] and publish them. */
    private suspend fun serveChunks(
        source: File,
        groupId: String,
        fileId: String,
        totalChunks: Int,
        indices: List<Int>,
        fileKey: ByteArray,
        fileKeyVersion: Int
    ) {
        val buffer = ByteArray(CHUNK_SIZE)
        java.io.RandomAccessFile(source, "r").use { raf ->
            for (index in indices) {
                val offset = index.toLong() * CHUNK_SIZE
                if (offset >= raf.length()) continue
                raf.seek(offset)
                var filled = 0
                while (filled < CHUNK_SIZE) {
                    val read = raf.read(buffer, filled, CHUNK_SIZE - filled)
                    if (read <= 0) break
                    filled += read
                }
                if (filled == 0) continue
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
                if (PUBLISH_PACING_MS > 0) delay(PUBLISH_PACING_MS)
            }
        }
    }

    /**
     * Chase every file that is due, oldest schedule first. Called by the background worker.
     *
     * @return true if anything remains incomplete, so the caller knows to run again.
     */
    suspend fun runRepairPass(): Boolean {
        return try {
            val now = System.currentTimeMillis()
            val due = sharedFileDao.getDueForRepair(now, MAX_FILES_PER_REPAIR_PASS)
            due.forEach { entity ->
                reconcileFile(entity.fileId)
                requestMissingChunks(entity.fileId)
            }

            // Re-state what this device holds on every pass. An announcement is a state
            // summary, not an event, so a lost one costs nothing — the next tick corrects it.
            // That is why this uses the announce pattern rather than per-file receipts.
            announceHoldings()
            trimTransferLog()

            // Only essential files keep the worker alive. A non-essential file nobody has
            // opened is not "incomplete work", it is simply not wanted here yet, and
            // treating it as pending would keep rescheduling the worker forever.
            sharedFileDao.getDueForRepair(now + REPAIR_MAX_BACKOFF_MS, Int.MAX_VALUE).isNotEmpty()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: repair pass failed")
            true
        }
    }

    /** User-initiated retry from the UI: clear the backoff and chase it now. */
    suspend fun retryNow(fileId: String) {
        sharedFileDao.clearRepairSchedule(fileId)
        reconcileFile(fileId)
        requestMissingChunks(fileId)
    }

    /**
     * Exponential backoff, capped. Starts at 30 s so a chunk lost to a momentary drop is
     * recovered quickly, and tops out at 30 min so a file whose peers are all offline stops
     * costing anything.
     */
    private fun backoffFor(attempt: Int): Long {
        val exponent = (attempt - 1).coerceIn(0, 6)
        return (REPAIR_BASE_BACKOFF_MS shl exponent).coerceAtMost(REPAIR_MAX_BACKOFF_MS)
    }

    /** Encrypt a file-subsystem control message under the group key. */
    private fun sealFileMessage(groupId: String, plaintext: String): ByteArray? {
        val (key, keyVersion) = resolveEncryptionKey(groupId)
        return runCatching {
            json.encodeToString(
                EncryptedFileMessage(
                    keyVersion = keyVersion,
                    data = Base64.getEncoder().encodeToString(encrypt(plaintext.toByteArray(), key))
                )
            ).toByteArray()
        }.getOrNull()
    }

    private fun openFileMessage(groupId: String, raw: String): String? {
        val wrapped = runCatching { json.decodeFromString<EncryptedFileMessage>(raw) }.getOrNull()
            ?: return null
        val key = keyForVersion(groupId, wrapped.keyVersion) ?: return null
        return runCatching {
            String(decrypt(Base64.getDecoder().decode(wrapped.data), key))
        }.getOrNull()
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
            log(fileId, FileTransferEvent.DELETED, "deleted on this phone")
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
            log(fileId, FileTransferEvent.HASH_MISMATCH, "contents did not match, fetching again")
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
        sharedFileDao.clearRepairSchedule(fileId)
        log(fileId, FileTransferEvent.DOWNLOAD_COMPLETE, "verified ${entity.chunkCount} pieces")
        Timber.i("$TAG: Assembled file ${entity.name}")

        // Tell the family this device now holds it, so their status boards can say so and
        // their repair requests can be aimed here.
        announceHoldings()
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
        isDeleted = isDeleted, deletedByMemberId = deletedByMemberId, deletedAt = deletedAt,
        isEssential = isEssential
    )

    /**
     * Mark a file so the background pass either chases it or leaves it alone.
     *
     * Essential files are pursued until every device holds one. The rest are parked with a
     * schedule that never comes due, so they appear in the library and download when opened —
     * which is what keeps a large file from being pushed onto every phone in the family.
     */
    private suspend fun applyFetchPolicy(entity: SharedFileEntity) {
        if (entity.downloadState == "COMPLETE" || entity.isDeleted) return
        val target = if (entity.isEssential) 0L else NEVER
        if (entity.nextAttemptAt == target) return
        sharedFileDao.recordRepairAttempt(
            fileId = entity.fileId,
            attemptAt = entity.lastAttemptAt ?: 0L,
            nextAttemptAt = target,
            lastError = entity.lastError,
            peerId = entity.lastRepairPeerId
        )
    }

    /** Called when the user opens a file that has not downloaded: fetch it now. */
    suspend fun requestDownload(fileId: String) {
        sharedFileDao.clearRepairSchedule(fileId)
        reconcileFile(fileId)
        requestMissingChunks(fileId)
    }

    /** Change whether a file replicates to every device without being asked. */
    suspend fun setEssential(fileId: String, essential: Boolean): Result<Unit> = runCatching {
        val groupId = groupStateManager.groupDefinition.value?.groupId
            ?: throw IllegalStateException("No group")
        sharedFileDao.setEssential(fileId, essential)
        log(
            fileId,
            FileTransferEvent.ESSENTIAL_CHANGED,
            if (essential) "pinned to every device" else "no longer pinned"
        )
        sharedFileDao.getFileById(fileId)?.let { applyFetchPolicy(it) }
        broadcastManifest(groupId)
        if (essential) requestMissingChunks(fileId)
        Unit
    }

    private fun SharedFile.toEntity() = SharedFileEntity(
        fileId = fileId, name = name, mimeType = mimeType, sizeBytes = sizeBytes,
        contentHash = contentHash, uploaderMemberId = uploaderMemberId,
        uploadedAt = uploadedAt, chunkCount = chunkCount,
        isDeleted = isDeleted, deletedByMemberId = deletedByMemberId, deletedAt = deletedAt,
        localPath = null, chunksReceived = 0, downloadState = if (isDeleted) "COMPLETE" else "PENDING",
        isEssential = isEssential
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

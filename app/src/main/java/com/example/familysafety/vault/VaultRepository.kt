package com.example.familysafety.vault

import android.content.Context
import android.net.Uri
import com.example.familysafety.files.ChunkBitmap
import com.example.familysafety.files.ChunkedFileStore
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.storage.VaultBlobDao
import com.example.familysafety.storage.VaultBlobEntity
import com.example.familysafety.transport.MqttConfig
import com.example.familysafety.transport.TransportProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The family vault: documents that are shared with everyone who knows a code, and invisible
 * to everyone who does not — including to the people holding the bytes.
 *
 * ## What is where
 *
 * - The **container** ([VaultContainer]) holds metadata only, in fixed-size slots. It always
 *   exists, is always the same size, and syncs to every device as one retained, signed
 *   message. See [VaultSlots] for why a wrong code cannot be told from a right one.
 * - A document's **bytes** live in a blob store of their own, encrypted under a content key
 *   that exists nowhere but inside a slot. Every device stores them, and a device without the
 *   code holds an opaque blob under a random id, indistinguishable from the unreferenced
 *   blobs a partial download already leaves behind.
 *
 * The split is what makes the vault work at family scale: the container is small enough to
 * broadcast whole on every change, while the documents move over a chunked path that never
 * has to be enumerated.
 *
 * ## What this class refuses to do
 *
 * - It never reports a wrong code. [open] returns an empty vault, and the caller must present
 *   that as an empty vault. An error would prove a correct code exists.
 * - It never persists a key, a code, or a hash of either.
 * - It never writes a vault document into `shared_files`, the manifest, the status board or
 *   the transfer log.
 */
@Singleton
class VaultRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultBlobDao: VaultBlobDao,
    private val groupStateManager: GroupStateManager,
    private val transportProvider: TransportProvider,
    private val cryptoProvider: LazysodiumCryptoProvider,
    private val keyDerivation: VaultKeyDerivation
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val container = VaultContainer { File(vaultDir(), CONTAINER_NAME) }

    /**
     * Blob storage for vault documents, reusing the file subsystem's store against a
     * different root. That store already streams, never materialises plaintext, keeps chunk
     * ciphertexts verbatim for retransmission and rebuilds its own accounting after a crash —
     * all of which the vault needs and none of which is specific to shared files.
     */
    private val blobStore = ChunkedFileStore { File(vaultDir(), BLOBS_DIR) }

    /** Highest container version seen, so an older one cannot roll the family back. */
    @Volatile
    private var containerVersion: Long = 0

    companion object {
        private const val TAG = "VaultRepository"
        private const val CONTAINER_NAME = "container.bin"
        private const val BLOBS_DIR = "blobs"
        private const val VAULT_DIR = "familysafety_vault"
        private const val VIEW_CACHE_DIR = "vault_views"

        private const val CHUNK_SIZE = 32 * 1024
        private const val GCM_TAG_BITS = 128
        private const val NONCE_BYTES = 12

        /** Same reasoning as the file subsystem: Paho's in-flight window is small. */
        private const val PUBLISH_PACING_MS = 15L

        /** Cap on a single vault document. Emergency papers, not media. */
        const val MAX_ITEM_BYTES = 25L * 1024 * 1024

        private const val MAX_CHUNKS_PER_REPAIR_RESPONSE = 64
    }

    // =========================================================================
    // OPENING
    // =========================================================================

    /**
     * Derive the key for [code] and return everything in the container that opens under it.
     *
     * An empty list is the ordinary result for a code nobody has used, and is returned in
     * exactly the same way, after exactly the same work, as a code that opens a full vault.
     * Callers must not distinguish the two, and there is nothing here that would let them.
     */
    suspend fun open(code: String): VaultSession? = withContext(Dispatchers.Default) {
        val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@withContext null
        val key = runCatching { keyDerivation.deriveKey(code, groupId) }
            .onFailure { Timber.e(it, "$TAG: key derivation failed") }
            .getOrNull() ?: return@withContext null

        val bytes = container.read()
        val items = VaultSlots.openAll(bytes, key)
        VaultSession(key = key, items = items.filter { it.isActive }, reclaimed = items.filterNot { it.isActive })
    }

    // =========================================================================
    // WRITING
    // =========================================================================

    /**
     * Add a document to the vault [session] opens.
     *
     * The bytes are streamed into the vault blob store under a fresh random id, encrypted with
     * a content key generated here and stored only inside the slot. Nothing about the document
     * — not its name, not its size, not its existence — is written anywhere outside the
     * container.
     */
    suspend fun add(session: VaultSession, uri: Uri, displayName: String, mimeType: String): Result<VaultSession> {
        val groupId = groupStateManager.groupDefinition.value?.groupId
            ?: return Result.failure(IllegalStateException("No group"))
        if (session.items.size >= VaultSlots.MAX_ITEMS) {
            return Result.failure(IllegalStateException("This vault is full"))
        }

        val fileId = UUID.randomUUID().toString()
        val contentKey = ByteArray(32).also { SecureRandom().nextBytes(it) }

        return try {
            val input = context.contentResolver.openInputStream(uri)
                ?: return Result.failure(IllegalArgumentException("Cannot read that file"))
            val ingest = input.use {
                blobStore.encryptInto(it, fileId, CHUNK_SIZE, strideBytes()) { plain ->
                    encrypt(plain, contentKey)
                }
            }

            // Kept separate from "cannot read": reading worked and storing did not, which
            // means a full disk rather than a document the picker cannot hand over.
            if (ingest == null) {
                blobStore.deleteAll(fileId)
                return Result.failure(IllegalStateException("Could not store that file"))
            }
            if (ingest.sizeBytes > MAX_ITEM_BYTES) {
                blobStore.deleteAll(fileId)
                return Result.failure(
                    IllegalStateException("Too large for the vault (limit ${MAX_ITEM_BYTES / 1024 / 1024} MB)")
                )
            }

            vaultBlobDao.upsert(
                VaultBlobEntity(
                    fileId = fileId,
                    chunkCount = ingest.chunkCount,
                    chunksReceived = ingest.chunkCount,
                    chunkBitmap = ChunkBitmap.full(ingest.chunkCount)
                )
            )

            val payload = VaultSlots.fitName(
                VaultSlotPayload(
                    fileId = fileId,
                    name = displayName,
                    mimeType = mimeType,
                    sizeBytes = ingest.sizeBytes,
                    contentHash = ingest.contentHash,
                    contentKeyHex = contentKey.toHex(),
                    chunkCount = ingest.chunkCount,
                    addedAt = System.currentTimeMillis(),
                    addedBy = groupStateManager.localMember.value?.memberId.orEmpty()
                )
            )

            val updated = writeItem(session.key, payload, existingSlots = emptyList())
                ?: run {
                    blobStore.deleteAll(fileId)
                    vaultBlobDao.delete(fileId)
                    return Result.failure(IllegalStateException("The vault has no room left"))
                }

            publishContainer(groupId, updated)
            publishBlob(groupId, fileId, ingest.chunkCount)
            Result.success(reload(session.key, updated))
        } catch (e: Exception) {
            blobStore.deleteAll(fileId)
            runCatching { vaultBlobDao.delete(fileId) }
            Timber.e(e, "$TAG: could not add to the vault")
            Result.failure(e)
        }
    }

    /**
     * Remove an item from view, recoverably.
     *
     * The slot is rewritten with its state flipped rather than overwritten with random bytes,
     * and the document's bytes are left in place. Anyone with the code can delete anything —
     * that is inherent to a shared key and not something the app could enforce otherwise — so
     * the deletion is made undoable instead, which is the protection that *can* be provided
     * against an accident or a deletion made under pressure.
     */
    suspend fun delete(session: VaultSession, item: VaultItem): Result<VaultSession> =
        setState(session, item, VAULT_STATE_RECLAIMED)

    /** Put a reclaimed item back. */
    suspend fun restore(session: VaultSession, item: VaultItem): Result<VaultSession> =
        setState(session, item, VAULT_STATE_ACTIVE)

    private suspend fun setState(
        session: VaultSession,
        item: VaultItem,
        state: String
    ): Result<VaultSession> {
        val groupId = groupStateManager.groupDefinition.value?.groupId
            ?: return Result.failure(IllegalStateException("No group"))
        val payload = VaultSlots.fitName(
            VaultSlotPayload(
                fileId = item.fileId,
                name = item.name,
                mimeType = item.mimeType,
                sizeBytes = item.sizeBytes,
                contentHash = item.contentHash,
                contentKeyHex = item.contentKeyHex,
                chunkCount = item.chunkCount,
                addedAt = item.addedAt,
                addedBy = item.addedBy,
                state = state,
                revision = System.currentTimeMillis()
            )
        )
        val updated = writeItem(session.key, payload, existingSlots = item.slots)
            ?: return Result.failure(IllegalStateException("Could not update the vault"))
        publishContainer(groupId, updated)
        return Result.success(reload(session.key, updated))
    }

    /**
     * Seal [payload] into its slots and store the container.
     *
     * Written to [VaultSlots.REPLICAS] slots, each with an independent nonce, so the two
     * copies are not recognisable as copies of each other. Redundancy matters here because
     * allocation is blind: another vault can overwrite a slot without either side being able
     * to detect the collision.
     */
    private suspend fun writeItem(
        key: ByteArray,
        payload: VaultSlotPayload,
        existingSlots: List<Int>
    ): ByteArray? = container.update { current ->
        val slots = VaultSlots.allocate(current, key, payload.fileId, existingSlots)
        if (slots.isEmpty()) return@update null
        var next = current
        val random = SecureRandom()
        slots.forEach { index ->
            next = VaultSlots.withSlot(next, index, VaultSlots.seal(payload, key, random))
        }
        next
    }

    private fun reload(key: ByteArray, container: ByteArray): VaultSession {
        val all = VaultSlots.openAll(container, key)
        return VaultSession(key, all.filter { it.isActive }, all.filterNot { it.isActive })
    }

    // =========================================================================
    // READING A DOCUMENT
    // =========================================================================

    /**
     * Decrypt an item into the cache so another app can open it.
     *
     * The cache copy is the only point at which a vault document exists in the clear. It is
     * app-private, wiped on launch, and [forgetViewCache] clears it when the vault closes.
     *
     * Returns null when this device does not yet hold every chunk, which is normal — the
     * request for the rest goes out at the same time.
     */
    suspend fun materialize(item: VaultItem): File? {
        val row = vaultBlobDao.get(item.fileId)
        if (row == null || !ChunkBitmap.isComplete(row.chunkBitmap, item.chunkCount)) {
            requestMissing(item)
            return null
        }

        val target = File(File(viewCacheDir(), item.fileId), safeName(item.name))
        if (target.exists() && target.length() > 0) return target
        target.parentFile?.mkdirs()

        val contentKey = item.contentKeyHex.hexToBytes()
        val hash = runCatching {
            target.outputStream().buffered().use { out ->
                blobStore.decryptTo(item.fileId, item.chunkCount, strideBytes(), out) { slot ->
                    decrypt(slot, contentKey)
                }
            }
        }.getOrNull()

        if (hash != item.contentHash) {
            runCatching { target.delete() }
            Timber.e("$TAG: a vault document did not verify")
            requestMissing(item)
            return null
        }
        return target
    }

    /** Wipe everything decrypted for viewing. Called when a vault session ends. */
    fun forgetViewCache() {
        runCatching { File(context.cacheDir, VIEW_CACHE_DIR).deleteRecursively() }
    }

    // =========================================================================
    // SYNC — CONTAINER
    // =========================================================================

    private suspend fun publishContainer(groupId: String, bytes: ByteArray) {
        containerVersion = maxOf(containerVersion + 1, System.currentTimeMillis())
        val data = Base64.getEncoder().encodeToString(bytes)
        val message = VaultContainerMessage(
            version = containerVersion,
            data = data,
            signerMemberId = groupStateManager.localMember.value?.memberId.orEmpty(),
            signature = runCatching {
                cryptoProvider.signMessage(vaultSigningPayload(containerVersion, data)).toHex()
            }.onFailure { Timber.e(it, "$TAG: could not sign the container") }.getOrDefault("")
        )
        transportProvider.broadcastMessage(
            topic = MqttConfig.getVaultContainerTopic(groupId),
            payload = json.encodeToString(message).toByteArray(),
            qos = MqttConfig.QOS_AT_LEAST_ONCE,
            retained = true
        )
    }

    /**
     * Accept a container published by a family member.
     *
     * Verified before it is stored, on the same rules as the file manifest: signed by someone
     * in *our* roster, never by a key carried in the message. An unsigned container is
     * refused — accepting one would let anyone who can reach the broker replace a family's
     * vault with random bytes, which is indistinguishable from erasing it.
     *
     * Older versions are ignored, so a replayed message cannot roll the family back to a
     * container from before a document was added.
     */
    fun handleIncomingContainer(payload: ByteArray) {
        scope.launch {
            try {
                val message = json.decodeFromString<VaultContainerMessage>(String(payload))
                if (message.version <= containerVersion) return@launch
                if (!verify(message)) return@launch

                val bytes = runCatching { Base64.getDecoder().decode(message.data) }.getOrNull()
                if (bytes == null || bytes.size != VaultSlots.CONTAINER_BYTES) {
                    Timber.w("$TAG: container is the wrong size — discarding")
                    return@launch
                }
                if (container.write(bytes)) {
                    containerVersion = message.version
                    Timber.d("$TAG: took container version ${message.version}")
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not handle an incoming container")
            }
        }
    }

    private fun verify(message: VaultContainerMessage): Boolean {
        if (message.signature.isBlank() || message.signerMemberId.isBlank()) {
            Timber.w("$TAG: refusing an unsigned container")
            return false
        }
        val signer = groupStateManager.groupDefinition.value?.members
            ?.firstOrNull { it.memberId == message.signerMemberId } ?: run {
            Timber.w("$TAG: container signed by someone outside the family")
            return false
        }
        return runCatching {
            cryptoProvider.verifySignature(
                message = vaultSigningPayload(message.version, message.data),
                signature = message.signature.hexToBytes(),
                publicKey = signer.ed25519PublicKey.hexToBytes()
            )
        }.getOrDefault(false).also {
            if (!it) Timber.e("$TAG: container signature does not verify")
        }
    }

    // =========================================================================
    // SYNC — DOCUMENT BYTES
    // =========================================================================

    private suspend fun publishBlob(
        groupId: String,
        fileId: String,
        totalChunks: Int,
        indices: Iterable<Int> = 0 until totalChunks
    ) {
        for (index in indices) {
            val ciphertext = blobStore.readChunk(fileId, index, strideBytes()) ?: continue
            val message = VaultChunkMessage(
                fileId = fileId,
                chunkIndex = index,
                totalChunks = totalChunks,
                data = Base64.getEncoder().encodeToString(ciphertext)
            )
            transportProvider.broadcastMessage(
                topic = MqttConfig.getVaultChunkTopic(groupId, fileId, index),
                payload = json.encodeToString(message).toByteArray(),
                qos = MqttConfig.QOS_AT_LEAST_ONCE,
                retained = false
            )
            if (PUBLISH_PACING_MS > 0) delay(PUBLISH_PACING_MS)
        }
    }

    /**
     * Store a chunk of a vault document.
     *
     * Deliberately does no authentication and asks no questions: this device may well not hold
     * a code that can read what is arriving, and refusing to store what it cannot verify would
     * mean vault documents only ever reached the phone that added them. The content key
     * authenticates every chunk at the moment someone opens the vault, and a corrupt chunk
     * fails there rather than here.
     */
    fun handleIncomingChunk(fileId: String, chunkIndex: Int, payload: ByteArray) {
        scope.launch {
            try {
                val message = json.decodeFromString<VaultChunkMessage>(String(payload))
                if (message.fileId != fileId || message.chunkIndex != chunkIndex) return@launch
                if (message.totalChunks <= 0) return@launch

                val ciphertext = Base64.getDecoder().decode(message.data)
                val existing = vaultBlobDao.get(fileId)
                if (existing != null && ChunkBitmap.isComplete(existing.chunkBitmap, existing.chunkCount)) {
                    return@launch
                }

                val bitmap = blobStore.writeChunk(
                    fileId = fileId,
                    chunkIndex = chunkIndex,
                    chunkCount = message.totalChunks,
                    stride = strideBytes(),
                    ciphertext = ciphertext,
                    currentBitmap = existing?.chunkBitmap
                ) ?: return@launch

                val held = ChunkBitmap.count(bitmap, message.totalChunks)
                if (existing == null) {
                    vaultBlobDao.upsert(
                        VaultBlobEntity(
                            fileId = fileId,
                            chunkCount = message.totalChunks,
                            chunksReceived = held,
                            chunkBitmap = bitmap
                        )
                    )
                } else {
                    vaultBlobDao.updateChunkState(fileId, bitmap, held, System.currentTimeMillis())
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not store a vault chunk")
            }
        }
    }

    /** Ask peers for the chunks of [item] this device is missing. */
    private suspend fun requestMissing(item: VaultItem) {
        val groupDef = groupStateManager.groupDefinition.value ?: return
        val me = groupStateManager.localMember.value?.memberId ?: return
        val row = vaultBlobDao.get(item.fileId)
        val missing = ChunkBitmap.missingIndices(row?.chunkBitmap, item.chunkCount, 256)
        if (missing.isEmpty()) return

        val request = json.encodeToString(
            VaultChunkRequest(requesterId = me, fileId = item.fileId, missing = missing)
        ).toByteArray()

        groupDef.members.filter { it.memberId != me }.forEach { member ->
            transportProvider.sendMessage(
                member.memberId,
                MqttConfig.getVaultRepairTopic(member.memberId),
                request,
                MqttConfig.QOS_AT_LEAST_ONCE,
                false
            )
        }
    }

    /**
     * Serve chunks a peer asked for.
     *
     * Answered from the blob store alone, with no reference to any vault, because this device
     * may not hold a code that can read what it is serving. Holding the bytes is the whole
     * qualification — which is also why this reveals nothing: a device that answers is not
     * saying it can read the document, only that it stores it, and every device stores every
     * vault document.
     */
    fun handleChunkRequest(payload: ByteArray) {
        scope.launch {
            try {
                val groupId = groupStateManager.groupDefinition.value?.groupId ?: return@launch
                val request = json.decodeFromString<VaultChunkRequest>(String(payload))
                val row = vaultBlobDao.get(request.fileId) ?: return@launch

                val wanted = request.missing
                    .filter { it in 0 until row.chunkCount }
                    .distinct()
                    .sorted()
                    .filter { ChunkBitmap.isSet(row.chunkBitmap, it) }
                    .take(MAX_CHUNKS_PER_REPAIR_RESPONSE)
                if (wanted.isEmpty()) return@launch

                publishBlob(groupId, request.fileId, row.chunkCount, wanted)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: could not serve a vault chunk request")
            }
        }
    }

    /**
     * Make sure the container exists, and clear anything left decrypted.
     *
     * Called at startup for every device, whether or not anyone has ever set a code. Creating
     * the container lazily on first use would make its existence the signal that a vault
     * exists, which is the one thing the design cannot allow.
     */
    fun initializeOnStartup() {
        scope.launch {
            runCatching {
                forgetViewCache()
                container.read()
            }.onFailure { Timber.e(it, "$TAG: could not initialise the vault container") }
        }
    }

    // =========================================================================
    // HELPERS
    // =========================================================================

    private fun strideBytes(): Int = blobStore.strideFor(CHUNK_SIZE, NONCE_BYTES, GCM_TAG_BITS)

    private fun vaultDir(): File =
        File(context.filesDir, VAULT_DIR).also { it.mkdirs() }

    private fun viewCacheDir(): File =
        File(context.cacheDir, VIEW_CACHE_DIR).also { it.mkdirs() }

    private fun encrypt(plaintext: ByteArray, key: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return nonce + cipher.doFinal(plaintext)
    }

    private fun decrypt(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, data.copyOfRange(0, NONCE_BYTES))
        )
        return cipher.doFinal(data.copyOfRange(NONCE_BYTES, data.size))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Same reasoning as the file subsystem: a name from a slot must not become a path. */
    private fun safeName(name: String): String {
        val cleaned = name
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .trim()
            .trimStart('.')
        return cleaned.takeIf { it.isNotBlank() }?.take(120) ?: "document"
    }

}

/**
 * An open vault, held in memory only.
 *
 * The key lives here and nowhere else — never on disk, never in preferences, never in a
 * database. Dropping this object is what closes the vault, which is why the ViewModel drops it
 * on stop and on timeout rather than relying on a flag.
 */
data class VaultSession(
    val key: ByteArray,
    val items: List<VaultItem>,
    val reclaimed: List<VaultItem> = emptyList()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VaultSession) return false
        return key.contentEquals(other.key) && items == other.items && reclaimed == other.reclaimed
    }

    override fun hashCode(): Int = 31 * key.contentHashCode() + items.hashCode()
}

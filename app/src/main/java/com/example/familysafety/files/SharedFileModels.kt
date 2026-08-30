package com.example.familysafety.files

import kotlinx.serialization.Serializable

@Serializable
data class SharedFile(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: String,       // SHA-256 hex of plaintext bytes
    val uploaderMemberId: String,
    val uploadedAt: Long,
    val chunkCount: Int,
    val isDeleted: Boolean = false,
    val deletedByMemberId: String? = null,
    val deletedAt: Long? = null,
    /**
     * Whether every device should hold a copy without being asked.
     *
     * The point of this feature is emergency documents — an insurance card is only useful if
     * it is already on the phone when there is no signal. But "replicate everything" does not
     * survive contact with a large file, so the uploader marks what actually matters and the
     * rest is listed and fetched on demand.
     *
     * Defaults true, which is both the safe direction and what older peers imply by omitting
     * the field: they replicate everything, so reading their files as essential matches what
     * they will actually do.
     */
    val isEssential: Boolean = true
)

@Serializable
data class FileManifest(
    val groupId: String,
    val files: List<SharedFile>,
    val version: Long,             // epoch ms of last change; higher = newer
    /**
     * Filler so the encrypted manifest lands on a fixed size grid. Without it the
     * ciphertext length tracks how many files the family has and how long their names
     * are, which survives the encryption added alongside it.
     */
    val pad: String = ""
)

/**
 * Encrypted wrapper for [FileManifest], published in its place on the retained manifest
 * topic.
 *
 * The manifest used to go out as plaintext JSON — file names, MIME types, exact sizes,
 * uploader and timestamps — retained, so it was served to any broker client on subscribe
 * whether or not they were listening when it was published. That leaked independently of
 * the file *contents* fix in 1.12.0, and a file name is often more revealing than the
 * file.
 *
 * Encrypting symmetrically with the group key keeps this a single retained broadcast, so
 * a joining member still catches up instantly. Per-recipient encryption would have cost
 * both.
 */
@Serializable
data class EncryptedFileManifest(
    /** Which key encrypted [data]; same scheme as [FileChunkMessage.keyVersion]. */
    val keyVersion: Int,
    /** Base64 of `nonce ‖ ciphertext ‖ tag` over the serialized [FileManifest]. */
    val data: String,
    /**
     * Who published this manifest. Verified against *our* current roster, never against
     * keys carried in the message — the same rule `GroupSyncManager` follows, and the
     * reason a forged manifest cannot simply supply the key that validates it.
     */
    val signerMemberId: String = "",
    /**
     * Hex Ed25519 signature over [manifestSigningPayload].
     *
     * Encryption alone proved only that the publisher held the group key, and the manifest
     * was previously not signed at all: anyone able to publish to the broker could rewrite
     * a family's entire file list, including marking documents deleted. Defaulted to empty
     * so a manifest from a build that predates signing still parses and can be reported as
     * unsigned rather than failing to decode.
     */
    val signature: String = ""
)

/**
 * The exact bytes covered by [EncryptedFileManifest.signature].
 *
 * Both fields are included so the key version cannot be swapped underneath a valid
 * signature, and the ciphertext is signed rather than the plaintext so a manifest is
 * authenticated before anything is decrypted.
 */
fun manifestSigningPayload(keyVersion: Int, data: String): ByteArray =
    "$keyVersion|$data".toByteArray(Charsets.UTF_8)

@Serializable
data class FileChunkMessage(
    val fileId: String,
    val chunkIndex: Int,
    val totalChunks: Int,
    val data: String,              // base64-encoded encrypted chunk bytes
    /**
     * Which key encrypted [data]. 1 = legacy key derived from the groupId, which is
     * public in the topic name and therefore not secret; 2 = the group's random
     * fileEncryptionKey. Defaults to 1 so chunks from older builds, which omit the
     * field entirely, still decrypt.
     */
    val keyVersion: Int = FILE_KEY_VERSION_LEGACY
)

/** Key derived from the public groupId. Readable by anyone on the broker. */
const val FILE_KEY_VERSION_LEGACY = 1

/**
 * Random per-group key carried inside the encrypted GroupDefinition, used **raw**.
 *
 * Published by 1.12.0 through 1.13.1. Still read, and always will be — files shared during
 * those releases are encrypted under it and re-encrypting them is not something the app can
 * do behind anyone's back. Superseded for new uploads by [FILE_KEY_VERSION_GROUP_SUBKEY],
 * because using the group key directly made it both the file key and the parent of every
 * other subkey.
 */
const val FILE_KEY_VERSION_GROUP_SECRET = 2

/**
 * `GroupCipher.deriveSubkey(fileEncryptionKey, PURPOSE_FILES)` — the group key with domain
 * separation, matching how presence already derives its own subkey instead of using the
 * shared secret raw.
 *
 * **Written as of 1.13.2 (31).** The reader shipped in 29, a release ahead of the writer, so
 * that a build which can already open version 3 was in everyone's hands before any device
 * started producing it. Flipping both at once would have meant peers on the previous release
 * silently failing to decrypt new files — silently because a device on 28 does not recognise
 * the version and reject it, it falls through to the legacy key, fails GCM authentication
 * and drops the chunk.
 *
 * That is also why the flip waited: it was gated on every device in the family being past
 * 28, confirmed 2026-08-30, and it is not reversible for files already published under it.
 */
const val FILE_KEY_VERSION_GROUP_SUBKEY = 3

@Serializable
data class FileRequestMessage(
    val requesterId: String        // memberId asking for a full re-broadcast
)

/**
 * A request for specific chunks of one file, addressed to one peer.
 *
 * Replaces the all-or-nothing [FileRequestMessage], whose only possible response was for the
 * peer to re-publish every chunk of every complete file it holds to the entire group. That
 * made the cost of one missing chunk proportional to the whole library, so it was only ever
 * triggered by a user tapping a file that had not downloaded — which is why a stalled transfer
 * could sit for half a day and then complete the instant someone touched it.
 *
 * [contentHash] binds the request to exact bytes. If the responder's copy hashes differently
 * the two are looking at different versions of the file, and sending chunks would corrupt the
 * requester's blob rather than repair it.
 */
@Serializable
data class ChunkRepairRequest(
    val requestId: String,
    val requesterId: String,
    val fileId: String,
    val contentHash: String,
    val totalChunks: Int,
    /** Ascending, capped by the requester so this fits in one message. */
    val missing: List<Int>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Encrypted wrapper for a file-subsystem control message, published in place of the plaintext.
 *
 * Same reasoning as [EncryptedFileManifest]: a bare [ChunkRepairRequest] names a file and how
 * much of it a device is missing, which is exactly the sort of metadata the manifest was
 * encrypted to stop leaking. Encrypted under the group key rather than per-recipient, because
 * every member can already read the manifest and the chunks, so per-pair encryption here would
 * add cost without narrowing who can read it.
 */
/**
 * What one device holds, sent to each peer so the family can answer "is this document
 * actually safe?"
 *
 * Modelled on the announce phase of `ReplicationManager` rather than on chat's delivery
 * receipts, and the difference matters. A receipt is a per-event fact: lose it and it is gone
 * forever. An announcement is a state summary — it self-heals on the next tick, and a member
 * who has just joined gets the whole picture at once instead of only what happens next.
 */
@Serializable
data class FileHoldingsAnnouncement(
    val announcerId: String,
    val holdings: List<FileHolding>,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
data class FileHolding(
    val fileId: String,
    /** A peer holding different bytes under the same id is not a copy of this file. */
    val contentHash: String,
    /** COMPLETE | PARTIAL */
    val state: String,
    val chunksHeld: Int,
    val chunkCount: Int
)

const val HOLDING_COMPLETE = "COMPLETE"
const val HOLDING_PARTIAL = "PARTIAL"

@Serializable
data class EncryptedFileMessage(
    val keyVersion: Int,
    /** Base64 of `nonce ‖ ciphertext ‖ tag`. */
    val data: String
)

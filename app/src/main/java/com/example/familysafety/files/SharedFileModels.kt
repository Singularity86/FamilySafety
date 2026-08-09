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
    val deletedAt: Long? = null
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
    val data: String
)

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

/** Random per-group key carried inside the encrypted GroupDefinition. */
const val FILE_KEY_VERSION_GROUP_SECRET = 2

@Serializable
data class FileRequestMessage(
    val requesterId: String        // memberId asking for a full re-broadcast
)

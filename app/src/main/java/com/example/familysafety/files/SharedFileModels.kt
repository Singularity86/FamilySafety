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
    val version: Long              // epoch ms of last change; higher = newer
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

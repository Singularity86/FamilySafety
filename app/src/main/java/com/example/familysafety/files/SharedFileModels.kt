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
    val data: String               // base64-encoded encrypted chunk bytes
)

@Serializable
data class FileRequestMessage(
    val requesterId: String        // memberId asking for a full re-broadcast
)

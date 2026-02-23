package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shared_files")
data class SharedFileEntity(
    @PrimaryKey val fileId: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val contentHash: String,
    val uploaderMemberId: String,
    val uploadedAt: Long,
    val chunkCount: Int,
    val isDeleted: Boolean = false,
    val deletedByMemberId: String? = null,
    val deletedAt: Long? = null,
    /** Absolute path on this device; null = file not yet fully downloaded. */
    val localPath: String? = null,
    val chunksReceived: Int = 0,
    /** PENDING | DOWNLOADING | COMPLETE | FAILED */
    val downloadState: String = "PENDING"
)

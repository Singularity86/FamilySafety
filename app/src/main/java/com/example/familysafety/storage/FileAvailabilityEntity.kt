package com.example.familysafety.storage

import androidx.room.Entity
import androidx.room.Index

/**
 * What each peer holds of each file, as most recently announced.
 *
 * A table rather than a field on the file row, because this is genuinely a peer × file matrix
 * and the interesting query — "how many devices hold a complete, verified copy of this?" — is
 * a group-by over it.
 *
 * That question is the whole point of the feature. For an emergency document the useful
 * assurance is not "it uploaded" but "it is on four phones", and until now nothing in the app
 * could tell you either way.
 */
@Entity(
    tableName = "file_availability",
    primaryKeys = ["fileId", "memberId"],
    indices = [Index("fileId")]
)
data class FileAvailabilityEntity(
    val fileId: String,
    val memberId: String,
    /** Guards against counting a peer holding different bytes under the same id. */
    val contentHash: String,
    /** COMPLETE | PARTIAL */
    val state: String,
    val chunksHeld: Int,
    val chunkCount: Int,
    val updatedAt: Long
)

/** Result of the availability roll-up: how many peers hold a complete copy of [fileId]. */
data class FileCopyCount(
    val fileId: String,
    val copies: Int
)

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
    val downloadState: String = "PENDING",

    /**
     * Which chunks we hold, one bit each (see [com.example.familysafety.files.ChunkBitmap]).
     *
     * [chunksReceived] is a denormalised popcount of this, kept so the UI never has to decode
     * the bitmap. The bitmap is the part that matters: it is what makes "which chunks are
     * missing" answerable, which the previous directory-listing count could not do. Treated as
     * a cache over the on-disk blob, which can rebuild it by testing which slots authenticate.
     *
     * Null for rows written before this existed; read as "nothing held".
     */
    val chunkBitmap: ByteArray? = null,

    /**
     * Which key version the in-flight blob's chunks were encrypted under.
     *
     * Slots are stored as received, so one blob cannot mix key versions — a repair chunk under
     * a different version has to be refused rather than written. Meaningless once the file is
     * COMPLETE and the blob is gone.
     */
    val blobKeyVersion: Int = 0,

    /**
     * Retry state, modelled on `PendingLocationPublishEntity` — the same shape that already
     * proved out for location publishes, kept deliberately identical so the two are obviously
     * the same pattern.
     *
     * Intent is stored here, not bytes: a 3200-chunk transfer is one row with a retry
     * schedule, not 3200 blobs in the database. [nextAttemptAt] is when this file may next be
     * chased; 0 means "as soon as anything runs". [lastError] is what the status board shows
     * the user instead of a file that is simply, silently absent.
     */
    val attemptCount: Int = 0,
    val lastAttemptAt: Long? = null,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    /** Member last asked for missing chunks, so requests rotate rather than nagging one peer. */
    val lastRepairPeerId: String? = null
) {
    // Room's generated equals/hashCode would compare the ByteArray by reference. That makes
    // two rows with identical bitmaps unequal, which quietly breaks Flow distinctUntilChanged
    // and any test that compares entities.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SharedFileEntity) return false
        return fileId == other.fileId &&
            name == other.name &&
            mimeType == other.mimeType &&
            sizeBytes == other.sizeBytes &&
            contentHash == other.contentHash &&
            uploaderMemberId == other.uploaderMemberId &&
            uploadedAt == other.uploadedAt &&
            chunkCount == other.chunkCount &&
            isDeleted == other.isDeleted &&
            deletedByMemberId == other.deletedByMemberId &&
            deletedAt == other.deletedAt &&
            localPath == other.localPath &&
            chunksReceived == other.chunksReceived &&
            downloadState == other.downloadState &&
            blobKeyVersion == other.blobKeyVersion &&
            attemptCount == other.attemptCount &&
            lastAttemptAt == other.lastAttemptAt &&
            nextAttemptAt == other.nextAttemptAt &&
            lastError == other.lastError &&
            lastRepairPeerId == other.lastRepairPeerId &&
            (chunkBitmap?.contentEquals(other.chunkBitmap) ?: (other.chunkBitmap == null))
    }

    override fun hashCode(): Int {
        var result = fileId.hashCode()
        result = 31 * result + chunkCount
        result = 31 * result + chunksReceived
        result = 31 * result + downloadState.hashCode()
        result = 31 * result + (chunkBitmap?.contentHashCode() ?: 0)
        return result
    }
}

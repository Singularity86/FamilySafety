package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SharedFileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SharedFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SharedFileEntity>)

    @Query("SELECT * FROM shared_files WHERE isDeleted = 0 ORDER BY uploadedAt DESC")
    fun observeAllFiles(): Flow<List<SharedFileEntity>>

    @Query("SELECT * FROM shared_files WHERE isDeleted = 0 ORDER BY uploadedAt DESC")
    suspend fun getAllFiles(): List<SharedFileEntity>

    @Query("SELECT * FROM shared_files WHERE fileId = :fileId")
    suspend fun getFileById(fileId: String): SharedFileEntity?

    /**
     * What goes into a published manifest: live files **and** recent tombstones.
     *
     * [getAllFiles] filters `isDeleted = 0`, and the manifest was built from it. Deleting a
     * file therefore removed it here and nowhere else, while the confirmation dialog said
     * "delete for everyone in the family" — the receiving side even had a remote-deletion
     * branch that no sender ever reached. For medical records and IDs that is a privacy
     * failure rather than a missing feature.
     *
     * Tombstones are dropped once they are older than [cutoff]. They exist to outlive any
     * device that might still be holding the file, not forever: a peer offline longer than
     * the retention window rejoins with the file still listed, which the manifest will not
     * correct. The window is set well beyond any plausible offline period for that reason.
     */
    @Query("""
        SELECT * FROM shared_files
        WHERE isDeleted = 0 OR deletedAt >= :cutoff
        ORDER BY uploadedAt DESC
    """)
    suspend fun getFilesForManifest(cutoff: Long): List<SharedFileEntity>

    /** Drop tombstones past the retention window, so they do not accumulate for ever. */
    @Query("DELETE FROM shared_files WHERE isDeleted = 1 AND deletedAt < :cutoff")
    suspend fun purgeExpiredTombstones(cutoff: Long): Int

    /**
     * Completed files still stored as plaintext, for the one-time at-rest migration.
     *
     * `localPath` is null for every file written by this release — the encrypted blob is the
     * stored form — so a non-null path on a complete row means the copy predates that and is
     * sitting unencrypted on external storage.
     */
    @Query("""
        SELECT * FROM shared_files
        WHERE isDeleted = 0 AND downloadState = 'COMPLETE' AND localPath IS NOT NULL
        ORDER BY sizeBytes ASC
    """)
    suspend fun getPlaintextStoredFiles(): List<SharedFileEntity>

    @Query("""
        UPDATE shared_files
        SET downloadState = :state, localPath = :localPath, chunksReceived = :chunksReceived
        WHERE fileId = :fileId
    """)
    suspend fun updateDownloadProgress(
        fileId: String,
        state: String,
        localPath: String?,
        chunksReceived: Int
    )

    /**
     * Record a chunk arrival: the updated bitmap, its popcount, and the resulting state.
     *
     * Separate from [updateDownloadProgress] because that statement also writes `localPath`,
     * setting it to null on every progress update — harmless while downloading, but it means
     * progress and completion cannot share a statement.
     */
    @Query("""
        UPDATE shared_files
        SET chunkBitmap = :bitmap,
            chunksReceived = :chunksReceived,
            downloadState = :state,
            blobKeyVersion = :blobKeyVersion
        WHERE fileId = :fileId
    """)
    suspend fun updateChunkState(
        fileId: String,
        bitmap: ByteArray?,
        chunksReceived: Int,
        state: String,
        blobKeyVersion: Int
    )

    @Query("""
        UPDATE shared_files
        SET isDeleted = 1, deletedByMemberId = :deletedBy, deletedAt = :deletedAt
        WHERE fileId = :fileId
    """)
    suspend fun markDeleted(fileId: String, deletedBy: String, deletedAt: Long)

    /**
     * Files that still need chunks. Drives both the startup reconcile and, from Phase 2, the
     * repair loop. `getPendingFiles` only matched the literal 'PENDING' state, so it missed
     * everything already part-downloaded — which is exactly the set that gets stuck.
     */
    @Query("""
        SELECT * FROM shared_files
        WHERE isDeleted = 0 AND downloadState IN ('PENDING', 'DOWNLOADING', 'FAILED')
        ORDER BY uploadedAt DESC
    """)
    suspend fun getIncompleteFiles(): List<SharedFileEntity>

    /**
     * Files due to be chased now. This is the outbox drain, and the reason a stalled transfer
     * no longer waits for a user to tap it.
     */
    @Query("""
        SELECT * FROM shared_files
        WHERE isDeleted = 0
          AND downloadState IN ('PENDING', 'DOWNLOADING', 'FAILED')
          AND nextAttemptAt <= :now
        ORDER BY nextAttemptAt ASC, uploadedAt DESC
        LIMIT :limit
    """)
    suspend fun getDueForRepair(now: Long, limit: Int): List<SharedFileEntity>

    @Query("""
        SELECT COUNT(*) FROM shared_files
        WHERE isDeleted = 0 AND downloadState IN ('PENDING', 'DOWNLOADING', 'FAILED')
    """)
    suspend fun countIncomplete(): Int

    /**
     * Record a repair attempt as a single atomic increment.
     *
     * Copied from `PendingLocationPublishDao.recordAttempt` — reading, incrementing and
     * writing back from Kotlin would lose counts whenever two attempts overlap, which is
     * exactly the situation a retry loop creates.
     */
    @Query("""
        UPDATE shared_files
        SET attemptCount = attemptCount + 1,
            lastAttemptAt = :attemptAt,
            nextAttemptAt = :nextAttemptAt,
            lastError = :lastError,
            lastRepairPeerId = :peerId
        WHERE fileId = :fileId
    """)
    suspend fun recordRepairAttempt(
        fileId: String,
        attemptAt: Long,
        nextAttemptAt: Long,
        lastError: String?,
        peerId: String?
    )

    @Query("UPDATE shared_files SET isEssential = :essential WHERE fileId = :fileId")
    suspend fun setEssential(fileId: String, essential: Boolean)

    /** Clear the retry schedule, e.g. when the user asks for an immediate retry. */
    @Query("""
        UPDATE shared_files
        SET attemptCount = 0, nextAttemptAt = 0, lastError = NULL
        WHERE fileId = :fileId
    """)
    suspend fun clearRepairSchedule(fileId: String)

    /** Total bytes of non-deleted files that are fully downloaded. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM shared_files WHERE isDeleted = 0")
    suspend fun getTotalSizeBytes(): Long

    @Query("SELECT * FROM shared_files WHERE downloadState = 'PENDING' AND isDeleted = 0")
    suspend fun getPendingFiles(): List<SharedFileEntity>
}

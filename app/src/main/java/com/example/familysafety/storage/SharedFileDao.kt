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

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

    @Query("""
        UPDATE shared_files
        SET isDeleted = 1, deletedByMemberId = :deletedBy, deletedAt = :deletedAt
        WHERE fileId = :fileId
    """)
    suspend fun markDeleted(fileId: String, deletedBy: String, deletedAt: Long)

    /** Total bytes of non-deleted files that are fully downloaded. */
    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM shared_files WHERE isDeleted = 0")
    suspend fun getTotalSizeBytes(): Long

    @Query("SELECT * FROM shared_files WHERE downloadState = 'PENDING' AND isDeleted = 0")
    suspend fun getPendingFiles(): List<SharedFileEntity>
}

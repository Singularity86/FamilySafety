package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FileTransferLogDao {

    @Insert
    suspend fun insert(entry: FileTransferLogEntity)

    @Query("SELECT * FROM file_transfer_log ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<FileTransferLogEntity>>

    @Query("SELECT * FROM file_transfer_log WHERE fileId = :fileId ORDER BY at DESC LIMIT :limit")
    fun observeForFile(fileId: String, limit: Int = 50): Flow<List<FileTransferLogEntity>>

    @Query("SELECT * FROM file_transfer_log WHERE fileId = :fileId ORDER BY at DESC LIMIT 1")
    suspend fun getLatestForFile(fileId: String): FileTransferLogEntity?

    /** Age-based trim so a diagnostic log cannot grow without bound. */
    @Query("DELETE FROM file_transfer_log WHERE at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("DELETE FROM file_transfer_log WHERE fileId = :fileId")
    suspend fun deleteForFile(fileId: String)

    @Query("DELETE FROM file_transfer_log")
    suspend fun deleteAll()
}

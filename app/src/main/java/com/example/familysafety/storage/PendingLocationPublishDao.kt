package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingLocationPublishDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PendingLocationPublishEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entities: List<PendingLocationPublishEntity>)

    @Query("""
        SELECT * FROM pending_location_publishes
        WHERE memberId = :memberId
        ORDER BY timestamp ASC, id ASC
    """)
    suspend fun getPendingForMember(memberId: String): List<PendingLocationPublishEntity>

    @Query("SELECT COUNT(*) FROM pending_location_publishes WHERE memberId = :memberId")
    suspend fun getPendingCountForMember(memberId: String): Int

    @Query("SELECT MAX(timestamp) FROM pending_location_publishes WHERE memberId = :memberId")
    suspend fun getNewestPendingTimestamp(memberId: String): Long?

    @Query("""
        UPDATE pending_location_publishes
        SET attemptCount = attemptCount + 1,
            lastAttemptAt = :attemptAt,
            lastError = :lastError
        WHERE id = :id
    """)
    suspend fun recordAttempt(id: Long, attemptAt: Long, lastError: String? = null)

    @Query("DELETE FROM pending_location_publishes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_location_publishes WHERE memberId = :memberId")
    suspend fun deleteAllForMember(memberId: String)

    @Query("DELETE FROM pending_location_publishes WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int
}

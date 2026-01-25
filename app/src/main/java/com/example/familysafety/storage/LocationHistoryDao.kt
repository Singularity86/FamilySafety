package com.example.familysafety.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for location history.
 * Provides methods for storing and querying location data for all family members.
 */
@Dao
interface LocationHistoryDao {

    // =========================================================================
    // INSERT OPERATIONS
    // =========================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: LocationHistoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(locations: List<LocationHistoryEntity>)

    // =========================================================================
    // QUERY - SINGLE MEMBER
    // =========================================================================

    /**
     * Get latest location for a specific member.
     */
    @Query("SELECT * FROM location_history WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestLocation(memberId: String): LocationHistoryEntity?

    /**
     * Get latest location for a specific member as Flow (reactive).
     */
    @Query("SELECT * FROM location_history WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT 1")
    fun observeLatestLocation(memberId: String): Flow<LocationHistoryEntity?>

    /**
     * Get location history for a member within a time range.
     */
    @Query("""
        SELECT * FROM location_history
        WHERE memberId = :memberId
        AND timestamp >= :startTime
        AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    suspend fun getLocationHistory(
        memberId: String,
        startTime: Long,
        endTime: Long
    ): List<LocationHistoryEntity>

    /**
     * Get location history for a member within time range as Flow.
     */
    @Query("""
        SELECT * FROM location_history
        WHERE memberId = :memberId
        AND timestamp >= :startTime
        AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    fun observeLocationHistory(
        memberId: String,
        startTime: Long,
        endTime: Long
    ): Flow<List<LocationHistoryEntity>>

    /**
     * Get recent locations for a member (last N entries).
     */
    @Query("SELECT * FROM location_history WHERE memberId = :memberId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLocations(memberId: String, limit: Int): List<LocationHistoryEntity>

    // =========================================================================
    // QUERY - ALL MEMBERS
    // =========================================================================

    /**
     * Get latest location for each family member.
     */
    @Query("""
        SELECT * FROM location_history lh1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM location_history lh2
            WHERE lh2.memberId = lh1.memberId
        )
    """)
    suspend fun getLatestLocationForAllMembers(): List<LocationHistoryEntity>

    /**
     * Observe latest location for each family member (reactive).
     */
    @Query("""
        SELECT * FROM location_history lh1
        WHERE timestamp = (
            SELECT MAX(timestamp) FROM location_history lh2
            WHERE lh2.memberId = lh1.memberId
        )
    """)
    fun observeLatestLocationForAllMembers(): Flow<List<LocationHistoryEntity>>

    /**
     * Get all locations within a time range for all members.
     */
    @Query("""
        SELECT * FROM location_history
        WHERE timestamp >= :startTime AND timestamp <= :endTime
        ORDER BY timestamp DESC
    """)
    suspend fun getAllLocationsInRange(startTime: Long, endTime: Long): List<LocationHistoryEntity>

    // =========================================================================
    // QUERY - REPLICATION SUPPORT
    // =========================================================================

    /**
     * Get all locations for a member after a specific timestamp.
     * Used for sync: "give me everything newer than X"
     */
    @Query("""
        SELECT * FROM location_history
        WHERE memberId = :memberId AND timestamp > :afterTimestamp
        ORDER BY timestamp ASC
    """)
    suspend fun getLocationsAfter(memberId: String, afterTimestamp: Long): List<LocationHistoryEntity>

    /**
     * Get the oldest timestamp we have for a member.
     */
    @Query("SELECT MIN(timestamp) FROM location_history WHERE memberId = :memberId")
    suspend fun getOldestTimestamp(memberId: String): Long?

    /**
     * Get the newest timestamp we have for a member.
     */
    @Query("SELECT MAX(timestamp) FROM location_history WHERE memberId = :memberId")
    suspend fun getNewestTimestamp(memberId: String): Long?

    /**
     * Get count of locations for a member in a time range.
     */
    @Query("""
        SELECT COUNT(*) FROM location_history
        WHERE memberId = :memberId
        AND timestamp >= :startTime
        AND timestamp <= :endTime
    """)
    suspend fun getLocationCount(memberId: String, startTime: Long, endTime: Long): Int

    /**
     * Check if we have any data for a member.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM location_history WHERE memberId = :memberId LIMIT 1)")
    suspend fun hasDataForMember(memberId: String): Boolean

    // =========================================================================
    // DELETE OPERATIONS
    // =========================================================================

    /**
     * Delete all locations for a specific member (e.g., when member leaves group).
     */
    @Query("DELETE FROM location_history WHERE memberId = :memberId")
    suspend fun deleteAllForMember(memberId: String)

    /**
     * Delete locations older than a specific timestamp (retention policy).
     */
    @Query("DELETE FROM location_history WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOlderThan(beforeTimestamp: Long): Int

    /**
     * Delete all location history.
     */
    @Query("DELETE FROM location_history")
    suspend fun deleteAll()

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /**
     * Get total count of stored locations.
     */
    @Query("SELECT COUNT(*) FROM location_history")
    suspend fun getTotalCount(): Int

    /**
     * Get count per member.
     */
    @Query("SELECT memberId, COUNT(*) as count FROM location_history GROUP BY memberId")
    suspend fun getCountPerMember(): List<MemberLocationCount>

    /**
     * Get distinct member IDs that have location data.
     */
    @Query("SELECT DISTINCT memberId FROM location_history")
    suspend fun getMemberIdsWithData(): List<String>
}

/**
 * Helper class for count per member query.
 */
data class MemberLocationCount(
    val memberId: String,
    val count: Int
)

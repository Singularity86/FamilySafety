package com.example.familysafety.storage

import com.example.familysafety.location.MemberLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for location history operations.
 * Provides a higher-level interface over the DAO with business logic.
 */
@Singleton
class LocationHistoryRepository @Inject constructor(
    private val locationHistoryDao: LocationHistoryDao
) {
    companion object {
        private const val TAG = "LocationHistoryRepo"

        /** Default retention period: 30 days */
        const val DEFAULT_RETENTION_DAYS = 30L
        private const val MS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    // =========================================================================
    // SAVE OPERATIONS
    // =========================================================================

    /**
     * Save a location to history.
     * Called when receiving location updates (own or from family members).
     */
    suspend fun saveLocation(
        location: MemberLocation,
        isReplicated: Boolean = false,
        replicatedFrom: String? = null
    ) {
        try {
            val entity = LocationHistoryEntity.fromMemberLocation(
                location = location,
                isReplicated = isReplicated,
                replicatedFrom = replicatedFrom
            )
            locationHistoryDao.insert(entity)
            Timber.d("$TAG: Saved location for ${location.memberId} at ${location.timestamp}")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save location")
        }
    }

    /**
     * Save multiple locations (batch insert for replication).
     */
    suspend fun saveLocations(
        locations: List<MemberLocation>,
        isReplicated: Boolean = false,
        replicatedFrom: String? = null
    ) {
        if (locations.isEmpty()) return

        try {
            val entities = locations.map { location ->
                LocationHistoryEntity.fromMemberLocation(
                    location = location,
                    isReplicated = isReplicated,
                    replicatedFrom = replicatedFrom
                )
            }
            locationHistoryDao.insertAll(entities)
            Timber.d("$TAG: Saved ${locations.size} locations (replicated=$isReplicated)")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to save locations batch")
        }
    }

    // =========================================================================
    // QUERY OPERATIONS
    // =========================================================================

    /**
     * Get the most recent location for a member.
     */
    suspend fun getLatestLocation(memberId: String): MemberLocation? {
        return locationHistoryDao.getLatestLocation(memberId)?.toMemberLocation()
    }

    /**
     * Observe the most recent location for a member (reactive).
     */
    fun observeLatestLocation(memberId: String): Flow<MemberLocation?> {
        return locationHistoryDao.observeLatestLocation(memberId)
            .map { it?.toMemberLocation() }
    }

    /**
     * Get the most recent location for all family members.
     */
    suspend fun getLatestLocationsForAllMembers(): Map<String, MemberLocation> {
        return locationHistoryDao.getLatestLocationForAllMembers()
            .associate { it.memberId to it.toMemberLocation() }
    }

    /**
     * Observe latest locations for all members (reactive).
     */
    fun observeLatestLocationsForAllMembers(): Flow<Map<String, MemberLocation>> {
        return locationHistoryDao.observeLatestLocationForAllMembers()
            .map { entities ->
                entities.associate { it.memberId to it.toMemberLocation() }
            }
    }

    /**
     * Get location history for a member within a time range.
     */
    suspend fun getLocationHistory(
        memberId: String,
        startTime: Long,
        endTime: Long = System.currentTimeMillis()
    ): List<MemberLocation> {
        return locationHistoryDao.getLocationHistory(memberId, startTime, endTime)
            .map { it.toMemberLocation() }
    }

    /**
     * Observe location history for a member (reactive).
     */
    fun observeLocationHistory(
        memberId: String,
        startTime: Long,
        endTime: Long = System.currentTimeMillis()
    ): Flow<List<MemberLocation>> {
        return locationHistoryDao.observeLocationHistory(memberId, startTime, endTime)
            .map { entities -> entities.map { it.toMemberLocation() } }
    }

    /**
     * Get recent locations for a member (last N entries).
     */
    suspend fun getRecentLocations(memberId: String, limit: Int = 100): List<MemberLocation> {
        return locationHistoryDao.getRecentLocations(memberId, limit)
            .map { it.toMemberLocation() }
    }

    /**
     * Get location history for today.
     */
    suspend fun getTodayHistory(memberId: String): List<MemberLocation> {
        val now = System.currentTimeMillis()
        val startOfDay = now - (now % MS_PER_DAY)
        return getLocationHistory(memberId, startOfDay, now)
    }

    /**
     * Get location history for the last N hours.
     */
    suspend fun getLastNHoursHistory(memberId: String, hours: Int): List<MemberLocation> {
        val now = System.currentTimeMillis()
        val startTime = now - (hours * 60 * 60 * 1000L)
        return getLocationHistory(memberId, startTime, now)
    }

    // =========================================================================
    // REPLICATION SUPPORT
    // =========================================================================

    /**
     * Get locations for a member after a specific timestamp.
     * Used for sync: "give me everything newer than X"
     */
    suspend fun getLocationsAfter(memberId: String, afterTimestamp: Long): List<MemberLocation> {
        return locationHistoryDao.getLocationsAfter(memberId, afterTimestamp)
            .map { it.toMemberLocation() }
    }

    /**
     * Get the newest timestamp we have for a member.
     * Used to request missing data from peers.
     */
    suspend fun getNewestTimestamp(memberId: String): Long? {
        return locationHistoryDao.getNewestTimestamp(memberId)
    }

    /**
     * Get summary of what data we have for each member.
     * Returns map of memberId -> (oldestTimestamp, newestTimestamp, count)
     */
    suspend fun getDataSummary(): Map<String, DataSummary> {
        val memberIds = locationHistoryDao.getMemberIdsWithData()
        return memberIds.associateWith { memberId ->
            DataSummary(
                oldestTimestamp = locationHistoryDao.getOldestTimestamp(memberId),
                newestTimestamp = locationHistoryDao.getNewestTimestamp(memberId),
                count = locationHistoryDao.getLocationCount(
                    memberId,
                    0,
                    System.currentTimeMillis()
                )
            )
        }
    }

    /**
     * Check if we have any data for a member.
     */
    suspend fun hasDataForMember(memberId: String): Boolean {
        return locationHistoryDao.hasDataForMember(memberId)
    }

    // =========================================================================
    // CLEANUP OPERATIONS
    // =========================================================================

    /**
     * Apply retention policy - delete data older than specified days.
     */
    suspend fun applyRetentionPolicy(retentionDays: Long = DEFAULT_RETENTION_DAYS): Int {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * MS_PER_DAY)
        val deleted = locationHistoryDao.deleteOlderThan(cutoffTime)
        Timber.d("$TAG: Deleted $deleted locations older than $retentionDays days")
        return deleted
    }

    /**
     * Delete all data for a member (when they leave the group).
     */
    suspend fun deleteDataForMember(memberId: String) {
        locationHistoryDao.deleteAllForMember(memberId)
        Timber.d("$TAG: Deleted all locations for member $memberId")
    }

    /**
     * Delete all location history.
     */
    suspend fun deleteAll() {
        locationHistoryDao.deleteAll()
        Timber.d("$TAG: Deleted all location history")
    }

    // =========================================================================
    // STATISTICS
    // =========================================================================

    /**
     * Get total count of stored locations.
     */
    suspend fun getTotalCount(): Int {
        return locationHistoryDao.getTotalCount()
    }

    /**
     * Get total count of stored locations for a specific member (all time).
     */
    suspend fun getCountForMember(memberId: String): Int {
        return locationHistoryDao.getLocationCount(memberId, 0L, Long.MAX_VALUE)
    }

    /**
     * Get count per member.
     */
    suspend fun getCountPerMember(): Map<String, Int> {
        return locationHistoryDao.getCountPerMember()
            .associate { it.memberId to it.count }
    }
}

/**
 * Summary of location data for a member.
 */
data class DataSummary(
    val oldestTimestamp: Long?,
    val newestTimestamp: Long?,
    val count: Int
)

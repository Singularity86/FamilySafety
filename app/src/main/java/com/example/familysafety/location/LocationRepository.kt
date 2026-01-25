package com.example.familysafety.location

import com.example.familysafety.storage.LocationHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for current member locations.
 * Maintains in-memory cache of latest locations and persists to history database.
 */
@Singleton
class LocationRepository @Inject constructor(
    private val locationHistoryRepository: LocationHistoryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _memberLocations = MutableStateFlow<Map<String, MemberLocation>>(emptyMap())
    val memberLocations: StateFlow<Map<String, MemberLocation>> = _memberLocations.asStateFlow()

    private val _myLocation = MutableStateFlow<MemberLocation?>(null)
    val myLocation: StateFlow<MemberLocation?> = _myLocation.asStateFlow()

    private var isInitialized = false

    /**
     * Initialize by loading latest locations from database.
     */
    suspend fun initialize() {
        if (isInitialized) return

        try {
            val latestLocations = locationHistoryRepository.getLatestLocationsForAllMembers()
            _memberLocations.value = latestLocations
            Timber.d("LocationRepository: Initialized with ${latestLocations.size} locations from history")
            isInitialized = true
        } catch (e: Exception) {
            Timber.e(e, "LocationRepository: Failed to initialize from history")
        }
    }

    /**
     * Update a member's location.
     * Persists to history database and updates in-memory cache.
     */
    fun updateMemberLocation(
        location: MemberLocation,
        isReplicated: Boolean = false,
        replicatedFrom: String? = null
    ) {
        // Update in-memory cache immediately
        _memberLocations.value = _memberLocations.value.toMutableMap().apply {
            put(location.memberId, location)
        }

        // Persist to database asynchronously
        scope.launch {
            try {
                locationHistoryRepository.saveLocation(
                    location = location,
                    isReplicated = isReplicated,
                    replicatedFrom = replicatedFrom
                )
            } catch (e: Exception) {
                Timber.e(e, "LocationRepository: Failed to persist location")
            }
        }
    }

    /**
     * Update the local user's location.
     */
    fun updateMyLocation(location: MemberLocation) {
        _myLocation.value = location
        updateMemberLocation(location, isReplicated = false)
    }

    /**
     * Update multiple locations at once (for replication).
     */
    fun updateMemberLocations(
        locations: List<MemberLocation>,
        isReplicated: Boolean = true,
        replicatedFrom: String? = null
    ) {
        if (locations.isEmpty()) return

        // Update in-memory cache
        val updatedMap = _memberLocations.value.toMutableMap()
        locations.forEach { location ->
            // Only update if newer than existing
            val existing = updatedMap[location.memberId]
            if (existing == null || location.timestamp > existing.timestamp) {
                updatedMap[location.memberId] = location
            }
        }
        _memberLocations.value = updatedMap

        // Persist to database asynchronously
        scope.launch {
            try {
                locationHistoryRepository.saveLocations(
                    locations = locations,
                    isReplicated = isReplicated,
                    replicatedFrom = replicatedFrom
                )
            } catch (e: Exception) {
                Timber.e(e, "LocationRepository: Failed to persist locations batch")
            }
        }
    }

    /**
     * Remove stale locations from in-memory cache.
     */
    fun removeStaleLocations(thresholdMs: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        _memberLocations.value = _memberLocations.value.filterValues {
            now - it.timestamp < thresholdMs
        }
    }

    /**
     * Get current location for a member.
     */
    fun getLocationForMember(memberId: String): MemberLocation? {
        return _memberLocations.value[memberId]
    }

    /**
     * Get location history for a member.
     */
    suspend fun getLocationHistory(
        memberId: String,
        startTime: Long,
        endTime: Long = System.currentTimeMillis()
    ): List<MemberLocation> {
        return locationHistoryRepository.getLocationHistory(memberId, startTime, endTime)
    }

    /**
     * Get recent locations for a member.
     */
    suspend fun getRecentLocations(memberId: String, limit: Int = 100): List<MemberLocation> {
        return locationHistoryRepository.getRecentLocations(memberId, limit)
    }

    /**
     * Clear in-memory cache.
     */
    fun clearAllLocations() {
        _memberLocations.value = emptyMap()
        _myLocation.value = null
    }

    /**
     * Delete all history for a member (when they leave).
     */
    suspend fun deleteHistoryForMember(memberId: String) {
        _memberLocations.value = _memberLocations.value.filterKeys { it != memberId }
        locationHistoryRepository.deleteDataForMember(memberId)
    }

    /**
     * Apply retention policy to clean up old data.
     */
    suspend fun applyRetentionPolicy(retentionDays: Long = 30L) {
        locationHistoryRepository.applyRetentionPolicy(retentionDays)
    }
}

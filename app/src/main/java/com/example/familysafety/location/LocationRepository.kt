package com.example.familysafety.location

import com.example.familysafety.geofence.GeofenceMonitor
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.storage.LocationHistoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val locationHistoryRepository: LocationHistoryRepository,
    private val geofenceMonitor: GeofenceMonitor,
    private val groupStateManager: GroupStateManager
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

            // History is keyed by member ID and kept for 30 days, so it outlives group
            // membership: anyone who left, anyone from a family that was recreated, and any
            // member missing from this device's roster all still have rows. The map draws one
            // marker per entry and labels it `displayName ?: "?"`, so every one of those
            // became an anonymous question-mark pin sitting on a stale position.
            //
            // History itself is left alone — it is the user's data, and the History screen is
            // entitled to it. What gets filtered is the live map.
            val known = groupStateManager.groupDefinition.value?.members?.map { it.memberId }?.toSet()
            val filtered = if (known.isNullOrEmpty()) latestLocations
            else latestLocations.filterKeys { it in known }

            val dropped = latestLocations.size - filtered.size
            if (dropped > 0) {
                Timber.i("LocationRepository: ignoring $dropped location(s) from non-members")
            }
            _memberLocations.value = filtered
            Timber.d("LocationRepository: Initialized with ${filtered.size} locations from history")
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
        // Replay/out-of-order guard: never move a member's pin backwards in time.
        // The timestamp is inside the signed+encrypted payload, so a replayed old
        // ciphertext cannot override a newer position. (Historical backfill goes
        // through updateMemberLocations, which persists regardless.)
        //
        // Check and write in one atomic update. Read-then-assign, as before, let two updates
        // arriving together each copy the old map, so one member's new position was erased
        // by the other's write.
        var accepted = false
        _memberLocations.update { current ->
            val existing = current[location.memberId]
            if (existing != null && location.timestamp <= existing.timestamp) {
                current
            } else {
                accepted = true
                current + (location.memberId to location)
            }
        }
        if (!accepted) {
            Timber.d("LocationRepository: ignoring stale location for ${location.memberId.take(8)}")
            return
        }

        // Check geofences and speed for this location update
        val displayName = groupStateManager.groupDefinition.value
            ?.findMemberById(location.memberId)?.displayName
            ?: location.memberId.take(8)
        geofenceMonitor.checkLocation(location, displayName)

        // Stamp when we last heard a position from this member. This is what the
        // diagnostics report's "last location" line reads, and until now nothing called
        // it — so that line said "never" for every member on every device whether
        // locations were flowing or not, which is worse than not printing it at all.
        scope.launch { groupStateManager.recordLocationUpdate(location.memberId) }

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

        // Update in-memory cache, atomically (see updateMemberLocation)
        _memberLocations.update { current ->
            val updatedMap = current.toMutableMap()
            locations.forEach { location ->
                // Only update if newer than existing
                val existing = updatedMap[location.memberId]
                if (existing == null || location.timestamp > existing.timestamp) {
                    updatedMap[location.memberId] = location
                }
            }
            updatedMap
        }

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
     * Drop live locations for anyone no longer in the group.
     *
     * Called whenever membership changes. Without it a removed member's last known position
     * stays on the map indefinitely — as a question-mark pin, since there is no longer a name
     * to put on it.
     */
    fun pruneToMembers(memberIds: Collection<String>) {
        if (memberIds.isEmpty()) return
        val allowed = memberIds.toSet()
        var dropped = 0
        _memberLocations.update { current ->
            current.filterKeys { it in allowed }.also { dropped = current.size - it.size }
        }
        if (dropped > 0) {
            Timber.i("LocationRepository: dropped $dropped location(s) for departed members")
        }
    }

    /**
     * Remove stale locations from in-memory cache.
     */
    fun removeStaleLocations(thresholdMs: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        _memberLocations.update { current ->
            current.filterValues { now - it.timestamp < thresholdMs }
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
        _memberLocations.update { current -> current.filterKeys { it != memberId } }
        locationHistoryRepository.deleteDataForMember(memberId)
    }

    /**
     * Apply retention policy to clean up old data.
     */
    suspend fun applyRetentionPolicy(retentionDays: Long = 30L) {
        locationHistoryRepository.applyRetentionPolicy(retentionDays)
    }
}

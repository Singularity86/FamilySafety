package com.example.familysafety.location

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocationRepository @Inject constructor() {

    private val _memberLocations = MutableStateFlow<Map<String, MemberLocation>>(emptyMap())
    val memberLocations: StateFlow<Map<String, MemberLocation>> = _memberLocations.asStateFlow()

    private val _myLocation = MutableStateFlow<MemberLocation?>(null)
    val myLocation: StateFlow<MemberLocation?> = _myLocation.asStateFlow()

    fun updateMemberLocation(location: MemberLocation) {
        _memberLocations.value = _memberLocations.value.toMutableMap().apply {
            put(location.memberId, location)
        }
    }

    fun updateMyLocation(location: MemberLocation) {
        _myLocation.value = location
        updateMemberLocation(location)
    }

    fun removeStaleLocations(thresholdMs: Long = 24 * 60 * 60 * 1000) {
        val now = System.currentTimeMillis()
        _memberLocations.value = _memberLocations.value.filterValues {
            now - it.timestamp < thresholdMs
        }
    }

    fun getLocationForMember(memberId: String): MemberLocation? {
        return _memberLocations.value[memberId]
    }

    fun clearAllLocations() {
        _memberLocations.value = emptyMap()
        _myLocation.value = null
    }
}

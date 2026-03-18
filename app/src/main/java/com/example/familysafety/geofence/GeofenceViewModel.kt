package com.example.familysafety.geofence

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class GeofenceViewModel @Inject constructor(
    private val geofenceRepository: GeofenceRepository,
    private val groupStateManager: GroupStateManager
) : ViewModel() {

    val geofences: StateFlow<List<GeofenceZone>> = geofenceRepository.geofences
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val familyMembers: StateFlow<List<FamilyMember>> = groupStateManager.groupDefinition
        .map { it?.members?.toList() ?: emptyList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Pending geo point set by map long-press, read by the editor when creating a new zone
    private val _pendingGeoPoint = MutableStateFlow<Pair<Double, Double>?>(null)
    val pendingGeoPoint: StateFlow<Pair<Double, Double>?> = _pendingGeoPoint.asStateFlow()

    fun setPendingGeoPoint(lat: Double, lng: Double) {
        _pendingGeoPoint.value = Pair(lat, lng)
    }

    fun clearPendingGeoPoint() {
        _pendingGeoPoint.value = null
    }

    fun addGeofence(zone: GeofenceZone) {
        viewModelScope.launch { geofenceRepository.add(zone) }
    }

    fun updateGeofence(zone: GeofenceZone) {
        viewModelScope.launch { geofenceRepository.update(zone) }
    }

    fun deleteGeofence(id: String) {
        viewModelScope.launch { geofenceRepository.delete(id) }
    }
}

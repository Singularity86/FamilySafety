package com.example.familysafety.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.sync.GroupSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val groupStateManager: GroupStateManager,
    private val locationRepository: LocationRepository,
    val groupSyncManager: GroupSyncManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val familyMembers: StateFlow<List<FamilyMember>> = groupStateManager.groupDefinition
        .map { it?.members?.toList() ?: emptyList() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val memberLocations: StateFlow<Map<String, MemberLocation>> = 
        locationRepository.memberLocations
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    val myLocation: StateFlow<MemberLocation?> = 
        locationRepository.myLocation
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )

    val groupName: StateFlow<String> = groupStateManager.groupDefinition
        .map { it?.groupName ?: "" }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    init {
        viewModelScope.launch {
            locationRepository.removeStaleLocations()
        }
    }

    suspend fun getMnemonic(): List<String> = withContext(Dispatchers.IO) {
        try {
            AndroidKeyStoreLocalKeyStore(context).getMnemonic() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

package com.example.familysafety.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.invite.JoinRequest
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
    private val inviteManager: InviteManager,
    private val localMemberId: LocalMemberId,
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

    // The local device's member ID — used by the UI to label "You"
    val myMemberId: String get() = localMemberId.value

    // Pending join requests from other users wanting to join this family
    val pendingJoinRequests: StateFlow<List<JoinRequest>> = inviteManager.pendingJoinRequests

    // Member the user wants to locate on the map (set from Members tab)
    private val _focusedMemberId = MutableStateFlow<String?>(null)
    val focusedMemberId: StateFlow<String?> = _focusedMemberId.asStateFlow()

    fun focusOnMember(memberId: String) {
        _focusedMemberId.value = memberId
    }

    fun clearFocus() {
        _focusedMemberId.value = null
    }

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

    suspend fun generateInviteCode(): Result<String> = withContext(Dispatchers.IO) {
        inviteManager.generateInviteCode()
    }

    fun approveJoinRequest(request: JoinRequest) {
        viewModelScope.launch {
            inviteManager.approveJoinRequest(request)
        }
    }

    fun rejectJoinRequest(request: JoinRequest) {
        viewModelScope.launch {
            inviteManager.rejectJoinRequest(request)
        }
    }
}

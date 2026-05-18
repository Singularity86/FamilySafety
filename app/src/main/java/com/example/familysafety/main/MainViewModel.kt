package com.example.familysafety.main

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.backup.BackupManager
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.Bip39
import com.example.familysafety.group.EncryptedGroupStatePersistence
import com.example.familysafety.group.ConnectionState
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.transport.MqttTransport
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.storage.LocationPublishOutboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val groupStateManager: GroupStateManager,
    private val locationRepository: LocationRepository,
    val groupSyncManager: GroupSyncManager,
    private val inviteManager: InviteManager,
    private val localMemberId: LocalMemberId,
    private val avatarRepository: AvatarRepository,
    private val locationPublishOutboxRepository: LocationPublishOutboxRepository,
    private val mqttTransport: MqttTransport,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class ConnectionMode { LAN, RELAY, OFFLINE }

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

    val connectionMode: StateFlow<ConnectionMode> = combine(
        groupStateManager.memberStates,
        mqttTransport.connectionState
    ) { members, mqttState ->
        val hasLanPeer = members.values
            .filter { it.member.memberId != localMemberId.value }
            .any { it.connectionState is ConnectionState.Local }
        when {
            hasLanPeer -> ConnectionMode.LAN
            mqttState is MqttTransport.ConnectionState.Connected -> ConnectionMode.RELAY
            else -> ConnectionMode.OFFLINE
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionMode.OFFLINE
    )

    // Pending join requests from other users wanting to join this family
    val pendingJoinRequests: StateFlow<List<JoinRequest>> = inviteManager.pendingJoinRequests

    // Avatar bitmaps keyed by memberId — null means no avatar set
    val memberAvatars: StateFlow<Map<String, Bitmap?>> = avatarRepository.memberAvatars
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyMap()
        )

    /** Replace the current user's avatar with the image at [uri]. */
    fun setMyAvatar(uri: Uri) {
        viewModelScope.launch {
            avatarRepository.setMyAvatar(uri)
        }
    }

    // Pending URI waiting to be cropped before saving as avatar
    private val _pendingCropUri = MutableStateFlow<Uri?>(null)
    val pendingCropUri: StateFlow<Uri?> = _pendingCropUri.asStateFlow()

    fun setPendingCropUri(uri: Uri?) { _pendingCropUri.value = uri }
    fun clearPendingCropUri() { _pendingCropUri.value = null }

    /** Save an already-cropped [Bitmap] as the current user's avatar. */
    fun setMyAvatarBitmap(bitmap: Bitmap) {
        viewModelScope.launch {
            avatarRepository.setMyAvatarFromBitmap(bitmap)
        }
    }

    /** The current user's chosen color hue (null = auto from memberId hash). */
    val myColorHue: StateFlow<Float?> = groupStateManager.groupDefinition
        .map { it?.findMemberById(localMemberId.value)?.colorHue }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Update the current user's display name and broadcast to the group. */
    fun updateMyDisplayName(name: String) {
        val sanitized = com.example.familysafety.core.DataValidator.sanitizeDisplayName(name)
        if (sanitized.isBlank()) return
        viewModelScope.launch {
            val result = groupStateManager.updateMyDisplayName(sanitized)
            if (result is com.example.familysafety.group.GroupOperationResult.Success) {
                groupSyncManager.broadcastGroupUpdate(
                    result.value,
                    com.example.familysafety.sync.ChangeType.VERSION_SYNC
                )
            }
        }
    }

    /** Persist a new color hue for the current user and broadcast to the group. */
    fun updateMyColorHue(hue: Float) {
        viewModelScope.launch {
            val result = groupStateManager.updateMyColorHue(hue)
            if (result is com.example.familysafety.group.GroupOperationResult.Success) {
                groupSyncManager.broadcastGroupUpdate(
                    result.value,
                    com.example.familysafety.sync.ChangeType.VERSION_SYNC
                )
            }
        }
    }

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

    /** Export an encrypted backup file and return a share-ready [Uri], or null on failure. */
    suspend fun exportBackup(password: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val mnemonic = AndroidKeyStoreLocalKeyStore(context).getMnemonic()
                ?: return@withContext null
            val groupDef = EncryptedGroupStatePersistence.getInstance(context).loadGroupDefinition()
                ?: return@withContext null
            val groupDefJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .encodeToString(groupDef)
            val bytes = BackupManager.export(mnemonic, groupDefJson, password)

            val dir = File(context.getExternalFilesDir(null), "familysafety_files")
            dir.mkdirs()
            val file = File(dir, "familysafety_backup.familysafe")
            file.writeBytes(bytes)

            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    sealed class ImportResult {
        object Success : ImportResult()
        data class Error(val message: String) : ImportResult()
    }

    /** Decrypt a backup, restore keys + group state, and mark onboarding complete. */
    suspend fun importBackup(uri: Uri, password: String): ImportResult = withContext(Dispatchers.IO) {
        try {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            val payload = BackupManager.import(bytes, password)

            val words = payload.mnemonic.trim().split("\\s+".toRegex())
            val seed = Bip39.mnemonicToSeed(payload.mnemonic)
            val keyStore = AndroidKeyStoreLocalKeyStore(context)
            keyStore.initializeFromSeed(seed, accountIndex = 0)
            keyStore.storeMnemonic(words)

            val groupDef = Json { ignoreUnknownKeys = true; encodeDefaults = true }
                .decodeFromString<GroupDefinition>(payload.groupDefinitionJson)
            EncryptedGroupStatePersistence.getInstance(context).saveGroupDefinition(groupDef)

            context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("onboarding_complete", true).commit()

            ImportResult.Success
        } catch (e: javax.crypto.BadPaddingException) {
            ImportResult.Error("Incorrect password")
        } catch (e: Exception) {
            ImportResult.Error(e.message ?: "Import failed")
        }
    }

    /**
     * Wipe all local group data and cryptographic keys so the app returns to
     * the onboarding screen.  The caller is responsible for restarting the
     * process afterwards.
     */
    suspend fun leaveFamily(): Unit = withContext(Dispatchers.IO) {
        try {
            EncryptedGroupStatePersistence.getInstance(context).deleteGroupDefinition()
        } catch (_: Exception) {}
        try {
            AndroidKeyStoreLocalKeyStore(context).destroyKeys()
        } catch (_: Exception) {}
        try {
            locationPublishOutboxRepository.clearForMember(localMemberId.value)
        } catch (_: Exception) {}
        // Remove the location-service member ID so it doesn't resume tracking on restart
        context.getSharedPreferences("location_service", Context.MODE_PRIVATE)
            .edit().remove("member_id").commit()
        // Clear the onboarding flag — causes MainActivity to show onboarding on next launch
        context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
            .edit().remove("onboarding_complete").commit()
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

    fun requestGroupStateRefresh() {
        viewModelScope.launch {
            val groupDef = groupStateManager.groupDefinition.value ?: return@launch
            groupSyncManager.requestGroupStateRefresh(
                reason = "manual_refresh",
                minimumVersion = groupDef.version.toInt()
            )
        }
    }

    /**
     * Remove a member from the group and broadcast the change to all remaining members.
     * Any member can remove any other member (including ghost/failed-invite members).
     */
    fun removeMember(memberId: String) {
        viewModelScope.launch {
            val result = groupStateManager.removeMemberByLocal(memberId)
            if (result is com.example.familysafety.group.GroupOperationResult.Success) {
                groupSyncManager.broadcastGroupUpdate(
                    result.value,
                    com.example.familysafety.sync.ChangeType.MEMBER_REMOVED,
                    memberId
                )
            }
        }
    }
}

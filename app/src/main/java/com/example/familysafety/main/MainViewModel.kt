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
import com.example.familysafety.group.GroupDefinition
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

    /** The current user's chosen color hue (null = auto from memberId hash). */
    val myColorHue: StateFlow<Float?> = groupStateManager.groupDefinition
        .map { it?.findMemberById(localMemberId.value)?.colorHue }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

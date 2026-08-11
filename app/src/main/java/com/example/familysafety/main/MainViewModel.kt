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
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.Bip39
import com.example.familysafety.group.EncryptedGroupStatePersistence
import com.example.familysafety.group.ConnectionState
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.group.PresenceStatus
import com.example.familysafety.transport.MessageProtocol
import com.example.familysafety.transport.MqttTransport
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.MemberLocation
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.routing.RoutingService
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.storage.LocationPublishOutboxRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    private val appInitializer: com.example.familysafety.AppInitializer,
    private val avatarRepository: AvatarRepository,
    private val locationPublishOutboxRepository: LocationPublishOutboxRepository,
    private val mqttTransport: MqttTransport,
    private val securityEventRepository: SecurityEventRepository,
    private val networkMonitor: NetworkMonitor,
    private val routingService: RoutingService,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class ConnectionMode { LAN, MIXED, RELAY, OFFLINE }

    sealed class KeySyncRequestState {
        data object Idle : KeySyncRequestState()
        data object Sending : KeySyncRequestState()
        data class Requested(
            val peerCount: Int,
            val sentCount: Int,
            val failedCount: Int,
            val version: Long,
            val requestedAtMs: Long
        ) : KeySyncRequestState()
        data class Updated(
            val fromVersion: Long,
            val toVersion: Long
        ) : KeySyncRequestState()
        data class NoNewerUpdate(
            val peerCount: Int,
            val sentCount: Int,
            val failedCount: Int,
            val version: Long
        ) : KeySyncRequestState()
        data object NoPeers : KeySyncRequestState()
        data class Error(val message: String) : KeySyncRequestState()
    }

    data class RouteHealth(
        val localPeerCount: Int = 0,
        val totalPeerCount: Int = 0,
        val hasRelay: Boolean = false
    )

    data class MemberRouteStatus(
        val memberId: String,
        /**
         * How this device is reaching the member ("Local"/"Relay"), or null when the
         * route has not been established yet. Null means "we don't know", NOT "broken" —
         * updates can be arriving fine while the connection state is still Unknown, so
         * callers must omit the route rather than inventing a placeholder for it.
         */
        val routeLabel: String? = null,
        val lastSeenMs: Long? = null,
        val lastLocationUpdateMs: Long? = null,
        val isRecentlyActive: Boolean = false
    )

    sealed class DriveEstimateState {
        data object Hidden : DriveEstimateState()
        data class Loading(
            val memberId: String,
            val memberName: String
        ) : DriveEstimateState()
        data class Ready(
            val memberId: String,
            val memberName: String,
            val distanceMeters: Double,
            val durationSeconds: Double,
            val locationTimestamp: Long
        ) : DriveEstimateState()
        data class Error(
            val memberId: String,
            val memberName: String,
            val message: String
        ) : DriveEstimateState()
    }

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

    val groupDefinition: StateFlow<GroupDefinition?> = groupStateManager.groupDefinition
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val mqttState: StateFlow<MqttTransport.ConnectionState> = mqttTransport.connectionState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MqttTransport.ConnectionState.Disconnected)

    val deviceNetworkAvailable: StateFlow<Boolean> = networkMonitor.isNetworkAvailable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), networkMonitor.isCurrentlyConnected())

    val syncState: StateFlow<GroupSyncManager.SyncState> = groupSyncManager.syncState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GroupSyncManager.SyncState.Idle)

    val decryptFailures: StateFlow<Map<String, SecurityEventRepository.DecryptStats>> =
        securityEventRepository.decryptFailures
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Members running a wire format older than this build. They cannot exchange files or
     * presence with us until they update, and without saying so the UI would just show
     * them as permanently absent.
     *
     * A member absent from the map has not been heard from yet — unknown, not outdated —
     * so they are excluded rather than assumed stale.
     */
    val outdatedMembers: StateFlow<Set<String>> = mqttTransport.peerProtocolVersions
        .map { versions ->
            versions.filterValues { it < MessageProtocol.PROTOCOL_VERSION }.keys
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    private val _keySyncRequestState = MutableStateFlow<KeySyncRequestState>(KeySyncRequestState.Idle)
    val keySyncRequestState: StateFlow<KeySyncRequestState> = _keySyncRequestState.asStateFlow()

    private val _driveEstimateState =
        MutableStateFlow<DriveEstimateState>(DriveEstimateState.Hidden)
    val driveEstimateState: StateFlow<DriveEstimateState> = _driveEstimateState.asStateFlow()
    private var driveEstimateJob: kotlinx.coroutines.Job? = null

    fun requestDriveEstimate(memberId: String) {
        val member = familyMembers.value.firstOrNull { it.memberId == memberId } ?: return
        val destination = memberLocations.value[memberId]
        val origin = selectDriveEstimateOrigin(
            liveLocation = myLocation.value,
            cachedLocalLocation = memberLocations.value[localMemberId.value]
        )

        driveEstimateJob?.cancel()
        if (destination == null) {
            _driveEstimateState.value = DriveEstimateState.Error(
                memberId,
                member.displayName,
                "No location is available for this member yet."
            )
            return
        }
        if (origin == null) {
            _driveEstimateState.value = DriveEstimateState.Error(
                memberId,
                member.displayName,
                "Your current location is not available yet."
            )
            return
        }

        _driveEstimateState.value = DriveEstimateState.Loading(memberId, member.displayName)
        driveEstimateJob = viewModelScope.launch {
            try {
                val estimate = routingService.getDriveEstimate(
                    startLatitude = origin.latitude,
                    startLongitude = origin.longitude,
                    destinationLatitude = destination.latitude,
                    destinationLongitude = destination.longitude
                )
                _driveEstimateState.value = DriveEstimateState.Ready(
                    memberId = memberId,
                    memberName = member.displayName,
                    distanceMeters = estimate.distanceMeters,
                    durationSeconds = estimate.durationSeconds,
                    locationTimestamp = destination.timestamp
                )
            } catch (e: Exception) {
                _driveEstimateState.value = DriveEstimateState.Error(
                    memberId,
                    member.displayName,
                    "Drive time is unavailable right now. Check your connection and try again."
                )
            }
        }
    }

    fun dismissDriveEstimate() {
        driveEstimateJob?.cancel()
        driveEstimateJob = null
        _driveEstimateState.value = DriveEstimateState.Hidden
    }

    val connectionMode: StateFlow<ConnectionMode> = combine(
        groupStateManager.memberStates,
        mqttTransport.connectionState
    ) { members, mqttState ->
        val peerStates = members.values
            .filter { it.member.memberId != localMemberId.value }
        val hasLanPeer = peerStates.any { it.connectionState is ConnectionState.Local }
        val hasNonLanPeer = peerStates.any { it.connectionState !is ConnectionState.Local }
        val relayConnected = mqttState is MqttTransport.ConnectionState.Connected
        when {
            hasLanPeer && hasNonLanPeer && relayConnected -> ConnectionMode.MIXED
            hasLanPeer -> ConnectionMode.LAN
            relayConnected -> ConnectionMode.RELAY
            else -> ConnectionMode.OFFLINE
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionMode.OFFLINE
    )

    val routeHealth: StateFlow<RouteHealth> = combine(
        groupStateManager.memberStates,
        mqttTransport.connectionState
    ) { members, mqttState ->
        val peerStates = members.values
            .filter { it.member.memberId != localMemberId.value }
        RouteHealth(
            localPeerCount = peerStates.count { it.connectionState is ConnectionState.Local },
            totalPeerCount = peerStates.size,
            hasRelay = mqttState is MqttTransport.ConnectionState.Connected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = RouteHealth()
    )

    val memberRouteStatuses: StateFlow<Map<String, MemberRouteStatus>> =
        groupStateManager.memberStates
            .map { states ->
                states.mapValues { (memberId, state) ->
                    // Unknown maps to null, not to a label. It only means no presence has
                    // pinned the route down yet; location updates routinely decrypt fine in
                    // that state, and labelling it "Waiting" made working members read as
                    // stuck.
                    val route = when (state.connectionState) {
                        is ConnectionState.Local -> "Local"
                        is ConnectionState.Cloud -> "Relay"
                        ConnectionState.Offline -> "Offline"
                        ConnectionState.Unknown -> null
                    }
                    MemberRouteStatus(
                        memberId = memberId,
                        routeLabel = route,
                        lastSeenMs = state.lastSeenEpochMs,
                        lastLocationUpdateMs = state.lastLocationUpdateEpochMs,
                        isRecentlyActive = state.presenceStatus == PresenceStatus.ACTIVE
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

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
     * Leave the family group: broadcast an authorized self-removal to the other
     * members (so they stop encrypting to our key and drop us from their rosters),
     * then wipe all local group data and cryptographic keys so the app returns to
     * the onboarding screen.  The caller is responsible for restarting the
     * process afterwards.
     *
     * The broadcast is best effort with a bounded wait — leaving must always
     * succeed locally even when offline (peers then never learn of the departure
     * until the group creator removes the stale member manually).
     */
    suspend fun leaveFamily(): Unit = withContext(Dispatchers.IO) {
        // Our own self-removal emits RemovedFromGroup; stop AppInitializer's
        // remote-removal handler from firing its notification and competing wipe.
        appInitializer.suppressRemovalHandling()

        try {
            val removal = groupStateManager.removeMemberByLocal(localMemberId.value)
            if (removal is com.example.familysafety.group.GroupOperationResult.Success) {
                // Peers accept this under the creator-or-self rule in
                // GroupTransitionValidator. Publishes complete quickly; the timeout
                // only cuts short the ack-polling that follows them.
                kotlinx.coroutines.withTimeoutOrNull(5_000) {
                    groupSyncManager.broadcastGroupUpdate(
                        removal.value,
                        com.example.familysafety.sync.ChangeType.MEMBER_REMOVED,
                        localMemberId.value
                    )
                }
            } else {
                timber.log.Timber.w("leaveFamily: self-removal not broadcast ($removal) — wiping locally anyway")
            }
        } catch (e: Exception) {
            timber.log.Timber.w(e, "leaveFamily: failed to broadcast departure — wiping locally anyway")
        }

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
            val groupDef = groupStateManager.groupDefinition.value
            if (groupDef == null) {
                _keySyncRequestState.value = KeySyncRequestState.Error("No family state on this device")
                return@launch
            }

            val peerCount = groupDef.members.count { it.memberId != localMemberId.value }
            if (peerCount == 0) {
                _keySyncRequestState.value = KeySyncRequestState.NoPeers
                return@launch
            }

            val startingVersion = groupDef.version
            _keySyncRequestState.value = KeySyncRequestState.Sending
            try {
                val requestResult = groupSyncManager.requestGroupStateRefresh(
                    reason = "manual_refresh",
                    minimumVersion = startingVersion
                )
                _keySyncRequestState.value = KeySyncRequestState.Requested(
                    peerCount = requestResult.peerCount.coerceAtLeast(peerCount),
                    sentCount = requestResult.sentCount,
                    failedCount = requestResult.failedCount,
                    version = startingVersion,
                    requestedAtMs = System.currentTimeMillis()
                )

                delay(8_000)
                val currentState = _keySyncRequestState.value
                val currentVersion = groupStateManager.groupDefinition.value?.version ?: startingVersion
                if (currentState is KeySyncRequestState.Requested &&
                    currentState.version == startingVersion
                ) {
                    _keySyncRequestState.value = if (currentVersion > startingVersion) {
                        KeySyncRequestState.Updated(startingVersion, currentVersion)
                    } else {
                        KeySyncRequestState.NoNewerUpdate(
                            peerCount = currentState.peerCount,
                            sentCount = currentState.sentCount,
                            failedCount = currentState.failedCount,
                            version = startingVersion
                        )
                    }
                }
            } catch (e: Exception) {
                _keySyncRequestState.value = KeySyncRequestState.Error(
                    e.message ?: "Could not request family sync"
                )
            }
        }
    }

    fun clearRecoveredDecryptFailures() {
        securityEventRepository.clearRecoveredFailures()
    }

    /**
     * Remove a member from the group and broadcast the change to all remaining members.
     * Authorization (enforced locally in GroupStateManager.removeMember and remotely by
     * GroupTransitionValidator on every peer): only the group creator may remove others;
     * any member may remove themselves.
     */
    fun removeMember(memberId: String) {
        viewModelScope.launch {
            when (val result = groupStateManager.removeMemberByLocal(memberId)) {
                is com.example.familysafety.group.GroupOperationResult.Success -> {
                    groupSyncManager.broadcastGroupUpdate(
                        result.value,
                        com.example.familysafety.sync.ChangeType.MEMBER_REMOVED,
                        memberId
                    )
                }
                is com.example.familysafety.group.GroupOperationResult.Failure -> {
                    timber.log.Timber.w("removeMember(${memberId.take(8)}) rejected: ${result.error}")
                }
            }
        }
    }
}

internal fun selectDriveEstimateOrigin(
    liveLocation: MemberLocation?,
    cachedLocalLocation: MemberLocation?
): MemberLocation? = liveLocation ?: cachedLocalLocation

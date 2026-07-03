package com.example.familysafety.group

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * GroupStateManager: Central orchestrator for family group membership and state.
 *
 * Responsibilities:
 * - Maintain the canonical group definition (members, keys, metadata)
 * - Track runtime state of each member (connection, presence)
 * - Emit events for state changes
 * - Coordinate with persistence layer for durable storage
 * - Validate cryptographic proofs for membership changes
 *
 * Thread Safety:
 * - All state mutations protected by Mutex
 * - External access via immutable StateFlow emissions
 *
 * @property localMemberId The memberId of this device's owner
 * @property persistence Storage adapter for durable group state
 * @property cryptoProvider Cryptographic operations (signature verification, etc.)
 */
class GroupStateManager(
    private val localMemberId: String,
    private val persistence: GroupStatePersistence,
    private val cryptoProvider: CryptoProvider
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateMutex = Mutex()

    // Persisted group definition (null if not yet part of a group)
    private val _groupDefinition = MutableStateFlow<GroupDefinition?>(null)
    val groupDefinition: StateFlow<GroupDefinition?> = _groupDefinition.asStateFlow()

    // Runtime state for all members (keyed by memberId)
    private val _memberStates = MutableStateFlow<Map<String, MemberRuntimeState>>(emptyMap())
    val memberStates: StateFlow<Map<String, MemberRuntimeState>> = _memberStates.asStateFlow()

    // Event stream for UI/service layer
    private val _events = MutableSharedFlow<GroupStateEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )
    val events: SharedFlow<GroupStateEvent> = _events.asSharedFlow()

    // Convenience flows
    val isInGroup: StateFlow<Boolean> = _groupDefinition
        .map { it != null }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val localMember: StateFlow<FamilyMember?> = _groupDefinition
        .map { it?.findMemberById(localMemberId) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val onlineMembers: StateFlow<List<MemberRuntimeState>> = _memberStates
        .map { states ->
            states.values.filter {
                it.connectionState !is ConnectionState.Offline &&
                        it.connectionState !is ConnectionState.Unknown
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Initialize manager by loading persisted state.
     * Must be called before any other operations.
     */
    suspend fun initialize(): GroupOperationResult<Unit> {
        return stateMutex.withLock {
            try {
                val persisted = persistence.loadGroupDefinition()
                if (persisted != null) {
                    _groupDefinition.value = persisted
                    initializeMemberRuntimeStates(persisted)
                }
                GroupOperationResult.Success(Unit)
            } catch (e: Exception) {
                GroupOperationResult.Failure(GroupError.StorageError)
            }
        }
    }

    // =========================================================================
    // GROUP CREATION
    // =========================================================================

    /**
     * Create a new family group with this device's owner as the creator.
     *
     * @param groupName Display name for the group
     * @param localMember The FamilyMember object for this device's owner
     */
    suspend fun createGroup(
        groupName: String,
        localMember: FamilyMember
    ): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            // Prevent creating a group if already in one
            if (_groupDefinition.value != null) {
                return@withLock GroupOperationResult.Failure(GroupError.MemberAlreadyExists)
            }

            val now = System.currentTimeMillis()
            val groupId = generateGroupId()

            val newGroup = GroupDefinition(
                groupId = groupId,
                groupName = groupName,
                createdAtEpochMs = now,
                creatorMemberId = localMember.memberId,
                members = setOf(localMember),
                version = 1,
                previousStateHash = null
            )

            // Persist before updating in-memory state
            try {
                persistence.saveGroupDefinition(newGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            _groupDefinition.value = newGroup
            initializeMemberRuntimeStates(newGroup)

            GroupOperationResult.Success(newGroup)
        }
    }

    // =========================================================================
    // MEMBER MANAGEMENT
    // =========================================================================

    /**
     * Add a new member to the family group.
     * Only existing members can add new members.
     *
     * @param newMember The FamilyMember to add
     * @param inviterSignature Ed25519 signature from an existing member approving the addition
     * @param inviterMemberId MemberId of the existing member who invited
     */
    suspend fun addMember(
        newMember: FamilyMember,
        inviterSignature: ByteArray,
        inviterMemberId: String
    ): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            val currentGroup = _groupDefinition.value
                ?: return@withLock GroupOperationResult.Failure(GroupError.NotGroupMember)

            // Verify inviter is a current member
            val inviter = currentGroup.findMemberById(inviterMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            // Prevent duplicate additions
            if (currentGroup.containsMember(newMember.memberId)) {
                return@withLock GroupOperationResult.Failure(GroupError.MemberAlreadyExists)
            }

            // Verify cryptographic approval
            val approvalMessage = buildMemberAdditionApprovalMessage(
                groupId = currentGroup.groupId,
                groupVersion = currentGroup.version,
                newMemberPublicKey = newMember.ed25519PublicKey
            )

            val signatureValid = cryptoProvider.verifySignature(
                message = approvalMessage,
                signature = inviterSignature,
                publicKeyHex = inviter.ed25519PublicKey
            )

            if (!signatureValid) {
                return@withLock GroupOperationResult.Failure(GroupError.InvalidSignature)
            }

            // Create updated group definition
            val updatedGroup = currentGroup.copy(
                members = currentGroup.members + newMember.copy(
                    addedByMemberId = inviterMemberId,
                    addedAtEpochMs = System.currentTimeMillis()
                ),
                version = currentGroup.version + 1,
                previousStateHash = currentGroup.computeStateHash()
            )

            // Persist
            try {
                persistence.saveGroupDefinition(updatedGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            _groupDefinition.value = updatedGroup
            addMemberRuntimeState(newMember)

            // Emit event
            _events.emit(GroupStateEvent.MemberAdded(newMember, inviter))

            GroupOperationResult.Success(updatedGroup)
        }
    }

    /**
     * Remove a member from the family group.
     * Only the group creator can remove members (except self-removal).
     *
     * @param targetMemberId MemberId to remove
     * @param removerSignature Signature authorizing removal
     * @param removerMemberId MemberId of who is removing
     */
    suspend fun removeMember(
        targetMemberId: String,
        removerSignature: ByteArray,
        removerMemberId: String
    ): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            val currentGroup = _groupDefinition.value
                ?: return@withLock GroupOperationResult.Failure(GroupError.NotGroupMember)

            val targetMember = currentGroup.findMemberById(targetMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            val remover = currentGroup.findMemberById(removerMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            // Only the group creator or the member themselves may remove a member.
            if (removerMemberId != currentGroup.creatorMemberId && removerMemberId != targetMemberId) {
                return@withLock GroupOperationResult.Failure(GroupError.NotGroupCreator)
            }

            // Verify signature
            val removalMessage = buildMemberRemovalApprovalMessage(
                groupId = currentGroup.groupId,
                groupVersion = currentGroup.version,
                targetMemberId = targetMemberId
            )

            val signatureValid = cryptoProvider.verifySignature(
                message = removalMessage,
                signature = removerSignature,
                publicKeyHex = remover.ed25519PublicKey
            )

            if (!signatureValid) {
                return@withLock GroupOperationResult.Failure(GroupError.InvalidSignature)
            }

            // Create updated group
            val updatedGroup = currentGroup.copy(
                members = currentGroup.members.filter { it.memberId != targetMemberId }.toSet(),
                version = currentGroup.version + 1,
                previousStateHash = currentGroup.computeStateHash()
            )

            // Persist
            try {
                persistence.saveGroupDefinition(updatedGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            _groupDefinition.value = updatedGroup
            removeMemberRuntimeState(targetMemberId)

            // Emit event
            _events.emit(GroupStateEvent.MemberRemoved(targetMember, remover))

            // Special case: we were removed
            if (targetMemberId == localMemberId) {
                _events.emit(GroupStateEvent.RemovedFromGroup)
            }

            GroupOperationResult.Success(updatedGroup)
        }
    }

    /**
     * Remove a member using this device's own key as authorization.
     * Generates the signature internally so callers don't need access to the crypto provider.
     */
    suspend fun removeMemberByLocal(targetMemberId: String): GroupOperationResult<GroupDefinition> {
        val currentGroup = _groupDefinition.value
            ?: return GroupOperationResult.Failure(GroupError.NotGroupMember)
        val removalMessage = buildMemberRemovalApprovalMessage(
            groupId = currentGroup.groupId,
            groupVersion = currentGroup.version,
            targetMemberId = targetMemberId
        )
        val signature = cryptoProvider.sign(removalMessage)
        return removeMember(targetMemberId, signature, localMemberId)
    }

    // =========================================================================
    // AVATAR HASH UPDATE
    // =========================================================================

    /**
     * Update the local member's avatarHash in the group definition.
     * Called after the user picks a new avatar; triggers a group sync broadcast.
     */
    suspend fun updateLocalMemberAvatarHash(hash: String?): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            val currentGroup = _groupDefinition.value
                ?: return@withLock GroupOperationResult.Failure(GroupError.NotGroupMember)

            val localMemberObj = currentGroup.findMemberById(localMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            val updatedGroup = currentGroup.copy(
                members = (currentGroup.members - localMemberObj) +
                        localMemberObj.copy(avatarHash = hash),
                version = currentGroup.version + 1,
                previousStateHash = currentGroup.computeStateHash()
            )

            try {
                persistence.saveGroupDefinition(updatedGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            val old = _groupDefinition.value!!
            _groupDefinition.value = updatedGroup
            _events.emit(GroupStateEvent.GroupUpdated(old, updatedGroup))

            GroupOperationResult.Success(updatedGroup)
        }
    }

    suspend fun updateMyDisplayName(name: String): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            val currentGroup = _groupDefinition.value
                ?: return@withLock GroupOperationResult.Failure(GroupError.NotGroupMember)

            val localMemberObj = currentGroup.findMemberById(localMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            val updatedGroup = currentGroup.copy(
                members = (currentGroup.members - localMemberObj) +
                        localMemberObj.copy(displayName = name),
                version = currentGroup.version + 1,
                previousStateHash = currentGroup.computeStateHash()
            )

            try {
                persistence.saveGroupDefinition(updatedGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            val old = _groupDefinition.value!!
            _groupDefinition.value = updatedGroup
            _events.emit(GroupStateEvent.GroupUpdated(old, updatedGroup))

            GroupOperationResult.Success(updatedGroup)
        }
    }

    suspend fun updateMyColorHue(hue: Float): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            val currentGroup = _groupDefinition.value
                ?: return@withLock GroupOperationResult.Failure(GroupError.NotGroupMember)

            val localMemberObj = currentGroup.findMemberById(localMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            val updatedGroup = currentGroup.copy(
                members = (currentGroup.members - localMemberObj) +
                        localMemberObj.copy(colorHue = hue),
                version = currentGroup.version + 1,
                previousStateHash = currentGroup.computeStateHash()
            )

            try {
                persistence.saveGroupDefinition(updatedGroup)
            } catch (e: Exception) {
                return@withLock GroupOperationResult.Failure(GroupError.StorageError)
            }

            val old = _groupDefinition.value!!
            _groupDefinition.value = updatedGroup
            _events.emit(GroupStateEvent.GroupUpdated(old, updatedGroup))

            GroupOperationResult.Success(updatedGroup)
        }
    }

    // =========================================================================
    // RUNTIME STATE MANAGEMENT
    // =========================================================================

    /**
     * Update a member's connection state.
     * Called by NSD discovery or MQTT connection layer.
     */
    suspend fun updateConnectionState(
        memberId: String,
        newState: ConnectionState
    ) {
        val event = stateMutex.withLock {
            val currentStates = _memberStates.value.toMutableMap()
            val currentMemberState = currentStates[memberId] ?: return@withLock null

            if (currentMemberState.connectionState == newState) return@withLock null

            val oldState = currentMemberState.connectionState
            currentStates[memberId] = currentMemberState.copy(
                connectionState = newState,
                lastSeenEpochMs = if (newState !is ConnectionState.Offline) {
                    System.currentTimeMillis()
                } else {
                    currentMemberState.lastSeenEpochMs
                }
            )

            _memberStates.value = currentStates

            // Return event to emit outside lock
            GroupStateEvent.ConnectionStateChanged(
                member = currentMemberState.member,
                oldState = oldState,
                newState = newState
            )
        }

        // Emit outside the lock
        event?.let { _events.emit(it) }
    }

    /**
     * Update a member's presence status.
     * Called when receiving presence updates from the member.
     */
    suspend fun updatePresenceStatus(
        memberId: String,
        newStatus: PresenceStatus
    ) {
        stateMutex.withLock {
            val currentStates = _memberStates.value.toMutableMap()
            val currentMemberState = currentStates[memberId] ?: return@withLock

            if (currentMemberState.presenceStatus == newStatus) return@withLock

            val oldStatus = currentMemberState.presenceStatus
            currentStates[memberId] = currentMemberState.copy(
                presenceStatus = newStatus,
                lastSeenEpochMs = System.currentTimeMillis()
            )

            _memberStates.value = currentStates

            _events.emit(
                GroupStateEvent.PresenceChanged(
                    member = currentMemberState.member,
                    oldStatus = oldStatus,
                    newStatus = newStatus
                )
            )
        }
    }

    /**
     * Mark member as having received a location update.
     */
    suspend fun recordLocationUpdate(memberId: String) {
        stateMutex.withLock {
            val currentStates = _memberStates.value.toMutableMap()
            val currentMemberState = currentStates[memberId] ?: return@withLock

            currentStates[memberId] = currentMemberState.copy(
                lastLocationUpdateEpochMs = System.currentTimeMillis()
            )

            _memberStates.value = currentStates
        }
    }

    // =========================================================================
    // GROUP STATE SYNCHRONIZATION
    // =========================================================================

    /**
     * Apply a group definition received from another member.
     * Handles version conflict resolution.
     *
     * @param remoteDefinition GroupDefinition received from peer
     * @param senderSignature Signature from the sender attesting to the state
     * @param senderMemberId Who sent this state update
     */
    suspend fun applyRemoteGroupState(
        remoteDefinition: GroupDefinition,
        senderSignature: ByteArray,
        senderMemberId: String
    ): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            // Verify sender is a member in the remote definition
            val sender = remoteDefinition.findMemberById(senderMemberId)
                ?: return@withLock GroupOperationResult.Failure(GroupError.MemberNotFound)

            // Verify signature over the remote state hash
            val stateHash = remoteDefinition.computeStateHash()
            val signatureValid = cryptoProvider.verifySignature(
                message = stateHash.toByteArray(),
                signature = senderSignature,
                publicKeyHex = sender.ed25519PublicKey
            )

            if (!signatureValid) {
                return@withLock GroupOperationResult.Failure(GroupError.InvalidSignature)
            }

            applyRemoteGroupStateLocked(remoteDefinition, senderMemberId)
        }
    }

    /**
     * Apply a group definition from a GroupSyncMessage whose envelope signature
     * has already been verified by GroupSyncManager.
     *
     * @param updaterMemberId The member whose signature was verified — used to
     *        authorize the transition (e.g. only the creator may remove others).
     */
    suspend fun applyVerifiedRemoteGroupState(
        remoteDefinition: GroupDefinition,
        updaterMemberId: String
    ): GroupOperationResult<GroupDefinition> {
        return stateMutex.withLock {
            applyRemoteGroupStateLocked(remoteDefinition, updaterMemberId)
        }
    }

    private suspend fun applyRemoteGroupStateLocked(
        remoteDefinition: GroupDefinition,
        updaterMemberId: String
    ): GroupOperationResult<GroupDefinition> {
        val currentGroup = _groupDefinition.value

        // Version conflict resolution
        if (currentGroup != null) {
            when {
                remoteDefinition.version < currentGroup.version -> {
                    // Remote is stale; ignore
                    return GroupOperationResult.Success(currentGroup)
                }
                remoteDefinition.version == currentGroup.version &&
                        remoteDefinition.computeStateHash() != currentGroup.computeStateHash() -> {
                    // Same version, different content = conflict
                    _events.emit(
                        GroupStateEvent.ConflictDetected(
                            localVersion = currentGroup.version,
                            remoteVersion = remoteDefinition.version
                        )
                    )
                    return GroupOperationResult.Failure(GroupError.VersionConflict)
                }
            }

            // Signature verification proved WHO sent this; now check they were
            // ALLOWED to make these changes relative to the state we hold.
            val rejection = GroupTransitionValidator.validate(
                current = currentGroup,
                remote = remoteDefinition,
                updaterMemberId = updaterMemberId
            )
            if (rejection != null) {
                return GroupOperationResult.Failure(GroupError.UnauthorizedChange(rejection))
            }
        }

        // Apply the remote state
        try {
            persistence.saveGroupDefinition(remoteDefinition)
        } catch (e: Exception) {
            return GroupOperationResult.Failure(GroupError.StorageError)
        }

        val oldDefinition = _groupDefinition.value
        _groupDefinition.value = remoteDefinition
        reinitializeMemberRuntimeStates(remoteDefinition)

        if (oldDefinition != null) {
            _events.emit(GroupStateEvent.GroupUpdated(oldDefinition, remoteDefinition))
        }

        // Check if we've been removed
        if (!remoteDefinition.containsMember(localMemberId)) {
            _events.emit(GroupStateEvent.RemovedFromGroup)
        }

        return GroupOperationResult.Success(remoteDefinition)
    }

    // =========================================================================
    // PRIVATE HELPERS
    // =========================================================================

    private fun initializeMemberRuntimeStates(group: GroupDefinition) {
        val states = group.members.associate { member ->
            member.memberId to MemberRuntimeState(
                member = member,
                connectionState = if (member.memberId == localMemberId) {
                    ConnectionState.Unknown  // Will be set by connection layer
                } else {
                    ConnectionState.Unknown
                },
                presenceStatus = if (member.memberId == localMemberId) {
                    PresenceStatus.ACTIVE
                } else {
                    PresenceStatus.OFFLINE
                }
            )
        }
        _memberStates.value = states
    }

    private fun addMemberRuntimeState(member: FamilyMember) {
        val currentStates = _memberStates.value.toMutableMap()
        currentStates[member.memberId] = MemberRuntimeState(
            member = member,
            connectionState = ConnectionState.Unknown,
            presenceStatus = PresenceStatus.OFFLINE
        )
        _memberStates.value = currentStates
    }

    private fun removeMemberRuntimeState(memberId: String) {
        val currentStates = _memberStates.value.toMutableMap()
        currentStates.remove(memberId)
        _memberStates.value = currentStates
    }

    private fun reinitializeMemberRuntimeStates(group: GroupDefinition) {
        // Preserve connection states for existing members where possible
        val oldStates = _memberStates.value
        val newStates = group.members.associate { member ->
            val existingState = oldStates[member.memberId]
            member.memberId to MemberRuntimeState(
                member = member,
                connectionState = existingState?.connectionState ?: ConnectionState.Unknown,
                presenceStatus = existingState?.presenceStatus ?: PresenceStatus.OFFLINE,
                lastSeenEpochMs = existingState?.lastSeenEpochMs,
                lastLocationUpdateEpochMs = existingState?.lastLocationUpdateEpochMs
            )
        }
        _memberStates.value = newStates
    }

    private fun generateGroupId(): String {
        return UUID.randomUUID().toString()
    }

    private fun buildMemberAdditionApprovalMessage(
        groupId: String,
        groupVersion: Long,
        newMemberPublicKey: String
    ): ByteArray {
        return "ADD:$groupId:$groupVersion:$newMemberPublicKey".toByteArray(Charsets.UTF_8)
    }

    private fun buildMemberRemovalApprovalMessage(
        groupId: String,
        groupVersion: Long,
        targetMemberId: String
    ): ByteArray {
        return "REMOVE:$groupId:$groupVersion:$targetMemberId".toByteArray(Charsets.UTF_8)
    }
}

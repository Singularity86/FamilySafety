package com.example.familysafety

import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.files.SharedFileRepository
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.LocalTransport
import com.example.familysafety.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles app initialization and wiring up dependencies.
 * Called after onboarding is complete and user is in a group.
 */
@Singleton
class AppInitializer @Inject constructor(
    private val groupStateManager: GroupStateManager,
    private val localMemberId: LocalMemberId,
    private val locationRepository: LocationRepository,
    private val mqttTransport: MqttTransport,
    private val localTransport: LocalTransport,
    private val replicationManager: ReplicationManager,
    private val chatRepository: ChatRepository,
    private val inviteManager: InviteManager,
    private val groupSyncManager: GroupSyncManager,
    private val avatarRepository: AvatarRepository,
    private val sharedFileRepository: SharedFileRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false

    companion object {
        private const val TAG = "AppInitializer"
    }

    /**
     * Initialize all app components.
     * Should be called once after the user has completed onboarding and is in a group.
     */
    fun initialize() {
        if (isInitialized) {
            Timber.d("$TAG: Already initialized")
            return
        }

        scope.launch {
            try {
                Timber.d("$TAG: Starting initialization...")

                // 1. Initialize GroupStateManager
                groupStateManager.initialize()
                Timber.d("$TAG: GroupStateManager initialized")

                // 2. Wait for group definition to be available (10 s timeout guards
                //    against persistence failures leaving the coroutine hung forever)
                val groupDef = withTimeoutOrNull(10_000) {
                    groupStateManager.groupDefinition.first { it != null }
                } ?: throw IllegalStateException("No group definition after initialization")
                Timber.d("$TAG: Group loaded: ${groupDef.groupName}")

                // 3. Initialize LocationRepository (loads history from database)
                locationRepository.initialize()
                Timber.d("$TAG: LocationRepository initialized")

                // 4. Wire up MqttTransport with all dependent managers
                mqttTransport.setReplicationManager(replicationManager)
                mqttTransport.setChatRepository(chatRepository)
                mqttTransport.setInviteManager(inviteManager)
                mqttTransport.setFileRepository(sharedFileRepository)
                mqttTransport.setLocalTransport(localTransport)
                mqttTransport.setGroupSyncManager(groupSyncManager)
                inviteManager.setMqttTransport(mqttTransport)
                Timber.d("$TAG: MQTT transport wired up")

                // 5. Set group ID for group-level subscriptions
                mqttTransport.setGroupId(groupDef.groupId)

                // 6. Initialize MQTT connection - Use the member ID injected from Hilt directly.
                val localMember = groupDef.findMemberById(localMemberId.value)
                    ?: throw IllegalStateException("Local member ${localMemberId.value} not found in group ${groupDef.groupId}")

                mqttTransport.initialize(
                    memberIdParam = localMemberId.value,
                    familyMembers = groupDef.members.toList(),
                    groupIdParam = groupDef.groupId
                )
                Timber.d("$TAG: MQTT transport initialized")

                // 6b. Start local WiFi transport (NSD registration + TCP server)
                localTransport.start(localMemberId.value)
                Timber.d("$TAG: LocalTransport started")

                // 7. Initialize InviteManager (needs member ID + state refs)
                inviteManager.initialize(localMemberId.value, groupStateManager, groupSyncManager)
                Timber.d("$TAG: InviteManager initialized")

                // 7b. Initialize GroupSyncManager so member-change broadcasts actually propagate.
                groupSyncManager.initialize(localMemberId.value, groupStateManager)
                Timber.d("$TAG: GroupSyncManager initialized")

                // 8. Initialize avatar sync (HTTP server + mDNS)
                avatarRepository.initialize()
                Timber.d("$TAG: AvatarRepository initialized")

                // 9. Apply retention policies
                scope.launch {
                    try {
                        locationRepository.applyRetentionPolicy(30) // 30 days for locations
                        chatRepository.applyRetentionPolicy(90) // 90 days for chat
                        Timber.d("$TAG: Retention policies applied")
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: Failed to apply retention policies")
                    }
                }

                isInitialized = true
                Timber.i("$TAG: Initialization complete")

                // Watch for membership changes (new members added/removed) and
                // update MQTT subscriptions + encryption keys automatically.
                var previousMemberIds: Set<String> = groupDef.members.map { it.memberId }.toSet()
                scope.launch {
                    groupStateManager.groupDefinition
                        .filterNotNull()
                        .distinctUntilChangedBy { it.members }
                        .collect { updatedGroup ->
                            try {
                                mqttTransport.updateFamilyMembers(updatedGroup.members.toList())
                                Timber.d("$TAG: Updated MQTT members after group change")

                                // Sync files to any newly added members
                                val newMemberIds = updatedGroup.members.map { it.memberId }.toSet()
                                if (newMemberIds.size > previousMemberIds.size) {
                                    sharedFileRepository.syncNewMember(updatedGroup.groupId)
                                    Timber.d("$TAG: Triggered file sync for new member(s)")
                                }
                                previousMemberIds = newMemberIds
                            } catch (e: Exception) {
                                Timber.e(e, "$TAG: Failed to update MQTT members")
                            }
                        }
                }

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Initialization failed")
            }
        }
    }

    /**
     * Reinitialize after group changes (e.g., new member added).
     */
    fun reinitialize() {
        scope.launch {
            try {
                val groupDef = groupStateManager.groupDefinition.value ?: return@launch

                // Update MQTT transport with new family members
                mqttTransport.updateFamilyMembers(groupDef.members.toList())
                mqttTransport.setGroupId(groupDef.groupId)

                Timber.d("$TAG: Reinitialized with updated group")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Reinitialization failed")
            }
        }
    }

    /**
     * Clean up on app shutdown.
     */
    fun cleanup() {
        mqttTransport.cleanup()
        localTransport.stop()
        avatarRepository.cleanup()
        isInitialized = false
        Timber.d("$TAG: Cleanup complete")
    }
}

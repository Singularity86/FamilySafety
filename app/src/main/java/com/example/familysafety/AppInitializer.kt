package com.example.familysafety

import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.transport.MqttTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
    private val locationRepository: LocationRepository,
    private val mqttTransport: MqttTransport,
    private val replicationManager: ReplicationManager,
    private val chatRepository: ChatRepository
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

                // 2. Wait for group definition to be available
                val groupDef = groupStateManager.groupDefinition.first { it != null }
                    ?: throw IllegalStateException("No group definition after initialization")
                Timber.d("$TAG: Group loaded: ${groupDef.groupName}")

                // 3. Initialize LocationRepository (loads history from database)
                locationRepository.initialize()
                Timber.d("$TAG: LocationRepository initialized")

                // 4. Wire up MqttTransport with ReplicationManager and ChatRepository
                mqttTransport.setReplicationManager(replicationManager)
                mqttTransport.setChatRepository(chatRepository)
                Timber.d("$TAG: MQTT transport wired up")

                // 5. Set group ID for group-level subscriptions
                mqttTransport.setGroupId(groupDef.groupId)

                // 6. Initialize MQTT connection
                val localMember = groupStateManager.localMember.first { it != null }
                    ?: throw IllegalStateException("No local member after initialization")

                mqttTransport.initialize(
                    memberIdParam = localMember.memberId,
                    familyMembers = groupDef.members.toList(),
                    groupIdParam = groupDef.groupId
                )
                Timber.d("$TAG: MQTT transport initialized")

                // 7. Apply retention policies
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
        isInitialized = false
        Timber.d("$TAG: Cleanup complete")
    }
}

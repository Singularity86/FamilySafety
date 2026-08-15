package com.example.familysafety

import android.content.Context
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.core.SecurityEventRepository
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.files.FileTransferWorker
import com.example.familysafety.files.SharedFileRepository
import com.example.familysafety.group.EncryptedGroupStatePersistence
import com.example.familysafety.group.GroupStateEvent
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.location.LocationHeartbeatReceiver
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.location.LocationService
import com.example.familysafety.location.ServiceWatchdogReceiver
import com.example.familysafety.location.ServiceWatchdogWorker
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.storage.LocationPublishOutboxRepository
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.transport.LocalTransport
import com.example.familysafety.transport.MqttTransport
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles app initialization and wiring up dependencies.
 * Called after onboarding is complete and user is in a group.
 */
@Singleton
class AppInitializer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val groupStateManager: GroupStateManager,
    private val localMemberId: LocalMemberId,
    private val locationRepository: LocationRepository,
    private val mqttTransport: MqttTransport,
    private val localTransport: LocalTransport,
    private val replicationManager: ReplicationManager,
    private val chatRepository: ChatRepository,
    private val e2eeManager: E2EEManager,
    private val inviteManager: InviteManager,
    private val groupSyncManager: GroupSyncManager,
    private val avatarRepository: AvatarRepository,
    private val sharedFileRepository: SharedFileRepository,
    private val securityEventRepository: SecurityEventRepository,
    private val locationPublishOutboxRepository: LocationPublishOutboxRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var isInitialized = false
    private var groupEventsJob: Job? = null
    private var replicationBackfillJob: Job? = null
    private var replicationAnnounceJob: Job? = null
    private val removalHandled = AtomicBoolean(false)

    // Emits once when this device learns it was removed from the group.
    // MainActivity collects this to restart the process into onboarding.
    private val _removedFromGroupEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val removedFromGroupEvent: SharedFlow<Unit> = _removedFromGroupEvent.asSharedFlow()

    companion object {
        private const val TAG = "AppInitializer"
        private const val MEMBERSHIP_CHANNEL_ID = "membership_events"
        private const val REMOVED_NOTIFICATION_ID = 3000
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

                // 5. Wire up InviteManager and GroupSyncManager BEFORE MQTT connects so that
                //    retained join_request messages delivered immediately on subscribe are not
                //    dropped due to a null groupStateManager.
                groupSyncManager.initialize(localMemberId.value, groupStateManager)
                Timber.d("$TAG: GroupSyncManager initialized")

                inviteManager.initialize(localMemberId.value, groupStateManager, groupSyncManager)
                Timber.d("$TAG: InviteManager initialized")

                // Watch group events BEFORE MQTT connects: a sync message that removes
                // this device can arrive immediately on subscribe, and nothing else in
                // the app collects this flow.
                if (groupEventsJob?.isActive != true) {
                    groupEventsJob = scope.launch {
                        groupStateManager.events.collect { event ->
                            if (event is GroupStateEvent.RemovedFromGroup) {
                                handleRemovedFromGroup()
                            }
                        }
                    }
                }

                // 6. Wire setter-injected dependencies into MQTT transport before connecting.
                mqttTransport.groupSyncManager = groupSyncManager
                mqttTransport.securityEventRepository = securityEventRepository
                mqttTransport.setGroupId(groupDef.groupId)

                // 7. Initialize MQTT connection - retained messages (join_request etc.) arrive
                //    immediately on subscribe, so InviteManager must be ready first (see above).
                val localMember = groupDef.findMemberById(localMemberId.value)
                    ?: throw IllegalStateException("Local member ${localMemberId.value} not found in group ${groupDef.groupId}")

                mqttTransport.initialize(
                    memberIdParam = localMemberId.value,
                    familyMembers = groupDef.members.toList(),
                    groupIdParam = groupDef.groupId
                )
                Timber.d("$TAG: MQTT transport initialized")

                // 7b. Start local WiFi transport (NSD registration + TCP server)
                localTransport.start(localMemberId.value)
                Timber.d("$TAG: LocalTransport started")

                // 8. Initialize avatar sync (HTTP server + mDNS)
                avatarRepository.initialize()
                Timber.d("$TAG: AvatarRepository initialized")

                // 8b. Replication back-fill. Real-time replication is QoS 0, so anything
                // sent while this device was offline is gone unless we ask for it: on
                // every relay (re)connect, request data newer than what we hold and
                // announce what we have. ReplicationManager rate-limits the full sync
                // so connection flaps don't spam the group.
                if (replicationBackfillJob?.isActive != true) {
                    replicationBackfillJob = scope.launch {
                        var wasConnected = false
                        mqttTransport.connectionState.collect { state ->
                            val isConnected =
                                state is MqttTransport.ConnectionState.Connected
                            if (isConnected && !wasConnected) {
                                try {
                                    replicationManager.requestFullSync()
                                    replicationManager.announceDataAvailability()
                                } catch (e: Exception) {
                                    Timber.e(e, "$TAG: reconnect back-fill failed")
                                }
                            }
                            wasConnected = isConnected
                        }
                    }
                }

                // 8c. Periodic availability announcements. Peers compare our summaries
                // against their own data and pull only what they're missing — this is
                // how a freshly restored device (which has nothing to request yet)
                // learns what exists.
                if (replicationAnnounceJob?.isActive != true) {
                    replicationAnnounceJob = scope.launch {
                        while (true) {
                            delay(ReplicationManager.SYNC_INTERVAL_MS)
                            if (mqttTransport.connectionState.value
                                is MqttTransport.ConnectionState.Connected
                            ) {
                                try {
                                    replicationManager.announceDataAvailability()
                                } catch (e: Exception) {
                                    Timber.w(e, "$TAG: periodic announce failed")
                                }
                            }
                        }
                    }
                }

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

                // Enqueue WorkManager watchdog — uses query-first logic to avoid
                // REPLACE resetting a healthy worker's schedule.
                ServiceWatchdogWorker.scheduleIfNeeded(context)

                // Clear plaintext chunk directories left by the pre-blob layout, and re-check
                // every unfinished file against what is actually on disk. A download
                // interrupted by the process dying used to stay stuck until someone tapped
                // the file; now it is picked up on the next launch.
                sharedFileRepository.reconcileOnStartup()

                // Background repair for files still missing chunks. Until now the only thing
                // that ever repaired a stalled transfer was a user tapping the file.
                FileTransferWorker.scheduleIfNeeded(context)

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
                                e2eeManager.clearSharedSecretCache()
                                Timber.d("$TAG: Updated MQTT members after group change")

                                // Sync files to any newly added members
                                val newMemberIds = updatedGroup.members.map { it.memberId }.toSet()
                                if (newMemberIds.size > previousMemberIds.size) {
                                    sharedFileRepository.syncNewMember(updatedGroup.groupId)
                                    // A joiner has no idea what anyone holds; tell it now
                                    // rather than making it wait for the next periodic tick.
                                    sharedFileRepository.announceHoldings(force = true)
                                    Timber.d("$TAG: Triggered file sync for new member(s)")
                                }
                                if (newMemberIds != previousMemberIds) {
                                    // A departed device's claim must not keep a document
                                    // looking safer than it is.
                                    sharedFileRepository.pruneAvailability(newMemberIds.toList())
                                    // Nor should their last known position stay on the map,
                                    // where it has no name to attach to.
                                    locationRepository.pruneToMembers(newMemberIds)
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
     * A voluntary leave (MainViewModel.leaveFamily) removes the local member from the
     * group itself, which emits the same RemovedFromGroup event as a remote removal.
     * Calling this first stops handleRemovedFromGroup from firing its "you were
     * removed" notification and competing teardown — the leave flow owns both.
     * One-way until the process restarts, which the leave flow performs anyway.
     */
    fun suppressRemovalHandling() {
        removalHandled.set(true)
    }

    /**
     * This device was removed from the family group (by another member, or by a
     * self-removal). Stop location sharing, disconnect, wipe the local group state,
     * tell the user, and signal the UI to restart into onboarding.
     *
     * Identity keys and the mnemonic are deliberately KEPT: removal is not
     * user-initiated, so we avoid destroying anything the user might still want
     * (onboarding overwrites the keys anyway if they create or join a new group).
     */
    private suspend fun handleRemovedFromGroup() {
        if (!removalHandled.compareAndSet(false, true)) return
        val groupName = groupStateManager.groupDefinition.value?.groupName
        Timber.w("$TAG: Removed from group '${groupName ?: "?"}' — stopping services and wiping group state")

        postRemovedNotification(groupName)

        // Stop location sharing and every watchdog that could resurrect it. The
        // service may not be running (e.g. permissions revoked), so also clear its
        // resume state and alarms directly.
        try {
            LocationService.stopTracking(context)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: could not send stop to LocationService (may not be running)")
        }
        ServiceWatchdogWorker.cancel(context)
        try { ServiceWatchdogReceiver.cancel(context) } catch (e: Exception) { Timber.w(e) }
        try { LocationHeartbeatReceiver.cancel(context) } catch (e: Exception) { Timber.w(e) }
        context.getSharedPreferences(LocationService.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(LocationService.PREFS_MEMBER_ID)
            .putBoolean(LocationService.PREF_SERVICE_ALIVE, false)
            .apply()

        // Disconnect transports so we stop sending and receiving as an ex-member.
        cleanup()

        // Wipe group state so the next launch goes to onboarding.
        try {
            EncryptedGroupStatePersistence.getInstance(context).deleteGroupDefinition()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to delete group definition")
        }
        try {
            locationPublishOutboxRepository.clearForMember(localMemberId.value)
        } catch (e: Exception) {
            Timber.w(e, "$TAG: failed to clear location outbox")
        }
        context.getSharedPreferences("familysafety_prefs", Context.MODE_PRIVATE)
            .edit()
            .remove("onboarding_complete")
            .commit()

        _removedFromGroupEvent.emit(Unit)
    }

    private fun postRemovedNotification(groupName: String?) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    MEMBERSHIP_CHANNEL_ID,
                    "Family membership",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Changes to your family group membership" }
                (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                    .createNotificationChannel(channel)
            }

            val launchIntent = context.packageManager
                .getLaunchIntentForPackage(context.packageName)
                ?.apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            val pendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context, REMOVED_NOTIFICATION_ID, it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val text = if (groupName.isNullOrBlank()) {
                "You are no longer a member of your family group. Location sharing has stopped."
            } else {
                "You are no longer a member of “$groupName”. Location sharing has stopped."
            }
            val notification = NotificationCompat.Builder(context, MEMBERSHIP_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Removed from family group")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
                .build()

            NotificationManagerCompat.from(context).notify(REMOVED_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: failed to post removal notification")
        }
    }

    /**
     * Clean up on app shutdown.
     */
    fun cleanup() {
        // Deliberately NOT cancelling groupEventsJob: handleRemovedFromGroup calls
        // cleanup() from inside that collector and must finish its remaining steps.
        replicationBackfillJob?.cancel()
        replicationBackfillJob = null
        replicationAnnounceJob?.cancel()
        replicationAnnounceJob = null
        mqttTransport.cleanup()
        localTransport.stop()
        avatarRepository.cleanup()
        ServiceWatchdogWorker.cancel(context)
        isInitialized = false
        Timber.d("$TAG: Cleanup complete")
    }
}

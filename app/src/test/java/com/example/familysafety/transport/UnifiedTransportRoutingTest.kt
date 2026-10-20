package com.example.familysafety.transport

import com.example.familysafety.avatar.AvatarRepository
import com.example.familysafety.chat.ChatRepository
import com.example.familysafety.files.SharedFileRepository
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LocalMemberId
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.replication.ReplicationManager
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.vault.VaultRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import javax.inject.Provider

/**
 * Which handler each incoming topic reaches, and from which transport. The photo topics are
 * the security-relevant case: they're only trusted from an authenticated LAN session, whose
 * peer is the sender, so the broker must never deliver them (spec §4.1).
 */
class UnifiedTransportRoutingTest {

    private val me = "a".repeat(32)
    private val bob = "b".repeat(32)

    private val local = mockk<LocalTransport>(relaxed = true)
    private val mqtt = mockk<MqttTransport>(relaxed = true)
    private val avatars = mockk<AvatarRepository>(relaxed = true)
    private val sync = mockk<GroupSyncManager>(relaxed = true)
    private val invites = mockk<InviteManager>(relaxed = true)
    private val files = mockk<SharedFileRepository>(relaxed = true)
    private val vault = mockk<VaultRepository>(relaxed = true)

    private val fromBroker = slot<suspend (String, String) -> Unit>()
    private val fromLan = slot<(String, String, String) -> Unit>()

    @Before
    fun setUp() {
        every { mqtt.onMessageReceived = capture(fromBroker) } answers { }
        every { local.onMessageReceived = capture(fromLan) } answers { }
        UnifiedTransportManager(
            local, mqtt, mockk<GroupStateManager>(relaxed = true), LocalMemberId(me),
            Provider { mockk<ChatRepository>(relaxed = true) },
            Provider { mockk<ReplicationManager>(relaxed = true) },
            Provider { invites }, Provider { sync }, Provider { files }, Provider { vault },
            Provider { avatars }
        )
    }

    @Test
    fun `a photo from a LAN session goes to the photo handler with the session's peer as sender`() {
        fromLan.captured("familysafe/$me/avatar/data", "{\"hash\":\"h\"}", bob)
        coVerify(timeout = 2_000) { avatars.handleLanMessage("familysafe/$me/avatar/data", "{\"hash\":\"h\"}", bob) }
    }

    @Test
    fun `a photo arriving from the broker is ignored`() {
        runBlocking { fromBroker.captured("familysafe/$me/avatar/data", "{\"hash\":\"h\",\"jpeg\":\"AA==\"}") }
        runBlocking { fromBroker.captured("familysafe/$me/avatar/request", "{\"hash\":\"h\"}") }
        // A later message is routed, so the two before it were looked at and dropped.
        runBlocking { fromBroker.captured("familysafe/$me/join_request", "{}") }
        coVerify(timeout = 2_000) { invites.handleIncomingJoinRequest("{}") }
        Thread.sleep(200)
        coVerify(exactly = 0) { avatars.handleLanMessage(any(), any(), any()) }
    }

    @Test
    fun `an empty payload, a cleared retained copy, reaches no handler`() {
        runBlocking { fromBroker.captured("familysafe/group/g/files/manifest", "") }
        runBlocking { fromBroker.captured("familysafe/group/g/vault/container", "") }
        Thread.sleep(300)
        coVerify(exactly = 0) { files.handleIncomingManifest(any()) }
        coVerify(exactly = 0) { vault.handleIncomingContainer(any()) }
    }

    @Test
    fun `family topics reach their handlers by suffix, from either transport`() {
        runBlocking { fromBroker.captured("familysafe/$me/group_sync", "s") }
        fromLan.captured("familysafe/group/g/files/manifest", "m", bob)
        runBlocking { fromBroker.captured("familysafe/group/g/vault/chunk/f1/3", "c") }
        runBlocking { fromBroker.captured("familysafe/$me/group_ack", "a") }

        coVerify(timeout = 2_000) { sync.handleSyncMessage("s") }
        coVerify(timeout = 2_000) { files.handleIncomingManifest("m".toByteArray()) }
        coVerify(timeout = 2_000) { vault.handleIncomingChunk("f1", 3, "c".toByteArray()) }
        coVerify(timeout = 2_000) { sync.handleAckMessage("a") }
    }
}

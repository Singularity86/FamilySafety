package com.example.familysafety.sync

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E5: a roster broadcast is released by the acks themselves, not by a poll. */
class AckWaitTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )

    @Test
    fun `a broadcast finishes as soon as the last ack arrives`() = runBlocking {
        val family = GroupDefinition(
            groupId = "group_123", groupName = "Test Family", createdAtEpochMs = 1000L,
            creatorMemberId = "alice", members = setOf(member("alice"), member("bob"), member("carol")),
            version = 4L
        )
        val transport = mockk<TransportProvider>(relaxed = true)
        coEvery { transport.sendMessage(any(), any(), any(), any(), any()) } returns true
        val e2ee = mockk<E2EEManager>(relaxed = true)
        every { e2ee.encryptMessage(any(), any(), any()) } returns "{}"
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        val sync = GroupSyncManager(e2ee, mockk<LazysodiumCryptoProvider>(relaxed = true), transport)
        sync.initialize("alice", gsm)

        val started = System.currentTimeMillis()
        val broadcast = async(Dispatchers.Default) { sync.broadcastGroupUpdate(family, ChangeType.FULL_SYNC) }
        delay(100)
        for (who in listOf("bob", "carol")) {
            sync.handleAckMessage(json.encodeToString(GroupUpdateAck("group_123", 4L, who, 0L)))
        }
        withTimeout(5_000) { broadcast.await() }

        // Far inside the 30 s ack window: it was the acks that released it.
        assertTrue(System.currentTimeMillis() - started < 5_000)
        assertEquals(GroupSyncManager.SyncState.Synced(4L), sync.syncState.value)
    }
}

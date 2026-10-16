package com.example.familysafety.sync

import com.example.familysafety.core.RateLimiter
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.ReplayVerdict
import com.example.familysafety.crypto.ReplayedEnvelopeException
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.transport.TransportProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.Test

/**
 * F16: a refresh request was unsigned plaintext that anyone on the broker can send, and each
 * one used to make this phone send the whole roster to every member. From protocol 4 it is
 * sealed and replay-checked (3.4); plaintext is taken only naming a member still on protocol 3.
 */
class RosterReplyRateLimitTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var transport: TransportProvider
    private lateinit var sync: GroupSyncManager
    private lateinit var e2ee: E2EEManager

    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )
    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Test Family", createdAtEpochMs = 1000L,
        creatorMemberId = "alice", members = setOf(member("alice"), member("bob")), version = 1L
    )

    @Before
    fun setup() = runTest {
        transport = mockk(relaxed = true)
        coEvery { transport.sendMessage(any(), any(), any(), any(), any()) } returns true
        e2ee = mockk(relaxed = true)
        every { e2ee.encryptMessage(any(), any(), any(), any()) } returns "{}"
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)

        sync = GroupSyncManager(e2ee, mockk<LazysodiumCryptoProvider>(relaxed = true), transport)
        sync.initialize("alice", gsm)
        sync.responseLimiter = RateLimiter(maxRequests = 10, windowMs = 60_000)
        // Bob has already acknowledged v1, so each allowed broadcast returns at once
        // instead of waiting out the ack window.
        sync.handleAckMessage(json.encodeToString(GroupUpdateAck("group_123", 1L, "bob", 0L)))
    }

    private fun plainRequest(requester: String) = json.encodeToString(
        GroupStateRefreshRequest(
            groupId = "group_123",
            requesterMemberId = requester,
            minimumVersion = 1L,
            reason = "forged",
            timestamp = 0L
        )
    )

    @Test
    fun `100 forged refresh requests produce at most the limiter's quota of broadcasts`() = runTest {
        // Naming a real member still on protocol 3, whose requests are plaintext.
        repeat(100) { sync.handleSyncRequestMessage(plainRequest("bob")) }

        // One roster send per broadcast here (one peer), so sends == broadcasts.
        coVerify(exactly = 10) { transport.sendMessage("bob", match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }

    @Test
    fun `a plaintext refresh request naming a non-member gets no reply`() = runTest {
        repeat(5) { i -> sync.handleSyncRequestMessage(plainRequest("forged_$i")) }

        coVerify(exactly = 0) { transport.sendMessage(any(), match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }

    @Test
    fun `a plaintext refresh request naming a member who seals theirs gets no reply`() = runTest {
        every { e2ee.hasSequenced("bob", any()) } returns true

        sync.handleSyncRequestMessage(plainRequest("bob"))

        coVerify(exactly = 0) { transport.sendMessage(any(), match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }

    @Test
    fun `a replayed sealed refresh request gets no reply`() = runTest {
        every { e2ee.decryptMessage(any(), any(), any()) } throws
            ReplayedEnvelopeException(ReplayVerdict.REPLAY)

        sync.handleSyncRequestMessage("""{"senderMemberId":"bob","nonce":"00","ciphertext":"00","signature":"00"}""")

        coVerify(exactly = 0) { transport.sendMessage(any(), match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }

    @Test
    fun `a sealed refresh request naming someone other than its sender gets no reply`() = runTest {
        every { e2ee.decryptMessage(any(), any(), any()) } returns plainRequest("alice")

        sync.handleSyncRequestMessage("""{"senderMemberId":"bob","nonce":"00","ciphertext":"00","signature":"00"}""")

        coVerify(exactly = 0) { transport.sendMessage(any(), match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }

    @Test
    fun `a sealed refresh request from its own sender is answered`() = runTest {
        every { e2ee.decryptMessage(any(), any(), any()) } returns plainRequest("bob")

        sync.handleSyncRequestMessage("""{"senderMemberId":"bob","nonce":"00","ciphertext":"00","signature":"00"}""")

        coVerify(exactly = 1) { transport.sendMessage("bob", match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }
}

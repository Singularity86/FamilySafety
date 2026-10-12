package com.example.familysafety.sync

import com.example.familysafety.core.RateLimiter
import com.example.familysafety.crypto.E2EEManager
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
 * F16: a refresh request is unsigned plaintext that anyone on the broker can send, and each
 * one used to make this phone send the whole roster to every member.
 */
class RosterReplyRateLimitTest {

    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var transport: TransportProvider
    private lateinit var sync: GroupSyncManager

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
        val e2ee = mockk<E2EEManager>(relaxed = true)
        every { e2ee.encryptMessage(any(), any(), any()) } returns "{}"
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)

        sync = GroupSyncManager(e2ee, mockk<LazysodiumCryptoProvider>(relaxed = true), transport)
        sync.initialize("alice", gsm)
        sync.responseLimiter = RateLimiter(maxRequests = 10, windowMs = 60_000)
        // Bob has already acknowledged v1, so each allowed broadcast returns at once
        // instead of waiting out the ack window.
        sync.handleAckMessage(json.encodeToString(GroupUpdateAck("group_123", 1L, "bob", 0L)))
    }

    @Test
    fun `100 forged refresh requests produce at most the limiter's quota of broadcasts`() = runTest {
        repeat(100) { i ->
            sync.handleSyncRequestMessage(
                json.encodeToString(
                    GroupStateRefreshRequest(
                        groupId = "group_123",
                        requesterMemberId = "forged_$i",
                        minimumVersion = 1L,
                        reason = "forged",
                        timestamp = 0L
                    )
                )
            )
        }

        // One roster send per broadcast here (one peer), so sends == broadcasts.
        coVerify(exactly = 10) { transport.sendMessage("bob", match { it.endsWith("/group_sync") }, any(), any(), any()) }
    }
}

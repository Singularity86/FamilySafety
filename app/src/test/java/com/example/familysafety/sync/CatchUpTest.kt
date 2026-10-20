package com.example.familysafety.sync

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A phone that missed roster updates asks the family for the current roster instead of
 * staying stuck: after a lost broker session, or on hearing from someone it doesn't know.
 */
class CatchUpTest {

    private lateinit var transport: TransportProvider
    private lateinit var sync: GroupSyncManager
    private var now = 1_000_000L

    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )

    // Alice's roster is old: it has Bob, but not Dana, who joined while Alice was away.
    // Carol was removed.
    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Test Family", createdAtEpochMs = 1000L,
        creatorMemberId = "alice", members = setOf(member("alice"), member("bob")), version = 3L,
        removedMemberIds = setOf("carol")
    )

    private fun envelopeFrom(sender: String) =
        """{"senderMemberId":"$sender","nonce":"00","ciphertext":"00","signature":"00"}"""

    private fun refreshRequestsToBob(count: Int) {
        coVerify(exactly = count, timeout = 2_000) {
            transport.sendMessage("bob", match { it.endsWith("/sync_request") }, any(), any(), any())
        }
    }

    @Before
    fun setup() {
        transport = mockk(relaxed = true)
        coEvery { transport.sendMessage(any(), any(), any(), any(), any()) } returns true
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        sync = GroupSyncManager(mockk<E2EEManager>(relaxed = true), mockk<LazysodiumCryptoProvider>(relaxed = true), transport)
        sync.initialize("alice", gsm)
        sync.clock = { now }
    }

    @Test
    fun `a roster update from someone we don't know makes us ask a member we do know`() = runTest {
        sync.handleSyncMessage(envelopeFrom("dana"))
        refreshRequestsToBob(1)
    }

    @Test
    fun `repeated messages from strangers ask at most once per five minutes`() = runTest {
        sync.handleSyncMessage(envelopeFrom("dana"))
        refreshRequestsToBob(1)
        repeat(10) { sync.handleSyncMessage(envelopeFrom("forged_$it")) }
        refreshRequestsToBob(1)

        now += 6 * 60_000L
        sync.handleSyncMessage(envelopeFrom("dana"))
        refreshRequestsToBob(2)
    }

    @Test
    fun `a removed member, ourselves and known members don't trigger it`() {
        assertFalse(sync.catchUpOnUnknownSender("carol"))
        assertFalse(sync.catchUpOnUnknownSender("alice"))
        assertFalse(sync.catchUpOnUnknownSender("bob"))
        assertTrue(sync.catchUpOnUnknownSender("dana"))
    }

    @Test
    fun `a removed later membership is recognised too`() {
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family.copy(removedMemberIds = setOf("dana#2")))
        sync.initialize("alice", gsm)
        assertFalse(sync.catchUpOnUnknownSender("dana"))
    }

    @Test
    fun `a lost broker session asks once, and shares the same limit`() = runTest {
        assertTrue(sync.requestCatchUp("broker didn't keep our session"))
        refreshRequestsToBob(1)
        assertFalse(sync.catchUpOnUnknownSender("dana"))
    }

    @Test
    fun `a sender the answering roster doesn't name isn't asked about again for a day`() {
        assertTrue(sync.catchUpOnUnknownSender("mallory"))
        sync.noteRosterAnswer(family) // a member answered; mallory isn't in it

        now += 6 * 60_000L // past the 5-minute limit
        assertFalse(sync.catchUpOnUnknownSender("mallory"))
        now += 24 * 60 * 60_000L
        assertTrue(sync.catchUpOnUnknownSender("mallory"))
    }

    @Test
    fun `a real new member is in the answer, so is never given up on`() {
        assertTrue(sync.catchUpOnUnknownSender("dana"))
        sync.noteRosterAnswer(family.copy(members = family.members + member("dana"), version = 4L))
        now += 6 * 60_000L
        // Our own roster hasn't taken the update yet in this test, so dana is still unknown here.
        assertTrue(sync.catchUpOnUnknownSender("dana"))
    }

    @Test
    fun `only senders we actually asked about are given up on`() {
        assertTrue(sync.catchUpOnUnknownSender("mallory"))
        assertFalse(sync.catchUpOnUnknownSender("eve")) // rate-limited, so never asked
        sync.noteRosterAnswer(family)
        now += 6 * 60_000L
        assertTrue(sync.catchUpOnUnknownSender("eve"))
    }

    @Test
    fun `a roster with nobody asked about gives up on no one`() {
        sync.noteRosterAnswer(family)
        assertTrue(sync.catchUpOnUnknownSender("mallory"))
    }
}

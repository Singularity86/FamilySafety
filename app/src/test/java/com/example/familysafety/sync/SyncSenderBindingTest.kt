package com.example.familysafety.sync

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

/**
 * F25: the envelope's sender is authenticated, and the updater named inside is only a claim.
 * A member passing on someone else's update could rewrite the fields the sync signature
 * doesn't cover, so the two must match.
 */
class SyncSenderBindingTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun member(id: String) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = "a".repeat(64),
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )

    @Test
    fun `an update relayed under someone else's name is dropped before anything is applied`() = runTest {
        val family = GroupDefinition(
            groupId = "group_123", groupName = "Family", createdAtEpochMs = 1000L,
            creatorMemberId = "alice", members = setOf(member("alice"), member("bob"), member("me")),
            version = 3L
        )
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        val crypto = mockk<LazysodiumCryptoProvider>(relaxed = true)
        every { crypto.verifySignature(any<ByteArray>(), any<ByteArray>(), any<ByteArray>()) } returns true
        val e2ee = mockk<E2EEManager>(relaxed = true)
        // Bob sends it (the envelope is his), but it claims Alice made the change.
        val claimed = GroupSyncMessage(
            groupId = "group_123", version = 4L, groupDefinition = family.copy(version = 4L),
            updaterMemberId = "alice", changeType = ChangeType.NAME_CHANGED,
            timestamp = 0L, signature = "00"
        )
        every { e2ee.decryptMessage(any(), any(), any()) } returns json.encodeToString(claimed)

        val sync = GroupSyncManager(e2ee, crypto, mockk(relaxed = true))
        sync.initialize("me", gsm)
        sync.handleSyncMessage("""{"senderMemberId":"bob","nonce":"00","ciphertext":"00","signature":"00"}""")

        coVerify(exactly = 0) { gsm.applyVerifiedRemoteGroupState(any(), any(), any(), any()) }
    }
}

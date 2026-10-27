package com.example.familysafety.vault

import com.example.familysafety.files.FileMessageCipher
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The answer-or-drop rules for `vault/repair`, agreed with iOS (Handoff log, 2026-10-26). */
class VaultRepairRequestsTest {

    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Family", createdAtEpochMs = 1L, creatorMemberId = "alice",
        members = setOf(
            FamilyMember(memberId = "alice", displayName = "Alice", ed25519PublicKey = "a".repeat(64), x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1L)
        ),
        version = 1L, fileEncryptionKey = "ab".repeat(32)
    )

    private fun requestFrom(id: String) = VaultChunkRequest(requesterId = id, fileId = "doc_1", missing = listOf(0, 3), timestamp = 1_000L)
    private fun plaintext(id: String) = Json.encodeToString(requestFrom(id))
    private fun sealed(id: String, group: GroupDefinition = family) =
        String(FileMessageCipher.seal(group, family.groupId, plaintext(id))!!)

    @Test
    fun `a sealed request from a member is answered`() {
        assertEquals(requestFrom("alice"), VaultRepairRequests.parse(family, sealed("alice")))
    }

    @Test
    fun `a sealed request under a key we don't hold is dropped, not read as plaintext`() {
        val otherFamily = family.copy(fileEncryptionKey = "cd".repeat(32))
        assertNull(VaultRepairRequests.parse(family, sealed("alice", otherFamily)))
    }

    @Test
    fun `a sealed request from outside the family is dropped`() {
        assertNull(VaultRepairRequests.parse(family, sealed("stranger")))
    }

    @Test
    fun `a plaintext request from a member is answered only while plaintext is accepted`() {
        assertEquals(requestFrom("alice"), VaultRepairRequests.parse(family, plaintext("alice"), acceptPlaintext = true))
        assertNull(VaultRepairRequests.parse(family, plaintext("alice"), acceptPlaintext = false))
    }

    @Test
    fun `a plaintext request from outside the family is dropped`() {
        assertNull(VaultRepairRequests.parse(family, plaintext("stranger"), acceptPlaintext = true))
    }
}

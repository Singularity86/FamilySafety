package com.example.familysafety.files

import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupKeys
import com.example.familysafety.vault.VaultChunkRequest
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** The wrapper `files/repair` and `vault/repair` both carry (EncryptedFileMessage). */
class FileMessageCipherTest {

    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Family", createdAtEpochMs = 1L,
        creatorMemberId = "alice", members = emptySet(), version = 1L,
        fileEncryptionKey = "ab".repeat(32)
    )
    private val request = Json.encodeToString(VaultChunkRequest(requesterId = "alice", fileId = "doc_1", missing = listOf(0, 3)))

    @Test
    fun `a vault chunk request round-trips, and the sealed bytes don't name the document`() {
        val sealed = FileMessageCipher.seal(family, family.groupId, request)!!

        assertFalse(String(sealed).contains("doc_1"))
        assertEquals(FILE_KEY_VERSION_GROUP_SUBKEY, Json.decodeFromString<EncryptedFileMessage>(String(sealed)).keyVersion)
        assertEquals(request, FileMessageCipher.open(family, family.groupId, String(sealed)))
    }

    @Test
    fun `another family can't open it`() {
        val sealed = FileMessageCipher.seal(family, family.groupId, request)!!
        val other = family.copy(fileEncryptionKey = "cd".repeat(32))

        assertNull(FileMessageCipher.open(other, family.groupId, String(sealed)))
    }

    @Test
    fun `after a rotation it is stamped with the new key, which a phone without it can't read`() {
        val rotated = GroupKeys.rotated(family.copy(removedMemberIds = setOf("gone#1")))
        val sealed = FileMessageCipher.seal(rotated, family.groupId, request)!!

        assertEquals(GroupKeys.stampFor(rotated), Json.decodeFromString<EncryptedFileMessage>(String(sealed)).kid)
        assertEquals(request, FileMessageCipher.open(rotated, family.groupId, String(sealed)))
        assertNull(FileMessageCipher.open(family, family.groupId, String(sealed)))
    }

    @Test
    fun `a family with a group key refuses the public legacy key`() {
        val legacySealed = FileMessageCipher.seal(family.copy(fileEncryptionKey = null), family.groupId, request)!!

        assertEquals(FILE_KEY_VERSION_LEGACY, Json.decodeFromString<EncryptedFileMessage>(String(legacySealed)).keyVersion)
        assertNull(FileMessageCipher.open(family, family.groupId, String(legacySealed)))
    }

    @Test
    fun `a plaintext request is not a sealed message`() {
        assertNull(FileMessageCipher.open(family, family.groupId, request))
    }
}

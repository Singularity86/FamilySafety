package com.example.familysafety.group

import com.example.familysafety.crypto.GroupCipher
import com.example.familysafety.files.FILE_KEY_VERSION_GROUP_SUBKEY
import com.example.familysafety.files.FileKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 3.3 (F17): someone who leaves or is removed can't read what the family seals afterwards. */
class GroupKeysTest {

    private val epoch0 = "11".repeat(32)
    private fun member(id: String) = FamilyMember(id, id, "aa".repeat(32), "bb".repeat(32), 0L)
    private val alice = member("alice")
    private val bob = member("bob")
    private val carol = member("carol")

    private fun family(members: Set<FamilyMember>, removed: Set<String> = emptySet(), keys: List<GroupKey> = emptyList()) =
        GroupDefinition(
            groupId = "g", groupName = "G", createdAtEpochMs = 0, creatorMemberId = "alice",
            members = members, version = 1, fileEncryptionKey = epoch0,
            removedMemberIds = removed, groupKeys = keys
        )

    @Test
    fun `the family key is epoch 0 and stamps no kid`() {
        val def = family(setOf(alice, bob))
        assertEquals(0, GroupKeys.current(def)!!.epoch)
        assertNull(GroupKeys.stampFor(def))
        assertFalse(GroupKeys.rotationDue(def))
    }

    @Test
    fun `a removal makes a rotation due, and rotating settles it`() {
        val def = family(setOf(alice, bob), removed = setOf("carol"))
        assertTrue(GroupKeys.rotationDue(def))
        val rotated = GroupKeys.rotated(def)
        assertFalse(GroupKeys.rotationDue(rotated))
        assertEquals(1, GroupKeys.current(rotated)!!.epoch)
        assertNotNull(GroupKeys.stampFor(rotated))
    }

    @Test
    fun `the removed member's key can't open what is sealed after the rotation`() {
        val before = family(setOf(alice, bob, carol))
        val after = GroupKeys.rotated(family(setOf(alice, bob), removed = setOf("carol")))
        val presenceKey = GroupCipher.deriveSubkey(GroupKeys.current(after)!!.key, GroupCipher.PURPOSE_PRESENCE)
        val sealed = GroupCipher.seal("online".toByteArray(), presenceKey)

        // Carol kept everything she held before leaving: the epoch-0 key.
        val carolsKey = GroupCipher.deriveSubkey(GroupKeys.current(before)!!.key, GroupCipher.PURPOSE_PRESENCE)
        assertTrue(runCatching { GroupCipher.open(sealed, carolsKey) }.isFailure)
        assertEquals("online", String(GroupCipher.open(sealed, presenceKey)))
    }

    @Test
    fun `old files stay readable after a rotation`() {
        val after = GroupKeys.rotated(family(setOf(alice, bob), removed = setOf("carol")))
        assertEquals(epoch0, GroupKeys.keyHexFor(after, null))
        assertNotNull(FileKeys.forVersion("g", GroupKeys.keyHexFor(after, null), FILE_KEY_VERSION_GROUP_SUBKEY))
    }

    @Test
    fun `an unknown kid opens nothing`() {
        assertNull(GroupKeys.keyHexFor(family(setOf(alice)), "0123456789abcdef"))
    }

    @Test
    fun `concurrent rotations converge on one current key and keep both readable`() {
        val base = family(setOf(alice, bob), removed = setOf("carol"))
        val a = GroupKeys.rotated(base)
        val b = GroupKeys.rotated(base)
        val union = GroupKeys.union(a.groupKeys, b.groupKeys)
        assertEquals(2, union.size)
        val fromA = GroupKeys.current(a.copy(groupKeys = GroupKeys.union(a.groupKeys, b.groupKeys)))
        val fromB = GroupKeys.current(b.copy(groupKeys = GroupKeys.union(b.groupKeys, a.groupKeys)))
        assertEquals(fromA, fromB)
        union.forEach { assertNotNull(GroupKeys.keyHexFor(a.copy(groupKeys = union), it.kid)) }
    }

    @Test
    fun `a keyring entry whose kid doesn't match its key is refused`() {
        val current = family(setOf(alice, bob), removed = setOf("carol"))
        val forged = GroupKey("0011223344556677", 1, "22".repeat(32))
        assertEquals("group key id doesn't match its key",
            GroupKeys.rejectReason(current, current.copy(groupKeys = listOf(forged))))
    }

    @Test
    fun `a key newer than the removals is refused, so nobody can keep minting keys`() {
        val current = family(setOf(alice, bob), removed = setOf("carol"))
        val early = GroupKeys.newKey(2)
        assertEquals("group key epoch out of range",
            GroupKeys.rejectReason(current, current.copy(groupKeys = listOf(early))))
        assertNull(GroupKeys.rejectReason(current, current.copy(groupKeys = listOf(GroupKeys.newKey(1)))))
    }

    @Test
    fun `an existing entry can't change epoch`() {
        val k = GroupKeys.newKey(1)
        val current = family(setOf(alice, bob), removed = setOf("carol"), keys = listOf(k))
        assertEquals("group key epoch changed",
            GroupKeys.rejectReason(current, current.copy(removedMemberIds = setOf("carol", "dan"), groupKeys = listOf(k.copy(epoch = 2)))))
    }

    @Test
    fun `a state sent to someone outside the roster carries no rotated key`() {
        val after = GroupKeys.rotated(family(setOf(alice, bob), removed = setOf("carol")))
        assertTrue(GroupKeys.forRecipient(after, "carol").groupKeys.isEmpty())
        assertEquals(after, GroupKeys.forRecipient(after, "bob"))
    }

    @Test
    fun `merging keeps both sides' keys`() {
        val k1 = GroupKeys.newKey(1)
        val ours = family(setOf(alice, bob), removed = setOf("carol"), keys = listOf(k1))
        val theirs = family(setOf(alice, bob), removed = setOf("carol"))
        assertEquals(listOf(k1), GroupKeys.union(theirs.groupKeys, ours.groupKeys))
    }

    private val day = 24L * 60 * 60 * 1000

    @Test
    fun `the gate opens only when everyone counted is on the new protocol`() {
        val sightings = mapOf(
            "bob" to GroupKeys.Sighting(4, 100 * day),
            "carol" to GroupKeys.Sighting(3, 100 * day)
        )
        assertFalse(GroupKeys.everyoneAtLeast(listOf("alice", "bob", "carol"), "alice", sightings, 0, 101 * day, 4))
        assertTrue(GroupKeys.everyoneAtLeast(listOf("alice", "bob"), "alice", sightings, 0, 101 * day, 4))
    }

    @Test
    fun `a member silent for 30 days no longer holds the family back`() {
        val sightings = mapOf("bob" to GroupKeys.Sighting(4, 100 * day), "lost" to GroupKeys.Sighting(3, 10 * day))
        assertTrue(GroupKeys.everyoneAtLeast(listOf("alice", "bob", "lost"), "alice", sightings, 0, 100 * day, 4))
    }

    @Test
    fun `a member never heard from blocks the gate until a full window of watching`() {
        val sightings = mapOf("bob" to GroupKeys.Sighting(4, 5 * day))
        assertFalse(GroupKeys.everyoneAtLeast(listOf("alice", "bob", "new"), "alice", sightings, 0, 5 * day, 4))
        assertTrue(GroupKeys.everyoneAtLeast(listOf("alice", "bob", "new"), "alice", sightings, 0, 31 * day, 4))
    }

    @Test
    fun `kid is the first 8 bytes of the key's SHA-256`() {
        // SHA-256 of 32 bytes of 0x11.
        assertEquals("02d449a31fbb267c", GroupKeys.kidOf(epoch0))
        assertNotEquals(GroupKeys.kidOf(epoch0), GroupKeys.kidOf("22".repeat(32)))
    }
}

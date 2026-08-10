package com.example.familysafety.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCipherTest {

    private val groupKeyHex = "0102030405060708".repeat(4)   // 64 hex chars = 32 bytes

    @Test
    fun sealAndOpenRoundTrip() {
        val key = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val plaintext = """{"type":"presence_update","payload":"..."}""".toByteArray()

        assertArrayEquals(plaintext, GroupCipher.open(GroupCipher.seal(plaintext, key), key))
    }

    @Test
    fun sealedOutputDoesNotContainThePlaintext() {
        val key = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val sealed = String(GroupCipher.seal("""{"isOnline":true}""".toByteArray(), key), Charsets.ISO_8859_1)

        assertFalse("plaintext leaked into the sealed blob", sealed.contains("isOnline"))
        assertFalse("plaintext leaked into the sealed blob", sealed.contains("true"))
    }

    @Test
    fun subkeysDifferByPurpose() {
        // Domain separation: recovering the presence key must not hand over the file key.
        val presence = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val other = GroupCipher.deriveSubkey(groupKeyHex, "files")

        assertNotEquals(presence.toList(), other.toList())
    }

    @Test
    fun subkeyIsNotTheGroupKeyItself() {
        val raw = groupKeyHex.chunked(2).map { it.toInt(16).toByte() }
        val subkey = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)

        assertNotEquals(raw, subkey.toList())
    }

    @Test
    fun subkeyIsDeterministic() {
        // Every member derives this independently; if it were not stable they could not
        // read each other's messages.
        assertArrayEquals(
            GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE),
            GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        )
    }

    @Test
    fun subkeyIsThirtyTwoBytes() {
        assertEquals(32, GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE).size)
    }

    @Test
    fun aDifferentGroupKeyCannotOpenTheBlob() {
        val mine = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val theirs = GroupCipher.deriveSubkey("ff00ff00".repeat(8), GroupCipher.PURPOSE_PRESENCE)
        val sealed = GroupCipher.seal("secret".toByteArray(), mine)

        assertTrue(runCatching { GroupCipher.open(sealed, theirs) }.isFailure)
    }

    @Test
    fun tamperingIsDetected() {
        // GCM authenticates, so a flipped bit must fail rather than decrypt to garbage.
        val key = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val sealed = GroupCipher.seal("secret".toByteArray(), key)
        sealed[sealed.size - 1] = (sealed[sealed.size - 1].toInt() xor 0x01).toByte()

        assertTrue(runCatching { GroupCipher.open(sealed, key) }.isFailure)
    }

    @Test
    fun nonceIsFreshPerSeal() {
        // A repeated nonce under the same key is catastrophic for GCM.
        val key = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)
        val a = GroupCipher.seal("same".toByteArray(), key)
        val b = GroupCipher.seal("same".toByteArray(), key)

        assertNotEquals(a.take(12), b.take(12))
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun shortBlobIsRejectedRatherThanCrashing() {
        val key = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)

        assertTrue(runCatching { GroupCipher.open(ByteArray(4), key) }.isFailure)
    }
}

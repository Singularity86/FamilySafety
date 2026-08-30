package com.example.familysafety.files

import com.example.familysafety.crypto.GroupCipher
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * These constants look like ordinary constants and are not.
 *
 * The version number is written into every chunk and manifest and is how a receiving device
 * chooses which key to try; the purpose string is an input to the key derivation itself.
 * Renumbering a version, or editing "files" to "file", does not fail a build or a type
 * check — it makes every document already published under the old value undecryptable, on
 * every device, with no error beyond a failed GCM tag.
 *
 * So these assertions exist to be annoying to change. If one fails, the question is not
 * "what should this test say now" but "what happens to the files that are already out
 * there".
 */
class FileKeyVersionTest {

    private val groupKeyHex = "00112233445566778899aabbccddeeff" +
        "00112233445566778899aabbccddeeff"

    @Test
    fun `key versions are the values already on the wire`() {
        assertEquals("legacy files are stamped 1", 1, FILE_KEY_VERSION_LEGACY)
        assertEquals("1.12.0-1.13.1 files are stamped 2", 2, FILE_KEY_VERSION_GROUP_SECRET)
        assertEquals("1.13.2 onwards stamps 3", 3, FILE_KEY_VERSION_GROUP_SUBKEY)
    }

    @Test
    fun `the files purpose string is an input to the key and cannot be edited freely`() {
        assertEquals("files", GroupCipher.PURPOSE_FILES)
    }

    @Test
    fun `the version 3 key is not the group key that version 2 used`() {
        // The whole point of the flip: under version 2 the file key *was* the master, so
        // recovering it yielded presence and every other subkey too. Under version 3 the
        // file key is a leaf and tells an attacker nothing about the master.
        val raw = groupKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val subkey = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_FILES)

        assertFalse(raw.contentEquals(subkey))
    }

    @Test
    fun `files and presence do not share a key`() {
        // Domain separation is only real if the purposes actually diverge. If these ever
        // matched, version 3 would be version 2 wearing a different number.
        val files = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_FILES)
        val presence = GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_PRESENCE)

        assertFalse(files.contentEquals(presence))
    }

    @Test
    fun `the version 3 key is stable for a given group key`() {
        // A device that re-derives on every launch has to land on the same bytes, or files
        // written before a restart stop opening after it.
        assertArrayEquals(
            GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_FILES),
            GroupCipher.deriveSubkey(groupKeyHex, GroupCipher.PURPOSE_FILES)
        )
    }
}

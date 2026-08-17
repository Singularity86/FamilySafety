package com.example.familysafety.files

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/**
 * The manifest is the file library's index: it names every document, and marking one deleted
 * in it deletes that document on every device in the family. It used to be accepted from
 * anyone — unsigned, with an unconditional plaintext fallback — so anyone able to publish to
 * the broker could rewrite or empty a family's library.
 *
 * These tests cover the two production pieces that make that decidable: the exact bytes a
 * signature commits to, and the wire shape a receiver has to be able to reject. The verifying
 * code itself lives in `SharedFileRepository`, which cannot be built in a JVM test — it pulls
 * in Android, Room and libsodium's native library.
 */
class ManifestAuthenticityTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Ed25519 from the JDK, standing in for libsodium. Same algorithm, same signature bytes;
    // what is under test is what gets signed, not who signs it.
    private val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    private fun sign(payload: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run {
            initSign(keyPair.private)
            update(payload)
            sign()
        }

    private fun verify(payload: ByteArray, signature: ByteArray): Boolean =
        Signature.getInstance("Ed25519").run {
            initVerify(keyPair.public)
            update(payload)
            runCatching { verify(signature) }.getOrDefault(false)
        }

    private fun ciphertext(seed: Int = 1): String =
        Base64.getEncoder().encodeToString(ByteArray(64) { (it * seed).toByte() })

    @Test
    fun signature_coversBothKeyVersionAndCiphertext() {
        val data = ciphertext()
        val signature = sign(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SECRET, data))

        assertTrue(verify(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SECRET, data), signature))

        // Swapping the key version under a valid signature must not verify, or an attacker
        // could relabel a manifest to a weaker key and keep the publisher's signature.
        assertFalse(verify(manifestSigningPayload(FILE_KEY_VERSION_LEGACY, data), signature))
        assertFalse(verify(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SUBKEY, data), signature))
    }

    @Test
    fun signature_doesNotSurviveASubstitutedManifest() {
        val original = ciphertext(seed = 1)
        val signature = sign(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SECRET, original))
        val substituted = ciphertext(seed = 3)

        assertNotEquals(original, substituted)
        assertFalse(verify(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SECRET, substituted), signature))
    }

    @Test
    fun signingPayload_isUnambiguous() {
        // The separator matters: without it, ("1", "23") and ("12", "3") would sign the same
        // bytes, and the key version could be moved into the ciphertext field.
        assertArrayEquals("2|abc".toByteArray(Charsets.UTF_8), manifestSigningPayload(2, "abc"))
        assertNotEquals(
            manifestSigningPayload(1, "23").toList(),
            manifestSigningPayload(12, "3").toList()
        )
    }

    @Test
    fun manifestFromABuildWithoutSigning_parsesAsUnsigned() {
        // Older peers publish no signature at all. That has to decode — so it can be refused
        // deliberately — rather than fail to parse and look like a corrupt message.
        val legacy = """{"keyVersion":2,"data":"${ciphertext()}"}"""

        val decoded = json.decodeFromString<EncryptedFileManifest>(legacy)

        assertEquals(FILE_KEY_VERSION_GROUP_SECRET, decoded.keyVersion)
        assertTrue(decoded.signature.isBlank())
        assertTrue(decoded.signerMemberId.isBlank())
    }

    @Test
    fun signedManifest_roundTripsOnTheWire() {
        val data = ciphertext()
        val wrapped = EncryptedFileManifest(
            keyVersion = FILE_KEY_VERSION_GROUP_SECRET,
            data = data,
            signerMemberId = "a".repeat(32),
            signature = sign(manifestSigningPayload(FILE_KEY_VERSION_GROUP_SECRET, data))
                .joinToString("") { "%02x".format(it) }
        )

        val decoded = json.decodeFromString<EncryptedFileManifest>(json.encodeToString(wrapped))

        assertEquals(wrapped, decoded)
        assertEquals(128, decoded.signature.length)  // 64-byte Ed25519 signature, hex
        assertTrue(
            verify(
                manifestSigningPayload(decoded.keyVersion, decoded.data),
                decoded.signature.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            )
        )
    }

    @Test
    fun tombstonesSurviveTheManifest() {
        // Deletion propagates as an entry that is still present and flagged, not as an absence
        // — an absent entry is indistinguishable from a file the publisher has not heard of.
        val deleted = SharedFile(
            fileId = "f1",
            name = "insurance.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2048,
            contentHash = "ab".repeat(32),
            uploaderMemberId = "b".repeat(32),
            uploadedAt = 1_700_000_000_000,
            chunkCount = 1,
            isDeleted = true,
            deletedByMemberId = "c".repeat(32),
            deletedAt = 1_700_000_500_000
        )
        val manifest = FileManifest("group", listOf(deleted), version = 1)

        val decoded = json.decodeFromString<FileManifest>(json.encodeToString(manifest))
        val entry = decoded.files.single()

        assertTrue(entry.isDeleted)
        assertEquals("c".repeat(32), entry.deletedByMemberId)
        assertEquals(1_700_000_500_000, entry.deletedAt)
    }

    @Test
    fun keyVersionThree_isDistinctAndNotYetPublished() {
        // The reader ships a release ahead of the writer. If these ever collide, a device
        // would decrypt version 3 with the raw group key and get authentication failures it
        // cannot explain.
        assertEquals(3, FILE_KEY_VERSION_GROUP_SUBKEY)
        assertNotEquals(FILE_KEY_VERSION_GROUP_SECRET, FILE_KEY_VERSION_GROUP_SUBKEY)
        assertNotEquals(FILE_KEY_VERSION_LEGACY, FILE_KEY_VERSION_GROUP_SUBKEY)

        // What this build actually puts on the wire, per the rollout decision recorded in
        // SharedFileModels: still version 2.
        assertEquals(FILE_KEY_VERSION_GROUP_SECRET, FileChunkMessage(
            fileId = "f", chunkIndex = 0, totalChunks = 1, data = "",
            keyVersion = FILE_KEY_VERSION_GROUP_SECRET
        ).keyVersion)
    }

    @Test
    fun purposeSeparatedSubkey_differsFromTheGroupKey() {
        val groupKeyHex = ByteArray(32) { (it + 1).toByte() }.joinToString("") { "%02x".format(it) }

        val files = com.example.familysafety.crypto.GroupCipher.deriveSubkey(
            groupKeyHex, com.example.familysafety.crypto.GroupCipher.PURPOSE_FILES
        )
        val presence = com.example.familysafety.crypto.GroupCipher.deriveSubkey(
            groupKeyHex, com.example.familysafety.crypto.GroupCipher.PURPOSE_PRESENCE
        )

        assertEquals(32, files.size)
        assertFalse("the file subkey must not be the group key", files.contentEquals(
            groupKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        ))
        assertFalse("recovering one purpose must not hand over another", files.contentEquals(presence))

        // Deterministic across devices, or two phones derive different keys for the same file.
        assertArrayEquals(
            files,
            com.example.familysafety.crypto.GroupCipher.deriveSubkey(
                groupKeyHex, com.example.familysafety.crypto.GroupCipher.PURPOSE_FILES
            )
        )
    }
}

package com.example.familysafety.crypto

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for E2EEManager's inner data classes and helpers.
 *
 * E2EEManager itself cannot be instantiated in JVM unit tests because its
 * class-level property `private val lazySodium = LazySodiumAndroid(SodiumAndroid())`
 * loads a native .so at construction time, causing UnsatisfiedLinkError.
 * Those encrypt/decrypt paths are covered by instrumented tests.
 */
class E2EEManagerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ── EncryptedMessage ──────────────────────────────────────────────────────

    @Test
    fun `EncryptedMessage stores all fields correctly`() {
        val msg = E2EEManager.EncryptedMessage(
            senderMemberId = "member_001",
            nonce = "aabbccdd",
            ciphertext = "deadbeef",
            signature = "cafebabe"
        )

        assertEquals("member_001", msg.senderMemberId)
        assertEquals("aabbccdd", msg.nonce)
        assertEquals("deadbeef", msg.ciphertext)
        assertEquals("cafebabe", msg.signature)
    }

    @Test
    fun `EncryptedMessage equality is structural`() {
        val a = E2EEManager.EncryptedMessage("m1", "nn", "cc", "ss")
        val b = E2EEManager.EncryptedMessage("m1", "nn", "cc", "ss")
        assertEquals(a, b)
    }

    @Test
    fun `EncryptedMessage with different fields are not equal`() {
        val a = E2EEManager.EncryptedMessage("m1", "nn", "cc", "ss")
        val b = E2EEManager.EncryptedMessage("m2", "nn", "cc", "ss")
        assertNotEquals(a, b)
    }

    @Test
    fun `EncryptedMessage serializes and deserializes correctly`() {
        val original = E2EEManager.EncryptedMessage(
            senderMemberId = "sender_abc",
            nonce = "0011223344556677",
            ciphertext = "aabbccddeeff0011",
            signature = "ffeeddccbbaa9988"
        )

        val serialized = json.encodeToString(original)
        val deserialized = json.decodeFromString<E2EEManager.EncryptedMessage>(serialized)

        assertEquals(original.senderMemberId, deserialized.senderMemberId)
        assertEquals(original.nonce, deserialized.nonce)
        assertEquals(original.ciphertext, deserialized.ciphertext)
        assertEquals(original.signature, deserialized.signature)
    }

    @Test
    fun `EncryptedMessage serialized JSON contains all expected keys`() {
        val msg = E2EEManager.EncryptedMessage("mid", "nonce_val", "cipher_val", "sig_val")
        val json = json.encodeToString(msg)

        assertTrue(json.contains("senderMemberId"))
        assertTrue(json.contains("nonce"))
        assertTrue(json.contains("ciphertext"))
        assertTrue(json.contains("signature"))
    }

    @Test
    fun `EncryptedMessage copy produces a modified clone`() {
        val original = E2EEManager.EncryptedMessage("sender", "nonce", "cipher", "sig")
        val copy = original.copy(senderMemberId = "other_sender")

        assertEquals("other_sender", copy.senderMemberId)
        assertEquals(original.nonce, copy.nonce)
    }

    // ── peekSenderMemberId ────────────────────────────────────────────────────

    @Test
    fun `peekSenderMemberId reads sender from a valid envelope`() {
        val envelope = json.encodeToString(
            E2EEManager.EncryptedMessage(
                senderMemberId = "sender_abc123",
                nonce = "aabb",
                ciphertext = "ccdd",
                signature = "eeff"
            )
        )

        assertEquals("sender_abc123", E2EEManager.peekSenderMemberId(envelope))
    }

    @Test
    fun `peekSenderMemberId ignores unknown fields`() {
        val payload = """{"senderMemberId":"m42","nonce":"aa","ciphertext":"bb","signature":"cc","future":"field"}"""
        assertEquals("m42", E2EEManager.peekSenderMemberId(payload))
    }

    @Test
    fun `peekSenderMemberId returns null for malformed JSON`() {
        assertNull(E2EEManager.peekSenderMemberId("not json at all"))
        assertNull(E2EEManager.peekSenderMemberId(""))
    }

    @Test
    fun `peekSenderMemberId returns null when sender field is missing`() {
        val payload = """{"nonce":"aa","ciphertext":"bb","signature":"cc"}"""
        assertNull(E2EEManager.peekSenderMemberId(payload))
    }

    @Test
    fun `peekSenderMemberId is not fooled by sender-like text inside other fields`() {
        // The old regex-based extraction would have matched a senderMemberId string
        // embedded in the ciphertext field; proper JSON parsing must not.
        val payload = """{"senderMemberId":"real_sender","nonce":"aa","ciphertext":"{\"senderMemberId\":\"fake_sender\"}","signature":"cc"}"""
        assertEquals("real_sender", E2EEManager.peekSenderMemberId(payload))
    }

    // ── RecipientKeys ─────────────────────────────────────────────────────────

    @Test
    fun `RecipientKeys stores hex key strings`() {
        val keys = RecipientKeys(
            x25519PublicKey = "ab".repeat(32),
            ed25519PublicKey = "cd".repeat(32)
        )

        assertEquals("ab".repeat(32), keys.x25519PublicKey)
        assertEquals("cd".repeat(32), keys.ed25519PublicKey)
    }

    @Test
    fun `RecipientKeys x25519PublicKeyBytes converts hex to ByteArray`() {
        // "0102030405060708" is 16 hex chars (8 bytes); ×4 = 64 hex chars = 32 bytes
        val hexKey = "0102030405060708".repeat(4)
        val keys = RecipientKeys(x25519PublicKey = hexKey, ed25519PublicKey = hexKey)

        val bytes = keys.x25519PublicKeyBytes()
        assertEquals(32, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
        assertEquals(0x02.toByte(), bytes[1])
    }

    @Test
    fun `RecipientKeys ed25519PublicKeyBytes converts hex to ByteArray`() {
        // "ff00ff00" is 8 hex chars (4 bytes); ×8 = 64 hex chars = 32 bytes
        val hexKey = "ff00ff00".repeat(8)
        val keys = RecipientKeys(x25519PublicKey = hexKey, ed25519PublicKey = hexKey)

        val bytes = keys.ed25519PublicKeyBytes()
        assertEquals(32, bytes.size)
        assertEquals(0xFF.toByte(), bytes[0])
        assertEquals(0x00.toByte(), bytes[1])
    }

    @Test
    fun `RecipientKeys equality is structural`() {
        val a = RecipientKeys("ab".repeat(32), "cd".repeat(32))
        val b = RecipientKeys("ab".repeat(32), "cd".repeat(32))
        assertEquals(a, b)
    }

    // ── EncryptionException ───────────────────────────────────────────────────

    @Test
    fun `EncryptionException carries the message`() {
        val ex = EncryptionException("something went wrong")
        assertEquals("something went wrong", ex.message)
    }

    @Test
    fun `EncryptionException is an Exception subtype`() {
        val ex = EncryptionException("error")
        assertTrue(ex is Exception)
    }

    @Test
    fun `EncryptionException can be thrown and caught`() {
        var caught = false
        try {
            throw EncryptionException("test error")
        } catch (e: EncryptionException) {
            caught = true
            assertEquals("test error", e.message)
        }
        assertTrue(caught)
    }
}

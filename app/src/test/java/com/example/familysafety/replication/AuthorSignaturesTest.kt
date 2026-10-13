package com.example.familysafety.replication

import com.example.familysafety.location.MemberLocation
import com.example.familysafety.storage.MessageType
import com.example.familysafety.transport.MessageProtocol
import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.MessageDigest

/** F24: history carries proof of who wrote each item, whoever passes it on. */
class AuthorSignaturesTest {

    private fun fakeSign(keyHex: String, message: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray() + message)
            .joinToString("") { "%02x".format(it) }

    private val verify = { message: ByteArray, sig: String, key: String -> fakeSign(key, message) == sig }
    private val danaKey = "dd".repeat(32)
    private val fix = MemberLocation("dana", 37.7749, -122.45000005, 12.5f, 1_700_000_000_000L, speed = 3.25f, bearing = 270f)

    @Test
    fun `location string uses fixed-point integers, half rounded up, negatives included`() {
        // floor(x * k + 0.5): -122.45000005 → -1224500000, where half-away-from-zero would give
        // -1224500001. Both platforms must use the same rule.
        assertEquals(
            "LOC:v1:dana:1700000000000:377749000:-1224500000:1250:325:27000",
            String(AuthorSignatures.location(fix))
        )
        assertEquals(
            "LOC:v1:dana:1700000000000:377749000:-1224500000:1250::",
            String(AuthorSignatures.location(fix.copy(speed = null, bearing = null)))
        )
    }

    @Test
    fun `chat string hashes the content and names the stored conversation`() {
        val contentHash = MessageDigest.getInstance("SHA-256").digest("hi".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals(
            "CHAT:v1:m1:alice:dana:dana:5:TEXT::$contentHash",
            String(AuthorSignatures.chatMessage("m1", "alice:dana", "dana", 5L, MessageType.TEXT.name, null, "hi"))
        )
    }

    @Test
    fun `a signed item by its author is accepted, whoever relays it`() {
        val msg = AuthorSignatures.location(fix)
        assertEquals(
            AuthorSignatures.Verdict.ACCEPT,
            AuthorSignatures.judge(fakeSign(danaKey, msg), danaKey, msg, acceptUnsigned = false, verify = verify)
        )
    }

    @Test
    fun `an item claiming someone else as author is dropped`() {
        val msg = AuthorSignatures.location(fix)
        val signedByBob = fakeSign("bb".repeat(32), msg)
        assertEquals(
            AuthorSignatures.Verdict.DROP,
            AuthorSignatures.judge(signedByBob, danaKey, msg, acceptUnsigned = true, verify = verify)
        )
    }

    @Test
    fun `an altered item no longer verifies`() {
        val sig = fakeSign(danaKey, AuthorSignatures.location(fix))
        val moved = AuthorSignatures.location(fix.copy(latitude = 40.0))
        assertEquals(AuthorSignatures.Verdict.DROP, AuthorSignatures.judge(sig, danaKey, moved, true, verify))
    }

    @Test
    fun `unsigned history is taken only during the grace release`() {
        val msg = AuthorSignatures.location(fix)
        assertEquals(AuthorSignatures.Verdict.ACCEPT, AuthorSignatures.judge(null, danaKey, msg, true, verify))
        assertEquals(AuthorSignatures.Verdict.DROP, AuthorSignatures.judge(null, danaKey, msg, false, verify))
    }

    @Test
    fun `history by someone no longer in the family can't be checked and is dropped`() {
        val msg = AuthorSignatures.location(fix)
        assertEquals(AuthorSignatures.Verdict.DROP, AuthorSignatures.judge(fakeSign(danaKey, msg), null, msg, true, verify))
    }

    @Test
    fun `a signed fix still pads moving and stationary to one size`() {
        val sig = "ab".repeat(64)
        val moving = MessageProtocol.encodeLocationUpdate(fix.copy(authorSig = sig))
        val still = MessageProtocol.encodeLocationUpdate(fix.copy(speed = null, bearing = null, authorSig = sig))
        assertEquals(moving.toByteArray().size, still.toByteArray().size)
    }
}

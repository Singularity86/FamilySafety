package com.example.familysafety.invite

import com.example.familysafety.group.GroupTransitionValidator
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/**
 * F13: a join request can only come from someone who saw the invite.
 *
 * Libsodium can't run on the JVM, so the envelope and Ed25519 are replaced by transparent
 * stand-ins with the same shape: "sealing" prefixes the sender's member ID, and a "signature"
 * is SHA-256(publicKeyHex ‖ message). What's under test is the verifier's logic, not the
 * primitives.
 */
class JoinV2Test {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val now = 1_700_000_000_000L
    private val secret = ByteArray(32) { 7 }
    private val invite = PendingInvite(
        inviteId = "0011223344556677",
        secret = Base64.getEncoder().encodeToString(secret),
        expiresAt = now + JoinV2.INVITE_LIFETIME_MS
    )

    private class Identity(seed: Byte) {
        val ed = ByteArray(32) { seed }
        val x = ByteArray(32) { (seed + 100).toByte() }
        val edHex = ed.joinToString("") { "%02x".format(it) }
        val xHex = x.joinToString("") { "%02x".format(it) }
        val memberId = GroupTransitionValidator.deriveMemberIdFromKey(edHex)
        fun sign(message: ByteArray) = fakeSign(edHex, message)
    }

    private val joiner = Identity(1)
    private val attacker = Identity(2)

    private fun signedRequest(who: Identity, name: String = "Dana", inviteId: String = invite.inviteId): JoinRequest {
        val unsigned = JoinRequest(
            requestId = "req-1", requesterId = who.memberId, displayName = name,
            ed25519PublicKey = who.ed, x25519PublicKey = who.x, groupId = "group_123",
            timestampMs = now, inviteId = inviteId
        )
        return unsigned.copy(signature = who.sign(unsigned.signedBytes()).toHex())
    }

    private fun seal(senderId: String, plaintext: String) = "$senderId|$plaintext"

    private fun message(who: Identity, secretUsed: ByteArray = secret, request: JoinRequest = signedRequest(who)) =
        ScannedInviteV2(invite.inviteId, secretUsed, "ed", "x", invite.expiresAt).requestMessage(
            signedRequest = request,
            encryptToInviter = { seal(who.memberId, it) },
            encode = { json.encodeToString(it) }
        )

    private var opened = 0
    private fun verify(message: JoinRequestV2Message, stored: PendingInvite? = invite, at: Long = now) =
        JoinV2Verifier.verify(
            message = message,
            now = at,
            findInvite = { id -> stored?.takeIf { it.inviteId == id } },
            envelopeSender = { it.substringBefore('|') },
            openEnvelope = { envelope, _, _ -> opened++; envelope.substringAfter('|') },
            verifySignature = { msg, sig, keyHex -> fakeSign(keyHex, msg).contentEquals(sig) },
            decodePayload = { json.decodeFromString<JoinRequestV2Payload>(it) }
        )

    private fun rejected(result: JoinV2Verifier.Result) = result is JoinV2Verifier.Result.Rejected

    @Test
    fun `an honest v2 request verifies and is marked verified`() {
        val result = verify(message(joiner))
        assertTrue(result is JoinV2Verifier.Result.Verified)
        assertTrue((result as JoinV2Verifier.Result.Verified).request.verifiedByInvite)
    }

    @Test
    fun `an attacker who replaces the retained request with their own keys is refused`() {
        // They can sign with their own key and seal to the inviter, but never saw the QR,
        // so the MAC is under the wrong secret.
        val forged = message(attacker, secretUsed = ByteArray(32) { 9 }, request = signedRequest(attacker, name = "Dana"))
        assertTrue(rejected(verify(forged)))
    }

    @Test
    fun `swapping the outer keys on a real request is refused`() {
        val real = message(joiner)
        val swapped = real.copy(requesterEd25519 = attacker.edHex, requesterX25519 = attacker.xHex)
        assertTrue(rejected(verify(swapped)))
    }

    @Test
    fun `an envelope naming an existing member is refused before it is opened`() {
        // Opening it would cache that member's shared secret against the wrong key.
        val real = message(joiner)
        val spoofed = real.copy(encryptedRequest = "someone_else" + real.encryptedRequest.dropWhile { it != '|' })
        assertTrue(rejected(verify(spoofed)))
        assertEquals(0, opened)
    }

    @Test
    fun `a changed display name breaks the signature`() {
        val request = signedRequest(joiner).copy(displayName = "Not Dana")
        assertTrue(rejected(verify(message(joiner, request = request))))
    }

    @Test
    fun `unknown, used and expired invites are refused`() {
        val m = message(joiner)
        assertTrue(rejected(verify(m, stored = null)))
        assertTrue(rejected(verify(m, stored = invite.copy(used = true))))
        assertTrue(rejected(verify(m, at = invite.expiresAt + 1)))
    }

    @Test
    fun `an unsigned request is refused`() {
        val unsigned = signedRequest(joiner).copy(signature = null)
        val m = ScannedInviteV2(invite.inviteId, secret, "ed", "x", invite.expiresAt).let {
            // requestMessage insists on a signature, so build this one by hand.
            JoinRequestV2Message(
                inviteId = invite.inviteId, requesterEd25519 = joiner.edHex, requesterX25519 = joiner.xHex,
                encryptedRequest = seal(joiner.memberId, json.encodeToString(
                    JoinRequestV2Payload(unsigned, JoinV2.mac(secret, unsigned.signedBytes()).toHex())
                ))
            )
        }
        assertTrue(rejected(verify(m)))
    }

    // ── The joiner reading the QR ───────────────────────────────────────────────

    private fun inviteMap(inviter: Identity, expiresAt: Long = invite.expiresAt) = mapOf(
        "groupId" to "group_123", "inviterMemberId" to inviter.memberId,
        JoinV2.FIELD_VERSION to "2", JoinV2.FIELD_INVITE_ID to invite.inviteId,
        JoinV2.FIELD_SECRET to invite.secret, JoinV2.FIELD_INVITER_ED25519 to inviter.edHex,
        JoinV2.FIELD_INVITER_X25519 to inviter.xHex, JoinV2.FIELD_EXPIRES_AT to expiresAt.toString()
    )

    @Test
    fun `a v1 invite means a v1 request`() {
        assertNull(ScannedInviteV2.from(mapOf("groupId" to "g", "inviterMemberId" to joiner.memberId), joiner.memberId, now))
    }

    @Test
    fun `a v2 invite whose keys aren't the inviter's is refused`() {
        try {
            ScannedInviteV2.from(inviteMap(attacker), inviterMemberId = joiner.memberId, now = now)
            fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.startsWith("Invalid invite code"))
        }
    }

    @Test
    fun `an expired v2 invite is refused`() {
        try {
            ScannedInviteV2.from(inviteMap(joiner, expiresAt = now - 1), joiner.memberId, now)
            fail("expected refusal")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("expired"))
        }
    }

    // ── Cross-platform vectors (iOS must produce the same) ──────────────────────

    @Test
    fun `fingerprint vector`() {
        assertEquals("367443", JoinV2.fingerprint(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
    }

    @Test
    fun `hmac vector`() {
        assertEquals(
            "d6855d7c0a3be87c363e0c5d5f3df3ac5ce67f4ad242ee224e932b34ae09efe4",
            JoinV2.mac(ByteArray(32) { 7 }, "JOIN:v2:test".toByteArray()).toHex()
        )
    }

    @Test
    fun `v2 signing bytes bind the invite and the name`() {
        val r = signedRequest(joiner)
        val text = String(r.signedBytes())
        assertTrue(text.startsWith("JOIN:v2:req-1:${joiner.memberId}:group_123:$now:"))
        assertTrue(text.endsWith(":${invite.inviteId}:${JoinV2.sha256Hex("Dana")}"))
        assertFalse(String(r.copy(inviteId = null).signedBytes()).startsWith("JOIN:v2"))
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    companion object {
        fun fakeSign(publicKeyHex: String, message: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(publicKeyHex.toByteArray() + message)
    }
}

package com.example.familysafety.invite

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupTransitionValidator
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Real Ed25519 signatures from the JDK, so these prove the joiner's signing and the inviter's
 * checking agree on [JoinRequest.signingBytes] — not just that the policy branches correctly.
 */
class RejoinPolicyTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A member identity: Ed25519 pair plus a stand-in X25519 public key. */
    private class Identity(val ed: KeyPair, val x25519: ByteArray) {
        val edPublic: ByteArray = ed.public.encoded.takeLast(32).toByteArray()
        val edHex: String = edPublic.toHex()
        val memberId: String = GroupTransitionValidator.deriveMemberIdFromKey(edHex)
    }

    private fun newIdentity(xFill: Byte = 7): Identity =
        Identity(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), ByteArray(32) { xFill })

    private val inviter = newIdentity(1)
    private val returning = newIdentity(2)

    private fun member(id: Identity, name: String) = FamilyMember(
        memberId = id.memberId,
        displayName = name,
        ed25519PublicKey = id.edHex,
        x25519PublicKey = id.x25519.toHex(),
        addedAtEpochMs = 1_000L
    )

    private val group = GroupDefinition(
        groupId = "group-1",
        groupName = "Test Family",
        createdAtEpochMs = 1_000L,
        creatorMemberId = inviter.memberId,
        members = setOf(member(inviter, "Mommy"), member(returning, "Saniya")),
        version = 3
    )

    private fun request(
        from: Identity,
        x25519: ByteArray = from.x25519,
        requesterId: String = from.memberId,
        signer: KeyPair? = from.ed
    ): JoinRequest {
        val unsigned = JoinRequest(
            requestId = "req-1",
            requesterId = requesterId,
            displayName = "Whoever",
            ed25519PublicKey = from.edPublic,
            x25519PublicKey = x25519,
            groupId = group.groupId,
            timestampMs = 5_000L
        )
        if (signer == null) return unsigned
        val sig = Signature.getInstance("Ed25519").run {
            initSign(signer.private)
            update(unsigned.signingBytes())
            sign()
        }
        return unsigned.copy(signature = sig.toHex())
    }

    /** What InviteManager passes in, done with the JDK instead of libsodium. */
    private val verify: (ByteArray, ByteArray, String) -> Boolean = { message, signature, keyHex ->
        val spki = "302a300506032b6570032100".hexToBytes() + keyHex.hexToBytes()
        val key = KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("Ed25519").run {
            initVerify(key)
            update(message)
            verify(signature)
        }
    }

    private fun evaluate(r: JoinRequest) = RejoinPolicy.evaluate(r, group, verify)

    @Test
    fun `a member's own restored phone is let back in without a prompt`() {
        assertEquals(RejoinPolicy.Verdict.AutoApprove, evaluate(request(returning)))
    }

    @Test
    fun `a newcomer is shown to the inviter`() {
        assertEquals(RejoinPolicy.Verdict.AskInviter, evaluate(request(newIdentity())))
    }

    @Test
    fun `someone who left and rejoins with the same phrase is shown to the inviter`() {
        val withoutThem = group.copy(members = setOf(member(inviter, "Mommy")))
        assertEquals(
            RejoinPolicy.Verdict.AskInviter,
            RejoinPolicy.evaluate(request(returning), withoutThem, verify)
        )
    }

    @Test
    fun `an unsigned request from a member, as older builds send, is shown to the inviter`() {
        assertEquals(RejoinPolicy.Verdict.AskInviter, evaluate(request(returning, signer = null)))
    }

    @Test
    fun `a member's public key signed by someone else is refused`() {
        val impostor = newIdentity()
        val verdict = evaluate(request(returning, signer = impostor.ed))
        assertTrue(verdict is RejoinPolicy.Verdict.Refuse)
    }

    @Test
    fun `a member's signing key with a different encryption key is refused`() {
        // The approval is encrypted to this key: accepting it would hand the family to the
        // requester. Refused even with a valid signature, and never offered for approval.
        val verdict = evaluate(request(returning, x25519 = ByteArray(32) { 9 }))
        assertTrue(verdict is RejoinPolicy.Verdict.Refuse)
    }

    @Test
    fun `a claimed member ID that does not match the key is refused`() {
        val verdict = evaluate(request(newIdentity(), requesterId = returning.memberId))
        assertTrue(verdict is RejoinPolicy.Verdict.Refuse)
    }

    @Test
    fun `tampering with any signed field breaks the signature`() {
        val signed = request(returning)
        listOf(
            signed.copy(requestId = "req-2"),
            signed.copy(timestampMs = 6_000L),
            signed.copy(groupId = "group-1", requestId = "other")
        ).forEach { tampered ->
            assertTrue(evaluate(tampered) is RejoinPolicy.Verdict.Refuse)
        }
    }

    @Test
    fun `the display name is not signed, and changing it does not matter`() {
        assertEquals(
            RejoinPolicy.Verdict.AutoApprove,
            evaluate(request(returning).copy(displayName = "Someone else"))
        )
    }

    @Test
    fun `keyConflict is null for a newcomer and for a matching member`() {
        assertNull(RejoinPolicy.keyConflict(request(newIdentity()), group))
        assertNull(RejoinPolicy.keyConflict(request(returning), group))
    }

    @Test
    fun `a request from an older build without a signature still decodes`() {
        val old = """{"requestId":"r","requesterId":"${returning.memberId}","displayName":"S",""" +
            """"ed25519PublicKey":"AA==","x25519PublicKey":"AA==","groupId":"g","timestampMs":1}"""
        assertNull(json.decodeFromString<JoinRequest>(old).signature)
    }

    @Test
    fun `the signature survives the trip through JSON`() {
        val signed = request(returning)
        val decoded = json.decodeFromString<JoinRequest>(json.encodeToString(signed))
        assertEquals(RejoinPolicy.Verdict.AutoApprove, evaluate(decoded))
    }

    private companion object {
        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
        fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

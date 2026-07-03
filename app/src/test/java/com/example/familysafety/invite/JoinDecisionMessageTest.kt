package com.example.familysafety.invite

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

/**
 * The joiner-side listener distinguishes approvals from rejections by decode
 * shape: it tries JoinApprovalMessage first and falls back to
 * JoinRejectionMessage. These tests pin down that the two wire formats can
 * never decode as each other.
 */
class JoinDecisionMessageTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val approval = JoinApprovalMessage(
        inviterEd25519PublicKey = "ab".repeat(32),
        inviterX25519PublicKey = "cd".repeat(32),
        encryptedGroupDefinition = """{"senderMemberId":"x","nonce":"n","ciphertext":"c","signature":"s"}"""
    )

    private val rejection = JoinRejectionMessage(
        inviterEd25519PublicKey = "ab".repeat(32),
        inviterX25519PublicKey = "cd".repeat(32),
        encryptedRejection = """{"senderMemberId":"x","nonce":"n","ciphertext":"c","signature":"s"}"""
    )

    @Test
    fun `approval round-trips through JSON`() {
        val decoded = json.decodeFromString<JoinApprovalMessage>(json.encodeToString(approval))
        assertEquals(approval, decoded)
    }

    @Test
    fun `rejection round-trips through JSON`() {
        val decoded = json.decodeFromString<JoinRejectionMessage>(json.encodeToString(rejection))
        assertEquals(rejection, decoded)
    }

    @Test
    fun `rejection JSON does not decode as an approval`() {
        val payload = json.encodeToString(rejection)
        assertThrows(Exception::class.java) {
            json.decodeFromString<JoinApprovalMessage>(payload)
        }
    }

    @Test
    fun `approval JSON does not decode as a rejection`() {
        val payload = json.encodeToString(approval)
        assertThrows(Exception::class.java) {
            json.decodeFromString<JoinRejectionMessage>(payload)
        }
    }

    @Test
    fun `rejection payload round-trips and binds request and joiner`() {
        val payload = JoinRejectionPayload(
            requestId = "req-123",
            joinerMemberId = "member-456",
            timestamp = 1_720_000_000_000L
        )
        val decoded = json.decodeFromString<JoinRejectionPayload>(json.encodeToString(payload))
        assertEquals("req-123", decoded.requestId)
        assertEquals("member-456", decoded.joinerMemberId)
        assertEquals(1_720_000_000_000L, decoded.timestamp)
    }
}

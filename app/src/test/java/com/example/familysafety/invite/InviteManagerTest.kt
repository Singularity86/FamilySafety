package com.example.familysafety.invite

import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.LazysodiumCryptoProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InviteManagerTest {

    private lateinit var mockCryptoProvider: LazysodiumCryptoProvider
    private lateinit var mockE2EEManager: E2EEManager
    private lateinit var inviteManager: InviteManager

    private fun makeRequest(id: String = "req_001") = JoinRequest(
        requestId = id,
        requesterId = "requester_$id",
        displayName = "Test User",
        ed25519PublicKey = ByteArray(32) { 0x01 },
        x25519PublicKey = ByteArray(32) { 0x02 },
        groupId = "group_123",
        timestampMs = 1_700_000_000_000L  // fixed so equals() is deterministic
    )

    @Before
    fun setup() {
        mockCryptoProvider = mockk(relaxed = true)
        mockE2EEManager = mockk(relaxed = true)
        inviteManager = InviteManager(mockCryptoProvider, mockE2EEManager)
    }

    // ── pendingJoinRequests ──────────────────────────────────────

    @Test
    fun `pendingJoinRequests initial state is empty`() {
        assertTrue(inviteManager.pendingJoinRequests.value.isEmpty())
    }

    // ── rejectJoinRequest ────────────────────────────────────────

    @Test
    fun `rejectJoinRequest removes request from pending list`() = runTest {
        val request = makeRequest("req_001")
        injectPendingRequests(listOf(request))

        val result = inviteManager.rejectJoinRequest(request)

        assertTrue(result.isSuccess)
        assertTrue(inviteManager.pendingJoinRequests.value.isEmpty())
    }

    @Test
    fun `rejectJoinRequest only removes matching request`() = runTest {
        val req1 = makeRequest("req_001")
        val req2 = makeRequest("req_002")
        injectPendingRequests(listOf(req1, req2))

        inviteManager.rejectJoinRequest(req1)

        val remaining = inviteManager.pendingJoinRequests.value
        assertEquals(1, remaining.size)
        assertEquals("req_002", remaining.first().requestId)
    }

    @Test
    fun `rejectJoinRequest is idempotent when request not present`() = runTest {
        val request = makeRequest("req_001")
        // List is empty; rejecting an absent request should not throw
        val result = inviteManager.rejectJoinRequest(request)
        assertTrue(result.isSuccess)
        assertTrue(inviteManager.pendingJoinRequests.value.isEmpty())
    }

    @Test
    fun `rejectJoinRequest returns success`() = runTest {
        val result = inviteManager.rejectJoinRequest(makeRequest())
        assertTrue(result.isSuccess)
    }

    // ── generateInviteCode ───────────────────────────────────────

    @Test
    fun `generateInviteCode returns failure when no group state manager`() = runTest {
        // groupStateManager is null by default (initialize() never called)
        val result = inviteManager.generateInviteCode()
        assertTrue(result.isFailure)
    }

    @Test
    fun `generateInviteCode returns base64 string when group is available`() = runTest {
        val groupDef = GroupDefinition(
            groupId = "group_123",
            groupName = "Test Family",
            createdAtEpochMs = 1000L,
            creatorMemberId = "member_001",
            members = setOf(
                FamilyMember(
                    memberId = "member_001",
                    displayName = "Alice",
                    ed25519PublicKey = "a".repeat(64),
                    x25519PublicKey = "b".repeat(64),
                    addedAtEpochMs = 1000L
                )
            ),
            version = 1L
        )
        injectGroupStateManager(groupDef)

        val result = inviteManager.generateInviteCode()

        assertTrue(result.isSuccess)
        val code = result.getOrNull()
        assertNotNull(code)
        // Must be valid Base64
        val decoded = java.util.Base64.getDecoder().decode(code)
        assertTrue(decoded.isNotEmpty())
        val json = String(decoded)
        assertTrue(json.contains("group_123"))
        assertTrue(json.contains("Test Family"))
    }

    // ── JoinRequest data class ───────────────────────────────────

    @Test
    fun `JoinRequest equals is based on requestId and keys`() {
        val req1 = makeRequest("id_A")
        val req2 = makeRequest("id_A")
        assertEquals(req1, req2)
    }

    @Test
    fun `JoinRequest with different requestId are not equal`() {
        val req1 = makeRequest("id_A")
        val req2 = makeRequest("id_B")
        assertNotEquals(req1, req2)
    }

    // ── Helpers ──────────────────────────────────────────────────

    private fun injectPendingRequests(requests: List<JoinRequest>) {
        val field = InviteManager::class.java.getDeclaredField("_pendingJoinRequests")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(inviteManager) as MutableStateFlow<List<JoinRequest>>
        flow.value = requests
    }

    private fun injectGroupStateManager(groupDef: GroupDefinition) {
        val mockGSM = mockk<GroupStateManager>(relaxed = true)
        every { mockGSM.groupDefinition } returns MutableStateFlow(groupDef)

        val field = InviteManager::class.java.getDeclaredField("groupStateManager")
        field.isAccessible = true
        field.set(inviteManager, mockGSM)

        val memberIdField = InviteManager::class.java.getDeclaredField("currentMemberId")
        memberIdField.isAccessible = true
        memberIdField.set(inviteManager, "member_001")
    }
}

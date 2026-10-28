package com.example.familysafety.security

import android.content.Context
import android.content.SharedPreferences
import com.example.familysafety.core.NetworkMonitor
import com.example.familysafety.crypto.E2EEManager
import com.example.familysafety.crypto.EncryptionException
import com.example.familysafety.crypto.EnvelopeReplay
import com.example.familysafety.crypto.RecipientKeys
import com.example.familysafety.crypto.ReplayGuard
import com.example.familysafety.crypto.ReplayedEnvelopeException
import com.example.familysafety.crypto.SequencedBody
import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.GroupStateManager
import com.example.familysafety.group.GroupStatePersistence
import com.example.familysafety.group.LazysodiumCryptoProvider
import com.example.familysafety.invite.InviteManager
import com.example.familysafety.invite.JoinApprovalValidator
import com.example.familysafety.invite.JoinRequest
import com.example.familysafety.location.LocationRepository
import com.example.familysafety.storage.FakePrefs
import com.example.familysafety.sync.ChangeType
import com.example.familysafety.sync.GroupSyncManager
import com.example.familysafety.sync.GroupSyncMessage
import com.example.familysafety.sync.GroupUpdateAck
import com.example.familysafety.transport.MessageProtocol
import com.example.familysafety.transport.MqttTransport
import com.example.familysafety.transport.PresenceTopics
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Every kind of security log entry, recorded where it happens: each test feeds that drop point
 * the bad input it exists to catch and checks the log holds exactly that one entry. The kinds
 * and details match iOS, so both apps' logs say the same thing about the same attack.
 */
class SecurityLogEntriesTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun member(id: String, ed: String = "a".repeat(64)) = FamilyMember(
        memberId = id, displayName = id, ed25519PublicKey = ed,
        x25519PublicKey = "b".repeat(64), addedAtEpochMs = 1000L
    )

    private val family = GroupDefinition(
        groupId = "group_123", groupName = "Family", createdAtEpochMs = 1000L,
        creatorMemberId = "alice", members = setOf(member("alice"), member("bob"), member("me")),
        version = 3L, removedMemberIds = setOf("gone#1")
    )

    /** Each preferences file a class asks for, kept in memory. */
    private val prefsFiles = mutableMapOf<String, FakePrefs>()
    private val context = mockk<Context>(relaxed = true).also { ctx ->
        every { ctx.getSharedPreferences(any(), any()) } answers {
            prefsFiles.getOrPut(firstArg()) { FakePrefs(mutableMapOf()) } as SharedPreferences
        }
    }

    @Before
    fun setUp() {
        SecurityRecorder.resetForTest()
        SecurityRecorder.install(mockk(relaxed = true), roster = { family }, myMemberId = { "me" })
    }

    @After
    fun tearDown() = SecurityRecorder.resetForTest()

    private fun assertOnlyEntry(kind: SecurityEventKind, memberId: String?, detail: String?) {
        assertEquals(
            listOf(Triple(kind, memberId, detail)),
            SecurityRecorder.log.value.events.map { Triple(it.kind, it.memberId, it.detail) }
        )
    }

    private fun assertNoEntries() = assertEquals(emptyList<SecurityEvent>(), SecurityRecorder.log.value.events)

    // ── Envelopes: E2EEManager and ReplayGuard ──────────────────────────────────

    private fun envelopeFrom(sender: String) =
        """{"senderMemberId":"$sender","nonce":"00","ciphertext":"00","signature":"00"}"""

    @Test
    fun `an envelope in a member's name with a bad signature is a forged signature`() {
        // The relaxed provider's verifySignature answers false: the signature doesn't verify.
        val e2ee = E2EEManager(mockk<LazysodiumCryptoProvider>(relaxed = true), mockk<ReplayGuard>(relaxed = true))

        assertThrows(EncryptionException::class.java) {
            e2ee.decryptMessage(envelopeFrom("bob"), ByteArray(32), ByteArray(32))
        }
        assertOnlyEntry(SecurityEventKind.FORGED_SIGNATURE, "bob", "envelope")
    }

    @Test
    fun `a stranger's envelope with a bad signature is not called a forgery`() {
        val e2ee = E2EEManager(mockk<LazysodiumCryptoProvider>(relaxed = true), mockk<ReplayGuard>(relaxed = true))

        assertThrows(EncryptionException::class.java) {
            e2ee.decryptMessage(envelopeFrom("stranger"), ByteArray(32), ByteArray(32))
        }
        assertNoEntries()
    }

    private val bobKey = "a".repeat(64)

    @Test
    fun `an envelope older than a week is a refused replay`() {
        val guard = ReplayGuard(context)

        assertThrows(ReplayedEnvelopeException::class.java) {
            guard.open("bob", bobKey, SequencedBody.encode(seq = 1, ts = 0, body = "hi"))
        }
        assertOnlyEntry(SecurityEventKind.REPLAY_REFUSED, "bob", "tooOld")
    }

    @Test
    fun `the same envelope arriving over LAN and broker is refused but not logged`() {
        val guard = ReplayGuard(context)
        val now = System.currentTimeMillis()
        val sealed = SequencedBody.encode(seq = now * 1000, ts = now, body = "hi")

        guard.open("bob", bobKey, sealed)
        assertThrows(ReplayedEnvelopeException::class.java) { guard.open("bob", bobKey, sealed) }
        assertNoEntries()
    }

    @Test
    fun `an unsequenced envelope after sequenced ones is a downgrade`() {
        val guard = ReplayGuard(context)
        val now = System.currentTimeMillis()

        guard.open("bob", bobKey, SequencedBody.encode(seq = now * 1000, ts = now, body = "hi"))
        // Past the newcomer grace: Bob first sequenced to us 11 minutes ago.
        context.getSharedPreferences("familysafety_replay_guard", 0).edit()
            .putLong("f:bob:$bobKey", now - EnvelopeReplay.SEQUENCING_GRACE_MS - 60_000).apply()
        assertThrows(ReplayedEnvelopeException::class.java) { guard.open("bob", bobKey, "plain") }
        assertOnlyEntry(SecurityEventKind.DOWNGRADE, "bob", null)
    }

    // ── Group sync ──────────────────────────────────────────────────────────────

    private fun syncManager(e2ee: E2EEManager): GroupSyncManager {
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(family)
        return GroupSyncManager(e2ee, mockk<LazysodiumCryptoProvider>(relaxed = true), mockk(relaxed = true))
            .also { it.initialize("me", gsm) }
    }

    @Test
    fun `a roster update whose signature fails is a forged signature`() = runTest {
        val e2ee = mockk<E2EEManager>(relaxed = true)
        val update = GroupSyncMessage(
            groupId = "group_123", version = 4L, groupDefinition = family.copy(version = 4L),
            updaterMemberId = "bob", changeType = ChangeType.NAME_CHANGED,
            timestamp = 0L, signature = "00"
        )
        every { e2ee.decryptMessage(any(), any(), any()) } returns json.encodeToString(update)

        syncManager(e2ee).handleSyncMessage(envelopeFrom("bob"))

        assertOnlyEntry(SecurityEventKind.FORGED_SIGNATURE, "bob", "roster update")
    }

    @Test
    fun `a plaintext ack in the name of a member who seals theirs is a forged control message`() = runTest {
        val e2ee = mockk<E2EEManager>(relaxed = true)
        every { e2ee.refusesUnsequenced("bob", any()) } returns true
        val ack = GroupUpdateAck(groupId = "group_123", version = 3L, memberId = "bob", timestamp = 0L)

        syncManager(e2ee).handleAckMessage(json.encodeToString(ack))

        assertOnlyEntry(SecurityEventKind.FORGED_CONTROL_MESSAGE, "bob", "GroupUpdateAck")
    }

    @Test
    fun `an encrypted message from outside the family is an unknown sender`() = runTest {
        syncManager(mockk(relaxed = true)).handleSyncMessage(envelopeFrom("stranger"))

        assertOnlyEntry(SecurityEventKind.UNKNOWN_SENDER, "stranger", "encrypted message")
    }

    @Test
    fun `an encrypted message from a removed member is removed-member activity`() = runTest {
        syncManager(mockk(relaxed = true)).handleSyncMessage(envelopeFrom("gone"))

        assertOnlyEntry(SecurityEventKind.REMOVED_MEMBER_ACTIVITY, "gone", "encrypted message")
    }

    // ── Roster rules: GroupStateManager ─────────────────────────────────────────

    @Test
    fun `a member renaming the family when only the creator may is a rejected roster update`() = runTest {
        val persistence = mockk<GroupStatePersistence>(relaxed = true)
        coEvery { persistence.loadGroupDefinition() } returns family
        val gsm = GroupStateManager("me", persistence, mockk(relaxed = true))
        gsm.initialize()
        val renamed = family.copy(version = 4L, groupName = "Renamed", previousStateHash = family.computeStateHash())

        gsm.applyVerifiedRemoteGroupState(renamed, "bob")

        assertOnlyEntry(SecurityEventKind.ROSTER_UPDATE_REJECTED, "bob", "only the group creator may rename the group")
    }

    // ── Join requests: InviteManager ────────────────────────────────────────────

    private fun inviteManager(group: GroupDefinition): InviteManager {
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(group)
        return InviteManager(
            context = context,
            cryptoProvider = mockk(relaxed = true),
            e2eeManager = mockk(relaxed = true),
            transportProvider = mockk(relaxed = true)
        ).also { it.initialize(memberId = "me", groupStateManager = gsm, groupSyncManager = mockk(relaxed = true)) }
    }

    private fun joinRequest(requesterId: String, ed: ByteArray) = JoinRequest(
        requestId = "req_1", requesterId = requesterId, displayName = "Someone",
        ed25519PublicKey = ed, x25519PublicKey = ByteArray(32) { 2 },
        groupId = "group_123", timestampMs = 0L
    )

    @Test
    fun `a join request whose ID doesn't match its key is a rejected join request`() = runTest {
        val request = joinRequest(requesterId = "not_derived_from_the_key", ed = ByteArray(32) { 1 })

        inviteManager(family).handleIncomingJoinRequest(json.encodeToString(request))

        assertOnlyEntry(SecurityEventKind.JOIN_REQUEST_REJECTED, null, "member ID does not match the signing key")
    }

    @Test
    fun `a join request using a member's ID with different keys is an impersonated join`() = runTest {
        // The ID is derived from the key, so the attacker uses a key whose ID is already a
        // member's, held on file with other keys.
        val ed = ByteArray(32) { 1 }
        val takenId = JoinApprovalValidator.deriveMemberId(ed.joinToString("") { "%02x".format(it) })
        val withVictim = family.copy(members = family.members + member(takenId, ed = "c".repeat(64)))

        inviteManager(withVictim).handleIncomingJoinRequest(json.encodeToString(joinRequest(takenId, ed)))

        assertOnlyEntry(SecurityEventKind.IMPERSONATED_JOIN, null, "member ID with different keys")
    }

    // ── Presence: MqttTransport ─────────────────────────────────────────────────

    /** A transport that knows the family and who it is, as after connecting, without connecting. */
    private fun transport(): MqttTransport {
        val gsm = mockk<GroupStateManager>(relaxed = true)
        every { gsm.groupDefinition } returns MutableStateFlow(null)
        every { gsm.memberStates } returns MutableStateFlow(emptyMap())
        val crypto = mockk<LazysodiumCryptoProvider>(relaxed = true)
        every { crypto.verifySignature(any(), any(), any<String>()) } returns true
        val t = MqttTransport(
            context, mockk<LocationRepository>(relaxed = true), mockk<E2EEManager>(relaxed = true),
            crypto, mockk<NetworkMonitor>(relaxed = true), gsm
        )
        setField(t, "memberId", "me")
        setField(t, "familyMemberKeys", family.members.associate {
            it.memberId to RecipientKeys(it.x25519PublicKey, it.ed25519PublicKey)
        }.toMutableMap())
        return t
    }

    private fun setField(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    private fun presence(from: String, device: String, at: Long) = MessageProtocol.encodePresenceUpdate(
        memberId = from, isOnline = true, sign = { ByteArray(64) }, deviceId = device, timestamp = at
    )

    @Test
    fun `a member online from two phones at once is a member on two phones`() = runTest {
        val t = transport()
        val now = System.currentTimeMillis()

        t.handleLocationOrPresencePublic(PresenceTopics.legacy("bob"), presence("bob", "phone-one", now - 1_000))
        t.handleLocationOrPresencePublic(PresenceTopics.legacy("bob"), presence("bob", "phone-two", now))

        assertOnlyEntry(SecurityEventKind.MEMBER_ON_TWO_PHONES, "bob", "device phone-tw")
    }

    @Test
    fun `our own identity online from another phone is identity in use elsewhere`() = runTest {
        val t = transport()

        t.handleLocationOrPresencePublic(
            PresenceTopics.legacy("me"), presence("me", "someone-elses-phone", System.currentTimeMillis())
        )

        assertOnlyEntry(SecurityEventKind.IDENTITY_IN_USE_ELSEWHERE, "me", "device someone-")
    }

    @Test
    fun `presence sealed under a family key this phone doesn't hold is an unknown group key`() = runTest {
        val sealed = MessageProtocol.encodeSealedPresence(MessageProtocol.SealedPresence(v = 2, data = "AAAA", kid = "deadbeef"))

        transport().handleLocationOrPresencePublic(PresenceTopics.legacy("bob"), sealed)

        assertOnlyEntry(SecurityEventKind.UNKNOWN_GROUP_KEY, "bob", null)
    }

    @Test
    fun `every kind has a test here`() {
        val tested = setOf(
            SecurityEventKind.IDENTITY_IN_USE_ELSEWHERE, SecurityEventKind.MEMBER_ON_TWO_PHONES,
            SecurityEventKind.JOIN_REQUEST_REJECTED, SecurityEventKind.IMPERSONATED_JOIN,
            SecurityEventKind.REMOVED_MEMBER_ACTIVITY, SecurityEventKind.FORGED_SIGNATURE,
            SecurityEventKind.FORGED_CONTROL_MESSAGE, SecurityEventKind.DOWNGRADE,
            SecurityEventKind.ROSTER_UPDATE_REJECTED, SecurityEventKind.REPLAY_REFUSED,
            SecurityEventKind.UNKNOWN_SENDER, SecurityEventKind.UNKNOWN_GROUP_KEY
        )
        assertTrue("A new kind needs a test above", tested.containsAll(SecurityEventKind.entries))
    }
}

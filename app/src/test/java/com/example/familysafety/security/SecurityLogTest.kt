package com.example.familysafety.security

import com.example.familysafety.group.FamilyMember
import com.example.familysafety.group.GroupDefinition
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** The security log: same kinds, grouping, retention and report as iOS `SecurityLog`. */
class SecurityLogTest {

    private val t0 = 1_800_000_000_000L
    private val hour = 60 * 60 * 1000L
    private val day = 24 * hour
    private val alice = "a".repeat(32)
    private val bob = "b".repeat(32)
    private val gone = "d".repeat(32)
    private val stranger = "e".repeat(32)

    private val family = GroupDefinition(
        groupId = "g",
        groupName = "Rivera",
        createdAtEpochMs = 1L,
        creatorMemberId = alice,
        members = setOf(
            FamilyMember(memberId = alice, displayName = "Ana", ed25519PublicKey = "11".repeat(32), x25519PublicKey = "22".repeat(32), addedAtEpochMs = 1L),
            FamilyMember(memberId = bob, displayName = "Ben", ed25519PublicKey = "33".repeat(32), x25519PublicKey = "44".repeat(32), addedAtEpochMs = 1L)
        ),
        version = 3L,
        removedMemberIds = setOf("$gone#1")
    )

    @After
    fun tearDown() = SecurityRecorder.resetForTest()

    @Test
    fun `raw values and titles match iOS`() {
        assertEquals(
            listOf(
                "identityInUseElsewhere", "memberOnTwoPhones", "joinRequestRejected", "impersonatedJoin",
                "removedMemberActivity", "forgedSignature", "forgedControlMessage", "downgrade",
                "rosterUpdateRejected", "replayRefused", "unknownSender", "unknownGroupKey"
            ),
            SecurityEventKind.entries.map { it.raw }
        )
        assertEquals("A member was online on two phones at once", SecurityEventKind.MEMBER_ON_TWO_PHONES.title)
        assertEquals(SecurityEventKind.Severity.HIGH, SecurityEventKind.IMPERSONATED_JOIN.severity)
        assertEquals(SecurityEventKind.Severity.LOW, SecurityEventKind.UNKNOWN_GROUP_KEY.severity)
    }

    @Test
    fun `repeats within an hour are grouped, and later or different ones are not`() {
        var log = SecurityLog()
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0)
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0 + 30 * 60_000)
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0 + 90 * 60_000) // within an hour of the last
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "tooOld", t0 + 91 * 60_000)  // different detail
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0 + 200 * 60_000) // gap over an hour
        assertEquals(listOf(3, 1, 1), log.events.map { it.count })
        assertEquals(t0, log.events[0].firstAtMs)
        assertEquals(t0 + 90 * 60_000, log.events[0].lastAtMs)
    }

    @Test
    fun `entries are kept 30 days and capped at 300`() {
        var log = SecurityLog().record(SecurityEventKind.DOWNGRADE, bob, null, t0)
        log = log.record(SecurityEventKind.UNKNOWN_SENDER, stranger, null, t0 + 31 * day)
        assertEquals(listOf(SecurityEventKind.UNKNOWN_SENDER), log.events.map { it.kind })

        var full = SecurityLog()
        repeat(305) { full = full.record(SecurityEventKind.UNKNOWN_SENDER, "%032x".format(it), null, t0 + it) }
        assertEquals(SecurityLog.CAP, full.events.size)
        assertEquals("%032x".format(5), full.events.first().memberId)
    }

    @Test
    fun `the report matches the iOS format`() {
        var log = SecurityLog()
        log = log.record(SecurityEventKind.JOIN_REQUEST_REJECTED, null, "invite already used", t0)
        log = log.record(SecurityEventKind.REMOVED_MEMBER_ACTIVITY, gone, "presence", t0 + hour)
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0 + 2 * hour)
        log = log.record(SecurityEventKind.REPLAY_REFUSED, bob, "replay", t0 + 2 * hour + 5 * 60_000)
        log = log.record(SecurityEventKind.UNKNOWN_SENDER, stranger, "encrypted message", t0 + 3 * hour)

        val report = log.report(family, alice, "1.15.6", t0 + 4 * hour, ZoneId.of("UTC"))
        assertEquals(
            """
            FamilySafety security report
            Made 2027-01-15 12:00 (UTC) on this phone (aaaaaaaa), app 1.15.6
            Family: Rivera
            Events in the last 30 days: 4
            No messages, locations or keys are included.

            [LOW] 2027-01-15 11:00 · Someone not in the family sent something · from eeeeeeee, not in the family · encrypted message
            [LOW] 2027-01-15 10:00 – 2027-01-15 10:05 · An old message was sent again and refused · from Ben (bbbbbbbb) · replay · ×2
            [HIGH] 2027-01-15 09:00 · Someone removed from the family sent something · from dddddddd, removed member · presence
            [MEDIUM] 2027-01-15 08:00 · A join request failed its checks · from — · invite already used
            """.trimIndent(),
            report
        )
    }

    @Test
    fun `an empty log says so`() {
        val report = SecurityLog().report(null, alice, "1.15.6", t0, ZoneId.of("UTC"))
        assertEquals("Family: none", report.lines()[2])
        assertEquals("Nothing unusual recorded.", report.lines().last())
    }

    @Test
    fun `strangers are told apart from removed members, and we are neither`() {
        SecurityRecorder.install(mockk(relaxed = true), roster = { family }, myMemberId = { alice })
        SecurityRecorder.recordStranger(gone, "presence")
        SecurityRecorder.recordStranger(stranger, "presence")
        SecurityRecorder.recordStranger(alice, "presence")
        assertEquals(
            listOf(SecurityEventKind.REMOVED_MEMBER_ACTIVITY to gone, SecurityEventKind.UNKNOWN_SENDER to stranger),
            SecurityRecorder.log.value.events.map { it.kind to it.memberId }
        )
    }
}

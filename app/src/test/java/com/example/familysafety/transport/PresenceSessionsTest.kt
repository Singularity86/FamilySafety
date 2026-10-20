package com.example.familysafety.transport

import com.example.familysafety.transport.PresenceSessions.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** A sudden disconnect shows as offline on phones already listening, and nothing else old does. */
class PresenceSessionsTest {

    private val bob = "b".repeat(32)
    private val t0 = 1_800_000_000_000L // connect: the will is signed now
    private val t1 = t0 + 60_000        // the live "online" after it

    private fun PresenceSessions.accept(isOnline: Boolean, ts: Long, start: Long? = null): Verdict =
        verdict(bob, isOnline, ts).also { accepted(bob, isOnline, ts, it, start) }

    @Test
    fun `the will of the session in progress is accepted though it is older`() {
        val s = PresenceSessions()
        assertEquals(Verdict.CURRENT, s.accept(true, t1, start = t0))
        assertEquals(Verdict.SESSION_WILL, s.verdict(bob, false, t0))
    }

    @Test
    fun `a session will is accepted once, and older updates stay replays after it`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t0)
        assertEquals(Verdict.SESSION_WILL, s.accept(false, t0))
        assertEquals(Verdict.STALE, s.verdict(bob, false, t0))
        assertEquals(Verdict.STALE, s.verdict(bob, true, t0 + 1))
        // The retained online redelivered at the same timestamp is still current, as before.
        assertEquals(Verdict.CURRENT, s.verdict(bob, true, t1))
    }

    @Test
    fun `an earlier session's will doesn't mark a reconnected member offline`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t0)
        val t2 = t1 + 60_000
        s.accept(true, t2 + 5, start = t2) // reconnected; the old connection's will comes late
        assertEquals(Verdict.STALE, s.verdict(bob, false, t0))
        assertEquals(Verdict.SESSION_WILL, s.verdict(bob, false, t2))
    }

    @Test
    fun `without a verified session start an older offline is still a replay`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = null) // older sender, or the session signature failed
        assertEquals(Verdict.STALE, s.verdict(bob, false, t0))
    }

    @Test
    fun `an older online is never a session will`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t0)
        assertEquals(Verdict.STALE, s.verdict(bob, true, t0))
    }

    @Test
    fun `a session start after its own update is ignored`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t1 + 1)
        assertEquals(Verdict.STALE, s.verdict(bob, false, t0))
    }

    @Test
    fun `a graceful offline ends the session`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t0)
        s.accept(false, t1 + 1)
        assertEquals(Verdict.STALE, s.verdict(bob, false, t0))
    }

    @Test
    fun `sessions are per member`() {
        val s = PresenceSessions()
        s.accept(true, t1, start = t0)
        assertEquals(Verdict.CURRENT, s.verdict("c".repeat(32), false, t0))
    }

    @Test
    fun `the session signing string`() {
        assertEquals(
            "presence-session:v1:alice:1700000000000:1699999990000",
            String(MessageProtocol.sessionSigningPayload("alice", 1_700_000_000_000L, 1_699_999_990_000L))
        )
    }

    @Test
    fun `online with a session start and the will pad to the same size`() {
        val sig = { _: ByteArray -> ByteArray(64) { 1 } }
        val id = "m".repeat(32)
        val online = MessageProtocol.encodePresenceUpdate(id, true, sig, deviceId = "d".repeat(32), timestamp = t1, sessionStart = t0)
        val will = MessageProtocol.encodePresenceUpdate(id, false, sig, deviceId = "d".repeat(32), timestamp = t0)
        assertEquals(MessageProtocol.PRESENCE_PADDED_BYTES, online.toByteArray().size)
        assertEquals(MessageProtocol.PRESENCE_PADDED_BYTES, will.toByteArray().size)
    }
}

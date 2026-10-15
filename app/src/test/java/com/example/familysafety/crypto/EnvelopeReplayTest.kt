package com.example.familysafety.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 3.4: the replay window inside every envelope. */
class EnvelopeReplayTest {

    private class MemoryStore : EnvelopeReplay.Store {
        var last = 0L
        val windows = mutableMapOf<String, ReplayWindow>()
        override fun lastSeq() = last
        override fun setLastSeq(seq: Long) { last = seq }
        override fun window(senderKey: String) = windows[senderKey]
        override fun setWindow(senderKey: String, window: ReplayWindow) { windows[senderKey] = window }
    }

    private val now = 1_800_000_000_000L
    private val bobKey = "b".repeat(64)
    private val day = 24 * 60 * 60 * 1000L

    private fun replay(store: EnvelopeReplay.Store = MemoryStore(), versions: Map<String, Int> = emptyMap()) =
        EnvelopeReplay(store) { versions[it] }

    private fun refused(block: () -> Unit): ReplayVerdict {
        try {
            block()
        } catch (e: ReplayedEnvelopeException) {
            return e.verdict
        }
        fail("expected a refusal")
        throw AssertionError()
    }

    @Test
    fun `sequence numbers rise and start from the clock`() {
        val r = replay()
        val a = r.nextSeq(now)
        val b = r.nextSeq(now)
        assertEquals(now * 1000, a)
        assertEquals(a + 1, b)
    }

    @Test
    fun `a clock that goes back does not reuse a sequence number`() {
        val r = replay()
        r.nextSeq(now)
        r.nextSeq(now)
        assertEquals(1_800_000_000_000_002L, r.nextSeq(now - 60_000))
    }

    @Test
    fun `a sealed body opens once and its replay is refused`() {
        val r = replay()
        val sealed = SequencedBody.encode(now * 1000, now, """{"x":1}""")

        assertEquals("""{"x":1}""", r.open("bob", bobKey, sealed, now))
        assertEquals(ReplayVerdict.REPLAY, refused { r.open("bob", bobKey, sealed, now) })
    }

    @Test
    fun `a replayed sync message is refused even after a restart`() {
        val store = MemoryStore()
        val sealed = SequencedBody.encode(now * 1000, now, """{"version":7}""")
        replay(store).open("bob", bobKey, sealed, now)

        // A fresh instance over the same saved windows, as after the app restarts.
        assertEquals(ReplayVerdict.REPLAY, refused { replay(store).open("bob", bobKey, sealed, now) })
    }

    @Test
    fun `an earlier message overtaken by a later one is still taken, across a restart`() {
        val store = MemoryStore()
        replay(store).open("bob", bobKey, SequencedBody.encode(now * 1000 + 11, now, "late"), now)

        assertEquals("early", replay(store).open("bob", bobKey, SequencedBody.encode(now * 1000 + 10, now, "early"), now))
    }

    @Test
    fun `past the cap the oldest are evicted and anything at or below them refused`() {
        var w = ReplayWindow.EMPTY
        for (seq in 1L..(EnvelopeReplay.SEEN_CAP + 1)) w = w.admit(seq)!!

        assertEquals(1L, w.floor)
        assertEquals(EnvelopeReplay.SEEN_CAP, w.seen.size)
        assertNull(w.admit(1L))
        assertTrue(w.admit(EnvelopeReplay.SEEN_CAP + 2L) != null)
    }

    @Test
    fun `a window survives being saved and read back`() {
        val w = ReplayWindow(5L, setOf(7L, 9L))
        assertEquals(w, ReplayWindow.deserialize(w.serialize()))
        assertEquals(ReplayWindow.EMPTY, ReplayWindow.deserialize(ReplayWindow.EMPTY.serialize()))
    }

    @Test
    fun `a body older than seven days is refused`() {
        val ts = now - 8 * day
        assertEquals(
            ReplayVerdict.TOO_OLD,
            refused { replay().open("bob", bobKey, SequencedBody.encode(ts * 1000, ts, "{}"), now) }
        )
    }

    @Test
    fun `a sender clock a year ahead is refused and the window does not move`() {
        val store = MemoryStore()
        val r = replay(store)
        r.open("bob", bobKey, SequencedBody.encode(now * 1000, now, "{}"), now)
        val before = store.windows.toMap()

        val ahead = now + 365 * day
        assertEquals(
            ReplayVerdict.FROM_THE_FUTURE,
            refused { r.open("bob", bobKey, SequencedBody.encode(ahead * 1000, ahead, "{}"), now) }
        )
        assertEquals(before, store.windows)
    }

    @Test
    fun `a sequence number beyond the future bound is refused even with a sane timestamp`() {
        val ahead = now + 2 * day
        assertEquals(
            ReplayVerdict.FROM_THE_FUTURE,
            refused { replay().open("bob", bobKey, SequencedBody.encode(ahead * 1000, now, "{}"), now) }
        )
    }

    @Test
    fun `an unsequenced body is taken until the sender has sequenced`() {
        val r = replay()
        assertEquals("""{"a":1}""", r.open("bob", bobKey, """{"a":1}""", now))

        r.open("bob", bobKey, SequencedBody.encode(now * 1000, now, "{}"), now)

        assertEquals(
            ReplayVerdict.UNSEQUENCED_AFTER_SEQUENCED,
            refused { r.open("bob", bobKey, """{"a":1}""", now) }
        )
    }

    @Test
    fun `windows are kept per key, so a joiner naming a member cannot move theirs`() {
        val store = MemoryStore()
        val r = replay(store)
        val far = SequencedBody.encode((now + day / 2) * 1000, now, "{}")
        r.open("bob", "e".repeat(64), far, now)

        assertFalse(r.hasSequenced("bob", bobKey))
        assertEquals("ok", r.open("bob", bobKey, SequencedBody.encode(now * 1000, now, "ok"), now))
    }

    @Test
    fun `a recipient on protocol 4, or who has sequenced to us, gets sequenced bodies`() {
        val r = replay(versions = mapOf("carol" to 4, "dave" to 3))
        assertTrue(r.sequenceFor("carol", null))
        assertFalse(r.sequenceFor("dave", null))
        assertFalse(r.sequenceFor("bob", bobKey))

        r.open("bob", bobKey, SequencedBody.encode(now * 1000, now, "{}"), now)
        assertTrue(r.sequenceFor("bob", bobKey))
    }

    @Test
    fun `ordinary message bodies are not mistaken for sequenced ones`() {
        assertNull(SequencedBody.parse("""{"groupId":"g","version":3,"timestamp":1}"""))
        assertNull(SequencedBody.parse("""{"type":"location_update","payload":"x"}"""))
        assertNull(SequencedBody.parse("not json"))
    }

    @Test
    fun `sequenced body vector`() {
        assertEquals(
            """{"seq":1800000000000000,"ts":1800000000000,"body":"{\"x\":1}"}""",
            SequencedBody.encode(1_800_000_000_000_000L, 1_800_000_000_000L, """{"x":1}""")
        )
    }
}

package com.example.familysafety.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LAN + broker duplicate filter for the security log (agreed with iOS). */
class RecentSequencesTest {

    private val now = 1_800_000_000_000L

    @Test
    fun `a copy of a sequence accepted within 10 minutes is a duplicate`() {
        val r = RecentSequences()
        r.accepted("bob", 7, now)
        assertTrue(r.isRecentDuplicate("bob", 7, now + RecentSequences.WINDOW_MS))
    }

    @Test
    fun `an older replay, another sequence or another sender is not`() {
        val r = RecentSequences()
        r.accepted("bob", 7, now)
        assertFalse(r.isRecentDuplicate("bob", 7, now + RecentSequences.WINDOW_MS + 1))
        assertFalse(r.isRecentDuplicate("bob", 8, now))
        assertFalse(r.isRecentDuplicate("eve", 7, now))
    }

    @Test
    fun `memory stays bounded, dropping the oldest`() {
        val r = RecentSequences(maxEntries = 3)
        (1L..5L).forEach { r.accepted("bob", it, now) }
        assertFalse(r.isRecentDuplicate("bob", 1, now))
        assertFalse(r.isRecentDuplicate("bob", 2, now))
        assertTrue(r.isRecentDuplicate("bob", 5, now))
    }
}

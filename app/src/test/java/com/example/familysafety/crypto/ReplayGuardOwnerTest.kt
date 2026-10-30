package com.example.familysafety.crypto

import android.content.Context
import android.content.SharedPreferences
import com.example.familysafety.storage.FakePrefs
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay windows belong to one identity. A phone that took on a new identity kept the old one's,
 * and refused the family's first messages to the new one, approval included (2026-10-27 test).
 */
class ReplayGuardOwnerTest {

    private val prefs = FakePrefs(mutableMapOf())
    private val context = mockk<Context>(relaxed = true).also {
        every { it.getSharedPreferences(any(), any()) } returns prefs as SharedPreferences
    }
    private val old = "a".repeat(64)
    private val new = "b".repeat(64)

    private fun seed(owner: String?) {
        prefs.edit().apply {
            owner?.let { putString("owner", it) }
            putString("w:bob:${"c".repeat(64)}", "5|7,9")
            putLong("f:bob:${"c".repeat(64)}", 1_000L)
            putLong("last_seq", 42L)
        }.commit()
    }

    @Test
    fun `a new identity drops the old one's windows but keeps our counter`() {
        seed(owner = old)
        ReplayGuard.belongTo(context, new)

        assertFalse(prefs.all.keys.any { it.startsWith("w:") || it.startsWith("f:") })
        assertEquals(42L, prefs.getLong("last_seq", 0L))
        assertEquals(new, prefs.getString("owner", null))
    }

    @Test
    fun `the same identity restored from its phrase keeps them`() {
        seed(owner = old)
        ReplayGuard.belongTo(context, old.uppercase())

        assertTrue(prefs.contains("w:bob:${"c".repeat(64)}"))
        assertTrue(prefs.contains("f:bob:${"c".repeat(64)}"))
    }

    @Test
    fun `windows saved before owners were recorded are dropped`() {
        seed(owner = null)
        ReplayGuard.belongTo(context, new)

        assertFalse(prefs.contains("w:bob:${"c".repeat(64)}"))
        assertEquals(new, prefs.getString("owner", null))
    }
}

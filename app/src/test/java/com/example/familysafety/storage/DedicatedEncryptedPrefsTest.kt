package com.example.familysafety.storage

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.KeyStoreException

/**
 * The rule under test: an unreadable store is reported, never deleted, and one store's
 * failure never touches another store's data or key.
 */
class DedicatedEncryptedPrefsTest {

    private val identityAlias = "familysafe_identity_master"
    private val dbAlias = "familysafety_db_master"

    @Test
    fun `fresh install opens a new store under its own alias`() {
        val backend = FakeBackend()

        val prefs = openIdentity(backend)
        prefs.edit().putString("k", "v").commit()

        assertEquals(listOf("keys_v2" to identityAlias), backend.opened)
        assertTrue(backend.deleted.isEmpty())
    }

    @Test
    fun `legacy store is copied under the dedicated alias, then removed`() {
        val backend = FakeBackend()
        backend.files["keys"] = FakePrefs(mutableMapOf("seed" to "abc", "initialized" to true, "index" to 0))

        val prefs = openIdentity(backend)

        assertEquals("abc", prefs.getString("seed", null))
        assertTrue(prefs.getBoolean("initialized", false))
        assertEquals(listOf("keys" to null, "keys_v2" to identityAlias), backend.opened)
        assertEquals(listOf("keys"), backend.deleted)
    }

    @Test
    fun `KeyStoreException on the store is reported and deletes nothing`() {
        val backend = FakeBackend()
        backend.files["keys_v2"] = FakePrefs(mutableMapOf("seed" to "abc"))
        backend.failOpen["keys_v2"] = KeyStoreException("keystore busy")

        assertUnavailable { openIdentity(backend) }

        assertTrue(backend.deleted.isEmpty())
        assertEquals("abc", backend.files.getValue("keys_v2").getString("seed", null))
    }

    @Test
    fun `GeneralSecurityException on the legacy store is reported and deletes nothing`() {
        val backend = FakeBackend()
        backend.files["keys"] = FakePrefs(mutableMapOf("seed" to "abc"))
        backend.failOpen["keys"] = GeneralSecurityException("bad keyset")

        assertUnavailable { openIdentity(backend) }

        assertTrue(backend.deleted.isEmpty())
        assertTrue("keys" in backend.files)
    }

    @Test
    fun `a failed copy leaves the legacy store in place for the next attempt`() {
        val backend = FakeBackend()
        backend.files["keys"] = FakePrefs(mutableMapOf("seed" to "abc"))
        backend.failOpen["keys_v2"] = GeneralSecurityException("cannot create key")

        assertUnavailable { openIdentity(backend) }

        assertFalse("keys" in backend.deleted)
        assertEquals("abc", backend.files.getValue("keys").getString("seed", null))
    }

    @Test
    fun `an interrupted migration is redone from the legacy store`() {
        val backend = FakeBackend()
        backend.files["keys"] = FakePrefs(mutableMapOf("seed" to "abc"))
        backend.files["keys_v2"] = FakePrefs(mutableMapOf()) // created, never filled

        val prefs = openIdentity(backend)

        assertEquals("abc", prefs.getString("seed", null))
        assertEquals(listOf("keys_v2", "keys"), backend.deleted)
    }

    @Test
    fun `one store failing leaves the other store and its key alone`() {
        val backend = FakeBackend()
        backend.files["keys"] = FakePrefs(mutableMapOf("seed" to "abc"))
        backend.files["db_prefs"] = FakePrefs(mutableMapOf("db_passphrase" to "cGFzcw=="))
        backend.failOpen["keys"] = KeyStoreException("identity unreadable")

        assertUnavailable { openIdentity(backend) }
        val db = DedicatedEncryptedPrefs.open(backend, "Database", "db_prefs_v2", dbAlias, "db_prefs")

        assertEquals("cGFzcw==", db.getString("db_passphrase", null))
        assertEquals("abc", backend.files.getValue("keys").getString("seed", null))
        assertEquals(listOf("db_prefs"), backend.deleted)
        // Each store wrote under its own alias; nothing but legacy reads used the shared one.
        assertEquals(
            setOf(dbAlias),
            backend.opened.mapNotNull { it.second }.toSet()
        )
    }

    private fun openIdentity(backend: FakeBackend) =
        DedicatedEncryptedPrefs.open(backend, "Identity", "keys_v2", identityAlias, "keys")

    private fun assertUnavailable(block: () -> Unit) {
        try {
            block()
            fail("expected StorageUnavailableException")
        } catch (e: StorageUnavailableException) {
            // expected
        }
    }

    private class FakeBackend : EncryptedPrefsBackend {
        val files = mutableMapOf<String, FakePrefs>()
        val failOpen = mutableMapOf<String, Exception>()
        val opened = mutableListOf<Pair<String, String?>>()
        val deleted = mutableListOf<String>()

        override fun exists(name: String) = name in files

        override fun open(name: String, keyAlias: String?): SharedPreferences {
            failOpen[name]?.let { throw it }
            opened += name to keyAlias
            return files.getOrPut(name) { FakePrefs(mutableMapOf()) }
        }

        override fun delete(name: String) {
            deleted += name
            files.remove(name)
        }
    }
}

/** In-memory SharedPreferences, enough for the copy-and-verify path. */
internal class FakePrefs(private val values: MutableMap<String, Any?>) : SharedPreferences {
    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) =
        values[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(pending)
            return true
        }
        override fun apply() { commit() }
    }
}

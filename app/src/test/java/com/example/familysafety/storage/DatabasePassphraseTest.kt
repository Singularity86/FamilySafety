package com.example.familysafety.storage

import android.content.SharedPreferences
import com.example.familysafety.group.EncryptedGroupStateSerializer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

class DatabasePassphraseTest {

    @Test
    fun `stored passphrase is returned`() {
        val bytes = ByteArray(32) { it.toByte() }
        val prefs = FakePrefs(
            mutableMapOf(FamilySafetyDatabase.KEY_DB_PASSPHRASE to Base64.getEncoder().encodeToString(bytes))
        )

        assertArrayEquals(bytes, FamilySafetyDatabase.resolvePassphrase(prefs, databaseExists = true))
    }

    @Test
    fun `new install gets a new passphrase, saved before use`() {
        val prefs = FakePrefs(mutableMapOf())

        val passphrase = FamilySafetyDatabase.resolvePassphrase(prefs, databaseExists = false)

        assertEquals(32, passphrase.size)
        assertArrayEquals(
            passphrase,
            Base64.getDecoder().decode(prefs.getString(FamilySafetyDatabase.KEY_DB_PASSPHRASE, null))
        )
    }

    @Test
    fun `existing database with no passphrase is reported, not replaced`() {
        val prefs = FakePrefs(mutableMapOf())

        try {
            FamilySafetyDatabase.resolvePassphrase(prefs, databaseExists = true)
            fail("expected StorageUnavailableException")
        } catch (e: StorageUnavailableException) {
            // expected
        }
        assertNull(prefs.getString(FamilySafetyDatabase.KEY_DB_PASSPHRASE, null))
    }

    @Test
    fun `a read that fails to decrypt is reported`() {
        val prefs = mockk<SharedPreferences>()
        every { prefs.getString(any(), any()) } throws SecurityException("could not decrypt value")

        try {
            FamilySafetyDatabase.resolvePassphrase(prefs, databaseExists = true)
            fail("expected StorageUnavailableException")
        } catch (e: StorageUnavailableException) {
            // expected
        }
    }

    @Test
    fun `truncated family state file is reported, not read as no family`() = runTest {
        assertCorrupt(byteArrayOf(0, 0, 0, 4, 1, 2))
    }

    @Test
    fun `family state file with a huge length field is reported without allocating it`() = runTest {
        assertCorrupt(byteArrayOf(0x7f, 0, 0, 0, 1))
    }

    private suspend fun assertCorrupt(bytes: ByteArray) {
        val serializer = EncryptedGroupStateSerializer(mockk(relaxed = true))
        try {
            serializer.readFrom(bytes.inputStream())
            fail("expected CorruptionException")
        } catch (e: androidx.datastore.core.CorruptionException) {
            // expected: defaultValue here would let the next write replace the family
        }
    }
}

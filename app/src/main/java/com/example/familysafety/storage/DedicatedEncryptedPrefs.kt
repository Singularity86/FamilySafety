package com.example.familysafety.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import timber.log.Timber
import java.io.File

/**
 * A store's encrypted data could not be read. Not the same as "there is nothing stored":
 * the data may well be intact and readable on the next attempt, so nothing that catches
 * this is allowed to delete it. Only an explicit reset, after the user has re-entered their
 * recovery phrase, may do that.
 */
class StorageUnavailableException(
    val store: String,
    cause: Throwable? = null
) : Exception("$store storage is unavailable", cause)

/**
 * What [DedicatedEncryptedPrefs] needs from the platform. Exists so the migration and its
 * failure rules can be tested on the JVM, where EncryptedSharedPreferences cannot run.
 */
interface EncryptedPrefsBackend {
    fun exists(name: String): Boolean

    /** Open [name] encrypted under [keyAlias]; null means the library's shared default alias. */
    fun open(name: String, keyAlias: String?): SharedPreferences

    fun delete(name: String)
}

/**
 * EncryptedSharedPreferences under a Keystore alias that belongs to one store only.
 *
 * Every store used to share `MasterKey.DEFAULT_MASTER_KEY_ALIAS`, and each one's recovery
 * path deleted that alias — so one store's transient failure destroyed every other store's
 * key. The identity keys and recovery phrase went with the database passphrase, silently.
 *
 * Existing installs are moved once: the legacy file is read under the default alias, copied
 * into a new file under the dedicated alias, verified, and only then deleted. The default
 * alias itself is never deleted, since another store may still be mid-migration from it.
 */
object DedicatedEncryptedPrefs {

    fun open(
        backend: EncryptedPrefsBackend,
        store: String,
        name: String,
        keyAlias: String,
        legacyName: String
    ): SharedPreferences {
        if (!backend.exists(legacyName)) {
            return openOrThrow(backend, store, name, keyAlias)
        }

        // The legacy file is deleted only after a verified copy, so while it exists any new
        // file is at best a partial copy from an interrupted migration. Start that over.
        if (backend.exists(name)) {
            Timber.w("$store: resuming an interrupted storage migration")
            backend.delete(name)
        }

        val entries = try {
            backend.open(legacyName, null).all.toMap()
        } catch (e: Exception) {
            throw StorageUnavailableException(store, e)
        }

        val target = openOrThrow(backend, store, name, keyAlias)
        try {
            val editor = target.edit()
            entries.forEach { (key, value) -> editor.putAny(key, value) }
            check(editor.commit()) { "commit failed" }
            check(target.all == entries) { "copy did not verify" }
        } catch (e: Exception) {
            runCatching { backend.delete(name) }
            throw StorageUnavailableException(store, e)
        }

        // A failed delete is harmless: the next open sees both files and copies again.
        runCatching { backend.delete(legacyName) }
        Timber.i("$store: moved to a dedicated Keystore key")
        return target
    }

    private fun openOrThrow(
        backend: EncryptedPrefsBackend,
        store: String,
        name: String,
        keyAlias: String
    ): SharedPreferences = try {
        backend.open(name, keyAlias)
    } catch (e: Exception) {
        throw StorageUnavailableException(store, e)
    }

    @Suppress("UNCHECKED_CAST")
    private fun SharedPreferences.Editor.putAny(key: String, value: Any?) {
        when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            is Float -> putFloat(key, value)
            is Set<*> -> putStringSet(key, value as Set<String>)
            null -> remove(key)
            else -> error("Unsupported preference type for $key")
        }
    }
}

class AndroidEncryptedPrefsBackend(private val context: Context) : EncryptedPrefsBackend {

    override fun exists(name: String): Boolean =
        File(context.dataDir, "shared_prefs/$name.xml").exists()

    override fun open(name: String, keyAlias: String?): SharedPreferences {
        val masterKey = MasterKey.Builder(context, keyAlias ?: MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override fun delete(name: String) {
        context.deleteSharedPreferences(name)
    }
}

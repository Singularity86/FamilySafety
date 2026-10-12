package com.example.familysafety.storage

import android.content.Context
import com.example.familysafety.group.AndroidKeyStoreLocalKeyStore
import com.example.familysafety.group.EncryptedGroupStatePersistence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The encrypted stores, named by what the user would lose. */
enum class StorageArea(val description: String) {
    IDENTITY("your keys and recovery phrase"),
    DATABASE("chat and location history"),
    FAMILY("your family's member list")
}

/**
 * Which encrypted stores exist but can't be read right now.
 *
 * Checked before anything starts that would read them: the main screen, the app's
 * background start-up, and the location service. Each of those used to meet an unreadable
 * store by deleting it.
 */
object StorageHealth {

    /** Identity and database. Synchronous: Keystore and prefs reads only. */
    fun unreadableKeyStores(context: Context): List<StorageArea> = buildList {
        probe(StorageArea.IDENTITY) { AndroidKeyStoreLocalKeyStore(context).isInitialized() }
        probe(StorageArea.DATABASE) { FamilySafetyDatabase.getPassphrase(context).fill(0) }
    }

    /** Every store, including the saved family. */
    suspend fun unreadableStores(context: Context): List<StorageArea> = withContext(Dispatchers.IO) {
        unreadableKeyStores(context) + buildList {
            probe(StorageArea.FAMILY) {
                EncryptedGroupStatePersistence.getInstance(context).loadGroupDefinition()
            }
        }
    }

    private inline fun MutableList<StorageArea>.probe(area: StorageArea, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Timber.e(e, "StorageHealth: $area storage is unreadable")
            add(area)
        }
    }
}

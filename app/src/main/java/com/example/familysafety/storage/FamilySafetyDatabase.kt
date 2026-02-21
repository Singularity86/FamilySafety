package com.example.familysafety.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

/**
 * Room database with SQLCipher encryption for storing location history and chat messages.
 *
 * All data is encrypted at rest using a key stored in Android Keystore.
 * This ensures that even if the device is compromised, the data remains protected.
 */
@Database(
    entities = [
        LocationHistoryEntity::class,
        ChatMessageEntity::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FamilySafetyDatabase : RoomDatabase() {

    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao

    companion object {
        private const val DATABASE_NAME = "familysafety_encrypted.db"
        private const val PREFS_NAME = "familysafety_db_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PASSPHRASE_LENGTH = 32

        @Volatile
        private var INSTANCE: FamilySafetyDatabase? = null

        /**
         * Get or create the encrypted database instance.
         */
        fun getInstance(context: Context): FamilySafetyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): FamilySafetyDatabase {
            val passphrase = getOrCreatePassphrase(context)
            val factory = SupportFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                FamilySafetyDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * Get existing passphrase or create a new one.
         * Passphrase is stored in EncryptedSharedPreferences backed by Android Keystore.
         *
         * If the Keystore key is in a corrupt state (e.g. after a reinstall), we clear
         * the stale prefs file and Keystore entry and start fresh.
         */
        private fun getOrCreatePassphrase(context: Context): ByteArray {
            return try {
                getOrCreatePassphraseInternal(context)
            } catch (e: Exception) {
                // Tink/Keystore corruption — wipe and retry with a clean slate.
                clearEncryptedPrefsAndKey(context)
                getOrCreatePassphraseInternal(context)
            }
        }

        private fun clearEncryptedPrefsAndKey(context: Context) {
            try { context.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (_: Exception) {}
        }

        private fun getOrCreatePassphraseInternal(context: Context): ByteArray {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            val existingPassphrase = prefs.getString(KEY_DB_PASSPHRASE, null)

            return if (existingPassphrase != null) {
                android.util.Base64.decode(existingPassphrase, android.util.Base64.NO_WRAP)
            } else {
                val newPassphrase = ByteArray(PASSPHRASE_LENGTH)
                SecureRandom().nextBytes(newPassphrase)
                prefs.edit()
                    .putString(
                        KEY_DB_PASSPHRASE,
                        android.util.Base64.encodeToString(newPassphrase, android.util.Base64.NO_WRAP)
                    )
                    .apply()
                newPassphrase
            }
        }

        /**
         * Close the database (for testing or app shutdown).
         */
        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

/**
 * Type converters for Room database.
 */
class Converters {
    @androidx.room.TypeConverter
    fun fromMessageStatus(status: MessageStatus): String = status.name

    @androidx.room.TypeConverter
    fun toMessageStatus(value: String): MessageStatus = MessageStatus.valueOf(value)

    @androidx.room.TypeConverter
    fun fromMessageType(type: MessageType): String = type.name

    @androidx.room.TypeConverter
    fun toMessageType(value: String): MessageType = MessageType.valueOf(value)
}

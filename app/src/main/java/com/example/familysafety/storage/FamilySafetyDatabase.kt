package com.example.familysafety.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
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
        ChatMessageEntity::class,
        SharedFileEntity::class,
        PendingLocationPublishEntity::class
    ],
    version = 3,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class FamilySafetyDatabase : RoomDatabase() {

    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun sharedFileDao(): SharedFileDao
    abstract fun pendingLocationPublishDao(): PendingLocationPublishDao

    companion object {
        private const val DATABASE_NAME = "familysafety_encrypted.db"
        private const val PREFS_NAME = "familysafety_db_prefs"
        private const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PASSPHRASE_LENGTH = 32

        @Volatile
        private var INSTANCE: FamilySafetyDatabase? = null

        fun getInstance(context: Context): FamilySafetyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): FamilySafetyDatabase {
            val passphrase = getOrCreatePassphrase(context)
            return buildRoomDatabase(context, passphrase)
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `shared_files` (
                        `fileId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `mimeType` TEXT NOT NULL,
                        `sizeBytes` INTEGER NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `uploaderMemberId` TEXT NOT NULL,
                        `uploadedAt` INTEGER NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `isDeleted` INTEGER NOT NULL DEFAULT 0,
                        `deletedByMemberId` TEXT,
                        `deletedAt` INTEGER,
                        `localPath` TEXT,
                        `chunksReceived` INTEGER NOT NULL DEFAULT 0,
                        `downloadState` TEXT NOT NULL DEFAULT 'PENDING',
                        PRIMARY KEY(`fileId`)
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `pending_location_publishes` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `memberId` TEXT NOT NULL,
                        `latitude` REAL NOT NULL,
                        `longitude` REAL NOT NULL,
                        `accuracy` REAL NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `speed` REAL,
                        `bearing` REAL,
                        `createdAt` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL DEFAULT 0,
                        `lastAttemptAt` INTEGER,
                        `lastError` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_location_publishes_memberId ON pending_location_publishes(memberId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_location_publishes_timestamp ON pending_location_publishes(timestamp)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_location_publishes_memberId_timestamp ON pending_location_publishes(memberId, timestamp)")
            }
        }

        private fun buildRoomDatabase(context: Context, passphrase: ByteArray): FamilySafetyDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                FamilySafetyDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(SupportFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .fallbackToDestructiveMigration()
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        // Called by Room when it wipes the DB — nothing extra needed.
                    }
                })
                .build()
        }

        /**
         * Get existing passphrase or create a new one.
         *
         * Recovery path: if the Keystore / Tink layer is corrupt (e.g. after a reinstall
         * that left a stale Keystore entry), we clear the encrypted prefs, the Keystore
         * alias, AND the SQLCipher database file (which would be unreadable with a new
         * passphrase anyway), then start completely fresh.
         */
        private fun getOrCreatePassphrase(context: Context): ByteArray {
            return try {
                getOrCreatePassphraseInternal(context)
            } catch (e: Exception) {
                // Wipe everything tied to the old key and retry.
                clearAll(context)
                getOrCreatePassphraseInternal(context)
            }
        }

        /**
         * Delete the encrypted prefs file, the Keystore master key alias, and the
         * SQLCipher database file so the app can start with a completely clean slate.
         */
        private fun clearAll(context: Context) {
            try { context.deleteSharedPreferences(PREFS_NAME) } catch (_: Exception) {}
            try {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                    ks.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
                }
            } catch (_: Exception) {}
            // Without the passphrase the database is unreadable — delete it too.
            try { context.deleteDatabase(DATABASE_NAME) } catch (_: Exception) {}
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
                // commit() — synchronous write so the passphrase is on disk before the
                // database file is created, preventing a mismatch on the next launch.
                prefs.edit()
                    .putString(
                        KEY_DB_PASSPHRASE,
                        android.util.Base64.encodeToString(newPassphrase, android.util.Base64.NO_WRAP)
                    )
                    .commit()
                newPassphrase
            }
        }

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

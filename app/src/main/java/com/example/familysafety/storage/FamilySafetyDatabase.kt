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
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
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
        FileAvailabilityEntity::class,
        FileTransferLogEntity::class,
        PendingLocationPublishEntity::class
    ],
    version = 7,
    // Exported so each version's schema is committed as JSON under app/schemas/. That is what
    // makes a migration testable — MigrationTestHelper builds the old schema from the exported
    // file, applies the migration and asserts the result. Without it a migration can only be
    // verified by shipping it.
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class FamilySafetyDatabase : RoomDatabase() {

    abstract fun locationHistoryDao(): LocationHistoryDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun sharedFileDao(): SharedFileDao
    abstract fun fileAvailabilityDao(): FileAvailabilityDao
    abstract fun fileTransferLogDao(): FileTransferLogDao
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

        /**
         * Explicit chunk accounting for shared files.
         *
         * `chunkBitmap` records which chunk indices are held, replacing a count of files in a
         * temp directory that could say how many chunks had arrived but never which ones —
         * so a single lost chunk stranded a file with no way to ask for it.
         *
         * `blobKeyVersion` records which key the in-flight blob's slots were encrypted under.
         * Slots are stored exactly as received so they can be retransmitted verbatim, which
         * means one blob cannot mix key versions.
         *
         * Additive only — no table rebuild, so existing rows are untouched. Rows mid-download
         * are moved back to PENDING: their progress lived entirely in a directory listing that
         * this release stops maintaining, so the honest reading of their state is "unknown",
         * and the blob will rebuild real accounting as chunks arrive.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_files ADD COLUMN chunkBitmap BLOB")
                db.execSQL("ALTER TABLE shared_files ADD COLUMN blobKeyVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    "UPDATE shared_files SET downloadState = 'PENDING', chunksReceived = 0 " +
                        "WHERE downloadState = 'DOWNLOADING'"
                )
            }
        }

        /**
         * Retry state for file transfers, so a stalled download is chased in the background
         * instead of waiting for a user to tap it.
         *
         * Deliberately the same columns as `pending_location_publishes` — attemptCount,
         * lastAttemptAt, lastError — plus `nextAttemptAt` for backoff and `lastRepairPeerId`
         * so requests rotate between peers rather than nagging one that cannot help.
         *
         * Stores intent rather than bytes: a 3200-chunk transfer is one row with a schedule,
         * not 3200 rows of payload.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_files ADD COLUMN attemptCount INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shared_files ADD COLUMN lastAttemptAt INTEGER")
                db.execSQL("ALTER TABLE shared_files ADD COLUMN nextAttemptAt INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE shared_files ADD COLUMN lastError TEXT")
                db.execSQL("ALTER TABLE shared_files ADD COLUMN lastRepairPeerId TEXT")
            }
        }

        /**
         * Essential pinning, and a record of what each peer holds.
         *
         * `isEssential` defaults to 1 so every existing file keeps replicating exactly as it
         * did — the on-demand behaviour is opt-in per file, never something that silently
         * stops an already-shared document from reaching a device.
         *
         * `file_availability` is what lets the app answer "this document is on three of four
         * phones", which is the only assurance that actually matters for an emergency
         * document and which nothing in the app could previously report.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shared_files ADD COLUMN isEssential INTEGER NOT NULL DEFAULT 1")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `file_availability` (
                        `fileId` TEXT NOT NULL,
                        `memberId` TEXT NOT NULL,
                        `contentHash` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `chunksHeld` INTEGER NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fileId`, `memberId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_availability_fileId ON file_availability(fileId)")
            }
        }

        /**
         * Append-only transfer log.
         *
         * A file once took half a day to arrive and nothing recorded whether it had been
         * retrying, blocked, or simply stopped. This is the table that makes that question
         * answerable — events only, trimmed by age, never payloads.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `file_transfer_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fileId` TEXT NOT NULL,
                        `at` INTEGER NOT NULL,
                        `event` TEXT NOT NULL,
                        `detail` TEXT
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_transfer_log_fileId ON file_transfer_log(fileId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_file_transfer_log_at ON file_transfer_log(at)")
            }
        }

        private fun buildRoomDatabase(context: Context, passphrase: ByteArray): FamilySafetyDatabase {
            // The retired android-database-sqlcipher artifact loaded its native library
            // implicitly; net.zetetic:sqlcipher-android requires the caller to do it. Without
            // this the first query fails with UnsatisfiedLinkError rather than anything that
            // names the cause.
            System.loadLibrary("sqlcipher")

            return Room.databaseBuilder(
                context.applicationContext,
                FamilySafetyDatabase::class.java,
                DATABASE_NAME
            )
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                // Deliberately NOT fallbackToDestructiveMigration(). It used to be here, and
                // it turns any mistake in a migration into a silent wipe of every user's chat
                // history and location history — data that exists on no server and cannot be
                // recovered. Failing loudly on a bad migration is strictly better than
                // deleting the thing the app exists to protect.
                //
                // A downgrade (older build opening a newer schema) still cannot be migrated
                // and would otherwise crash on every launch, so that one case is allowed to
                // reset — it only happens when someone side-grades backwards.
                .fallbackToDestructiveMigrationOnDowngrade()
                .addCallback(object : Callback() {
                    override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                        Timber.w("Database was reset by a destructive downgrade migration")
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

package com.example.familysafety.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import timber.log.Timber
import java.security.SecureRandom
import java.util.Base64

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
        PendingLocationPublishEntity::class,
        VaultBlobEntity::class,
        EmergencyOutboxEntity::class
    ],
    version = 10,
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
    abstract fun vaultBlobDao(): VaultBlobDao
    abstract fun emergencyOutboxDao(): EmergencyOutboxDao

    companion object {
        private const val DATABASE_NAME = "familysafety_encrypted.db"
        private const val PREFS_NAME = "familysafety_db_prefs_v2"
        private const val LEGACY_PREFS_NAME = "familysafety_db_prefs"
        private const val KEY_ALIAS = "familysafety_db_master"
        internal const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PASSPHRASE_LENGTH = 32
        private const val STORE = "Database"

        @Volatile
        private var INSTANCE: FamilySafetyDatabase? = null

        fun getInstance(context: Context): FamilySafetyDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): FamilySafetyDatabase =
            buildRoomDatabase(context)

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

        /**
         * Chunk accounting for vault document bytes.
         *
         * Every device gets this table whether or not anyone in the family ever sets a vault
         * code, and it stays empty until someone does — an empty table is not evidence of
         * anything, and a table created lazily on first use would be.
         *
         * It holds no names, types, sizes or hashes; see [VaultBlobEntity] for why that is
         * deliberate rather than incomplete.
         */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `vault_blobs` (
                        `fileId` TEXT NOT NULL,
                        `chunkCount` INTEGER NOT NULL,
                        `chunksReceived` INTEGER NOT NULL DEFAULT 0,
                        `chunkBitmap` BLOB,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`fileId`)
                    )
                """.trimIndent())
            }
        }

        /**
         * Crash alerts owed to each family member, retried until that member's phone
         * confirms delivery. See [EmergencyOutboxEntity]. Additive: a new, empty table.
         */
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `emergency_outbox` (
                        `alertId` TEXT NOT NULL,
                        `recipientId` TEXT NOT NULL,
                        `groupId` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `attemptCount` INTEGER NOT NULL,
                        `nextAttemptAt` INTEGER NOT NULL,
                        `lastAttemptAt` INTEGER,
                        `lastError` TEXT,
                        `deliveredAt` INTEGER,
                        PRIMARY KEY(`alertId`, `recipientId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_emergency_outbox_nextAttemptAt ON emergency_outbox(nextAttemptAt)")
            }
        }

        /**
         * Author signatures on chat messages and location fixes (F24), stored so that history
         * passed on to another phone still proves who wrote it. Additive: nullable columns, and
         * rows from before this release stay unsigned.
         */
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chat_messages ADD COLUMN authorSig TEXT")
                db.execSQL("ALTER TABLE location_history ADD COLUMN authorSig TEXT")
                db.execSQL("ALTER TABLE pending_location_publishes ADD COLUMN authorSig TEXT")
            }
        }

        private fun buildRoomDatabase(context: Context): FamilySafetyDatabase {
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
                // The passphrase is read when the database is first opened, not here. This
                // runs during Hilt injection of MainActivity, so a throw here would crash
                // every launch instead of reaching the storage-unavailable screen.
                .openHelperFactory(
                    DeferredOpenHelperFactory { SupportOpenHelperFactory(getPassphrase(context)) }
                )
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10
                )
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
         * Read the database passphrase, creating one only for a database that doesn't exist yet.
         *
         * Throws [StorageUnavailableException] rather than recovering. This used to catch any
         * exception, delete the shared Keystore alias and the database and start over, which
         * silently destroyed chat and location history, and through the shared alias the
         * identity keys and recovery phrase too.
         */
        fun getPassphrase(context: Context): ByteArray {
            val prefs = DedicatedEncryptedPrefs.open(
                backend = AndroidEncryptedPrefsBackend(context),
                store = STORE,
                name = PREFS_NAME,
                keyAlias = KEY_ALIAS,
                legacyName = LEGACY_PREFS_NAME
            )
            return resolvePassphrase(prefs, context.getDatabasePath(DATABASE_NAME).exists())
        }

        internal fun resolvePassphrase(prefs: SharedPreferences, databaseExists: Boolean): ByteArray {
            val stored = try {
                prefs.getString(KEY_DB_PASSPHRASE, null)
            } catch (e: Exception) {
                throw StorageUnavailableException(STORE, e)
            }
            if (stored != null) return Base64.getDecoder().decode(stored)

            // A database with no passphrase can't be opened, and a fresh passphrase would
            // not open it either. Report it; don't paper over it with a new empty database.
            if (databaseExists) {
                throw StorageUnavailableException(
                    STORE,
                    IllegalStateException("database exists but its passphrase is missing")
                )
            }

            val newPassphrase = ByteArray(PASSPHRASE_LENGTH).also { SecureRandom().nextBytes(it) }
            // commit() — synchronous write so the passphrase is on disk before the
            // database file is created, preventing a mismatch on the next launch.
            val saved = prefs.edit()
                .putString(KEY_DB_PASSPHRASE, Base64.getEncoder().encodeToString(newPassphrase))
                .commit()
            if (!saved) {
                throw StorageUnavailableException(STORE, IllegalStateException("passphrase write failed"))
            }
            return newPassphrase
        }

        /**
         * Delete the database, its passphrase and its Keystore key. Only for the reset the
         * user confirms with their recovery phrase on the storage-unavailable screen.
         */
        fun resetUnreadableStorage(context: Context) {
            closeDatabase()
            runCatching { context.deleteSharedPreferences(PREFS_NAME) }
            runCatching { context.deleteSharedPreferences(LEGACY_PREFS_NAME) }
            runCatching { context.deleteDatabase(DATABASE_NAME) }
            runCatching {
                val ks = java.security.KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
            }
        }

        fun closeDatabase() {
            INSTANCE?.close()
            INSTANCE = null
        }
    }
}

/**
 * Builds the real open helper the first time the database is opened, rather than when Room
 * is constructed. Room asks for the helper (and sets WAL on it) at build time, which is
 * long before the first query and happens inside dependency injection.
 */
internal class DeferredOpenHelperFactory(
    private val createFactory: () -> SupportSQLiteOpenHelper.Factory
) : SupportSQLiteOpenHelper.Factory {

    override fun create(
        configuration: SupportSQLiteOpenHelper.Configuration
    ): SupportSQLiteOpenHelper = DeferredOpenHelper(configuration)

    private inner class DeferredOpenHelper(
        private val configuration: SupportSQLiteOpenHelper.Configuration
    ) : SupportSQLiteOpenHelper {
        private val lock = Any()
        private var delegate: SupportSQLiteOpenHelper? = null
        private var writeAheadLogging: Boolean? = null

        private fun helper(): SupportSQLiteOpenHelper = synchronized(lock) {
            delegate ?: createFactory().create(configuration).also { created ->
                writeAheadLogging?.let { created.setWriteAheadLoggingEnabled(it) }
                delegate = created
            }
        }

        override val databaseName: String? get() = configuration.name

        override fun setWriteAheadLoggingEnabled(enabled: Boolean) = synchronized(lock) {
            writeAheadLogging = enabled
            delegate?.setWriteAheadLoggingEnabled(enabled)
            Unit
        }

        override val writableDatabase: SupportSQLiteDatabase get() = helper().writableDatabase

        override val readableDatabase: SupportSQLiteDatabase get() = helper().readableDatabase

        override fun close() = synchronized(lock) {
            delegate?.close()
            Unit
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

package com.example.familysafety.security

import android.content.Context
import android.content.SharedPreferences
import com.example.familysafety.group.GroupDefinition
import com.example.familysafety.group.Tombstones
import com.example.familysafety.storage.AndroidEncryptedPrefsBackend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * The phone's one security log, written to from wherever a drop already happens (the
 * transport, sync, join and crypto code), so it is process-wide rather than injected into each.
 * Keeps entries in memory until [install] opens the file; unit tests that build those classes
 * directly never install it, so nothing is written.
 *
 * Kept in its own encrypted preferences file. A new entry is saved at once, a repeat at most
 * every 5 s, so a burst of junk from outside can't become a burst of disk writes.
 */
object SecurityRecorder {

    private const val PREFS_NAME = "familysafety_security_log"
    private const val KEY_ALIAS = "familysafety_security_log_key"
    private const val KEY_LOG = "log"
    private const val SAVE_SPACING_MS = 5_000L

    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "security-log").apply { isDaemon = true } }

    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var roster: () -> GroupDefinition? = { null }
    @Volatile private var myMemberId: () -> String? = { null }
    private var lastSaveMs = 0L

    private val _log = MutableStateFlow(SecurityLog())
    val log: StateFlow<SecurityLog> = _log.asStateFlow()

    /** Opens the stored log. [roster] and [myMemberId] let callers that know only an ID be classified. */
    fun install(context: Context, roster: () -> GroupDefinition?, myMemberId: () -> String?) {
        this.roster = roster
        this.myMemberId = myMemberId
        writer.execute {
            try {
                val p = AndroidEncryptedPrefsBackend(context.applicationContext).open(PREFS_NAME, KEY_ALIAS)
                val stored = p.getString(KEY_LOG, null)?.let { runCatching { json.decodeFromString<SecurityLog>(it) }.getOrNull() }
                synchronized(this) {
                    // Anything recorded before the file opened is kept, after the stored entries.
                    if (stored != null) _log.value = SecurityLog(stored.events + _log.value.events)
                    prefs = p
                }
            } catch (e: Exception) {
                // The log is a convenience; an unreadable file must not stop anything else.
                Timber.w(e, "Security log storage unavailable; keeping it in memory")
            }
        }
    }

    fun record(kind: SecurityEventKind, memberId: String? = null, detail: String? = null) {
        val now = System.currentTimeMillis()
        val save: String?
        synchronized(this) {
            val after = _log.value.record(kind, memberId, detail, now)
            _log.value = after
            val newEntry = after.events.lastOrNull()?.let {
                it.count == 1 && it.firstAtMs == now && it.kind == kind && it.memberId == memberId && it.detail == detail
            } == true
            save = if (prefs != null && (newEntry || now - lastSaveMs >= SAVE_SPACING_MS)) {
                lastSaveMs = now
                json.encodeToString(SecurityLog.serializer(), after)
            } else null
        }
        save?.let { text -> writer.execute { runCatching { prefs?.edit()?.putString(KEY_LOG, text)?.apply() } } }
    }

    /**
     * Someone outside the roster sent something: a removed member is worth more attention than
     * a stranger, and our own ID is not a stranger.
     */
    fun recordStranger(memberId: String, detail: String) {
        if (memberId == myMemberId()) return
        val group = roster()
        if (group != null && group.removedMemberIds.any { Tombstones.memberIdOf(it) == memberId }) {
            record(SecurityEventKind.REMOVED_MEMBER_ACTIVITY, memberId, detail)
        } else {
            record(SecurityEventKind.UNKNOWN_SENDER, memberId, detail)
        }
    }

    /** Whether [memberId] is in the current roster; false before [install]. */
    fun isMember(memberId: String): Boolean = roster()?.findMemberById(memberId) != null

    /** Saves the latest state now, e.g. before the report is built or the app goes to the background. */
    fun flush() {
        val text = synchronized(this) { json.encodeToString(SecurityLog.serializer(), _log.value) }
        writer.execute { runCatching { prefs?.edit()?.putString(KEY_LOG, text)?.apply() } }
    }

    /** Tests only: forget everything and stop persisting. */
    internal fun resetForTest() {
        synchronized(this) {
            prefs = null
            roster = { null }
            myMemberId = { null }
            lastSaveMs = 0L
            _log.value = SecurityLog()
        }
    }
}

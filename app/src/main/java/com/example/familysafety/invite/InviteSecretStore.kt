package com.example.familysafety.invite

import android.content.SharedPreferences
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import java.util.Base64

/**
 * The secrets behind the invites this phone has handed out (join v2).
 *
 * Each invite screen visit makes a new one. They live in app-private storage for a day and are
 * single use: answering a request, either way, uses its invite up. The secret only ever leaves
 * the phone inside the QR code, which is the point: a request that proves knowledge of it came
 * from someone who saw the screen.
 */
class InviteSecretStore(
    private val prefs: SharedPreferences,
    private val random: SecureRandom = SecureRandom()
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PendingInvite.serializer())

    @Synchronized
    fun create(now: Long): Pair<PendingInvite, ByteArray> {
        val secret = ByteArray(JoinV2.SECRET_BYTES).also { random.nextBytes(it) }
        val id = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val invite = PendingInvite(
            inviteId = id,
            secret = Base64.getEncoder().encodeToString(secret),
            expiresAt = now + JoinV2.INVITE_LIFETIME_MS
        )
        save(load(now) + invite)
        return invite to secret
    }

    @Synchronized
    fun find(inviteId: String, now: Long): PendingInvite? = load(now).firstOrNull { it.inviteId == inviteId }

    @Synchronized
    fun markUsed(inviteId: String, now: Long) {
        save(load(now).map { if (it.inviteId == inviteId) it.copy(used = true) else it })
    }

    /** Stored invites, minus expired ones. */
    private fun load(now: Long): List<PendingInvite> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
            .filter { it.expiresAt >= now }
    }

    private fun save(invites: List<PendingInvite>) {
        // commit(): a secret lost to a process kill would refuse a real joiner.
        prefs.edit().putString(KEY, json.encodeToString(serializer, invites.takeLast(MAX_INVITES))).commit()
    }

    companion object {
        const val PREFS_NAME = "familysafety_invites"
        private const val KEY = "pending_invites"
        /** Plenty for a day of invite-screen visits; the oldest drop first. */
        private const val MAX_INVITES = 50
    }
}

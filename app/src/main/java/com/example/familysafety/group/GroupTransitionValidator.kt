package com.example.familysafety.group

import java.security.MessageDigest

/**
 * Authorization rules for applying a remote GroupDefinition on top of the local one.
 *
 * The sync layer verifies WHO signed an update (envelope + payload signatures);
 * this validator decides whether that member was ALLOWED to make the change.
 * Without it, any group member could broadcast a state that removes the creator,
 * swaps another member's keys, or rewrites the group identity.
 *
 * Policy (matches the local rules in GroupStateManager):
 * - groupId, creatorMemberId, createdAtEpochMs are immutable
 * - the updater must be a member of the state being upgraded FROM
 * - a member's keys can never change under the same member ID (no key rotation)
 * - added members must have memberId == SHA-256(ed25519 key).take(16)
 * - only the creator may remove other members; anyone may remove themselves
 * - removal tombstones are append-only, and a tombstoned member may never reappear in
 *   the roster (see GroupStateMerge — merging two rosters would otherwise readmit anyone
 *   the other side had just removed)
 * - only the creator may rename the group
 * - for a direct successor (version + 1), previousStateHash must match the
 *   current state's hash; across version jumps the chain cannot be verified
 *   because peers only hold the latest state
 *
 * Deliberately NOT enforced: displayName/avatarHash/colorHue changes by an
 * updater other than the member concerned. Members relaying FULL_SYNC responses
 * re-sign states containing changes they did not author, and cosmetic fields are
 * not worth breaking that recovery path over.
 *
 * Known limitation: a REMOVAL relayed by a non-creator (e.g. a FULL_SYNC response
 * to a peer that missed the original update) is rejected, because a relayed
 * removal is indistinguishable from a forged one. Affected peers still converge:
 * the creator's original broadcast is QoS 1 into each member's persistent
 * session, and refresh requests are answered by every peer including the creator.
 */
object GroupTransitionValidator {

    /**
     * Prefix identifying a broken hash chain, so callers can tell it apart from a genuine
     * authorization failure. A chain mismatch means the two states diverged and both moved
     * on — recoverable by merging — whereas the other rejections mean the update must not be
     * applied at all.
     */
    const val CHAIN_MISMATCH_PREFIX = "previousStateHash"


    /** memberId = SHA-256(ed25519PublicKeyBytes).take(16).toHex() — must match CryptoProvider.deriveMemberId. */
    fun deriveMemberIdFromKey(ed25519PublicKeyHex: String): String {
        val publicKey = ed25519PublicKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(publicKey)
            .take(16)
            .joinToString("") { "%02x".format(it) }
    }

    /**
     * @return null if [remote] is an authorized successor of [current] made by
     *         [updaterMemberId], otherwise a human-readable rejection reason.
     */
    fun validate(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String
    ): String? = check(current, remote, updaterMemberId, concurrent = false)

    /**
     * Same rules, for a state that is a concurrent sibling of ours rather than a successor
     * (see [GroupStateMerge]). Two differences, both forced by what an equal version means:
     *
     * - a member we hold and they lack has not been removed, only not yet learned about, so
     *   the untombstoned-removal rule cannot apply. Genuine removals still have to be
     *   carried by a tombstone, and tombstones are validated exactly as strictly here.
     * - there is no chain to check: siblings share our parent, they do not descend from us.
     *
     * Everything protecting identity — immutable group fields, no key rotation, added
     * members hashing to their own ID, append-only tombstones — is enforced unchanged.
     */
    fun validateConcurrent(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String
    ): String? = check(current, remote, updaterMemberId, concurrent = true)

    private fun check(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String,
        concurrent: Boolean
    ): String? {
        if (remote.groupId != current.groupId) {
            return "group ID changed"
        }
        if (remote.creatorMemberId != current.creatorMemberId) {
            return "creator changed"
        }
        if (remote.createdAtEpochMs != current.createdAtEpochMs) {
            return "creation timestamp changed"
        }

        if (current.findMemberById(updaterMemberId) == null) {
            return "updater ${updaterMemberId.take(8)} is not a member of our current group state"
        }

        if (!concurrent &&
            remote.version == current.version + 1 &&
            remote.previousStateHash != current.computeStateHash()
        ) {
            return "$CHAIN_MISMATCH_PREFIX does not chain from our current state"
        }

        val currentById = current.members.associateBy { it.memberId }
        val remoteById = remote.members.associateBy { it.memberId }

        for ((id, member) in remoteById) {
            val existing = currentById[id]
            if (existing != null) {
                if (!member.ed25519PublicKey.equals(existing.ed25519PublicKey, ignoreCase = true) ||
                    !member.x25519PublicKey.equals(existing.x25519PublicKey, ignoreCase = true)
                ) {
                    return "keys changed for member ${id.take(8)} — key rotation is not supported"
                }
            } else {
                val derivedId = try {
                    deriveMemberIdFromKey(member.ed25519PublicKey)
                } catch (e: Exception) {
                    return "added member ${id.take(8)} has a malformed key"
                }
                if (derivedId != id) {
                    return "added member ${id.take(8)} has an ID that does not match its signing key"
                }
            }
        }

        // Tombstones are append-only, but a state that simply lacks them is NOT an attempt
        // to withdraw them. `removedMemberIds` defaults to empty, so every peer on a build
        // from before tombstones existed sends exactly that — and this used to read it as
        // "they dropped our tombstones" and reject the whole update.
        //
        // The effect was severe and easy to miss: as soon as one member left, creating the
        // first tombstone, a device stopped accepting *any* group update from an older peer,
        // including member additions. Rosters diverged and stayed diverged, which looks to
        // the user like family members going missing.
        //
        // Our tombstones are preserved by the caller unioning them in, so nothing is lost by
        // accepting. What still must not happen is a tombstoned member reappearing in the
        // roster — checked below against the union, so it holds whether or not the sender
        // knows about tombstones at all.
        val effectiveTombstones = current.removedMemberIds + remote.removedMemberIds
        val resurrected = remoteById.keys.intersect(effectiveTombstones)
        if (resurrected.isNotEmpty()) {
            return "removed member ${resurrected.first().take(8)} is present in the roster"
        }

        // Authorizing a *new* tombstone follows the same rule as the removal it records:
        // the creator may remove anyone, anyone may remove themselves.
        val newTombstones = remote.removedMemberIds - current.removedMemberIds
        if (newTombstones.isNotEmpty() &&
            updaterMemberId != current.creatorMemberId &&
            newTombstones != setOf(updaterMemberId)
        ) {
            return "only the group creator may remove other members " +
                "(updater ${updaterMemberId.take(8)} tombstoned ${newTombstones.size})"
        }

        // A member may also disappear from the roster without a tombstone — that is what
        // every build before tombstones existed produced, and what a relayed FULL_SYNC from
        // such a peer still looks like.
        val removedIds = currentById.keys - remoteById.keys - remote.removedMemberIds
        if (!concurrent &&
            removedIds.isNotEmpty() &&
            updaterMemberId != current.creatorMemberId &&
            removedIds != setOf(updaterMemberId)
        ) {
            return "only the group creator may remove other members " +
                "(updater ${updaterMemberId.take(8)} removed ${removedIds.size})"
        }

        if (remote.groupName != current.groupName && updaterMemberId != current.creatorMemberId) {
            return "only the group creator may rename the group"
        }

        return null
    }
}

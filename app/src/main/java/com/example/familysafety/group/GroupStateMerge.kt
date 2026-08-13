package com.example.familysafety.group

/**
 * Deterministic reconciliation of two group states that were edited concurrently.
 *
 * ## Why this exists
 *
 * Any member may approve a join, so two members who approve at the same moment both
 * produce a state at version N+1 from the same parent — same version number, different
 * contents. Before this, the sync layer treated a same-version message as "nothing to do",
 * acknowledged it, and dropped it. Both devices then believed they were in sync while
 * holding permanently different rosters, and every later update failed the hash-chain check
 * because it descended from the other branch. Observed in the wild on 2026-08-12: a device
 * sat at v5 with three members while the rest of the family ran v6 with four, receiving the
 * fourth member's traffic and discarding it as an unknown sender.
 *
 * Telling users "only one person should approve joins at a time" is not a fix. This is.
 *
 * ## How it converges
 *
 * Membership has exactly two concurrent operations: additions (any member) and removals
 * (creator or self only). Both commute, so the states can be merged rather than arbitrated:
 *
 * 1. Both sides pick the same **winner** — the state with the lexicographically smaller
 *    state hash. The hash is already deterministic across devices, so no coordination,
 *    clock, or tie-break authority is needed, and the choice is stable no matter which
 *    order the two devices learn about each other.
 * 2. The loser rebuilds its state as a *successor of the winner*: the union of both
 *    rosters, minus the union of both tombstone sets, at `winner.version + 1`, chained to
 *    the winner's hash.
 *
 * The result is an ordinary version+1 update that chains correctly, so the winner accepts it
 * through the existing [GroupTransitionValidator] with no special case: from the winner's
 * point of view the loser simply added the members it was missing. One round trip, no
 * user-visible conflict, and no new trust in the merging device — every member in the
 * merged roster was already validated by whichever side first accepted them.
 *
 * Removals win over additions (a tombstoned member is dropped even if the other side still
 * lists them), which is the safe direction: the failure mode of the alternative is silently
 * readmitting someone who was just removed.
 */
object GroupStateMerge {

    /**
     * The state both devices will independently agree to build on.
     *
     * Ties are impossible in practice — equal hashes mean equal states, which is not a
     * conflict — but if it happens, returning [a] is still deterministic given both sides
     * pass the same pair.
     */
    fun pickWinner(a: GroupDefinition, b: GroupDefinition): GroupDefinition =
        if (a.computeStateHash() <= b.computeStateHash()) a else b

    /**
     * Build the reconciled successor of [winner] that also carries everything [ours] knows.
     *
     * @return the merged state to persist and broadcast, or **null** when [winner] already
     *   covers everything we hold — in that case adopt [winner] unchanged rather than
     *   burning a version on a no-op update, which would otherwise ping-pong forever
     *   between two devices that each keep "merging" the other's identical state.
     */
    fun merge(winner: GroupDefinition, ours: GroupDefinition): GroupDefinition? {
        val tombstones = winner.removedMemberIds + ours.removedMemberIds

        // Winner's record wins for a member both sides know. Keys are immutable per
        // GroupTransitionValidator (no rotation), so the two records can only differ in
        // cosmetic fields, and preferring one side consistently keeps the result
        // order-independent.
        val byId = LinkedHashMap<String, FamilyMember>()
        ours.members.forEach { byId[it.memberId] = it }
        winner.members.forEach { byId[it.memberId] = it }

        val roster = byId.values
            .filterNot { it.memberId in tombstones }
            .toSet()

        val nothingToAdd = roster == winner.members && tombstones == winner.removedMemberIds
        if (nothingToAdd) return null

        return winner.copy(
            members = roster,
            removedMemberIds = tombstones,
            version = winner.version + 1,
            previousStateHash = winner.computeStateHash(),
            // A group key is generated once at creation and never rotates, so both sides
            // hold the same value. Preferring the non-null one only helps the case where
            // the winner predates the key and we already learned it.
            fileEncryptionKey = winner.fileEncryptionKey ?: ours.fileEncryptionKey
        )
    }
}

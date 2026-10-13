package com.example.familysafety.group

/**
 * Who may change a member's profile and the family's subscription record (F25).
 *
 * Neither is covered by the state hash, and roster updates are routinely passed on by members
 * other than the one who made a change (refresh replies, reconciles). So the updater can't be
 * held to "only your own profile". Instead the *author* signs:
 *
 * - each member signs their own profile (name, avatar, colour) with a timestamp;
 * - the subscriber signs the subscription confirmation.
 *
 * A relay carries those signatures verbatim. A receiver takes a changed profile or
 * subscription only if it's authenticated (from its author directly, or carrying the author's
 * signature) **and newer** than what it holds. Anything else keeps the receiver's own values,
 * and the rest of the update still applies. Newer matters as much as signed: an old, validly
 * signed profile replayed by a stale phone would otherwise roll a rename back.
 *
 * None of these fields, signatures included, is in [GroupDefinition.computeStateHash], so
 * keeping our own values never makes two phones disagree about the hash chain.
 */
object ProfileGuard {

    /** What a member signs for their own profile. Hue as hundredths of a degree, an integer. */
    fun profileMessage(groupId: String, member: FamilyMember): ByteArray =
        ("PROFILE:v1:$groupId:${member.memberId}:${member.joinEpoch}:${member.profileUpdatedAtMs ?: ""}:" +
            "${member.displayName}:${member.avatarHash ?: ""}:${hueFixed(member.colorHue)}")
            .toByteArray(Charsets.UTF_8)

    /** What the subscriber signs when confirming the family's subscription. */
    fun subscriptionMessage(groupId: String, subscriberMemberId: String?, confirmedAtEpochMs: Long?): ByteArray =
        "SUBSCRIPTION:v1:$groupId:${subscriberMemberId ?: ""}:${confirmedAtEpochMs ?: ""}"
            .toByteArray(Charsets.UTF_8)

    /**
     * Fixed-point, not float bits: parsing a JSON number as a double and narrowing it (Swift)
     * can land on a different float than parsing it straight to a float (Kotlin).
     */
    fun hueFixed(hue: Float?): String = hue?.let { Math.round(it * 100.0).toString() } ?: ""

    /**
     * [incoming] with any profile or subscription change it isn't entitled to make replaced by
     * ours from [current].
     *
     * @param updaterMemberId the authenticated sender of [incoming], or null when it's the
     *   result of a merge and no single member authored it
     * @param verify Ed25519 check of (message, signatureHex, ed25519PublicKeyHex)
     */
    fun reconcile(
        current: GroupDefinition,
        incoming: GroupDefinition,
        updaterMemberId: String?,
        verify: (message: ByteArray, signatureHex: String, ed25519Hex: String) -> Boolean
    ): GroupDefinition {
        val ours = current.members.associateBy { it.memberId }
        val members = incoming.members.map { member ->
            val mine = ours[member.memberId]
            // New members, and new memberships, arrive as an add: the join itself vouches.
            if (mine == null || mine.joinEpoch != member.joinEpoch || !profileDiffers(mine, member)) {
                return@map member
            }
            val theirTime = member.profileUpdatedAtMs ?: 0L
            val ourTime = mine.profileUpdatedAtMs ?: 0L
            val fromThemselves = updaterMemberId == member.memberId && theirTime >= ourTime
            val signedAndNewer = theirTime > ourTime && member.profileSignature?.let {
                runCatching { verify(profileMessage(incoming.groupId, member), it, mine.ed25519PublicKey) }
                    .getOrDefault(false)
            } == true
            if (fromThemselves || signedAndNewer) member else member.copy(
                displayName = mine.displayName,
                avatarHash = mine.avatarHash,
                colorHue = mine.colorHue,
                profileUpdatedAtMs = mine.profileUpdatedAtMs,
                profileSignature = mine.profileSignature
            )
        }.toSet()

        val subscriptionDiffers = incoming.subscriberMemberId != current.subscriberMemberId ||
            incoming.subscriptionConfirmedAtEpochMs != current.subscriptionConfirmedAtEpochMs ||
            incoming.subscriptionSignature != current.subscriptionSignature
        val keepOurSubscription = subscriptionDiffers && !subscriptionAccepted(current, incoming, updaterMemberId, verify)

        return incoming.copy(
            members = members,
            subscriberMemberId = if (keepOurSubscription) current.subscriberMemberId else incoming.subscriberMemberId,
            subscriptionConfirmedAtEpochMs =
                if (keepOurSubscription) current.subscriptionConfirmedAtEpochMs else incoming.subscriptionConfirmedAtEpochMs,
            subscriptionSignature = if (keepOurSubscription) current.subscriptionSignature else incoming.subscriptionSignature
        )
    }

    /**
     * Nothing clears a subscription: a cancelled one stops being re-confirmed and ages out
     * (EntitlementCalculator). So a record without a subscriber is never taken over ours.
     */
    private fun subscriptionAccepted(
        current: GroupDefinition,
        incoming: GroupDefinition,
        updaterMemberId: String?,
        verify: (ByteArray, String, String) -> Boolean
    ): Boolean {
        val subscriber = incoming.subscriberMemberId ?: return false
        val theirTime = incoming.subscriptionConfirmedAtEpochMs ?: return false
        val ourTime = current.subscriptionConfirmedAtEpochMs ?: -1L
        if (updaterMemberId == subscriber && theirTime >= ourTime) return true
        if (theirTime <= ourTime) return false
        val key = current.findMemberById(subscriber)?.ed25519PublicKey ?: return false
        val signature = incoming.subscriptionSignature ?: return false
        return runCatching {
            verify(subscriptionMessage(incoming.groupId, subscriber, theirTime), signature, key)
        }.getOrDefault(false)
    }

    private fun profileDiffers(a: FamilyMember, b: FamilyMember) =
        a.displayName != b.displayName || a.avatarHash != b.avatarHash || a.colorHue != b.colorHue ||
            a.profileUpdatedAtMs != b.profileUpdatedAtMs || a.profileSignature != b.profileSignature
}

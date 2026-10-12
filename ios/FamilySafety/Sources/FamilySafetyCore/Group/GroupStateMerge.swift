import Foundation

/// Reconciliation of two branches of the same group (§7.4). Ports GroupStateMerge.kt as of
/// Android 1.15.3. Every device must reach the same answer from the same two inputs, so this
/// is deliberately order-independent and free of local preferences.
public enum GroupStateMerge {

    /// The branch both sides build on: the lower state hash wins.
    public static func pickWinner(_ a: GroupDefinition, _ b: GroupDefinition) -> GroupDefinition {
        a.computeStateHash() <= b.computeStateHash() ? a : b
    }

    /// The winner plus everything `ours` knew that it lacks, as a version+1 successor of the
    /// winner — or nil when `ours` adds nothing (adopt the winner unchanged).
    public static func merge(winner: GroupDefinition, ours: GroupDefinition) -> GroupDefinition? {
        let tombstones = Set(winner.removedMemberIds).union(ours.removedMemberIds)

        // Winner's record wins for a member both sides hold, unless ours is a later membership.
        var byId: [String: FamilyMember] = [:]
        var order: [String] = []
        for member in ours.members {
            if byId[member.memberId] == nil { order.append(member.memberId) }
            byId[member.memberId] = member
        }
        for member in winner.members {
            if let held = byId[member.memberId] {
                if member.joinEpoch >= held.joinEpoch { byId[member.memberId] = member }
            } else {
                order.append(member.memberId)
                byId[member.memberId] = member
            }
        }
        let roster = order.compactMap { byId[$0] }.filter { !Tombstones.isRemoved($0, tombstones: tombstones) }

        let proofs = Set(winner.removalProofs).union(ours.removalProofs).filter { tombstones.contains($0.tombstone) }

        // The winner's creator may have been removed on a branch it hasn't seen; recompute the
        // successor every device would reach rather than trusting either copy of the field.
        let creatorRemoved = !roster.contains { $0.memberId == winner.creatorMemberId } &&
            tombstones.contains { Tombstones.memberId(of: $0) == winner.creatorMemberId }
        let creatorMemberId = creatorRemoved
            ? (GroupDefinition.computeSuccessorCreator(members: roster, excludedIds: [winner.creatorMemberId]) ?? winner.creatorMemberId)
            : winner.creatorMemberId

        // Billing confirmations change on their own schedule: keep the most recent.
        let subscriptionIsOurs = (ours.subscriptionConfirmedAtEpochMs ?? -1) > (winner.subscriptionConfirmedAtEpochMs ?? -1)
        let subscriberMemberId = subscriptionIsOurs ? ours.subscriberMemberId : winner.subscriberMemberId
        let subscriptionConfirmedAt = subscriptionIsOurs ? ours.subscriptionConfirmedAtEpochMs : winner.subscriptionConfirmedAtEpochMs
        let grantCode = winner.grantCode ?? ours.grantCode

        let nothingToAdd = Set(roster) == Set(winner.members) &&
            tombstones == Set(winner.removedMemberIds) &&
            creatorMemberId == winner.creatorMemberId &&
            subscriberMemberId == winner.subscriberMemberId &&
            subscriptionConfirmedAt == winner.subscriptionConfirmedAtEpochMs &&
            grantCode == winner.grantCode &&
            proofs == Set(winner.removalProofs)
        if nothingToAdd { return nil }

        return winner.with(
            creatorMemberId: creatorMemberId,
            members: roster,
            version: winner.version + 1,
            previousStateHash: .some(winner.computeStateHash()),
            fileEncryptionKey: .some(winner.fileEncryptionKey ?? ours.fileEncryptionKey),
            removedMemberIds: tombstones.sorted(),
            subscriberMemberId: .some(subscriberMemberId),
            subscriptionConfirmedAtEpochMs: .some(subscriptionConfirmedAt),
            grantCode: .some(grantCode),
            removalProofs: proofs.sorted { ($0.tombstone, $0.authorizerMemberId) < ($1.tombstone, $1.authorizerMemberId) }
        )
    }
}

import Foundation

/// Why a remote `GroupDefinition` was rejected. Mirrors the reasons returned by
/// GroupTransitionValidator.check on Android (IOS_PORT_SPEC.md §7.3, §7.5).
public enum GroupTransitionRejection: Error, Equatable {
    case immutableFieldChanged
    case updaterNotInRoster
    case chainMismatch
    case membershipChangedWithoutRemoval
    case memberKeyRotation
    case addedMemberIdMismatch
    case memberBothPresentAndRemoved
    case unauthorizedMemberRemoval
    case unauthorizedNewTombstone
    case unauthorizedCreatorChange
    case unauthorizedRename
    /// When or by whom a member was added changed within one membership (3.1, F25).
    case joinDetailsChanged
    /// A set file key was replaced (3.1, F25). Rotation (3.3) uses `groupKeys`, never this field.
    case fileKeyChanged
}

/// One already-collected vote toward a quorum removal (§7.5).
public struct QuorumVote {
    public let voterMemberId: String
    public let signatureHex: String
    public init(voterMemberId: String, signatureHex: String) {
        self.voterMemberId = voterMemberId
        self.signatureHex = signatureHex
    }
}

/// Ports GroupTransitionValidator.kt as of Android 1.15.3 (§7.3) line for line. Signatures
/// are verified *before* this runs — by `verifiedQuorumVoters` and
/// `verifiedRemovalAuthorizers` below, exactly as GroupSyncManager does on Android — so the
/// rules here only ever see member IDs that have already proven themselves.
public enum GroupTransitionValidator {

    /// - Parameters:
    ///   - concurrent: true for a sibling (same version, §7.4) or a diverged branch being
    ///     reconciled. Relaxes exactly two rules: the hash-chain check, and "a member missing
    ///     without a tombstone was removed" (on a branch it just means "not heard of yet").
    ///     nil = decide from the versions (equal → concurrent).
    public static func validate(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String,
        concurrent: Bool? = nil,
        verifiedQuorumVoterIds: Set<String> = [],
        verifiedRemovalAuthorizers: [String: String] = [:]
    ) throws {
        let concurrent = concurrent ?? (remote.version == current.version)

        guard remote.groupId == current.groupId,
              remote.createdAtEpochMs == current.createdAtEpochMs else {
            throw GroupTransitionRejection.immutableFieldChanged
        }

        guard current.containsMember(updaterMemberId) else {
            throw GroupTransitionRejection.updaterNotInRoster
        }

        if !concurrent && remote.version == current.version + 1 &&
            remote.previousStateHash != current.computeStateHash() {
            throw GroupTransitionRejection.chainMismatch
        }

        let currentById = Dictionary(current.members.map { ($0.memberId, $0) }, uniquingKeysWith: { a, _ in a })
        let remoteById = Dictionary(remote.members.map { ($0.memberId, $0) }, uniquingKeysWith: { a, _ in a })
        let remoteTombstones = Set(remote.removedMemberIds)
        let currentTombstones = Set(current.removedMemberIds)
        let effectiveTombstones = currentTombstones.union(remoteTombstones)

        for (id, member) in remoteById {
            let existing = currentById[id]
            if let existing, existing.joinEpoch != member.joinEpoch {
                // A new membership is only legal once the old one has been ended.
                if member.joinEpoch < existing.joinEpoch ||
                    !remoteTombstones.contains(Tombstones.key(existing)) {
                    throw GroupTransitionRejection.membershipChangedWithoutRemoval
                }
            }
            if let existing {
                guard member.ed25519PublicKey.lowercased() == existing.ed25519PublicKey.lowercased(),
                      member.x25519PublicKey.lowercased() == existing.x25519PublicKey.lowercased() else {
                    throw GroupTransitionRejection.memberKeyRotation
                }
                // When and by whom someone was added are facts fixed at the add; a new membership
                // is a new add, so it's exempt.
                if existing.joinEpoch == member.joinEpoch &&
                    (existing.addedAtEpochMs != member.addedAtEpochMs || existing.addedByMemberId != member.addedByMemberId) {
                    throw GroupTransitionRejection.joinDetailsChanged
                }
            } else {
                guard let derived = MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyHex: member.ed25519PublicKey),
                      derived == id else {
                    throw GroupTransitionRejection.addedMemberIdMismatch
                }
            }
        }

        // A tombstoned membership reappearing in the roster. Checked against the union, so it
        // holds whether or not the sender knows about our tombstones — a peer that lacks them
        // is not rejected for that alone (the store unions them back in).
        if remote.members.contains(where: { Tombstones.isRemoved($0, tombstones: effectiveTombstones) }) {
            throw GroupTransitionRejection.memberBothPresentAndRemoved
        }

        let silentlyRemoved = Set(current.members
            .filter { remoteById[$0.memberId] == nil && !remoteTombstones.contains(Tombstones.key($0)) }
            .map(\.memberId))
        if !concurrent && !silentlyRemoved.isEmpty &&
            updaterMemberId != current.creatorMemberId &&
            silentlyRemoved != [updaterMemberId] {
            throw GroupTransitionRejection.unauthorizedMemberRemoval
        }

        let newTombstones = remoteTombstones.subtracting(currentTombstones)
        if !newTombstones.isEmpty {
            let isCreatorAuthorized = updaterMemberId == current.creatorMemberId
            let isSelfRemoval = Set(newTombstones.map(Tombstones.memberId(of:))) == [updaterMemberId]
            let isQuorumAuthorized = newTombstones.count == 1 && isQuorumSufficient(
                current: current,
                targetMemberId: Tombstones.memberId(of: newTombstones.first!),
                verifiedQuorumVoterIds: verifiedQuorumVoterIds
            )
            let isProvenByProofs = newTombstones.allSatisfy { tombstone in
                guard let authorizer = verifiedRemovalAuthorizers[tombstone],
                      current.containsMember(authorizer) else { return false }
                return authorizer == current.creatorMemberId || authorizer == Tombstones.memberId(of: tombstone)
            }
            guard isCreatorAuthorized || isSelfRemoval || isQuorumAuthorized || isProvenByProofs else {
                throw GroupTransitionRejection.unauthorizedNewTombstone
            }
        }

        if remote.creatorMemberId != current.creatorMemberId {
            let newCreator = current.findMember(byId: remote.creatorMemberId)
            let voluntaryTransfer = updaterMemberId == current.creatorMemberId &&
                newTombstones.isEmpty &&
                newCreator.map { !Tombstones.isRemoved($0, tombstones: effectiveTombstones) } == true

            let currentCreator = current.findMember(byId: current.creatorMemberId)
            let creatorWasQuorumRemoved = currentCreator.map {
                newTombstones == [Tombstones.key($0)]
            } == true && isQuorumSufficient(
                current: current,
                targetMemberId: current.creatorMemberId,
                verifiedQuorumVoterIds: verifiedQuorumVoterIds
            )
            let quorumSuccession = creatorWasQuorumRemoved && remote.creatorMemberId ==
                GroupDefinition.computeSuccessorCreator(members: current.members, excludedIds: [current.creatorMemberId])

            guard voluntaryTransfer || quorumSuccession else {
                throw GroupTransitionRejection.unauthorizedCreatorChange
            }
        }

        if remote.groupName != current.groupName && updaterMemberId != current.creatorMemberId {
            throw GroupTransitionRejection.unauthorizedRename
        }

        // Once set, the file key is never replaced. A state without one isn't a request to
        // forget ours (the store keeps it); a phone without one may learn it from any member.
        if let ours = current.fileEncryptionKey, !ours.isEmpty, let theirs = remote.fileEncryptionKey, !theirs.isEmpty,
           ours.lowercased() != theirs.lowercased() {
            throw GroupTransitionRejection.fileKeyChanged
        }
    }

    /// Convenience for callers holding raw votes rather than verified voter IDs: verifies
    /// them first (against `remote.version` and our current state hash, as Android does),
    /// then validates.
    public static func validate(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String,
        quorumVotes: [String: [QuorumVote]]
    ) throws {
        var voters = Set<String>()
        for (target, votes) in quorumVotes {
            voters.formUnion(verifiedQuorumVoters(
                current: current, targetMemberId: target, proposedVersion: remote.version, votes: votes
            ))
        }
        try validate(
            current: current, remote: remote, updaterMemberId: updaterMemberId,
            verifiedQuorumVoterIds: voters,
            verifiedRemovalAuthorizers: verifiedRemovalAuthorizers(current: current, remote: remote)
        )
    }

    /// Voters whose signature over `REMOVE_QUORUM:{gid}:{target}:{proposedVersion}:{ourHash}`
    /// verifies against *our* roster. Mirrors GroupSyncManager.verifyQuorumSignatures.
    public static func verifiedQuorumVoters(
        current: GroupDefinition,
        targetMemberId: String,
        proposedVersion: Int64,
        votes: [QuorumVote]
    ) -> Set<String> {
        guard (try? SodiumRaw.ensureInitialized()) != nil else { return [] }
        let payload = Array(RemovalVoteMessage.signingPayload(
            groupId: current.groupId, targetMemberId: targetMemberId,
            proposedVersion: proposedVersion, previousStateHash: current.computeStateHash()
        ).utf8)
        var valid = Set<String>()
        for vote in votes {
            guard let voter = current.findMember(byId: vote.voterMemberId),
                  let key = Hex.decode(voter.ed25519PublicKey),
                  let signature = Hex.decode(vote.signatureHex),
                  SodiumRaw.signVerifyDetached(signature: signature, message: payload, publicKey: key) else { continue }
            valid.insert(vote.voterMemberId)
        }
        return valid
    }

    /// Tombstone → authorizer, for each *new* tombstone in `remote` whose proof verifies
    /// against our roster. Mirrors GroupSyncManager.verifyRemovalProofs.
    public static func verifiedRemovalAuthorizers(current: GroupDefinition, remote: GroupDefinition) -> [String: String] {
        let newTombstones = Set(remote.removedMemberIds).subtracting(current.removedMemberIds)
        guard !newTombstones.isEmpty, (try? SodiumRaw.ensureInitialized()) != nil else { return [:] }
        var result: [String: String] = [:]
        for proof in remote.removalProofs where newTombstones.contains(proof.tombstone) {
            guard let authorizer = current.findMember(byId: proof.authorizerMemberId),
                  let key = Hex.decode(authorizer.ed25519PublicKey),
                  let signature = Hex.decode(proof.signature) else { continue }
            let message = Array(RemovalProof.signingPayload(groupId: current.groupId, tombstone: proof.tombstone).utf8)
            if SodiumRaw.signVerifyDetached(signature: signature, message: message, publicKey: key) {
                result[proof.tombstone] = proof.authorizerMemberId
            }
        }
        return result
    }

    private static func isQuorumSufficient(
        current: GroupDefinition,
        targetMemberId: String,
        verifiedQuorumVoterIds: Set<String>
    ) -> Bool {
        let eligible = verifiedQuorumVoterIds.filter { $0 != targetMemberId && current.containsMember($0) }.count
        let remaining = current.members.count - 1
        guard remaining > 0 else { return false }
        return eligible >= remaining / 2 + 1
    }
}

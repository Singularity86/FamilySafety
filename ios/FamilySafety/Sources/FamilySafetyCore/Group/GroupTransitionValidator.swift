import Foundation

/// Why a remote `GroupDefinition` was rejected. Mirrors the reasons enumerated in
/// IOS_PORT_SPEC.md §7.3 (plus the §7.5 creator-transfer/quorum-removal authorization
/// that §7.3 itself defers to).
public enum GroupTransitionRejection: Error, Equatable {
    case immutableFieldChanged
    case updaterNotInRoster
    case chainMismatch
    case memberKeyRotation
    case addedMemberIdMismatch
    case unauthorizedMemberRemoval
    case tombstoneDropped
    case memberBothPresentAndRemoved
    case unauthorizedNewTombstone
    case unauthorizedRename
    case unauthorizedCreatorChange
}

/// One already-collected vote toward a quorum removal (§7.5). The signature is verified
/// inside `GroupTransitionValidator`, not by the caller.
public struct QuorumVote {
    public let voterMemberId: String
    public let signatureHex: String
    public init(voterMemberId: String, signatureHex: String) {
        self.voterMemberId = voterMemberId
        self.signatureHex = signatureHex
    }
}

/// Ports GroupTransitionValidator.kt (§7.3). Apply before accepting ANY remote
/// `GroupDefinition` — both a successor (`remote.version == current.version + 1`) and a
/// sibling (`remote.version == current.version`, a concurrent edit per §7.4) go through
/// this same entry point, since only two of the rules below differ between the two cases.
public enum GroupTransitionValidator {

    /// - Parameters:
    ///   - quorumVotes: verified-elsewhere-collected votes authorizing each of this
    ///     remote's *new* tombstones, keyed by the target member being removed. Only
    ///     consulted when the removal isn't otherwise authorized by the creator or by
    ///     the target removing itself.
    public static func validate(
        current: GroupDefinition,
        remote: GroupDefinition,
        updaterMemberId: String,
        quorumVotes: [String: [QuorumVote]] = [:]
    ) throws {
        let isSibling = remote.version == current.version

        // Immutable identity fields — creatorMemberId is checked separately below, since
        // it has two narrow legal transitions (§7.5) rather than being simply immutable.
        guard remote.groupId == current.groupId,
              remote.createdAtEpochMs == current.createdAtEpochMs else {
            throw GroupTransitionRejection.immutableFieldChanged
        }

        guard current.containsMember(updaterMemberId) else {
            throw GroupTransitionRejection.updaterNotInRoster
        }

        // Hash-chain continuity. Siblings share a parent, not a chain — §7.3 relaxes this
        // rule for them explicitly.
        if !isSibling && remote.version == current.version + 1 {
            guard remote.previousStateHash == current.computeStateHash() else {
                throw GroupTransitionRejection.chainMismatch
            }
        }

        // No key rotation: any member present in both states must carry identical keys.
        for remoteMember in remote.members {
            if let localMember = current.findMember(byId: remoteMember.memberId) {
                guard localMember.ed25519PublicKey == remoteMember.ed25519PublicKey,
                      localMember.x25519PublicKey == remoteMember.x25519PublicKey else {
                    throw GroupTransitionRejection.memberKeyRotation
                }
            }
        }

        // Added members must self-authenticate: memberId == SHA-256(their ed25519 key)[0..15].
        let currentIds = Set(current.members.map(\.memberId))
        let addedMembers = remote.members.filter { !currentIds.contains($0.memberId) }
        for added in addedMembers {
            guard let derivedId = MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyHex: added.ed25519PublicKey),
                  derivedId == added.memberId else {
                throw GroupTransitionRejection.addedMemberIdMismatch
            }
        }

        // Tombstones are append-only.
        let remoteRemoved = Set(remote.removedMemberIds)
        let currentRemoved = Set(current.removedMemberIds)
        guard currentRemoved.isSubset(of: remoteRemoved) else {
            throw GroupTransitionRejection.tombstoneDropped
        }

        // No member may be simultaneously present and tombstoned.
        let remoteMemberIds = Set(remote.members.map(\.memberId))
        guard remoteMemberIds.isDisjoint(with: remoteRemoved) else {
            throw GroupTransitionRejection.memberBothPresentAndRemoved
        }

        // A successor that silently drops a member (present locally, absent remotely, no
        // tombstone) is an unauthorized removal. Siblings are exempt: an untombstoned gap
        // there just means "hasn't learned about this member yet" (§7.3) — the actual
        // removal-authorization check below still applies to any *new* tombstone either way.
        if !isSibling {
            let silentlyDropped = currentIds.subtracting(remoteMemberIds).subtracting(remoteRemoved)
            guard silentlyDropped.isEmpty else {
                throw GroupTransitionRejection.unauthorizedMemberRemoval
            }
        }

        // Every new tombstone needs authorization: the creator, the target removing only
        // itself, or a verified quorum (§7.5).
        let newTombstones = remoteRemoved.subtracting(currentRemoved)
        if !newTombstones.isEmpty {
            try SodiumRaw.ensureInitialized()
        }
        for target in newTombstones {
            // Authority to remove is judged against the CURRENT creator, not whatever
            // creatorMemberId this same update proposes — otherwise a legitimate creator
            // bundling a removal with a voluntary transfer would misclassify itself.
            let updaterIsCreator = updaterMemberId == current.creatorMemberId
            let isSelfRemoval = updaterMemberId == target && newTombstones == [target]
            if updaterIsCreator || isSelfRemoval {
                continue
            }
            let remaining = current.members.count - 1 // target never votes
            guard remaining > 0 else {
                throw GroupTransitionRejection.unauthorizedNewTombstone
            }
            let needed = remaining / 2 + 1
            let payload = RemovalVoteMessage.signingPayload(
                groupId: remote.groupId, targetMemberId: target,
                proposedVersion: remote.version, previousStateHash: current.computeStateHash()
            )
            let payloadBytes = Array(payload.utf8)

            var validVoters = Set<String>()
            for vote in quorumVotes[target] ?? [] {
                guard vote.voterMemberId != target,
                      let voter = current.findMember(byId: vote.voterMemberId),
                      let voterKey = Hex.decode(voter.ed25519PublicKey),
                      let signature = Hex.decode(vote.signatureHex) else { continue }
                if SodiumRaw.signVerifyDetached(signature: signature, message: payloadBytes, publicKey: voterKey) {
                    validVoters.insert(vote.voterMemberId)
                }
            }
            guard validVoters.count >= needed else {
                throw GroupTransitionRejection.unauthorizedNewTombstone
            }
        }

        // Rename is creator-only.
        if remote.groupName != current.groupName {
            guard updaterMemberId == current.creatorMemberId else {
                throw GroupTransitionRejection.unauthorizedRename
            }
        }

        // Creator change: only the current creator may transfer it voluntarily, to a
        // roster member who isn't tombstoned, with no new tombstone riding along in the
        // same update (§7.5) — quorum removal of the creator is a separate, later step
        // taken by whoever reaches quorum, not a same-update creator swap.
        if remote.creatorMemberId != current.creatorMemberId {
            guard updaterMemberId == current.creatorMemberId,
                  newTombstones.isEmpty,
                  remoteMemberIds.contains(remote.creatorMemberId),
                  !remoteRemoved.contains(remote.creatorMemberId) else {
                throw GroupTransitionRejection.unauthorizedCreatorChange
            }
        }
    }
}

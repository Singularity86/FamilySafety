import Foundation

/// What applying a remote state did. The sync engine turns these into acks, rebroadcasts
/// and refresh requests.
public enum GroupApplyOutcome: Equatable {
    /// The remote was older than ours. Nothing changed; ours should be sent back.
    case stale(GroupDefinition)
    /// A concurrent edit where our branch won the tiebreak. Nothing changed; ours should be
    /// sent back so the peer can merge onto it.
    case keptOurs(GroupDefinition)
    /// The new state was committed (possibly a merge of both branches).
    case applied(GroupDefinition)
    /// The remote broke a §7.3 rule.
    case rejected(GroupTransitionRejection)
    /// The resulting roster no longer contains this device, but nothing *authorized* our
    /// removal — no tombstone ends our membership. Refused rather than committed; the caller
    /// should ask the family for a refresh. Review finding F26.
    case refusedMissingSelf
    /// An authorized tombstone ended our membership. The state is committed; leaving (stop
    /// sharing, clear local data) is the caller's decision.
    case removed(GroupDefinition)
    /// The state was valid but couldn't be saved; nothing changed in memory either. The next
    /// sync brings it again.
    case storageFailed
}

public enum LocalEditError: Error, Equatable {
    case noFamily
    case alreadyInFamily
    case alreadyMember
    case notMember
    /// Storage couldn't be read or written. Nothing changed (F14: never treated as "no family").
    case storageUnavailable
}

/// The single source of truth for this device's group (§7.3, §7.4). Ports
/// GroupStateManager.applyRemoteGroupStateLocked and its reconcile paths from Android 1.15.3,
/// plus the iOS-only F26 rule above. An actor, so applies are strictly serial.
public actor GroupStateStore {
    public let localMemberId: String
    private let persistence: GroupStatePersistence
    public private(set) var current: GroupDefinition?

    public init(localMemberId: String, persistence: GroupStatePersistence) {
        self.localMemberId = localMemberId
        self.persistence = persistence
    }

    /// Loads the persisted group. Throws if it exists but can't be read — never treats that
    /// as "no group" (F14).
    @discardableResult
    public func load() throws -> GroupDefinition? {
        current = try persistence.load()
        return current
    }

    /// The first state of a freshly joined device, from a verified join approval (§8.5).
    public func adopt(_ group: GroupDefinition) throws {
        try persistence.save(group)
        current = group
    }

    // MARK: - Local edits

    /// §8.1: a new family with `creator` as its creator and only member, and a fresh file key.
    /// Refuses when a family is already saved, and when storage can't say whether one is
    /// (Android 1.1: never guess "no family" and overwrite one).
    public func create(groupName: String, creator: FamilyMember, now: Int64,
                       groupId: String = UUID().uuidString.lowercased()) throws -> GroupDefinition {
        guard current == nil else { throw LocalEditError.alreadyInFamily }
        let stored: GroupDefinition?
        do { stored = try persistence.load() } catch { throw LocalEditError.storageUnavailable }
        guard stored == nil else { throw LocalEditError.alreadyInFamily }
        try SodiumRaw.ensureInitialized()
        let group = GroupDefinition(
            groupId: groupId, groupName: groupName, createdAtEpochMs: now, creatorMemberId: creator.memberId,
            members: [creator], version: 1, previousStateHash: nil,
            fileEncryptionKey: Hex.encode(SodiumRaw.randomBytes(32))
        )
        do { try persistence.save(group) } catch { throw LocalEditError.storageUnavailable }
        current = group
        return group
    }

    /// Adds an approved joiner as the next version (Android GroupStateManager.addMember). Someone
    /// who left or was removed comes back as their next membership, so the old tombstone keeps
    /// meaning "removed". Throws `alreadyMember` for a member already in the roster: a restored
    /// phone changes nothing and only gets the approval again (§8.4 step 1).
    public func addMember(_ joiner: FamilyMember, inviterMemberId: String, now: Int64) throws -> GroupDefinition {
        guard let group = current else { throw LocalEditError.noFamily }
        guard group.containsMember(inviterMemberId) else { throw LocalEditError.notMember }
        guard !group.containsMember(joiner.memberId) else { throw LocalEditError.alreadyMember }
        let entry = FamilyMember(
            memberId: joiner.memberId, displayName: joiner.displayName,
            ed25519PublicKey: joiner.ed25519PublicKey.lowercased(), x25519PublicKey: joiner.x25519PublicKey.lowercased(),
            addedAtEpochMs: now, addedByMemberId: inviterMemberId, avatarHash: joiner.avatarHash, colorHue: joiner.colorHue,
            joinEpoch: Tombstones.nextJoinEpoch(memberId: joiner.memberId, tombstones: Set(group.removedMemberIds))
        )
        let next = GroupDefinition(
            groupId: group.groupId, groupName: group.groupName, createdAtEpochMs: group.createdAtEpochMs,
            creatorMemberId: group.creatorMemberId, members: group.members + [entry], version: group.version + 1,
            previousStateHash: group.computeStateHash(), fileEncryptionKey: group.fileEncryptionKey,
            removedMemberIds: group.removedMemberIds, subscriberMemberId: group.subscriberMemberId,
            subscriptionConfirmedAtEpochMs: group.subscriptionConfirmedAtEpochMs, grantCode: group.grantCode,
            removalProofs: group.removalProofs
        )
        do { try persistence.save(next) } catch { throw LocalEditError.storageUnavailable }
        current = next
        return next
    }

    public func apply(
        remote: GroupDefinition,
        updaterMemberId: String,
        verifiedQuorumVoterIds: Set<String> = [],
        verifiedRemovalAuthorizers: [String: String] = [:]
    ) -> GroupApplyOutcome {
        guard let current else { return .rejected(.updaterNotInRoster) } // no group yet: nothing to sync

        if remote.version < current.version {
            return .stale(current)
        }
        if remote.version == current.version && remote.computeStateHash() != current.computeStateHash() {
            return reconcileConcurrent(current: current, remote: remote, updaterMemberId: updaterMemberId,
                                       voters: verifiedQuorumVoterIds, authorizers: verifiedRemovalAuthorizers)
        }

        do {
            try GroupTransitionValidator.validate(
                current: current, remote: remote, updaterMemberId: updaterMemberId, concurrent: false,
                verifiedQuorumVoterIds: verifiedQuorumVoterIds, verifiedRemovalAuthorizers: verifiedRemovalAuthorizers
            )
        } catch GroupTransitionRejection.chainMismatch {
            // Both sides moved on from a common parent. Rejecting forever forks the family;
            // reconcile the two branches instead (Android: reconcileDivergedLocked).
            return reconcileDiverged(current: current, remote: remote, updaterMemberId: updaterMemberId,
                                     voters: verifiedQuorumVoterIds, authorizers: verifiedRemovalAuthorizers)
        } catch {
            return .rejected(error as? GroupTransitionRejection ?? .immutableFieldChanged)
        }

        return commit(Self.withPreservedTombstones(current: current, remote: remote), previous: current)
    }

    private func reconcileConcurrent(
        current: GroupDefinition, remote: GroupDefinition, updaterMemberId: String,
        voters: Set<String>, authorizers: [String: String]
    ) -> GroupApplyOutcome {
        // pickWinner keeps `current` on a tie, exactly as Android's reference comparison does.
        guard remote.computeStateHash() < current.computeStateHash() else {
            return .keptOurs(current)
        }
        do {
            try GroupTransitionValidator.validate(
                current: current, remote: remote, updaterMemberId: updaterMemberId, concurrent: true,
                verifiedQuorumVoterIds: voters, verifiedRemovalAuthorizers: authorizers
            )
        } catch {
            return .rejected(error as? GroupTransitionRejection ?? .immutableFieldChanged)
        }
        let merged = GroupStateMerge.merge(winner: remote, ours: current) ?? remote
        return commit(merged, previous: current)
    }

    private func reconcileDiverged(
        current: GroupDefinition, remote: GroupDefinition, updaterMemberId: String,
        voters: Set<String>, authorizers: [String: String]
    ) -> GroupApplyOutcome {
        do {
            try GroupTransitionValidator.validate(
                current: current, remote: remote, updaterMemberId: updaterMemberId, concurrent: true,
                verifiedQuorumVoterIds: voters, verifiedRemovalAuthorizers: authorizers
            )
        } catch {
            return .rejected(error as? GroupTransitionRejection ?? .immutableFieldChanged)
        }
        let resolved = GroupStateMerge.merge(winner: remote, ours: current) ?? remote
        return commit(Self.withPreservedTombstones(current: current, remote: resolved), previous: current)
    }

    private func commit(_ next: GroupDefinition, previous: GroupDefinition) -> GroupApplyOutcome {
        if !next.containsMember(localMemberId) {
            // F26: only an authorized tombstone ending *our* membership counts as removal.
            let ourKey = previous.findMember(byId: localMemberId).map(Tombstones.key)
            guard let ourKey, next.removedMemberIds.contains(ourKey) else {
                return .refusedMissingSelf
            }
            if (try? persistence.save(next)) == nil { return .storageFailed }
            current = next
            return .removed(next)
        }
        do {
            try persistence.save(next)
        } catch {
            return .storageFailed
        }
        current = next
        return .applied(next)
    }

    /// Our tombstones are ours to keep: a peer that predates them (or just lacks some) sends
    /// fewer, and taking that verbatim would silently readmit everyone we removed. Union
    /// them, with their proofs. Mirrors GroupStateManager.withPreservedTombstones.
    static func withPreservedTombstones(current: GroupDefinition?, remote: GroupDefinition) -> GroupDefinition {
        let ours = Set(current?.removedMemberIds ?? [])
        if ours.isEmpty { return remote }
        let merged = Set(remote.removedMemberIds).union(ours)
        let proofs = Set(remote.removalProofs).union((current?.removalProofs ?? []).filter { merged.contains($0.tombstone) })
        if merged == Set(remote.removedMemberIds) &&
            proofs == Set(remote.removalProofs) &&
            !remote.members.contains(where: { Tombstones.isRemoved($0, tombstones: merged) }) {
            return remote
        }
        return remote.with(
            members: remote.members.filter { !Tombstones.isRemoved($0, tombstones: merged) },
            removedMemberIds: merged.sorted(),
            removalProofs: proofs.sorted { ($0.tombstone, $0.authorizerMemberId) < ($1.tombstone, $1.authorizerMemberId) }
        )
    }
}

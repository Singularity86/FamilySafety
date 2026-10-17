import Foundation

/// The family keyring (3.3, spec §6.5 "Group-key rotation"): `fileEncryptionKey` as epoch 0, plus
/// a new key for every removal. Ports Android GroupKeys.kt; pure, so the rules test on their own.
public enum GroupKeys {
    /// Protocol every counted member must advertise before anyone rotates.
    public static let rotationProtocolVersion = 4
    /// A member silent this long no longer holds the family back (a lost phone never returns).
    public static let gateWindowMs: Int64 = 30 * 24 * 60 * 60 * 1000

    /// Every key this family has had, epoch 0 first. Empty for a family created before 1.12.0.
    public static func ring(_ group: GroupDefinition) -> [GroupKey] {
        var ring: [GroupKey] = []
        if let base = group.fileEncryptionKey?.lowercased(), !base.isEmpty, let kid = GroupKey.kid(forKeyHex: base) {
            ring.append(GroupKey(kid: kid, epoch: 0, key: base))
        }
        return ring + group.groupKeys.filter { $0.epoch > 0 }
    }

    /// The key to encrypt with: highest epoch, ties broken by the lowest kid.
    public static func current(_ group: GroupDefinition) -> GroupKey? {
        ring(group).min { ($0.epoch, $1.kid) > ($1.epoch, $0.kid) }
    }

    /// The key a message stamped with `kid` was sealed under; an absent kid means
    /// `fileEncryptionKey`. Nil when this phone doesn't hold it. Never falls back.
    public static func keyHex(_ group: GroupDefinition, kid: String?) -> String? {
        guard let kid else { return group.fileEncryptionKey.flatMap { $0.isEmpty ? nil : $0 } }
        return ring(group).first { $0.kid.lowercased() == kid.lowercased() }?.key
    }

    /// The kid to stamp on outgoing traffic, or nil for the epoch-0 key (byte-compatible).
    public static func stamp(_ group: GroupDefinition) -> String? {
        current(group).flatMap { $0.epoch > 0 ? $0.kid : nil }
    }

    /// True when a removal has happened since the newest key was made.
    public static func rotationDue(_ group: GroupDefinition) -> Bool {
        guard let newest = current(group) else { return false }
        return group.removedMemberIds.count > newest.epoch
    }

    public static func newKey(epoch: Int) throws -> GroupKey {
        try SodiumRaw.ensureInitialized()
        let key = Hex.encode(SodiumRaw.randomBytes(32))
        return GroupKey(kid: GroupKey.kid(forKeyHex: key)!, epoch: epoch, key: key)
    }

    /// True when `remote`'s keyring can't be taken on top of `current`: an existing entry
    /// changed epoch, or a new one isn't genuine or is newer than the removals it came with.
    public static func rejects(current: GroupDefinition, remote: GroupDefinition) -> Bool {
        let known = Dictionary(current.groupKeys.map { ($0.kid.lowercased(), $0) }, uniquingKeysWith: { a, _ in a })
        for k in remote.groupKeys {
            guard let bytes = Hex.decode(k.key), bytes.count == 32,
                  GroupKey.kid(forKeyHex: k.key) == k.kid.lowercased() else { return true }
            if let existing = known[k.kid.lowercased()] {
                if existing.epoch != k.epoch { return true }
                continue
            }
            if k.epoch < 1 || k.epoch > remote.removedMemberIds.count { return true }
        }
        return false
    }

    /// `group` as it may be sent to `recipient`: someone outside the roster gets no rotated key.
    public static func forRecipient(_ group: GroupDefinition, recipient: String) -> GroupDefinition {
        group.containsMember(recipient) ? group : group.with(groupKeys: [])
    }

    /// Whether every member who counts advertises at least `required`. Counted: anyone seen
    /// within the window; a member never seen blocks until this phone has watched a full window.
    public static func everyoneAtLeast(memberIds: [String], selfId: String, sightings: [String: PeerSighting],
                                       observingSinceMs: Int64, nowMs: Int64, required: Int) -> Bool {
        memberIds.filter { $0 != selfId }.allSatisfy { id in
            guard let s = sightings[id] else { return nowMs - observingSinceMs >= gateWindowMs }
            if nowMs - s.seenAtMs > gateWindowMs { return true }
            return s.protocolVersion >= required
        }
    }
}

/// What a member's authenticated presence advertised, by local receive time.
public struct PeerSighting: Codable, Equatable, Sendable {
    public let protocolVersion: Int
    public let seenAtMs: Int64
    public init(protocolVersion: Int, seenAtMs: Int64) {
        self.protocolVersion = protocolVersion
        self.seenAtMs = seenAtMs
    }
}

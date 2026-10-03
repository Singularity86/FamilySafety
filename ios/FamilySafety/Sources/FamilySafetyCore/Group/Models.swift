import CryptoKit
import Foundation

/// `memberId = lowercase_hex(SHA-256(ed25519PublicKey)[0..15])` (§2.3) — shared by identity
/// derivation, added-member self-authentication (§7.3), and join-request validation (§8.4).
public enum MemberIdDerivation {
    public static func deriveMemberId(fromEd25519PublicKeyBytes bytes: [UInt8]) -> String {
        Hex.encode(Array(SHA256.hash(data: Data(bytes)).prefix(16)))
    }

    /// Returns nil if `ed25519PublicKeyHex` isn't valid hex.
    public static func deriveMemberId(fromEd25519PublicKeyHex hex: String) -> String? {
        guard let keyBytes = Hex.decode(hex) else { return nil }
        return deriveMemberId(fromEd25519PublicKeyBytes: keyBytes)
    }
}

/// Mirrors FamilyMember in Models.kt.
public struct FamilyMember: Codable, Hashable {
    public let memberId: String
    public let displayName: String
    public let ed25519PublicKey: String
    public let x25519PublicKey: String
    public let addedAtEpochMs: Int64
    public let addedByMemberId: String?
    public let avatarHash: String?
    public let colorHue: Float?   // 0–359; nil = derive from memberId hash

    public init(
        memberId: String,
        displayName: String,
        ed25519PublicKey: String,
        x25519PublicKey: String,
        addedAtEpochMs: Int64,
        addedByMemberId: String? = nil,
        avatarHash: String? = nil,
        colorHue: Float? = nil
    ) {
        self.memberId = memberId
        self.displayName = displayName
        self.ed25519PublicKey = ed25519PublicKey
        self.x25519PublicKey = x25519PublicKey
        self.addedAtEpochMs = addedAtEpochMs
        self.addedByMemberId = addedByMemberId
        self.avatarHash = avatarHash
        self.colorHue = colorHue
    }
}

/// The complete family group definition — the persisted, signed group state that all
/// members must agree upon. Mirrors GroupDefinition in Models.kt. `members` is a plain
/// array (Android uses a Set; order carries no meaning — computeStateHash sorts it).
public struct GroupDefinition: Codable, Equatable {
    public let groupId: String
    public let groupName: String
    public let createdAtEpochMs: Int64
    public let creatorMemberId: String
    public let members: [FamilyMember]
    public let version: Int64
    public let previousStateHash: String?
    /// Random 32-byte AES key, hex encoded. Absent/nil for groups created before Android
    /// 1.12.0. Deliberately excluded from computeStateHash (§7.1) — it is secret material.
    public let fileEncryptionKey: String?
    /// Append-only tombstones (Android 1.12.7+). Defaults to empty for groups that have
    /// never removed anyone, which also keeps computeStateHash unchanged for them (§7.1).
    public let removedMemberIds: [String]
    /// Whoever most recently confirmed the family's subscription is active (§6.9,
    /// unreleased on Android as of this field's addition). Excluded from
    /// computeStateHash — a fact about the family, not a membership decision.
    public let subscriberMemberId: String?
    /// When `subscriberMemberId` last confirmed. Valid only while under a staleness
    /// window the caller enforces (Android: 4 days) — there is no explicit "unsubscribed"
    /// message; a lapsed subscription simply stops being re-confirmed and ages out.
    public let subscriptionConfirmedAtEpochMs: Int64?
    /// A developer-issued grant code (base64 `GrantCodePayload`, §6.9), if this family has
    /// one. Verified entirely on-device against an embedded public key — no server.
    public let grantCode: String?

    public init(
        groupId: String,
        groupName: String,
        createdAtEpochMs: Int64,
        creatorMemberId: String,
        members: [FamilyMember],
        version: Int64,
        previousStateHash: String? = nil,
        fileEncryptionKey: String? = nil,
        removedMemberIds: [String] = [],
        subscriberMemberId: String? = nil,
        subscriptionConfirmedAtEpochMs: Int64? = nil,
        grantCode: String? = nil
    ) {
        self.groupId = groupId
        self.groupName = groupName
        self.createdAtEpochMs = createdAtEpochMs
        self.creatorMemberId = creatorMemberId
        self.members = members
        self.version = version
        self.previousStateHash = previousStateHash
        self.fileEncryptionKey = fileEncryptionKey
        self.removedMemberIds = removedMemberIds
        self.subscriberMemberId = subscriberMemberId
        self.subscriptionConfirmedAtEpochMs = subscriptionConfirmedAtEpochMs
        self.grantCode = grantCode
    }

    private enum CodingKeys: String, CodingKey {
        case groupId, groupName, createdAtEpochMs, creatorMemberId, members, version
        case previousStateHash, fileEncryptionKey, removedMemberIds
        case subscriberMemberId, subscriptionConfirmedAtEpochMs, grantCode
    }

    /// Tolerates senders that predate this field entirely (absent, not just null).
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        groupId = try c.decode(String.self, forKey: .groupId)
        groupName = try c.decode(String.self, forKey: .groupName)
        createdAtEpochMs = try c.decode(Int64.self, forKey: .createdAtEpochMs)
        creatorMemberId = try c.decode(String.self, forKey: .creatorMemberId)
        members = try c.decode([FamilyMember].self, forKey: .members)
        version = try c.decode(Int64.self, forKey: .version)
        previousStateHash = try c.decodeIfPresent(String.self, forKey: .previousStateHash)
        fileEncryptionKey = try c.decodeIfPresent(String.self, forKey: .fileEncryptionKey)
        removedMemberIds = try c.decodeIfPresent([String].self, forKey: .removedMemberIds) ?? []
        subscriberMemberId = try c.decodeIfPresent(String.self, forKey: .subscriberMemberId)
        subscriptionConfirmedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .subscriptionConfirmedAtEpochMs)
        grantCode = try c.decodeIfPresent(String.self, forKey: .grantCode)
    }

    /// Deterministic hash of this group state, used for hash-chain integrity; members
    /// sign this hash to approve membership changes. MUST match
    /// GroupDefinition.computeStateHash() on Android byte-for-byte — see
    /// IOS_PORT_SPEC.md §7.1. Only memberId + both public keys are hashed, sorted
    /// ascending by memberId; displayName/avatar/etc. are not included. Deliberately
    /// excludes fileEncryptionKey and the three billing fields (subscriberMemberId,
    /// subscriptionConfirmedAtEpochMs, grantCode, §6.9) — facts about the family, not
    /// membership decisions; they still travel via the ordinary version-bump broadcast,
    /// just outside the hash, so re-confirming a subscription daily doesn't change the
    /// roster's identity hash.
    public func computeStateHash() -> String {
        var canonical = groupId
        canonical += "|"
        canonical += groupName
        canonical += "|"
        canonical += String(createdAtEpochMs)
        canonical += "|"
        canonical += creatorMemberId
        canonical += "|"
        canonical += String(version)
        canonical += "|"
        for member in members.sorted(by: { $0.memberId < $1.memberId }) {
            canonical += member.memberId
            canonical += ","
            canonical += member.ed25519PublicKey
            canonical += ","
            canonical += member.x25519PublicKey
            canonical += ";"
        }
        // Only appended when non-empty, so a group that has never removed anyone hashes
        // exactly as it did before this field existed (§7.1). "removed:" is a ONE-TIME
        // prefix before the whole list, not repeated per id — matches
        // GroupDefinition.computeStateHash on Android exactly (confirmed against source,
        // since the spec prose here reads ambiguously).
        if !removedMemberIds.isEmpty {
            canonical += "|removed:"
            for tombstone in removedMemberIds.sorted() {
                canonical += tombstone
                canonical += ";"
            }
        }
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return Hex.encode(Array(digest))
    }

    /// Deterministic replacement creator when the current one is removed (§7.5 point 7):
    /// the remaining member with the smallest `(addedAtEpochMs, memberId)` — longest-
    /// standing first, memberId ascending as the tie-break. Never voted on — every device
    /// computes this independently from the same roster and reaches the same answer.
    /// Mirrors GroupDefinition.computeSuccessorCreator on Android exactly.
    public static func computeSuccessorCreator(members: [FamilyMember], excludedIds: Set<String>) -> String? {
        let survivor = members
            .filter { !excludedIds.contains($0.memberId) }
            .min { ($0.addedAtEpochMs, $0.memberId) < ($1.addedAtEpochMs, $1.memberId) }
        return survivor?.memberId
    }

    public func findMember(byId memberId: String) -> FamilyMember? {
        members.first { $0.memberId == memberId }
    }

    public func containsMember(_ memberId: String) -> Bool {
        members.contains { $0.memberId == memberId }
    }

    public func isTombstoned(_ memberId: String) -> Bool {
        removedMemberIds.contains(memberId)
    }
}

/// String payloads that get Ed25519-signed for group-state operations. Mirrors
/// GroupSyncManager.createSyncSignaturePayload and the "ADD:…" approval string built
/// inline in InviteManager.approveJoinRequest (see IOS_PORT_SPEC.md §7.2, §8.4).
public enum GroupSignaturePayloads {
    public static func syncSignature(
        groupId: String,
        version: Int64,
        updaterMemberId: String,
        timestamp: Int64,
        groupDefinition: GroupDefinition
    ) -> String {
        "\(groupId)|\(version)|\(updaterMemberId)|\(timestamp)|\(groupDefinition.computeStateHash())"
    }

    public static func memberAddApproval(
        groupId: String,
        preAddVersion: Int64,
        newMemberEd25519PublicKeyHex: String
    ) -> String {
        "ADD:\(groupId):\(preAddVersion):\(newMemberEd25519PublicKeyHex)"
    }
}

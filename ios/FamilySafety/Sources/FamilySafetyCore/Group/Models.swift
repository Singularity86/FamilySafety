import CryptoKit
import Foundation

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
public struct GroupDefinition: Codable {
    public let groupId: String
    public let groupName: String
    public let createdAtEpochMs: Int64
    public let creatorMemberId: String
    public let members: [FamilyMember]
    public let version: Int64
    public let previousStateHash: String?

    public init(
        groupId: String,
        groupName: String,
        createdAtEpochMs: Int64,
        creatorMemberId: String,
        members: [FamilyMember],
        version: Int64,
        previousStateHash: String? = nil
    ) {
        self.groupId = groupId
        self.groupName = groupName
        self.createdAtEpochMs = createdAtEpochMs
        self.creatorMemberId = creatorMemberId
        self.members = members
        self.version = version
        self.previousStateHash = previousStateHash
    }

    /// Deterministic hash of this group state, used for hash-chain integrity; members
    /// sign this hash to approve membership changes. MUST match
    /// GroupDefinition.computeStateHash() on Android byte-for-byte — see
    /// IOS_PORT_SPEC.md §7.1. Only memberId + both public keys are hashed, sorted
    /// ascending by memberId; displayName/avatar/etc. are not included.
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
        let digest = SHA256.hash(data: Data(canonical.utf8))
        return Hex.encode(Array(digest))
    }

    public func findMember(byId memberId: String) -> FamilyMember? {
        members.first { $0.memberId == memberId }
    }

    public func containsMember(_ memberId: String) -> Bool {
        members.contains { $0.memberId == memberId }
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

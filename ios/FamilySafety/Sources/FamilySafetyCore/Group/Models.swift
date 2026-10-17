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
public struct FamilyMember: Codable, Hashable, Sendable {
    public let memberId: String
    public let displayName: String
    public let ed25519PublicKey: String
    public let x25519PublicKey: String
    public let addedAtEpochMs: Int64
    public let addedByMemberId: String?
    public let avatarHash: String?
    public let colorHue: Float?   // 0–359; nil = derive from memberId hash
    /// Which membership this is for `memberId` (Android 1.15.3+, §6.5): 0 the first time, one
    /// more each time someone who left or was removed is let back in. Hashed only when > 0.
    public let joinEpoch: Int
    /// When this member last changed their own profile (3.1). Signed with it; never hashed.
    public let profileUpdatedAtMs: Int64?
    /// Ed25519 by this member over `ProfileGuard.profileMessage` (3.1).
    public let profileSignature: String?
    /// Fields this build doesn't know, kept verbatim and written back out.
    public let extras: [String: JSONValue]

    public init(
        memberId: String,
        displayName: String,
        ed25519PublicKey: String,
        x25519PublicKey: String,
        addedAtEpochMs: Int64,
        addedByMemberId: String? = nil,
        avatarHash: String? = nil,
        colorHue: Float? = nil,
        joinEpoch: Int = 0,
        profileUpdatedAtMs: Int64? = nil,
        profileSignature: String? = nil,
        extras: [String: JSONValue] = [:]
    ) {
        self.memberId = memberId
        self.displayName = displayName
        self.ed25519PublicKey = ed25519PublicKey
        self.x25519PublicKey = x25519PublicKey
        self.addedAtEpochMs = addedAtEpochMs
        self.addedByMemberId = addedByMemberId
        self.avatarHash = avatarHash
        self.colorHue = colorHue
        self.joinEpoch = joinEpoch
        self.profileUpdatedAtMs = profileUpdatedAtMs
        self.profileSignature = profileSignature
        self.extras = extras
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case memberId, displayName, ed25519PublicKey, x25519PublicKey, addedAtEpochMs
        case addedByMemberId, avatarHash, colorHue, joinEpoch, profileUpdatedAtMs, profileSignature
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(memberId, forKey: .memberId)
        try c.encode(displayName, forKey: .displayName)
        try c.encode(ed25519PublicKey, forKey: .ed25519PublicKey)
        try c.encode(x25519PublicKey, forKey: .x25519PublicKey)
        try c.encode(addedAtEpochMs, forKey: .addedAtEpochMs)
        try c.encodeIfPresent(addedByMemberId, forKey: .addedByMemberId)
        try c.encodeIfPresent(avatarHash, forKey: .avatarHash)
        try c.encodeIfPresent(colorHue, forKey: .colorHue)
        try c.encode(joinEpoch, forKey: .joinEpoch)
        try c.encodeIfPresent(profileUpdatedAtMs, forKey: .profileUpdatedAtMs)
        try c.encodeIfPresent(profileSignature, forKey: .profileSignature)
        try encoder.encodeExtras(extras)
    }

    /// The same member with a new profile (the cosmetic fields 3.1 covers).
    public func withProfile(displayName: String, avatarHash: String?, colorHue: Float?,
                            updatedAtMs: Int64?, signature: String?) -> FamilyMember {
        FamilyMember(memberId: memberId, displayName: displayName, ed25519PublicKey: ed25519PublicKey,
                     x25519PublicKey: x25519PublicKey, addedAtEpochMs: addedAtEpochMs, addedByMemberId: addedByMemberId,
                     avatarHash: avatarHash, colorHue: colorHue, joinEpoch: joinEpoch,
                     profileUpdatedAtMs: updatedAtMs, profileSignature: signature, extras: extras)
    }

    /// Android omits default-valued fields on the sync path, so every optional field may be
    /// absent rather than null.
    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        memberId = try c.decode(String.self, forKey: .memberId)
        displayName = try c.decode(String.self, forKey: .displayName)
        ed25519PublicKey = try c.decode(String.self, forKey: .ed25519PublicKey)
        x25519PublicKey = try c.decode(String.self, forKey: .x25519PublicKey)
        addedAtEpochMs = try c.decode(Int64.self, forKey: .addedAtEpochMs)
        addedByMemberId = try c.decodeIfPresent(String.self, forKey: .addedByMemberId)
        avatarHash = try c.decodeIfPresent(String.self, forKey: .avatarHash)
        colorHue = try c.decodeIfPresent(Float.self, forKey: .colorHue)
        joinEpoch = try c.decodeIfPresent(Int.self, forKey: .joinEpoch) ?? 0
        profileUpdatedAtMs = try c.decodeIfPresent(Int64.self, forKey: .profileUpdatedAtMs)
        profileSignature = try c.decodeIfPresent(String.self, forKey: .profileSignature)
        extras = try decoder.unknownFields(known: Set(CodingKeys.allCases.map(\.rawValue)))
    }
}

/// Tombstone keys (Android 1.15.3+, §6.5). A tombstone ends one *membership*: the bare
/// memberId for membership 0, `"<memberId>#<n>"` for membership n ≥ 1. Mirrors `Tombstones`
/// in Models.kt.
public enum Tombstones {
    public static func key(memberId: String, joinEpoch: Int) -> String {
        joinEpoch <= 0 ? memberId : "\(memberId)#\(joinEpoch)"
    }

    public static func key(_ member: FamilyMember) -> String {
        key(memberId: member.memberId, joinEpoch: member.joinEpoch)
    }

    public static func memberId(of tombstone: String) -> String {
        tombstone.split(separator: "#", maxSplits: 1, omittingEmptySubsequences: false).first.map(String.init) ?? tombstone
    }

    public static func epoch(of tombstone: String) -> Int {
        guard let hash = tombstone.firstIndex(of: "#") else { return 0 }
        return Int(tombstone[tombstone.index(after: hash)...]) ?? 0
    }

    public static func isRemoved(_ member: FamilyMember, tombstones: Set<String>) -> Bool {
        tombstones.contains(key(member))
    }

    /// The membership to give `memberId` if they are let in now: one past their last removal.
    public static func nextJoinEpoch(memberId: String, tombstones: Set<String>) -> Int {
        tombstones.filter { self.memberId(of: $0) == memberId }.map { epoch(of: $0) + 1 }.max() ?? 0
    }
}

/// Signed authority for one tombstone (Android 1.15.3+, §6.5): by the creator, or by the
/// member whose membership it ends. Lets any member relay a removal it did not witness.
/// Not part of `computeStateHash` — each proof carries its own signature.
public struct RemovalProof: Codable, Hashable, Sendable {
    public let tombstone: String
    public let authorizerMemberId: String
    public let signature: String

    public init(tombstone: String, authorizerMemberId: String, signature: String) {
        self.tombstone = tombstone
        self.authorizerMemberId = authorizerMemberId
        self.signature = signature
    }

    public static func signingPayload(groupId: String, tombstone: String) -> String {
        "REMOVAL_PROOF:v1:\(groupId):\(tombstone)"
    }
}

/// The complete family group definition — the persisted, signed group state that all
/// members must agree upon. Mirrors GroupDefinition in Models.kt. `members` is a plain
/// array (Android uses a Set; order carries no meaning — computeStateHash sorts it).
public struct GroupDefinition: Codable, Equatable, Sendable {
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
    /// Proofs for tombstones (Android 1.15.3+). Unordered on the wire (Android uses a Set).
    public let removalProofs: [RemovalProof]
    /// Ed25519 by `subscriberMemberId` over `ProfileGuard.subscriptionMessage` (3.1). Unhashed.
    public let subscriptionSignature: String?
    /// The family's keyring after removals (3.3). Epoch 0 is `fileEncryptionKey`. Unhashed;
    /// merged as a union, never shrunk.
    public let groupKeys: [GroupKey]
    public let extras: [String: JSONValue]

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
        grantCode: String? = nil,
        removalProofs: [RemovalProof] = [],
        subscriptionSignature: String? = nil,
        groupKeys: [GroupKey] = [],
        extras: [String: JSONValue] = [:]
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
        self.removalProofs = removalProofs
        self.subscriptionSignature = subscriptionSignature
        self.groupKeys = groupKeys
        self.extras = extras
    }

    private enum CodingKeys: String, CodingKey, CaseIterable {
        case groupId, groupName, createdAtEpochMs, creatorMemberId, members, version
        case previousStateHash, fileEncryptionKey, removedMemberIds
        case subscriberMemberId, subscriptionConfirmedAtEpochMs, grantCode, removalProofs
        case subscriptionSignature, groupKeys
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(groupId, forKey: .groupId)
        try c.encode(groupName, forKey: .groupName)
        try c.encode(createdAtEpochMs, forKey: .createdAtEpochMs)
        try c.encode(creatorMemberId, forKey: .creatorMemberId)
        try c.encode(members, forKey: .members)
        try c.encode(version, forKey: .version)
        try c.encodeIfPresent(previousStateHash, forKey: .previousStateHash)
        try c.encodeIfPresent(fileEncryptionKey, forKey: .fileEncryptionKey)
        if !removedMemberIds.isEmpty { try c.encode(removedMemberIds, forKey: .removedMemberIds) }
        try c.encodeIfPresent(subscriberMemberId, forKey: .subscriberMemberId)
        try c.encodeIfPresent(subscriptionConfirmedAtEpochMs, forKey: .subscriptionConfirmedAtEpochMs)
        try c.encodeIfPresent(grantCode, forKey: .grantCode)
        if !removalProofs.isEmpty { try c.encode(removalProofs, forKey: .removalProofs) }
        try c.encodeIfPresent(subscriptionSignature, forKey: .subscriptionSignature)
        if !groupKeys.isEmpty { try c.encode(groupKeys, forKey: .groupKeys) }
        try encoder.encodeExtras(extras)
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
        removalProofs = try c.decodeIfPresent([RemovalProof].self, forKey: .removalProofs) ?? []
        subscriptionSignature = try c.decodeIfPresent(String.self, forKey: .subscriptionSignature)
        groupKeys = try c.decodeIfPresent([GroupKey].self, forKey: .groupKeys) ?? []
        extras = try decoder.unknownFields(known: Set(CodingKeys.allCases.map(\.rawValue)))
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
            // Only from the second membership on, so every existing roster hashes as before.
            if member.joinEpoch > 0 {
                canonical += ",#"
                canonical += String(member.joinEpoch)
            }
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

    /// True when this member's *current* membership has a tombstone.
    public func isRemoved(_ member: FamilyMember) -> Bool {
        Tombstones.isRemoved(member, tombstones: Set(removedMemberIds))
    }

    /// A copy with the given fields replaced — Swift has no `copy(...)`, and every state
    /// transition in the sync engine is "this group, but with …".
    public func with(
        groupName: String? = nil,
        creatorMemberId: String? = nil,
        members: [FamilyMember]? = nil,
        version: Int64? = nil,
        previousStateHash: String?? = .none,
        fileEncryptionKey: String?? = .none,
        removedMemberIds: [String]? = nil,
        subscriberMemberId: String?? = .none,
        subscriptionConfirmedAtEpochMs: Int64?? = .none,
        grantCode: String?? = .none,
        removalProofs: [RemovalProof]? = nil,
        subscriptionSignature: String?? = .none,
        groupKeys: [GroupKey]? = nil
    ) -> GroupDefinition {
        GroupDefinition(
            groupId: groupId,
            groupName: groupName ?? self.groupName,
            createdAtEpochMs: createdAtEpochMs,
            creatorMemberId: creatorMemberId ?? self.creatorMemberId,
            members: members ?? self.members,
            version: version ?? self.version,
            previousStateHash: previousStateHash ?? self.previousStateHash,
            fileEncryptionKey: fileEncryptionKey ?? self.fileEncryptionKey,
            removedMemberIds: removedMemberIds ?? self.removedMemberIds,
            subscriberMemberId: subscriberMemberId ?? self.subscriberMemberId,
            subscriptionConfirmedAtEpochMs: subscriptionConfirmedAtEpochMs ?? self.subscriptionConfirmedAtEpochMs,
            grantCode: grantCode ?? self.grantCode,
            removalProofs: removalProofs ?? self.removalProofs,
            subscriptionSignature: subscriptionSignature ?? self.subscriptionSignature,
            groupKeys: groupKeys ?? self.groupKeys,
            extras: extras
        )
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

/// One entry of the family keyring (3.3, spec §6.5). `kid` = first 8 bytes of SHA-256(key), hex.
public struct GroupKey: Codable, Hashable, Sendable {
    public let kid: String
    public let epoch: Int
    public let key: String

    public init(kid: String, epoch: Int, key: String) {
        self.kid = kid
        self.epoch = epoch
        self.key = key
    }

    public static func kid(forKeyHex keyHex: String) -> String? {
        guard let bytes = Hex.decode(keyHex), bytes.count == 32 else { return nil }
        return Hex.encode(Array(SHA256.hash(data: Data(bytes)).prefix(8)))
    }

    /// Well-formed: a 32-byte key whose kid matches it.
    public var isValid: Bool { epoch >= 0 && Self.kid(forKeyHex: key) == kid.lowercased() }
}

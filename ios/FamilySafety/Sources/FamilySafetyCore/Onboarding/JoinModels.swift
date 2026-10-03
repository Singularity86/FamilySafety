import Foundation

/// The QR/invite-code payload (§8.2): Base64(standard, padded) of this JSON object of
/// strings. NOTE: an `InviteData` class with a signature field exists elsewhere in the
/// Android codebase but is NOT what the current invite path emits — this map shape is the
/// wire contract.
public struct InviteCode: Codable, Equatable {
    public let groupId: String
    public let groupName: String
    public let inviterMemberId: String
    public let inviterName: String
    /// Epoch ms — AS A STRING, unlike every other timestamp on the wire.
    public let timestamp: String

    public init(groupId: String, groupName: String, inviterMemberId: String, inviterName: String, timestamp: String) {
        self.groupId = groupId
        self.groupName = groupName
        self.inviterMemberId = inviterMemberId
        self.inviterName = inviterName
        self.timestamp = timestamp
    }

    /// Strips every whitespace character before decoding — a pasted or scanned code
    /// routinely arrives with a trailing newline or gets line-wrapped by the sharing app,
    /// and a strict decoder rejects an otherwise-good code (§8.3).
    public static func decode(base64: String) -> InviteCode? {
        let stripped = String(base64.unicodeScalars.filter { !CharacterSet.whitespacesAndNewlines.contains($0) })
        guard let data = Data(base64Encoded: stripped) else { return nil }
        return try? JSONDecoder().decode(InviteCode.self, from: data)
    }

    public func encodedBase64() throws -> String {
        try JSONEncoder().encode(self).base64EncodedString()
    }
}

/// Plaintext, retained, on `familysafe/{inviterMemberId}/join_request` (§8.3). NOTE: keys
/// are Base64 here — the one exception to the wire's hex convention (§2.4) — since this
/// struct is shared with the pre-roster QR trust path rather than the post-join envelope.
public struct JoinRequest: Codable, Equatable {
    public let requestId: String
    public let requesterId: String
    public let displayName: String
    public let ed25519PublicKey: String
    public let x25519PublicKey: String
    public let groupId: String
    public let timestampMs: Int64

    public init(
        requestId: String, requesterId: String, displayName: String,
        ed25519PublicKey: String, x25519PublicKey: String, groupId: String, timestampMs: Int64
    ) {
        self.requestId = requestId
        self.requesterId = requesterId
        self.displayName = displayName
        self.ed25519PublicKey = ed25519PublicKey
        self.x25519PublicKey = x25519PublicKey
        self.groupId = groupId
        self.timestampMs = timestampMs
    }
}

/// Plaintext, retained, on `familysafe/{joinerId}/join_approval` (§8.4). `inviter*PublicKey`
/// are hex (ordinary wire convention); `encryptedGroupDefinition` is an E2EE envelope (§3)
/// JSON string wrapping the serialized `GroupDefinition`.
public struct JoinApprovalMessage: Codable, Equatable {
    public let inviterEd25519PublicKey: String
    public let inviterX25519PublicKey: String
    public let encryptedGroupDefinition: String

    public init(inviterEd25519PublicKey: String, inviterX25519PublicKey: String, encryptedGroupDefinition: String) {
        self.inviterEd25519PublicKey = inviterEd25519PublicKey
        self.inviterX25519PublicKey = inviterX25519PublicKey
        self.encryptedGroupDefinition = encryptedGroupDefinition
    }
}

/// Same topic as approval, distinct field name so a joiner can tell the two apart without
/// guessing from shape alone.
public struct JoinRejectionMessage: Codable, Equatable {
    public let inviterEd25519PublicKey: String
    public let inviterX25519PublicKey: String
    public let encryptedRejection: String

    public init(inviterEd25519PublicKey: String, inviterX25519PublicKey: String, encryptedRejection: String) {
        self.inviterEd25519PublicKey = inviterEd25519PublicKey
        self.inviterX25519PublicKey = inviterX25519PublicKey
        self.encryptedRejection = encryptedRejection
    }
}

/// The plaintext wrapped inside `JoinRejectionMessage.encryptedRejection`'s envelope.
/// Anti-replay: a joiner must check this matches its own pending `requestId` + memberId
/// before treating a rejection as real (§8.5).
public struct JoinRejectionPayload: Codable, Equatable {
    public let requestId: String
    public let joinerMemberId: String
    public let timestamp: Int64

    public init(requestId: String, joinerMemberId: String, timestamp: Int64) {
        self.requestId = requestId
        self.joinerMemberId = joinerMemberId
        self.timestamp = timestamp
    }
}

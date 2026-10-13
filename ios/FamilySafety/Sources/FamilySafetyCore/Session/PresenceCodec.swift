import Foundation

/// What we know about one member's presence.
public struct PresenceStatus: Equatable, Sendable {
    public let isOnline: Bool
    public let timestamp: Int64
    /// The sender's install, only when its device signature verified (2.2).
    public let deviceId: String?

    public init(isOnline: Bool, timestamp: Int64, deviceId: String? = nil) {
        self.isOnline = isOnline
        self.timestamp = timestamp
        self.deviceId = deviceId
    }
}

/// Builds and reads the presence topic's payload (§6.1, §6.2): a signed `PresenceUpdate` in a
/// padded envelope, sealed under the family's presence key when the family has one.
public enum PresenceCodec {
    public static func encode(identity: FamilySafeIdentity, isOnline: Bool, timestamp: Int64,
                              groupFileKeyHex: String?, deviceId: String? = nil) throws -> Data {
        let presence = try PresenceUpdate.signed(memberId: identity.memberId, isOnline: isOnline, timestamp: timestamp,
                                                 ed25519SecretKey64: identity.ed25519SecretKey64, deviceId: deviceId)
        let inner = String(decoding: try JSONEncoder().encode(presence), as: UTF8.self)
        let envelope = try JSONEncoder().encode(try EnvelopePadding.build(type: .presenceUpdate, payload: inner))
        guard let groupFileKeyHex else { return envelope }
        return try JSONEncoder().encode(try PresenceSealing.seal(envelopeJSON: envelope, groupFileEncryptionKeyHex: groupFileKeyHex))
    }

    /// The verified presence of `sender`, or nil. Accepts a sealed payload and a legacy bare
    /// envelope; an unsigned or wrongly signed one is ignored.
    public static func decode(_ payload: Data, sender: FamilyMember, groupFileKeyHex: String?) -> PresenceStatus? {
        guard !payload.isEmpty else { return nil }
        var envelopeJSON = payload
        if let sealed = try? JSONDecoder().decode(SealedPresence.self, from: payload) {
            guard let groupFileKeyHex, let opened = try? PresenceSealing.open(sealed, groupFileEncryptionKeyHex: groupFileKeyHex) else { return nil }
            envelopeJSON = opened
        }
        guard let envelope = try? JSONDecoder().decode(MessageEnvelope.self, from: envelopeJSON), envelope.type == .presenceUpdate,
              let presence = try? JSONDecoder().decode(PresenceUpdate.self, from: Data(envelope.payload.utf8)),
              presence.memberId == sender.memberId,
              let key = Hex.decode(sender.ed25519PublicKey), presence.verifySignature(publicKey: key) else { return nil }
        return PresenceStatus(isOnline: presence.isOnline, timestamp: presence.timestamp, deviceId: presence.verifiedDeviceId(publicKey: key))
    }
}

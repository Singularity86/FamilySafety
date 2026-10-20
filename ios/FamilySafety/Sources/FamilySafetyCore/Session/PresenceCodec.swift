import Foundation

/// What we know about one member's presence. Codable so the last known state survives a
/// restart: a reconnect that keeps the broker session gets no retained replay (§4).
public struct PresenceStatus: Codable, Equatable, Sendable {
    public let isOnline: Bool
    public let timestamp: Int64
    /// The sender's install, only when its device signature verified (2.2).
    public let deviceId: String?
    /// What the sender advertises (§6.2); recorded only because the presence authenticated.
    public let protocolVersion: Int
    /// On online presence, the sender's connection start, only when its session signature
    /// verified (§6.2 "Sudden disconnect").
    public let sessionStart: Int64?

    public init(isOnline: Bool, timestamp: Int64, deviceId: String? = nil, protocolVersion: Int = 1, sessionStart: Int64? = nil) {
        self.isOnline = isOnline
        self.timestamp = timestamp
        self.deviceId = deviceId
        self.protocolVersion = protocolVersion
        self.sessionStart = sessionStart
    }
}

/// What reading one presence payload gave.
public enum PresenceReading: Equatable, Sendable {
    case status(PresenceStatus)
    /// Sealed under a group key this phone doesn't hold yet (probably behind after a rotation).
    /// Anyone can publish junk kids, so a refresh it prompts goes through a rate limiter.
    case unknownKey
    case invalid
}

/// Builds and reads the presence topic's payload (§6.1, §6.2): a signed `PresenceUpdate` in a
/// padded envelope, sealed under the family's current presence key when the family has one.
public enum PresenceCodec {
    /// Seals under the group's current key (3.3), stamping its kid unless it is epoch 0. A group
    /// without a shared key (or none at all) publishes plaintext.
    public static func encode(identity: FamilySafeIdentity, isOnline: Bool, timestamp: Int64,
                              group: GroupDefinition?, deviceId: String? = nil, sessionStart: Int64? = nil) throws -> Data {
        let presence = try PresenceUpdate.signed(memberId: identity.memberId, isOnline: isOnline, timestamp: timestamp,
                                                 ed25519SecretKey64: identity.ed25519SecretKey64, deviceId: deviceId,
                                                 sessionStart: sessionStart)
        let inner = String(decoding: try JSONEncoder().encode(presence), as: UTF8.self)
        let envelope = try JSONEncoder().encode(try EnvelopePadding.build(type: .presenceUpdate, payload: inner))
        guard let group, let key = GroupKeys.current(group) else { return envelope }
        return try JSONEncoder().encode(try PresenceSealing.seal(envelopeJSON: envelope, groupFileEncryptionKeyHex: key.key,
                                                                 kid: GroupKeys.stamp(group)))
    }

    /// The verified presence of `sender`. Accepts a sealed payload (opened with the key its kid
    /// names, never a fallback) and a legacy bare envelope; an unsigned or wrongly signed one is
    /// invalid.
    public static func read(_ payload: Data, sender: FamilyMember, group: GroupDefinition) -> PresenceReading {
        guard !payload.isEmpty else { return .invalid }
        var envelopeJSON = payload
        if let sealed = try? JSONDecoder().decode(SealedPresence.self, from: payload) {
            guard let keyHex = GroupKeys.keyHex(group, kid: sealed.kid) else {
                return sealed.kid == nil ? .invalid : .unknownKey
            }
            guard let opened = try? PresenceSealing.open(sealed, groupFileEncryptionKeyHex: keyHex) else { return .invalid }
            envelopeJSON = opened
        }
        guard let envelope = try? JSONDecoder().decode(MessageEnvelope.self, from: envelopeJSON), envelope.type == .presenceUpdate,
              let presence = try? JSONDecoder().decode(PresenceUpdate.self, from: Data(envelope.payload.utf8)),
              presence.memberId == sender.memberId,
              let key = Hex.decode(sender.ed25519PublicKey), presence.verifySignature(publicKey: key) else { return .invalid }
        return .status(PresenceStatus(isOnline: presence.isOnline, timestamp: presence.timestamp,
                                      deviceId: presence.verifiedDeviceId(publicKey: key),
                                      protocolVersion: presence.protocolVersion,
                                      sessionStart: presence.verifiedSessionStart(publicKey: key)))
    }
}

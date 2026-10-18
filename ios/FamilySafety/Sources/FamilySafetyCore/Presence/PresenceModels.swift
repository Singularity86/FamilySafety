import CryptoKit
import Foundation

/// 1 = pre-1.12.x; 2 = padded + signed + sealed + encrypted manifests (§6.2); 3 = tombstones and
/// merge-don't-arbitrate (1.12.7), which iOS implements in GroupStateStore. Set this to
/// the generation whose rules are actually implemented — claiming 2 without also doing
/// padding, signatures, sealed presence and encrypted manifests turns a legible "peer
/// needs to update" warning back into silent invisibility.
public enum ProtocolVersion {
    public static let current = 4
    /// Below this a peer can't interoperate; the UI names them (§6.2 rule 3).
    public static let minCompatible = 3
}

public struct LocationUpdate: Codable, Equatable {
    public let memberId: String
    public let latitude: Double
    public let longitude: Double
    /// float32 on Android; kept as Float so the author signature (3.2) widens it the same way.
    public let accuracy: Float
    public let timestamp: Int64
    public let speed: Float?
    public let bearing: Float?
    /// The author's Ed25519 over `AuthorSignatures.location` (3.2, F24). Optional for older senders.
    public let authorSig: String?

    public init(
        memberId: String, latitude: Double, longitude: Double, accuracy: Float,
        timestamp: Int64, speed: Float? = nil, bearing: Float? = nil, authorSig: String? = nil
    ) {
        self.memberId = memberId
        self.latitude = latitude
        self.longitude = longitude
        self.accuracy = accuracy
        self.timestamp = timestamp
        self.speed = speed
        self.bearing = bearing
        self.authorSig = authorSig
    }
}

public struct PresenceUpdate: Codable, Equatable {
    public let memberId: String
    public let isOnline: Bool
    public let timestamp: Int64
    /// Ed25519-detached hex, absent from senders predating Android 1.12.3.
    public let signature: String?
    /// Absent from senders predating Android 1.12.6 — decode a missing field as
    /// generation 1, which is the correct reading of "old client", not a fallback (§6.2).
    public let protocolVersion: Int
    /// 16 random bytes as 32 lowercase hex, one per install (Android 2.2, F15). Optional.
    public let deviceId: String?
    /// Ed25519 by the member over `presence-device:v1:{memberId}:{timestamp}:{deviceId}`.
    public let deviceSignature: String?

    public init(memberId: String, isOnline: Bool, timestamp: Int64, signature: String? = nil, protocolVersion: Int = ProtocolVersion.current,
                deviceId: String? = nil, deviceSignature: String? = nil) {
        self.memberId = memberId
        self.isOnline = isOnline
        self.timestamp = timestamp
        self.signature = signature
        self.protocolVersion = protocolVersion
        self.deviceId = deviceId
        self.deviceSignature = deviceSignature
    }

    private enum CodingKeys: String, CodingKey { case memberId, isOnline, timestamp, signature, protocolVersion, deviceId, deviceSignature }

    public static func deviceSigningPayload(memberId: String, timestamp: Int64, deviceId: String) -> String {
        "presence-device:v1:\(memberId):\(timestamp):\(deviceId)"
    }

    /// The device ID, only if its signature verifies under the member's key; nil otherwise, so
    /// an unsigned or forged ID is never used for clone detection.
    public func verifiedDeviceId(publicKey: [UInt8]) -> String? {
        guard let deviceId, let deviceSignature, let sig = Hex.decode(deviceSignature) else { return nil }
        let payload = Self.deviceSigningPayload(memberId: memberId, timestamp: timestamp, deviceId: deviceId)
        return SodiumRaw.signVerifyDetached(signature: sig, message: Array(payload.utf8), publicKey: publicKey) ? deviceId : nil
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        memberId = try c.decode(String.self, forKey: .memberId)
        isOnline = try c.decode(Bool.self, forKey: .isOnline)
        timestamp = try c.decode(Int64.self, forKey: .timestamp)
        signature = try c.decodeIfPresent(String.self, forKey: .signature)
        protocolVersion = try c.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? 1
        deviceId = try c.decodeIfPresent(String.self, forKey: .deviceId)
        deviceSignature = try c.decodeIfPresent(String.self, forKey: .deviceSignature)
    }

    /// The exact UTF-8 string that gets Ed25519-signed (§6.2) — built from the fields
    /// themselves, never from serialized JSON, so key order and future additive fields
    /// can't change what was signed.
    public static func signingPayload(memberId: String, isOnline: Bool, timestamp: Int64) -> String {
        "presence:\(memberId):\(isOnline ? "true" : "false"):\(timestamp)"
    }

    /// Builds a signed `PresenceUpdate` for `memberId` (§6.2) — the sender side of the
    /// signature scheme `verifySignature` checks on receive.
    public static func signed(
        memberId: String, isOnline: Bool, timestamp: Int64,
        ed25519SecretKey64: [UInt8], protocolVersion: Int = ProtocolVersion.current, deviceId: String? = nil
    ) throws -> PresenceUpdate {
        let payload = signingPayload(memberId: memberId, isOnline: isOnline, timestamp: timestamp)
        let signature = try SodiumRaw.signDetached(message: Array(payload.utf8), secretKey: ed25519SecretKey64)
        var deviceSignature: String?
        if let deviceId {
            let devicePayload = deviceSigningPayload(memberId: memberId, timestamp: timestamp, deviceId: deviceId)
            deviceSignature = Hex.encode(try SodiumRaw.signDetached(message: Array(devicePayload.utf8), secretKey: ed25519SecretKey64))
        }
        return PresenceUpdate(memberId: memberId, isOnline: isOnline, timestamp: timestamp, signature: Hex.encode(signature),
                              protocolVersion: protocolVersion, deviceId: deviceId, deviceSignature: deviceSignature)
    }

    /// Verifies `signature` against the sender's roster `ed25519PublicKey` (§6.2). False
    /// (never throws) if there's no signature to check — unsigned presence from a sender
    /// predating Android 1.12.3 is a caller-level policy decision, not an error here.
    public func verifySignature(publicKey: [UInt8]) -> Bool {
        guard let signature, let signatureBytes = Hex.decode(signature) else { return false }
        let payload = Self.signingPayload(memberId: memberId, isOnline: isOnline, timestamp: timestamp)
        return SodiumRaw.signVerifyDetached(signature: signatureBytes, message: Array(payload.utf8), publicKey: publicKey)
    }
}

/// What actually rides the presence topic since Android 1.12.5 — the bare `PresenceUpdate`
/// envelope, sealed under a per-group key so the relay can no longer read `isOnline`
/// directly. Receivers must accept both this and a legacy bare envelope (§6.2).
public struct SealedPresence: Codable, Equatable {
    public let v: Int
    /// Base64 of `nonce ‖ ciphertext ‖ tag` over the presence `MessageEnvelope`.
    public let data: String
    /// The rotated group key it's sealed under (3.3). Absent for the epoch-0 file key, so an
    /// unrotated family sends exactly the bytes older builds expect.
    public let kid: String?

    public init(v: Int = 2, data: String, kid: String? = nil) {
        self.v = v
        self.data = data
        self.kid = kid
    }

    private enum CodingKeys: String, CodingKey { case v, data, kid }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(v, forKey: .v)
        try c.encode(data, forKey: .data)
        if let kid { try c.encode(kid, forKey: .kid) }
    }
}

public enum PresenceSealingError: Error {
    case noGroupKey
    case malformedContainer
}

/// AES-256-GCM sealing for the presence topic (§6.2). `presenceKey = SHA-256(groupFileKey
/// ‖ "presence")` — the purpose label gives domain separation from the file key itself, so
/// recovering one does not yield the other.
public enum PresenceSealing {
    public static func presenceKey(groupFileEncryptionKeyHex: String) -> SymmetricKey? {
        guard let keyBytes = Hex.decode(groupFileEncryptionKeyHex) else { return nil }
        let digest = SHA256.hash(data: Data(keyBytes) + Data("presence".utf8))
        return SymmetricKey(data: Data(digest))
    }

    /// Seals at connect time, together with the presence signature — the last-will body
    /// must already be final when handed to the broker, since the device signs/seals it
    /// once and cannot do either after it's gone.
    public static func seal(envelopeJSON: Data, groupFileEncryptionKeyHex: String, kid: String? = nil) throws -> SealedPresence {
        guard let key = presenceKey(groupFileEncryptionKeyHex: groupFileEncryptionKeyHex) else {
            throw PresenceSealingError.noGroupKey
        }
        let box = try AES.GCM.seal(envelopeJSON, using: key)
        guard let combined = box.combined else { throw PresenceSealingError.malformedContainer }
        return SealedPresence(data: combined.base64EncodedString(), kid: kid)
    }

    public static func open(_ sealed: SealedPresence, groupFileEncryptionKeyHex: String) throws -> Data {
        guard let key = presenceKey(groupFileEncryptionKeyHex: groupFileEncryptionKeyHex) else {
            throw PresenceSealingError.noGroupKey
        }
        guard let combinedData = Data(base64Encoded: sealed.data) else {
            throw PresenceSealingError.malformedContainer
        }
        let box = try AES.GCM.SealedBox(combined: combinedData)
        return try AES.GCM.open(box, using: key)
    }
}

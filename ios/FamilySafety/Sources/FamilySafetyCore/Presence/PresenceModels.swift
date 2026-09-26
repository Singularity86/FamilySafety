import CryptoKit
import Foundation

/// 1 = pre-1.12.x; 2 = padded + signed + sealed + encrypted manifests (§6.2). Set this to
/// the generation whose rules are actually implemented — claiming 2 without also doing
/// padding, signatures, sealed presence and encrypted manifests turns a legible "peer
/// needs to update" warning back into silent invisibility.
public enum ProtocolVersion {
    public static let current = 2
}

public struct LocationUpdate: Codable, Equatable {
    public let memberId: String
    public let latitude: Double
    public let longitude: Double
    public let accuracy: Double
    public let timestamp: Int64
    public let speed: Double?
    public let bearing: Double?

    public init(
        memberId: String, latitude: Double, longitude: Double, accuracy: Double,
        timestamp: Int64, speed: Double? = nil, bearing: Double? = nil
    ) {
        self.memberId = memberId
        self.latitude = latitude
        self.longitude = longitude
        self.accuracy = accuracy
        self.timestamp = timestamp
        self.speed = speed
        self.bearing = bearing
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

    public init(memberId: String, isOnline: Bool, timestamp: Int64, signature: String? = nil, protocolVersion: Int = ProtocolVersion.current) {
        self.memberId = memberId
        self.isOnline = isOnline
        self.timestamp = timestamp
        self.signature = signature
        self.protocolVersion = protocolVersion
    }

    private enum CodingKeys: String, CodingKey { case memberId, isOnline, timestamp, signature, protocolVersion }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        memberId = try c.decode(String.self, forKey: .memberId)
        isOnline = try c.decode(Bool.self, forKey: .isOnline)
        timestamp = try c.decode(Int64.self, forKey: .timestamp)
        signature = try c.decodeIfPresent(String.self, forKey: .signature)
        protocolVersion = try c.decodeIfPresent(Int.self, forKey: .protocolVersion) ?? 1
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
        ed25519SecretKey64: [UInt8], protocolVersion: Int = ProtocolVersion.current
    ) throws -> PresenceUpdate {
        let payload = signingPayload(memberId: memberId, isOnline: isOnline, timestamp: timestamp)
        let signature = try SodiumRaw.signDetached(message: Array(payload.utf8), secretKey: ed25519SecretKey64)
        return PresenceUpdate(memberId: memberId, isOnline: isOnline, timestamp: timestamp, signature: Hex.encode(signature), protocolVersion: protocolVersion)
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

    public init(v: Int = 2, data: String) {
        self.v = v
        self.data = data
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
    public static func seal(envelopeJSON: Data, groupFileEncryptionKeyHex: String) throws -> SealedPresence {
        guard let key = presenceKey(groupFileEncryptionKeyHex: groupFileEncryptionKeyHex) else {
            throw PresenceSealingError.noGroupKey
        }
        let box = try AES.GCM.seal(envelopeJSON, using: key)
        guard let combined = box.combined else { throw PresenceSealingError.malformedContainer }
        return SealedPresence(data: combined.base64EncodedString())
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

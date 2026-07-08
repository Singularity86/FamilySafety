import Foundation

/// The E2EE envelope used for every encrypted MQTT payload except presence (see
/// IOS_PORT_SPEC.md §3). Field names match the Android EncryptedMessage JSON exactly;
/// decoding ignores unknown keys implicitly (Codable already only reads keys it knows).
public struct EncryptedEnvelope: Codable, Equatable {
    public let senderMemberId: String
    public let nonce: String
    public let ciphertext: String
    public let signature: String

    public init(senderMemberId: String, nonce: String, ciphertext: String, signature: String) {
        self.senderMemberId = senderMemberId
        self.nonce = nonce
        self.ciphertext = ciphertext
        self.signature = signature
    }

    /// Reads the plaintext senderMemberId without verifying anything. This value is
    /// UNAUTHENTICATED routing metadata only — never use it to look up crypto keys
    /// from anywhere but your own local roster, and always verify the signature
    /// before trusting the decrypted content. Mirrors E2EEManager.peekSenderMemberId.
    public static func peekSenderMemberId(json: Data) -> String? {
        try? JSONDecoder().decode(EncryptedEnvelope.self, from: json).senderMemberId
    }
}

public enum E2EEError: Error {
    case encryptionFailed
    case invalidSignature
    case decryptionFailed
    case malformedEnvelope
}

/// Ports E2EEManager.kt: X25519 shared-secret cache + XSalsa20-Poly1305 + detached
/// Ed25519 signature. Verify-then-decrypt, always. Shared-secret cache access is
/// synchronized with a lock, matching the ConcurrentHashMap on Android.
public final class E2EECrypto {
    private let localMemberId: String
    private let localEd25519SecretKey: [UInt8]   // 64 bytes
    private let localX25519SecretKey: [UInt8]    // 32 bytes

    private var sharedSecretCache: [String: [UInt8]] = [:]
    private let cacheLock = NSLock()

    public init(localMemberId: String, localEd25519SecretKey: [UInt8], localX25519SecretKey: [UInt8]) {
        self.localMemberId = localMemberId
        self.localEd25519SecretKey = localEd25519SecretKey
        self.localX25519SecretKey = localX25519SecretKey
    }

    /// Encrypts `plaintext` for one recipient. Produces the JSON envelope bytes ready
    /// to publish to that recipient's inbox topic. Mirrors E2EEManager.encryptMessage.
    public func encrypt(
        plaintext: String,
        recipientMemberId: String,
        recipientX25519PublicKey: [UInt8]
    ) throws -> Data {
        try SodiumRaw.ensureInitialized()
        let shared = try sharedSecret(otherMemberId: recipientMemberId, otherX25519PublicKey: recipientX25519PublicKey)

        let nonce = SodiumRaw.randomBytes(SodiumRaw.Lengths.secretboxNonceBytes)
        guard let plaintextBytes = plaintext.data(using: .utf8) else { throw E2EEError.encryptionFailed }
        let ciphertext = try SodiumRaw.secretboxEasy(message: Array(plaintextBytes), nonce: nonce, key: shared)

        let signature = try SodiumRaw.signDetached(message: nonce + ciphertext, secretKey: localEd25519SecretKey)

        let envelope = EncryptedEnvelope(
            senderMemberId: localMemberId,
            nonce: Hex.encode(nonce),
            ciphertext: Hex.encode(ciphertext),
            signature: Hex.encode(signature)
        )
        return try JSONEncoder().encode(envelope)
    }

    /// Decrypts an envelope. `senderX25519PublicKey`/`senderEd25519PublicKey` MUST come
    /// from the caller's local roster — never from the envelope itself (§3, §9.2).
    public func decrypt(
        envelopeJSON: Data,
        senderMemberId: String,
        senderX25519PublicKey: [UInt8],
        senderEd25519PublicKey: [UInt8]
    ) throws -> String {
        try SodiumRaw.ensureInitialized()
        let envelope: EncryptedEnvelope
        do {
            envelope = try JSONDecoder().decode(EncryptedEnvelope.self, from: envelopeJSON)
        } catch {
            throw E2EEError.malformedEnvelope
        }

        guard let nonce = Hex.decode(envelope.nonce),
              let ciphertext = Hex.decode(envelope.ciphertext),
              let signature = Hex.decode(envelope.signature) else {
            throw E2EEError.malformedEnvelope
        }

        guard SodiumRaw.signVerifyDetached(signature: signature, message: nonce + ciphertext, publicKey: senderEd25519PublicKey) else {
            throw E2EEError.invalidSignature
        }

        let shared = try sharedSecret(otherMemberId: senderMemberId, otherX25519PublicKey: senderX25519PublicKey)
        guard let plaintextBytes = SodiumRaw.secretboxOpenEasy(ciphertext: ciphertext, nonce: nonce, key: shared) else {
            throw E2EEError.decryptionFailed
        }
        guard let plaintext = String(bytes: plaintextBytes, encoding: .utf8) else {
            throw E2EEError.decryptionFailed
        }
        return plaintext
    }

    public func removeSharedSecret(memberId: String) {
        cacheLock.lock()
        sharedSecretCache.removeValue(forKey: memberId)
        cacheLock.unlock()
    }

    public func clearSharedSecretCache() {
        cacheLock.lock()
        sharedSecretCache.removeAll()
        cacheLock.unlock()
    }

    private func sharedSecret(otherMemberId: String, otherX25519PublicKey: [UInt8]) throws -> [UInt8] {
        cacheLock.lock()
        if let cached = sharedSecretCache[otherMemberId] {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()

        let shared = try SodiumRaw.boxBeforenm(recipientPublicKey: otherX25519PublicKey, senderSecretKey: localX25519SecretKey)

        cacheLock.lock()
        sharedSecretCache[otherMemberId] = shared
        cacheLock.unlock()
        return shared
    }
}

import Clibsodium
import Foundation

/// Thin wrapper over libsodium's C API, called directly (not through swift-sodium's
/// higher-level Swift wrapper) so every primitive matches the exact function used on
/// Android (Lazysodium) and in the Python vector generator (PyNaCl) byte-for-byte.
/// Byte lengths below are standard, version-stable libsodium constants — see
/// libsodium's crypto_sign.h / crypto_box.h / crypto_secretbox.h.
enum SodiumRaw {
    enum Lengths {
        static let signSeedBytes = 32
        static let signPublicKeyBytes = 32
        static let signSecretKeyBytes = 64
        static let signBytes = 64          // detached signature length

        static let scalarBytes = 32        // X25519 private/public key length

        static let boxBeforenmBytes = 32   // crypto_box_beforenm shared-secret length

        static let secretboxNonceBytes = 24
        static let secretboxMacBytes = 16
    }

    enum SodiumError: Error {
        case initFailed
        case operationFailed(String)
    }

    /// Must succeed before any other call. libsodium is safe to init multiple times.
    static func ensureInitialized() throws {
        if sodium_init() < 0 {
            throw SodiumError.initFailed
        }
    }

    // MARK: - Ed25519 signing

    /// crypto_sign_seed_keypair — expands a 32-byte seed into (publicKey, secretKey64).
    static func signSeedKeypair(seed: [UInt8]) throws -> (publicKey: [UInt8], secretKey: [UInt8]) {
        precondition(seed.count == Lengths.signSeedBytes)
        var publicKey = [UInt8](repeating: 0, count: Lengths.signPublicKeyBytes)
        var secretKey = [UInt8](repeating: 0, count: Lengths.signSecretKeyBytes)
        let rc = crypto_sign_seed_keypair(&publicKey, &secretKey, seed)
        guard rc == 0 else { throw SodiumError.operationFailed("crypto_sign_seed_keypair") }
        return (publicKey, secretKey)
    }

    /// crypto_sign_detached — signs `message` with a 64-byte Ed25519 secret key.
    static func signDetached(message: [UInt8], secretKey: [UInt8]) throws -> [UInt8] {
        precondition(secretKey.count == Lengths.signSecretKeyBytes)
        var signature = [UInt8](repeating: 0, count: Lengths.signBytes)
        var signatureLen: UInt64 = 0
        let rc = crypto_sign_detached(&signature, &signatureLen, message, UInt64(message.count), secretKey)
        guard rc == 0 else { throw SodiumError.operationFailed("crypto_sign_detached") }
        return signature
    }

    /// crypto_sign_verify_detached.
    static func signVerifyDetached(signature: [UInt8], message: [UInt8], publicKey: [UInt8]) -> Bool {
        guard signature.count == Lengths.signBytes, publicKey.count == Lengths.signPublicKeyBytes else {
            return false
        }
        return crypto_sign_verify_detached(signature, message, UInt64(message.count), publicKey) == 0
    }

    // MARK: - X25519

    /// crypto_scalarmult_base — derives an X25519 public key from an already-clamped
    /// 32-byte private scalar. Matches LazysodiumX25519.derivePublicKey on Android:
    /// the SLIP-10 output is clamped first (see Slip10.clampX25519PrivateKey), then
    /// this is called directly — it is NOT crypto_box_seed_keypair, which hashes the
    /// seed before clamping and would produce a different key.
    static func scalarmultBase(privateKey: [UInt8]) throws -> [UInt8] {
        precondition(privateKey.count == Lengths.scalarBytes)
        var publicKey = [UInt8](repeating: 0, count: Lengths.scalarBytes)
        let rc = crypto_scalarmult_base(&publicKey, privateKey)
        guard rc == 0 else { throw SodiumError.operationFailed("crypto_scalarmult_base") }
        return publicKey
    }

    /// crypto_box_beforenm — X25519 scalarmult + HSalsa20, producing a key usable
    /// directly with crypto_secretbox_easy. Symmetric: either side computes the same
    /// value from (their private key, the other's public key).
    static func boxBeforenm(recipientPublicKey: [UInt8], senderSecretKey: [UInt8]) throws -> [UInt8] {
        precondition(recipientPublicKey.count == Lengths.scalarBytes)
        precondition(senderSecretKey.count == Lengths.scalarBytes)
        var shared = [UInt8](repeating: 0, count: Lengths.boxBeforenmBytes)
        let rc = crypto_box_beforenm(&shared, recipientPublicKey, senderSecretKey)
        guard rc == 0 else { throw SodiumError.operationFailed("crypto_box_beforenm") }
        return shared
    }

    // MARK: - XSalsa20-Poly1305 (secretbox)

    /// crypto_secretbox_easy — output layout is 16-byte MAC || ciphertext, matching
    /// Lazysodium's cryptoSecretBoxEasy and PyNaCl's crypto_secretbox exactly.
    static func secretboxEasy(message: [UInt8], nonce: [UInt8], key: [UInt8]) throws -> [UInt8] {
        precondition(nonce.count == Lengths.secretboxNonceBytes)
        precondition(key.count == Lengths.boxBeforenmBytes)
        var ciphertext = [UInt8](repeating: 0, count: message.count + Lengths.secretboxMacBytes)
        let rc = crypto_secretbox_easy(&ciphertext, message, UInt64(message.count), nonce, key)
        guard rc == 0 else { throw SodiumError.operationFailed("crypto_secretbox_easy") }
        return ciphertext
    }

    /// crypto_secretbox_open_easy. Returns nil (not throws) on authentication failure,
    /// since a failed decrypt is an expected, non-exceptional outcome for callers.
    static func secretboxOpenEasy(ciphertext: [UInt8], nonce: [UInt8], key: [UInt8]) -> [UInt8]? {
        guard ciphertext.count >= Lengths.secretboxMacBytes else { return nil }
        guard nonce.count == Lengths.secretboxNonceBytes, key.count == Lengths.boxBeforenmBytes else { return nil }
        var message = [UInt8](repeating: 0, count: ciphertext.count - Lengths.secretboxMacBytes)
        let rc = crypto_secretbox_open_easy(&message, ciphertext, UInt64(ciphertext.count), nonce, key)
        return rc == 0 ? message : nil
    }

    // MARK: - Randomness

    /// randombytes_buf — fills `count` bytes from libsodium's CSPRNG.
    static func randomBytes(_ count: Int) -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        randombytes_buf(&bytes, count)
        return bytes
    }
}

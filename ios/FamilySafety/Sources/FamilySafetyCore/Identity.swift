import CryptoKit
import Foundation

/// Standard derivation paths for FamilySafety keys. Mirrors FamilySafeKeyPaths in
/// Slip10KeyDerivation.kt.
public enum FamilySafeKeyPaths {
    public static let purpose: UInt32 = 44
    public static let coinType: UInt32 = 1984 // custom, unregistered coin type

    /// m/44'/1984'/account'/0' → Ed25519 signing key
    public static func signingKeyPath(accountIndex: UInt32 = 0) -> [UInt32] {
        [purpose, coinType, accountIndex, 0]
    }

    /// m/44'/1984'/account'/1' → X25519 encryption key
    public static func encryptionKeyPath(accountIndex: UInt32 = 0) -> [UInt32] {
        [purpose, coinType, accountIndex, 1]
    }
}

/// A fully-derived FamilySafety identity for one account index. Mirrors
/// FamilySafeKeyDerivation.FamilySafeKeys on Android.
public struct FamilySafeIdentity: Equatable {
    /// 32-byte SLIP-10 seed at the signing key path — the canonical Ed25519 "private key".
    public let ed25519Seed: [UInt8]
    public let ed25519PublicKey: [UInt8]     // 32 bytes
    public let ed25519SecretKey64: [UInt8]   // 64 bytes, expanded — required by crypto_sign_detached

    public let x25519PrivateKey: [UInt8]     // 32 bytes, RFC 7748 clamped
    public let x25519PublicKey: [UInt8]      // 32 bytes

    /// SHA-256(ed25519PublicKey)[0..<16], lowercase hex — matches
    /// LazysodiumCryptoProvider.deriveMemberId / GroupTransitionValidator.deriveMemberIdFromKey.
    public let memberId: String

    public var ed25519PublicKeyHex: String { Hex.encode(ed25519PublicKey) }
    public var x25519PublicKeyHex: String { Hex.encode(x25519PublicKey) }
}

public enum FamilySafeKeyDerivation {
    /// Derives the full identity for `accountIndex` from a 64-byte BIP-39 seed.
    public static func deriveIdentity(seed: [UInt8], accountIndex: UInt32 = 0) throws -> FamilySafeIdentity {
        precondition(seed.count == 64, "Seed must be 64 bytes from BIP-39")

        let edKeyPair = try Slip10.deriveEd25519(seed: seed, path: FamilySafeKeyPaths.signingKeyPath(accountIndex: accountIndex))
        let (_, edSecretKey64) = try SodiumRaw.signSeedKeypair(seed: edKeyPair.privateKey)

        let xKeyPair = try Slip10.deriveX25519(seed: seed, path: FamilySafeKeyPaths.encryptionKeyPath(accountIndex: accountIndex))

        let memberIdHash = Array(SHA256.hash(data: Data(edKeyPair.publicKey)))
        let memberId = Hex.encode(Array(memberIdHash.prefix(16)))

        return FamilySafeIdentity(
            ed25519Seed: edKeyPair.privateKey,
            ed25519PublicKey: edKeyPair.publicKey,
            ed25519SecretKey64: edSecretKey64,
            x25519PrivateKey: xKeyPair.privateKey,
            x25519PublicKey: xKeyPair.publicKey,
            memberId: memberId
        )
    }

    /// Convenience overload: derives straight from a mnemonic string.
    public static func deriveIdentity(
        mnemonic: String,
        passphrase: String = "",
        accountIndex: UInt32 = 0
    ) throws -> FamilySafeIdentity {
        let seed = Bip39.mnemonicToSeed(mnemonic: mnemonic, passphrase: passphrase)
        return try deriveIdentity(seed: seed, accountIndex: accountIndex)
    }
}

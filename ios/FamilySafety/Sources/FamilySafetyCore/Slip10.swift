import CryptoKit
import Foundation

/// SLIP-10 key derivation on the ed25519 curve. Mirrors Slip10KeyDerivation.kt exactly
/// (https://github.com/satoshilabs/slips/blob/master/slip-0010.md), including the
/// X25519 branch: same SLIP-10 derivation, then RFC 7748 clamping of the 32-byte
/// output. Path format: m/44'/1984'/account'/keyType' — every index is hardened.
public enum Slip10 {
    private static let hardenedOffset: UInt32 = 0x8000_0000

    public struct KeyPair: Equatable {
        public let privateKey: [UInt8]
        public let publicKey: [UInt8]
    }

    /// Derives an Ed25519 keypair. `privateKey` in the result is the raw 32-byte
    /// SLIP-10 seed (not the 64-byte expanded libsodium secret key) — matching
    /// Android's Slip10.KeyPair semantics. Use FamilySafeKeyDerivation to also get
    /// the expanded 64-byte secret key needed for signing.
    public static func deriveEd25519(seed: [UInt8], path: [UInt32]) throws -> KeyPair {
        precondition(seed.count == 64, "Seed must be 64 bytes (512 bits)")
        var (privateKey, chainCode) = masterKey(seed: seed)
        for index in path {
            (privateKey, chainCode) = childKey(parentPrivateKey: privateKey, parentChainCode: chainCode, index: index | hardenedOffset)
        }
        let (publicKey, _) = try SodiumRaw.signSeedKeypair(seed: privateKey)
        return KeyPair(privateKey: privateKey, publicKey: publicKey)
    }

    /// Derives an X25519 keypair using the same SLIP-10 derivation as Ed25519, then
    /// clamps the 32-byte output per RFC 7748 before deriving the public key via
    /// crypto_scalarmult_base — NOT crypto_box_seed_keypair, which hashes the seed
    /// first and would produce a different key.
    public static func deriveX25519(seed: [UInt8], path: [UInt32]) throws -> KeyPair {
        precondition(seed.count == 64, "Seed must be 64 bytes")
        var (privateKey, chainCode) = masterKey(seed: seed)
        for index in path {
            (privateKey, chainCode) = childKey(parentPrivateKey: privateKey, parentChainCode: chainCode, index: index | hardenedOffset)
        }
        let clamped = clampX25519PrivateKey(privateKey)
        let publicKey = try SodiumRaw.scalarmultBase(privateKey: clamped)
        return KeyPair(privateKey: clamped, publicKey: publicKey)
    }

    private static func masterKey(seed: [UInt8]) -> (privateKey: [UInt8], chainCode: [UInt8]) {
        let key = SymmetricKey(data: Data("ed25519 seed".utf8))
        let hmac = Array(HMAC<SHA512>.authenticationCode(for: Data(seed), using: key))
        return (Array(hmac[0..<32]), Array(hmac[32..<64]))
    }

    private static func childKey(
        parentPrivateKey: [UInt8],
        parentChainCode: [UInt8],
        index: UInt32
    ) -> (privateKey: [UInt8], chainCode: [UInt8]) {
        precondition(index & hardenedOffset != 0, "Ed25519 SLIP-10 only supports hardened derivation")

        var data = [UInt8]()
        data.reserveCapacity(1 + 32 + 4)
        data.append(0x00)
        data.append(contentsOf: parentPrivateKey)
        data.append(UInt8((index >> 24) & 0xFF))
        data.append(UInt8((index >> 16) & 0xFF))
        data.append(UInt8((index >> 8) & 0xFF))
        data.append(UInt8(index & 0xFF))

        let key = SymmetricKey(data: Data(parentChainCode))
        let hmac = Array(HMAC<SHA512>.authenticationCode(for: Data(data), using: key))
        return (Array(hmac[0..<32]), Array(hmac[32..<64]))
    }

    /// RFC 7748 clamping: clear the bottom 3 bits of the first byte, clear the top
    /// bit and set the second-to-top bit of the last byte.
    private static func clampX25519PrivateKey(_ key: [UInt8]) -> [UInt8] {
        var k = key
        k[0] &= 0xF8
        k[31] = (k[31] & 0x7F) | 0x40
        return k
    }
}

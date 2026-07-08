import CryptoKit
import Foundation

/// PBKDF2 implemented directly over CryptoKit's `HMAC<SHA512>` rather than
/// CommonCrypto, so this package has no C bridging-header requirements. Only ever
/// used here with dkLen=64 (a single SHA-512 block), but implemented generally.
enum PBKDF2 {
    static func sha512(password: [UInt8], salt: [UInt8], iterations: Int, keyLength: Int) -> [UInt8] {
        let hLen = 64
        let blockCount = (keyLength + hLen - 1) / hLen
        let key = SymmetricKey(data: Data(password))
        var derived = Data()
        derived.reserveCapacity(blockCount * hLen)

        for blockIndex in 1...blockCount {
            var saltBlock = Data(salt)
            var indexBE = UInt32(blockIndex).bigEndian
            withUnsafeBytes(of: &indexBE) { saltBlock.append(contentsOf: $0) }

            var u = Data(HMAC<SHA512>.authenticationCode(for: saltBlock, using: key))
            var t = u

            if iterations > 1 {
                for _ in 1..<iterations {
                    u = Data(HMAC<SHA512>.authenticationCode(for: u, using: key))
                    for i in 0..<t.count { t[i] ^= u[i] }
                }
            }
            derived.append(t)
        }
        return Array(derived.prefix(keyLength))
    }
}

public enum Bip39Error: Error {
    case wordlistNotFound
    case invalidState
}

/// BIP-39 mnemonic utilities. Mirrors Bip39.kt exactly, including the bundled
/// wordlist: `Resources/bip39_english.txt` is a byte-for-byte copy of the Android
/// app's `app/src/main/res/raw/bip39_english.txt`, so both platforms generate and
/// validate mnemonics identically.
public enum Bip39 {
    private static let pbkdf2Iterations = 2048
    private static let seedLength = 64

    private static let lock = NSLock()
    nonisolated(unsafe) private static var cachedWordlist: [String]?

    private static func wordlist() throws -> [String] {
        lock.lock()
        defer { lock.unlock() }
        if let cached = cachedWordlist { return cached }
        guard let url = Bundle.module.url(forResource: "bip39_english", withExtension: "txt") else {
            throw Bip39Error.wordlistNotFound
        }
        let text = try String(contentsOf: url, encoding: .utf8)
        let words = text
            .split(whereSeparator: { $0.isNewline })
            .map(String.init)
            .filter { !$0.isEmpty }
        cachedWordlist = words
        return words
    }

    /// Generates a 12-word BIP-39 mnemonic: 128 bits of entropy + first 4 bits of
    /// SHA-256(entropy) as checksum = 132 bits = twelve 11-bit word indices.
    public static func generate12WordMnemonic() throws -> [String] {
        let words = try wordlist()
        let entropy = SodiumRaw.randomBytes(16)
        let hashBytes = Array(SHA256.hash(data: Data(entropy)))

        var bits = ""
        bits.reserveCapacity(132)
        for byte in entropy {
            for bitIndex in stride(from: 7, through: 0, by: -1) {
                bits.append(((byte >> bitIndex) & 1) == 1 ? "1" : "0")
            }
        }
        let checksumByte = hashBytes[0]
        for bitIndex in stride(from: 7, through: 4, by: -1) {
            bits.append(((checksumByte >> bitIndex) & 1) == 1 ? "1" : "0")
        }

        var result = [String]()
        result.reserveCapacity(12)
        for i in 0..<12 {
            let start = bits.index(bits.startIndex, offsetBy: i * 11)
            let end = bits.index(start, offsetBy: 11)
            guard let index = Int(bits[start..<end], radix: 2) else { throw Bip39Error.invalidState }
            result.append(words[index])
        }
        return result
    }

    /// Converts a mnemonic to its 64-byte BIP-39 seed via
    /// PBKDF2-HMAC-SHA512(password: normalized mnemonic, salt: "mnemonic" + passphrase,
    /// iterations: 2048). Does not require the wordlist to be loaded.
    public static func mnemonicToSeed(mnemonic: String, passphrase: String = "") -> [UInt8] {
        let normalized = mnemonic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")

        let password = Array(normalized.utf8)
        let salt = Array(("mnemonic" + passphrase).utf8)
        return PBKDF2.sha512(password: password, salt: salt, iterations: pbkdf2Iterations, keyLength: seedLength)
    }

    /// True if every word in `mnemonic` is present in the BIP-39 English wordlist.
    public static func validateMnemonic(_ mnemonic: String) throws -> Bool {
        let words = Set(try wordlist())
        let mnemonicWords = mnemonic
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: { $0.isWhitespace })
        return mnemonicWords.allSatisfy { words.contains(String($0)) }
    }
}

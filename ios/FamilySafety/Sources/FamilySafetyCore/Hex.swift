import Foundation

/// Lowercase-hex codec matching the Android app's `toHexString()` / `hexToByteArray()`
/// extension functions used throughout E2EEManager.kt, GroupSyncManager.kt, etc.
/// Works on UTF-8 bytes rather than `String.Index` (grapheme clusters), so it is linear and a
/// non-ASCII character can never be mistaken for a pair of hex digits.
public enum Hex {
    private static let digits: [UInt8] = Array("0123456789abcdef".utf8)

    public static func encode(_ bytes: [UInt8]) -> String {
        var out = [UInt8]()
        out.reserveCapacity(bytes.count * 2)
        for byte in bytes {
            out.append(digits[Int(byte >> 4)])
            out.append(digits[Int(byte & 0x0f)])
        }
        return String(decoding: out, as: UTF8.self)
    }

    /// Decodes a hex string of either case. Returns nil if the string has odd length
    /// or contains non-hex characters (callers should treat that as a protocol error).
    public static func decode(_ hex: String) -> [UInt8]? {
        let chars = Array(hex.utf8)
        guard chars.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let high = nibble(chars[i]), let low = nibble(chars[i + 1]) else { return nil }
            bytes.append(high << 4 | low)
            i += 2
        }
        return bytes
    }

    private static func nibble(_ c: UInt8) -> UInt8? {
        switch c {
        case UInt8(ascii: "0")...UInt8(ascii: "9"): return c - UInt8(ascii: "0")
        case UInt8(ascii: "a")...UInt8(ascii: "f"): return c - UInt8(ascii: "a") + 10
        case UInt8(ascii: "A")...UInt8(ascii: "F"): return c - UInt8(ascii: "A") + 10
        default: return nil
        }
    }
}

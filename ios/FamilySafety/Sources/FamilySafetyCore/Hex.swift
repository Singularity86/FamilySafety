import Foundation

/// Lowercase-hex codec matching the Android app's `toHexString()` / `hexToByteArray()`
/// extension functions used throughout E2EEManager.kt, GroupSyncManager.kt, etc.
enum Hex {
    static func encode(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Decodes a hex string of either case. Returns nil if the string has odd length
    /// or contains non-hex characters (callers should treat that as a protocol error).
    static func decode(_ hex: String) -> [UInt8]? {
        guard hex.count % 2 == 0 else { return nil }
        var bytes = [UInt8]()
        bytes.reserveCapacity(hex.count / 2)
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2)
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        return bytes
    }
}

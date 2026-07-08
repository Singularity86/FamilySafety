import CryptoKit
import Foundation

/// Shared-file-library crypto. Mirrors the small key-derivation piece of
/// SharedFileRepository.kt (see IOS_PORT_SPEC.md §6.7). The full AES-256-GCM
/// chunk encrypt/decrypt + upload/download pipeline is Phase 6 scope — not
/// implemented yet.
public enum SharedFileCrypto {
    private static let fileKeySalt = "familysafety-files-v1"

    /// AES-256 key for the group's shared file library: SHA-256(groupId + salt).
    /// Matches SharedFileRepository.deriveFileKey on Android exactly.
    public static func deriveFileKey(groupId: String) -> [UInt8] {
        Array(SHA256.hash(data: Data((groupId + fileKeySalt).utf8)))
    }
}

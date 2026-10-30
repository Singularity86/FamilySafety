import CryptoKit
import Foundation

/// A slot too big for the Keychain (chat history): a file sealed with AES-256-GCM under a key
/// that lives in the Keychain (`keySlot`). The file alone reads as noise, and it is excluded
/// from backups like the key, so it never leaves this phone.
///
/// The same F14 rules as the other slots: nil only when nothing was ever written; a key that is
/// locked right now, or a file that won't open, throws, and nothing is deleted for failing to read.
public final class SealedFileSlot: SecretSlot, @unchecked Sendable {
    private let url: URL
    private let keySlot: SecretSlot
    private let lock = NSLock()

    public init(url: URL, keySlot: SecretSlot) {
        self.url = url
        self.keySlot = keySlot
    }

    public func read() throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        guard let raw = try keySlot.read() else { throw GroupStatePersistenceError.unreadable }
        do {
            return try AES.GCM.open(try AES.GCM.SealedBox(combined: Data(contentsOf: url)), using: SymmetricKey(data: raw))
        } catch {
            throw GroupStatePersistenceError.unreadable
        }
    }

    public func write(_ data: Data) throws {
        lock.lock(); defer { lock.unlock() }
        let key: SymmetricKey
        if let raw = try keySlot.read() {
            key = SymmetricKey(data: raw)
        } else {
            key = SymmetricKey(size: .bits256)
            try keySlot.write(key.withUnsafeBytes { Data($0) })
        }
        guard let sealed = try AES.GCM.seal(data, using: key).combined else { throw GroupStatePersistenceError.unreadable }
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        #if os(iOS)
        try sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        #else
        try sealed.write(to: url, options: .atomic)
        #endif
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var target = url
        try? target.setResourceValues(values)
    }

    public func delete() throws {
        lock.lock(); defer { lock.unlock() }
        if FileManager.default.fileExists(atPath: url.path) { try FileManager.default.removeItem(at: url) }
        try keySlot.delete()
    }
}

import CryptoKit
import Foundation
import Security

/// Storage for the one `GroupDefinition` this device belongs to.
///
/// Review rule F14: an error is never "no group". `load()` returns nil only when nothing has
/// ever been saved; anything unreadable throws, so a caller can't mistake a transient failure
/// for an empty slate and create (or join) a family over the top of the real one. Nothing in
/// here deletes data in response to an error.
public protocol GroupStatePersistence: Sendable {
    func load() throws -> GroupDefinition?
    func save(_ group: GroupDefinition) throws
    func delete() throws
}

public enum GroupStatePersistenceError: Error, Equatable {
    /// The key exists but can't be read right now (device locked, keychain busy). Retry later.
    case keyTemporarilyUnavailable(OSStatus)
    /// The key store returned something unexpected. Surface it; never wipe in response.
    case keyStoreFailure(OSStatus)
    /// The file exists but doesn't decrypt or decode. Surface it; never wipe in response.
    case unreadable
}

/// For tests and previews.
public final class InMemoryGroupStatePersistence: GroupStatePersistence, @unchecked Sendable {
    private let lock = NSLock()
    private var stored: GroupDefinition?

    public init(initial: GroupDefinition? = nil) { stored = initial }

    public func load() throws -> GroupDefinition? { lock.withLock { stored } }
    public func save(_ group: GroupDefinition) throws { lock.withLock { stored = group } }
    public func delete() throws { lock.withLock { stored = nil } }
}

/// Supplies the AES-256 key that encrypts the group file.
public protocol GroupStateKeyProvider: Sendable {
    func key() throws -> SymmetricKey
}

/// AES-256-GCM over the JSON of the group, written atomically to `fileURL`.
public final class FileGroupStatePersistence: GroupStatePersistence, @unchecked Sendable {
    private let fileURL: URL
    private let keyProvider: GroupStateKeyProvider
    private let lock = NSLock()

    public init(fileURL: URL, keyProvider: GroupStateKeyProvider) {
        self.fileURL = fileURL
        self.keyProvider = keyProvider
    }

    public func load() throws -> GroupDefinition? {
        try lock.withLock {
            guard FileManager.default.fileExists(atPath: fileURL.path) else { return nil }
            let sealed = try Data(contentsOf: fileURL)
            let key = try keyProvider.key()
            guard let box = try? AES.GCM.SealedBox(combined: sealed),
                  let plain = try? AES.GCM.open(box, using: key),
                  let group = try? JSONDecoder().decode(GroupDefinition.self, from: plain) else {
                throw GroupStatePersistenceError.unreadable
            }
            return group
        }
    }

    public func save(_ group: GroupDefinition) throws {
        try lock.withLock {
            let key = try keyProvider.key()
            let plain = try JSONEncoder().encode(group)
            guard let sealed = try AES.GCM.seal(plain, using: key).combined else {
                throw GroupStatePersistenceError.unreadable
            }
            try FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
            try sealed.write(to: fileURL, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    public func delete() throws {
        try lock.withLock {
            if FileManager.default.fileExists(atPath: fileURL.path) {
                try FileManager.default.removeItem(at: fileURL)
            }
        }
    }
}

/// Keeps the group-file key in the keychain, created on first use.
///
/// Distinguishes "locked right now" from "not there" from "broken", and never deletes the
/// item on any of them — the Android bug this avoids (F14) was a generic catch that wiped
/// the key, and with it everything the key protected.
public final class KeychainGroupStateKeyProvider: GroupStateKeyProvider, @unchecked Sendable {
    private let service: String
    private let account: String

    public init(service: String = "FamilySafety.groupState", account: String = "group-file-key") {
        self.service = service
        self.account = account
    }

    public func key() throws -> SymmetricKey {
        var query: [String: Any] = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data, data.count == 32 else {
                throw GroupStatePersistenceError.keyStoreFailure(status)
            }
            return SymmetricKey(data: data)
        case errSecItemNotFound:
            return try create()
        case errSecInteractionNotAllowed:
            throw GroupStatePersistenceError.keyTemporarilyUnavailable(status)
        default:
            throw GroupStatePersistenceError.keyStoreFailure(status)
        }
    }

    private var baseQuery: [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    private func create() throws -> SymmetricKey {
        let key = SymmetricKey(size: .bits256)
        var attributes = baseQuery
        attributes[kSecValueData as String] = key.withUnsafeBytes { Data($0) }
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            if status == errSecInteractionNotAllowed {
                throw GroupStatePersistenceError.keyTemporarilyUnavailable(status)
            }
            throw GroupStatePersistenceError.keyStoreFailure(status)
        }
        return key
    }
}

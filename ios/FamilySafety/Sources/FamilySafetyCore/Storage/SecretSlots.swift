import Foundation
import Security

/// One small secret: the recovery phrase, the invite book, the device ID.
///
/// Same F14 rules as the group file: `read()` returns nil only when nothing was ever written;
/// "locked right now" and "broken" throw, and nothing here deletes an item because reading it
/// failed.
public protocol SecretSlot: Sendable {
    func read() throws -> Data?
    func write(_ data: Data) throws
    /// Only on an explicit user action (leaving, or a reset confirmed with the phrase).
    func delete() throws
}

/// A Keychain generic password, readable after first unlock, never backed up or transferred
/// to another device (`…ThisDeviceOnly`).
public final class KeychainSlot: SecretSlot, @unchecked Sendable {
    private let service: String
    private let account: String

    public init(service: String, account: String) {
        self.service = service
        self.account = account
    }

    private var baseQuery: [String: Any] {
        [kSecClass as String: kSecClassGenericPassword,
         kSecAttrService as String: service,
         kSecAttrAccount as String: account]
    }

    public func read() throws -> Data? {
        var query = baseQuery
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        switch status {
        case errSecSuccess:
            guard let data = item as? Data else { throw GroupStatePersistenceError.keyStoreFailure(status) }
            return data
        case errSecItemNotFound:
            return nil
        case errSecInteractionNotAllowed:
            throw GroupStatePersistenceError.keyTemporarilyUnavailable(status)
        default:
            throw GroupStatePersistenceError.keyStoreFailure(status)
        }
    }

    public func write(_ data: Data) throws {
        let update = SecItemUpdate(baseQuery as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return }
        guard update == errSecItemNotFound else { throw Self.error(update) }
        var attributes = baseQuery
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let add = SecItemAdd(attributes as CFDictionary, nil)
        guard add == errSecSuccess else { throw Self.error(add) }
    }

    public func delete() throws {
        let status = SecItemDelete(baseQuery as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else { throw Self.error(status) }
    }

    private static func error(_ status: OSStatus) -> GroupStatePersistenceError {
        status == errSecInteractionNotAllowed ? .keyTemporarilyUnavailable(status) : .keyStoreFailure(status)
    }
}

/// For tests and previews.
public final class InMemorySlot: SecretSlot, @unchecked Sendable {
    private let lock = NSLock()
    private var data: Data?
    public init(_ data: Data? = nil) { self.data = data }
    public func read() throws -> Data? { lock.lock(); defer { lock.unlock() }; return data }
    public func write(_ data: Data) throws { lock.lock(); self.data = data; lock.unlock() }
    public func delete() throws { lock.lock(); data = nil; lock.unlock() }
}

/// What this phone keeps about its own person: the recovery phrase (the identity) and the
/// name it asked to be known by.
public struct LocalProfile: Codable, Equatable, Sendable {
    public let mnemonic: String
    public let displayName: String

    public init(mnemonic: String, displayName: String) {
        self.mnemonic = mnemonic
        self.displayName = displayName
    }

    public func identity() throws -> FamilySafeIdentity {
        try FamilySafeKeyDerivation.deriveIdentity(mnemonic: mnemonic)
    }
}

public extension SecretSlot {
    func readJSON<T: Decodable>(_ type: T.Type) throws -> T? {
        guard let data = try read() else { return nil }
        do { return try JSONDecoder().decode(T.self, from: data) } catch { throw GroupStatePersistenceError.unreadable }
    }

    func writeJSON<T: Encodable>(_ value: T) throws {
        try write(JSONEncoder().encode(value))
    }
}

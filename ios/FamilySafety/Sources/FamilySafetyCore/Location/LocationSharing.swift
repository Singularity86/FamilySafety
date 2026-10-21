import Foundation

/// Sending and receiving live location (B2; spec §6.1, §6.2, §4). Pure rules; the app's
/// CoreLocation layer feeds fixes in and the node publishes what comes out.
public enum LocationCodec {
    /// A signed `LocationUpdate` (3.2) wrapped in a padded envelope (§6.1, 512-byte buckets),
    /// as the plaintext to encrypt once per member.
    public static func plaintext(for fix: LocationUpdate, identity: FamilySafeIdentity) throws -> String {
        let unsigned = LocationUpdate(memberId: identity.memberId, latitude: fix.latitude, longitude: fix.longitude,
                                      accuracy: fix.accuracy, timestamp: fix.timestamp, speed: fix.speed, bearing: fix.bearing)
        let signed = LocationUpdate(memberId: unsigned.memberId, latitude: unsigned.latitude, longitude: unsigned.longitude,
                                    accuracy: unsigned.accuracy, timestamp: unsigned.timestamp, speed: unsigned.speed,
                                    bearing: unsigned.bearing,
                                    authorSig: try AuthorSignatures.sign(AuthorSignatures.location(unsigned), identity: identity))
        let inner = String(decoding: try JSONEncoder().encode(signed), as: UTF8.self)
        return String(decoding: try JSONEncoder().encode(try EnvelopePadding.build(type: .locationUpdate, payload: inner)), as: UTF8.self)
    }

    /// The location in an opened envelope body from `sender`, or nil. Its `memberId` must be the
    /// authenticated sender (§6.2, F23), and a present author signature must verify (3.2).
    public static func read(_ body: String, sender: FamilyMember) -> LocationUpdate? {
        guard let envelope = try? JSONDecoder().decode(MessageEnvelope.self, from: Data(body.utf8)),
              envelope.type == .locationUpdate,
              let fix = try? JSONDecoder().decode(LocationUpdate.self, from: Data(envelope.payload.utf8)),
              fix.memberId == sender.memberId else { return nil }
        if fix.authorSig != nil {
            guard AuthorSignatures.accept(authorSig: fix.authorSig, authorEd25519Hex: sender.ed25519PublicKey,
                                          message: AuthorSignatures.location(fix), acceptUnsigned: false) else { return nil }
        }
        return fix
    }
}

/// Whether a new fix is worth sending (Android LocationPublishPolicy): when it moved more than
/// 30 m (or its own accuracy), is markedly more accurate, or the last one is 5 minutes old.
public enum LocationPublishPolicy {
    public static let minMoveMeters = 30.0
    public static let maxSilenceMs: Int64 = 5 * 60_000

    public static func isRedundant(previous: LocationUpdate, next: LocationUpdate) -> Bool {
        if next.timestamp <= previous.timestamp { return true }
        if next.timestamp - previous.timestamp >= maxSilenceMs { return false }
        if next.accuracy < previous.accuracy / 2 { return false }
        let moved = distanceMeters(previous.latitude, previous.longitude, next.latitude, next.longitude)
        return moved < max(minMoveMeters, Double(next.accuracy))
    }

    public static func distanceMeters(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        let r = 6_371_000.0, toRad = Double.pi / 180
        let dLat = (lat2 - lat1) * toRad, dLon = (lon2 - lon1) * toRad
        let a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1 * toRad) * cos(lat2 * toRad) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * asin(sqrt(a))
    }
}

/// The newest known location per member, persisted so the map isn't empty after a restart.
/// Older fixes are discarded on arrival (§4: a position supersedes itself).
public struct LocationBook: Codable, Equatable, Sendable {
    public private(set) var latest: [String: LocationUpdate] = [:]
    public init() {}

    public mutating func accept(_ fix: LocationUpdate) -> Bool {
        if let held = latest[fix.memberId], held.timestamp >= fix.timestamp { return false }
        latest[fix.memberId] = fix
        return true
    }

    public mutating func keepOnly(_ memberIds: Set<String>) {
        latest = latest.filter { memberIds.contains($0.key) }
    }
}

/// What the node keeps about location, persisted in one slot: the newest fix per member, the
/// last fix we published, and whether sharing is paused.
public struct LocationState: Codable, Equatable, Sendable {
    public var book = LocationBook()
    public var lastSent: LocationUpdate?
    /// "Share my location" off: nothing is published, and nothing restarts it on relaunch
    /// (spec §11, "A real pause").
    public var paused = false
    public init() {}
}

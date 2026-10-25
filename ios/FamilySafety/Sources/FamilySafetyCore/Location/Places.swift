import Foundation

/// A place this phone watches (Android GeofenceZone). Local to this phone: places aren't shared
/// with the family, and nothing about them goes over the wire.
public struct Place: Codable, Equatable, Identifiable, Sendable {
    public let id: String
    public var name: String
    public var latitude: Double
    public var longitude: Double
    public var radiusMeters: Double
    /// Empty means everyone else in the family.
    public var watchedMemberIds: Set<String>
    public var alertOnEnter: Bool
    public var alertOnExit: Bool
    public var isEnabled: Bool

    public init(id: String = UUID().uuidString.lowercased(), name: String, latitude: Double, longitude: Double, radiusMeters: Double,
                watchedMemberIds: Set<String> = [], alertOnEnter: Bool = true, alertOnExit: Bool = true, isEnabled: Bool = true) {
        self.id = id
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
        self.radiusMeters = radiusMeters
        self.watchedMemberIds = watchedMemberIds
        self.alertOnEnter = alertOnEnter
        self.alertOnExit = alertOnExit
        self.isEnabled = isEnabled
    }
}

public enum PlaceEvent: Equatable, Sendable {
    case entered(memberId: String, place: Place)
    case left(memberId: String, place: Place)
    case speeding(memberId: String, mph: Int, thresholdMph: Int)
}

/// Place and speed alerts, as Android's GeofenceMonitor decides them: inside means within the
/// radius; leaving needs the radius plus 15% (at least 25 m), so a fix wobbling on the edge doesn't
/// flap; a member and place change state at most every 2 minutes; speed alerts at most every 5.
/// Unlike Android, who is inside what survives a relaunch, so reopening the app doesn't announce
/// everyone who is already at home.
public struct PlaceWatch: Codable, Equatable, Sendable {
    public static let transitionCooldownMs: Int64 = 2 * 60_000
    public static let speedCooldownMs: Int64 = 5 * 60_000
    public static let exitMarginMinMeters = 25.0
    public static let exitMarginFraction = 0.15

    public private(set) var places: [Place] = []
    public var speedAlertsOn = false
    public var speedThresholdMph = 90
    /// memberId → ids of places they're inside.
    public private(set) var inside: [String: Set<String>] = [:]
    private var lastTransition: [String: Int64] = [:]
    private var lastSpeedAlert: [String: Int64] = [:]

    public init() {}

    public mutating func save(_ place: Place) {
        if let i = places.firstIndex(where: { $0.id == place.id }) { places[i] = place } else { places.append(place) }
    }

    public mutating func remove(_ placeId: String) {
        places.removeAll { $0.id == placeId }
        for m in inside.keys { inside[m]?.remove(placeId) }
    }

    public mutating func keepOnly(_ members: Set<String>) {
        inside = inside.filter { members.contains($0.key) }
        for i in places.indices { places[i].watchedMemberIds.formIntersection(members) }
    }

    /// What a member's new live fix means here. Call only for fixes newer than the last one.
    public mutating func evaluate(_ fix: LocationUpdate, now: Int64) -> [PlaceEvent] {
        var events: [PlaceEvent] = []
        let was = inside[fix.memberId] ?? []
        var now_inside: Set<String> = []
        for place in places where place.isEnabled && (place.watchedMemberIds.isEmpty || place.watchedMemberIds.contains(fix.memberId)) {
            let d = LocationPublishPolicy.distanceMeters(fix.latitude, fix.longitude, place.latitude, place.longitude)
            let wasInside = was.contains(place.id)
            let exitRadius = place.radiusMeters + max(Self.exitMarginMinMeters, place.radiusMeters * Self.exitMarginFraction)
            let key = "\(fix.memberId):\(place.id)"
            let canChange = now - (lastTransition[key] ?? 0) >= Self.transitionCooldownMs
            if d <= place.radiusMeters {
                if wasInside { now_inside.insert(place.id) }
                else if canChange {
                    now_inside.insert(place.id)
                    lastTransition[key] = now
                    if place.alertOnEnter { events.append(.entered(memberId: fix.memberId, place: place)) }
                }
            } else if wasInside {
                if d <= exitRadius || !canChange { now_inside.insert(place.id) }
                else {
                    lastTransition[key] = now
                    if place.alertOnExit { events.append(.left(memberId: fix.memberId, place: place)) }
                }
            }
        }
        inside[fix.memberId] = now_inside
        if speedAlertsOn, let speed = fix.speed, speed.isFinite {
            let mph = Double(speed) * 2.237
            if mph > Double(speedThresholdMph), now - (lastSpeedAlert[fix.memberId] ?? 0) > Self.speedCooldownMs {
                lastSpeedAlert[fix.memberId] = now
                events.append(.speeding(memberId: fix.memberId, mph: Int(mph), thresholdMph: speedThresholdMph))
            }
        }
        return events
    }
}

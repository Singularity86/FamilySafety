import Foundation

/// Notices one identity in use on two installs at once (Android 2.2, F15; spec §6.2).
///
/// A recovery phrase restores an identity on any phone, so a phrase someone else learned makes a
/// second, invisible copy of a family member. Each install signs a random device ID into its
/// presence; two different IDs for one member online close together mean two phones.
///
/// An offline from the tracked device ends its sighting, so a reinstall or restore (the old
/// install's last-will arrives, then the new one comes online) is a change of phone, not a clone.
/// Times are this phone's receive times, so clock skew can't hide or fake an overlap.
public struct CloneDetector: Sendable {
    public static let window: Int64 = 10 * 60_000
    public static let cooldown: Int64 = 6 * 60 * 60_000

    private struct Sighting: Sendable { let deviceId: String; let at: Int64; let online: Bool }
    private var lastSighting: [String: Sighting] = [:]
    private var lastAlert: [String: Int64] = [:]

    public init() {}

    /// Records a verified sighting; true when it should be announced now.
    public mutating func observe(memberId: String, deviceId: String, isOnline: Bool, now: Int64) -> Bool {
        let previous = lastSighting[memberId]
        guard isOnline else {
            // Only the device we're tracking can sign itself off; another device's offline says
            // nothing about whether two were ever running together.
            if previous == nil || previous?.deviceId == deviceId {
                lastSighting[memberId] = Sighting(deviceId: deviceId, at: now, online: false)
            }
            return false
        }
        lastSighting[memberId] = Sighting(deviceId: deviceId, at: now, online: true)
        guard let previous, previous.deviceId != deviceId else { return false }
        guard previous.online else { return false }                // the old one signed off: a switch
        guard now - previous.at <= Self.window else { return false } // not heard from lately: a switch
        if let alerted = lastAlert[memberId], now - alerted < Self.cooldown { return false }
        lastAlert[memberId] = now
        return true
    }

    /// Our own identity seen online from an install that isn't this one.
    public static func ownPresenceIsAnotherDevice(isOnline: Bool, deviceId: String, myDeviceId: String) -> Bool {
        isOnline && deviceId != myDeviceId
    }
}

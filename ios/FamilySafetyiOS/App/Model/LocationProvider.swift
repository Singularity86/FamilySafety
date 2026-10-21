import CoreLocation
import FamilySafetyCore
import Foundation

/// This phone's position (B2). While the app is open: continuous updates. While it's in the
/// background or closed, with Always permission: significant-location-change plus a 150 m circle
/// around the last fix, which wake (or relaunch) the app for one fix (spec §11).
@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    /// A fix while the app is open.
    var onFix: ((LocationUpdate) -> Void)?
    /// A fix delivered while the app is in the background: the caller has ~10 s to send it.
    var onBackgroundFix: ((LocationUpdate) -> Void)?
    var onAuthorization: ((CLAuthorizationStatus) -> Void)?
    private(set) var running = false
    private var inBackground = false
    private var memberId = ""
    static let wakeRadius: CLLocationDistance = 150
    private static let regionId = "familysafety.last-fix"

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        // Finer than the 30 m publish rule, so the rule (not the OS) decides what's news.
        manager.distanceFilter = 10
        manager.activityType = .other
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }

    /// The app is open: continuous updates, background wake-ups off.
    func start(memberId: String) {
        self.memberId = memberId
        inBackground = false
        stopWakeUps()
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            running = true
            manager.startUpdatingLocation()
        default: break
        }
    }

    /// Sharing is paused, or there's no family: everything off, including background wake-ups.
    func stop() {
        running = false
        manager.stopUpdatingLocation()
        stopWakeUps()
    }

    /// Asks for Always (only from the user's own tap in Settings).
    func requestAlways() { manager.requestAlwaysAuthorization() }

    /// The app went to the background (or was launched into it): with Always permission, arm the
    /// wake-ups around `lastFix`; without it, sharing simply stops until the app opens again.
    func enterBackground(memberId: String, lastFix: LocationUpdate?) {
        self.memberId = memberId
        inBackground = true
        running = false
        manager.stopUpdatingLocation()
        guard manager.authorizationStatus == .authorizedAlways else { return }
        manager.allowsBackgroundLocationUpdates = true
        manager.startMonitoringSignificantLocationChanges()
        if let lastFix { armRegion(CLLocationCoordinate2D(latitude: lastFix.latitude, longitude: lastFix.longitude)) }
    }

    private func armRegion(_ center: CLLocationCoordinate2D) {
        for region in manager.monitoredRegions where region.identifier == Self.regionId { manager.stopMonitoring(for: region) }
        let region = CLCircularRegion(center: center, radius: Self.wakeRadius, identifier: Self.regionId)
        region.notifyOnExit = true
        region.notifyOnEntry = false
        manager.startMonitoring(for: region)
    }

    private func stopWakeUps() {
        manager.stopMonitoringSignificantLocationChanges()
        for region in manager.monitoredRegions where region.identifier == Self.regionId { manager.stopMonitoring(for: region) }
        manager.allowsBackgroundLocationUpdates = false
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            onAuthorization?(status)
            if !inBackground, status == .authorizedWhenInUse || status == .authorizedAlways, !memberId.isEmpty, !running {
                running = true
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didExitRegion region: CLRegion) {
        // Left the circle around the last fix: one fresh fix, which re-arms the circle.
        Task { @MainActor in if inBackground { self.manager.requestLocation() } }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        Task { @MainActor in
            guard !memberId.isEmpty else { return }
            let fix = LocationUpdate(memberId: memberId, latitude: l.coordinate.latitude, longitude: l.coordinate.longitude,
                                     accuracy: Float(l.horizontalAccuracy), timestamp: Int64(l.timestamp.timeIntervalSince1970 * 1000),
                                     speed: l.speed >= 0 ? Float(l.speed) : nil, bearing: l.course >= 0 ? Float(l.course) : nil)
            if inBackground {
                armRegion(l.coordinate)
                onBackgroundFix?(fix)
            } else if running {
                onFix?(fix)
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

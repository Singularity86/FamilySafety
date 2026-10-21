import CoreLocation
import FamilySafetyCore
import Foundation

/// This phone's position while the app is open (B2, first part). Background updates
/// (Always + significant-change relaunch, spec §11) come in a later part.
@MainActor
final class LocationProvider: NSObject, CLLocationManagerDelegate {
    private let manager = CLLocationManager()
    var onFix: ((LocationUpdate) -> Void)?
    var onAuthorization: ((CLAuthorizationStatus) -> Void)?
    private(set) var running = false

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyNearestTenMeters
        // Finer than the 30 m publish rule, so the rule (not the OS) decides what's news.
        manager.distanceFilter = 10
        manager.activityType = .other
    }

    var authorization: CLAuthorizationStatus { manager.authorizationStatus }

    func start(memberId: String) {
        self.memberId = memberId
        switch manager.authorizationStatus {
        case .notDetermined: manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            running = true
            manager.startUpdatingLocation()
        default: break
        }
    }

    func stop() {
        running = false
        manager.stopUpdatingLocation()
    }

    private var memberId = ""

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        let status = manager.authorizationStatus
        Task { @MainActor in
            onAuthorization?(status)
            if (status == .authorizedWhenInUse || status == .authorizedAlways), !memberId.isEmpty, !running {
                running = true
                self.manager.startUpdatingLocation()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let l = locations.last, l.horizontalAccuracy >= 0 else { return }
        Task { @MainActor in
            guard running, !memberId.isEmpty else { return }
            onFix?(LocationUpdate(memberId: memberId, latitude: l.coordinate.latitude, longitude: l.coordinate.longitude,
                                  accuracy: Float(l.horizontalAccuracy), timestamp: Int64(l.timestamp.timeIntervalSince1970 * 1000),
                                  speed: l.speed >= 0 ? Float(l.speed) : nil, bearing: l.course >= 0 ? Float(l.course) : nil))
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {}
}

import FamilySafetyCore
import UserNotifications

/// Local notifications for place and speed alerts (B6). Nothing is sent anywhere: the alert is
/// decided on this phone from the positions it already receives.
enum Notifier {
    private final class Presenter: NSObject, UNUserNotificationCenterDelegate {
        // Shown even while the app is open, since that's when most positions arrive on iOS.
        func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async
            -> UNNotificationPresentationOptions { [.banner, .sound, .list] }
    }

    private static let presenter = Presenter()

    /// Call once at launch.
    static func install() { UNUserNotificationCenter.current().delegate = presenter }

    /// Asks for permission the first time someone sets up an alert, not at launch.
    static func askOnce() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound]) { _, _ in }
    }

    static func post(_ event: PlaceEvent, memberName: String) {
        let content = UNMutableNotificationContent()
        switch event {
        case .entered(_, let place): content.title = "\(memberName) arrived at \(place.name)"
        case .left(_, let place): content.title = "\(memberName) left \(place.name)"
        case .speeding(_, let mph, let threshold):
            content.title = "\(memberName) is going \(mph) mph"
            content.body = "Over your \(threshold) mph alert."
        }
        content.sound = .default
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil))
    }
}

import AVFoundation
import CoreImage.CIFilterBuiltins
import FamilySafetyCore
import LocalAuthentication
import SwiftUI
import UIKit

/// The shared broker credential (review F2, accepted), baked in at build time from the Mac
/// Keychain by scripts/make-secrets.sh, as hex so no character can break the xcconfig.
enum BrokerSecrets {
    static func load() -> BrokerCredentials? {
        guard let user = decode("FSBrokerUsernameHex"), let pass = decode("FSBrokerPasswordHex"),
              !user.isEmpty, !pass.isEmpty else { return nil }
        return BrokerCredentials(username: user, password: pass)
    }

    private static func decode(_ key: String) -> String? {
        guard let hex = Bundle.main.object(forInfoDictionaryKey: key) as? String, !hex.hasPrefix("$("),
              let bytes = Hex.decode(hex) else { return nil }
        return String(bytes: bytes, encoding: .utf8)
    }
}

enum QRCodeImage {
    static func make(_ text: String) -> UIImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage?.transformed(by: CGAffineTransform(scaleX: 10, y: 10)),
              let cg = CIContext().createCGImage(output, from: output.extent) else { return nil }
        return UIImage(cgImage: cg)
    }
}

/// Scans one QR code and reports its text. Shows nothing but the camera feed.
struct QRScanner: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerController {
        let controller = ScannerController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: ScannerController, context: Context) {}

    final class ScannerController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
        var onCode: ((String) -> Void)?
        private let session = AVCaptureSession()
        private var reported = false

        override func viewDidLoad() {
            super.viewDidLoad()
            view.backgroundColor = .black
            guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let output = AVCaptureMetadataOutput()
            guard session.canAddOutput(output) else { return }
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            let preview = AVCaptureVideoPreviewLayer(session: session)
            preview.videoGravity = .resizeAspectFill
            preview.frame = view.bounds
            view.layer.addSublayer(preview)
            DispatchQueue.global(qos: .userInitiated).async { [session] in session.startRunning() }
        }

        override func viewDidLayoutSubviews() {
            super.viewDidLayoutSubviews()
            view.layer.sublayers?.first { $0 is AVCaptureVideoPreviewLayer }?.frame = view.bounds
        }

        override func viewWillDisappear(_ animated: Bool) {
            super.viewWillDisappear(animated)
            session.stopRunning()
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            guard !reported, let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue else { return }
            reported = true
            onCode?(code)
        }
    }
}

/// Hides secret content (the recovery phrase, an invite code) while the screen is being
/// recorded or mirrored, and when the app leaves the foreground so the app-switcher snapshot
/// shows nothing. iOS can't block screenshots, so one taken shows a warning instead
/// (Android 1.4, F15).
struct CaptureShield: ViewModifier {
    @Environment(\.scenePhase) private var scenePhase
    @State private var captured = UIScreen.main.isCaptured
    @State private var screenshotTaken = false

    func body(content: Content) -> some View {
        content
            .blur(radius: hidden ? 24 : 0)
            .overlay {
                if hidden {
                    Text(captured ? "Hidden while the screen is being recorded or shared." : "Hidden while the app is in the background.")
                        .font(Typeface.bodyStrong).multilineTextAlignment(.center).padding(Spacing.l)
                }
            }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in
                captured = UIScreen.main.isCaptured
            }
            .onReceive(NotificationCenter.default.publisher(for: UIApplication.userDidTakeScreenshotNotification)) { _ in
                screenshotTaken = true
            }
            .alert("Screenshot taken", isPresented: $screenshotTaken) {
                Button("OK", role: .cancel) {}
            } message: {
                Text("Anyone who sees that screenshot can take over your place in the family. Delete it from Photos.")
            }
    }

    private var hidden: Bool { captured || scenePhase != .active }
}

extension View {
    func captureShield() -> some View { modifier(CaptureShield()) }
}

/// Asks the phone's owner to confirm it's them, every time, with Face ID, Touch ID or the
/// passcode (Android 1.4 rule 1–2).
enum DeviceOwner {
    enum Result: Equatable { case confirmed, cancelled, noPasscode, failed(String) }

    static func confirm(reason: String) async -> Result {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthentication, error: &error) else {
            return error?.code == LAError.passcodeNotSet.rawValue ? .noPasscode : .failed(error?.localizedDescription ?? "Not available")
        }
        do {
            return try await context.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason) ? .confirmed : .failed("Not confirmed")
        } catch let e as LAError where e.code == .userCancel || e.code == .appCancel || e.code == .systemCancel {
            return .cancelled
        } catch {
            return .failed(error.localizedDescription)
        }
    }
}

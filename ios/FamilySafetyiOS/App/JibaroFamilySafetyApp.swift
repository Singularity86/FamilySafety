import FamilySafetyCore
import SwiftUI

@main
struct JibaroFamilySafetyApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    init() {
        Typeface.registerBundledFonts()
        Notifier.install()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .onAppear { model.load() }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active { model.setActive(true) }
                    if phase == .background { model.setActive(false) }
                }
                .alert("Something needs attention", isPresented: Binding(get: { model.lastError != nil }, set: { if !$0 { model.lastError = nil } })) {
                    Button("OK", role: .cancel) {}
                } message: { Text(model.lastError ?? "") }
                .alert("Signed in on another phone", isPresented: Binding(get: { model.identityNotice != nil }, set: { if !$0 { model.identityNotice = nil } })) {
                    Button("OK", role: .cancel) {}
                } message: { Text(model.identityNotice ?? "") }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        Group {
            switch model.phase {
            case .loading:
                Screen { ProgressView() }
            case .welcome:
                OnboardingFlow()
            case .storageUnavailable:
                Screen {
                    VStack(alignment: .leading, spacing: Spacing.m) {
                        Text("Storage temporarily unavailable").font(Typeface.headline)
                        Text("This phone's saved family data can't be read right now. Nothing has been deleted. Unlock your phone and try again.")
                            .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                        Button("Try again") { model.load() }.buttonStyle(PrimaryButtonStyle())
                    }
                }
            case .missingBrokerConfig:
                Screen {
                    Text("This build has no relay settings. Run scripts/make-secrets.sh on the Mac and build again.")
                        .font(Typeface.body)
                }
            case .running:
                FamilyRoot()
            }
        }
        .preferredColorScheme(nil)
    }
}

struct FamilyRoot: View {
    @EnvironmentObject private var model: AppModel
    @State private var tab = FamilyRoot.startTab

    /// Debug builds only: FS_TEST_TAB=0…4 opens on that tab (for screenshots without taps).
    private static var startTab: Int {
        #if DEBUG
        return ProcessInfo.processInfo.environment["FS_TEST_TAB"].flatMap(Int.init) ?? 0
        #else
        return 0
        #endif
    }

    var body: some View {
        switch model.nodeState {
        case .noFamily:
            NoFamilyView()
        case .joining(let familyName, let inviterName, let fingerprint):
            PendingJoinView(familyName: familyName, inviterName: inviterName, fingerprint: fingerprint)
        case .member(let group):
            TabView(selection: $tab) {
                FamilyMapView(group: group).tabItem { Label("Map", systemImage: "map") }.tag(0)
                FamilyView(group: group).tabItem { Label("Family", systemImage: "person.3") }.tag(1)
                FilesView(group: group).tabItem { Label("Files", systemImage: "folder") }.tag(2)
                ChatListView(group: group)
                    .tabItem { Label("Chat", systemImage: "bubble.left.and.bubble.right") }
                    .badge(model.chat.unreadCount())
                    .tag(3)
                SettingsView().tabItem { Label("Settings", systemImage: "gearshape") }.tag(4)
            }
            .tint(Palette.amber)
        case .rejected:
            EndedView(title: "Not approved", detail: "Your request to join was turned down. If that was a mistake, ask for a new invite.")
        case .removed:
            EndedView(title: "You're no longer in this family", detail: "Someone removed this phone from the family. Ask for a new invite to rejoin.")
        case .storageUnavailable:
            Screen { Text("Storage temporarily unavailable. Nothing has been deleted.").font(Typeface.body) }
        }
    }
}

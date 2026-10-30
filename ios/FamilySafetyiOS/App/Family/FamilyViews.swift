import FamilySafetyCore
import SwiftUI

/// The family: people in rows, join requests waiting on top.
struct FamilyView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    @State private var inviting = false
    @State private var removing: FamilyMember?

    private var isCreator: Bool { group.creatorMemberId == model.memberId }

    var body: some View {
        NavigationStack {
            Screen {
                List {
                    if !model.prompts.isEmpty {
                        Section {
                            ForEach(model.prompts) { JoinPromptRow(prompt: $0) }
                        } header: { Text("Asking to join").font(Typeface.label) }
                    }
                    Section {
                        ForEach(sortedMembers, id: \.memberId) { member in
                            MemberRow(member: member, isMe: member.memberId == model.memberId,
                                      status: model.presence[member.memberId],
                                      lastFix: model.locations[member.memberId],
                                      needsUpdate: model.needsUpdate.contains(member.memberId))
                                .swipeActions {
                                    if isCreator && member.memberId != model.memberId {
                                        Button("Remove", role: .destructive) { removing = member }
                                    }
                                }
                        }
                    } header: { Text("\(group.members.count) in \(group.groupName)").font(Typeface.label) }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, -Spacing.m)
            }
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: Spacing.s) {
                    ConnectionLine()
                    Button("Invite someone") { inviting = true }.buttonStyle(PrimaryButtonStyle())
                }
                .padding(Spacing.m).background(Palette.background)
            }
            .navigationTitle(group.groupName)
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $inviting) { InviteView() }
            .confirmationDialog("Remove \(removing?.displayName ?? "") from the family?",
                                isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                                titleVisibility: .visible) {
                Button("Remove", role: .destructive) {
                    if let id = removing?.memberId { Task { await model.removeMember(id) } }
                }
            } message: {
                Text("Their phone stops receiving the family's locations, messages and files. They can only come back with a new invite.")
            }
        }
    }

    private var sortedMembers: [FamilyMember] {
        group.members.sorted { a, b in
            if a.memberId == model.memberId { return true }
            if b.memberId == model.memberId { return false }
            return a.displayName.localizedCaseInsensitiveCompare(b.displayName) == .orderedAscending
        }
    }
}

struct MemberRow: View {
    let member: FamilyMember
    let isMe: Bool
    let status: PresenceStatus?
    var lastFix: LocationUpdate? = nil
    var needsUpdate = false

    var body: some View {
        HStack(spacing: Spacing.m) {
            PersonAvatar(name: member.displayName, memberId: member.memberId, colorHue: member.colorHue)
            VStack(alignment: .leading, spacing: 2) {
                Text(isMe ? "\(member.displayName) (you)" : member.displayName)
                    .font(Typeface.title)
                    .foregroundStyle(Palette.personText(PersonPalette.forMember(memberId: member.memberId, colorHue: member.colorHue)))
                Group {
                    if let seen = lastSeen { RelativeTime(date: seen, prefix: "Last seen ") } else { detailText }
                }
                .font(Typeface.small).foregroundStyle(Palette.secondaryText)
            }
            Spacer()
        }
        .padding(.vertical, Spacing.xs)
        .listRowBackground(Palette.background)
        .listRowSeparatorTint(Palette.hairline)
    }

    /// The status line; "Last seen …" is drawn by `RelativeTime` so it keeps up (it used to freeze).
    private var detailText: Text {
        if isMe { return Text("This phone") }
        if needsUpdate { return Text("Needs to update the app to stay in sync") }
        guard let status else { return Text("Not heard from yet") }
        return Text("Online")
    }

    /// When an offline member was last seen; nil when the plain text above applies. A last-will
    /// is signed at connect, so after a sudden death its timestamp is when the session began; a
    /// newer location fix is the better answer.
    private var lastSeen: Date? {
        guard !isMe, !needsUpdate, let status, !status.isOnline else { return nil }
        let millis = max(status.timestamp, lastFix?.timestamp ?? 0)
        return Date(timeIntervalSince1970: Double(millis) / 1000)
    }
}

/// A verified request: name, the six digits to compare, approve or turn down.
struct JoinPromptRow: View {
    @EnvironmentObject private var model: AppModel
    let prompt: JoinPrompt
    @State private var busy = false

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text("\(prompt.displayName) wants to join").font(Typeface.title)
            Text("Only approve if their screen shows the same code:").font(Typeface.small).foregroundStyle(Palette.secondaryText)
            Text(JoinV2.displayFingerprint(prompt.fingerprint)).font(Typeface.codeLarge)
            HStack(spacing: Spacing.s) {
                Button("Turn down") { act { await model.reject(prompt) } }.buttonStyle(SecondaryButtonStyle())
                Button("Approve") { act { await model.approve(prompt) } }.buttonStyle(PrimaryButtonStyle())
            }
            .disabled(busy)
        }
        .padding(.vertical, Spacing.s)
        .listRowBackground(Palette.background)
    }

    private func act(_ work: @escaping () async -> Void) {
        busy = true
        Task { await work(); busy = false }
    }
}

/// A fresh one-time invite: the QR, and the same code as text to send privately.
struct InviteView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var code: String?
    @State private var failed = false

    var body: some View {
        Screen {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Text("Invite someone").font(Typeface.headline).padding(.top, Spacing.l)
                    Text("Share this privately with one person. Anyone with this code can ask to join for the next 24 hours. To invite someone else, open this screen again.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                    if let code, let image = QRCodeImage.make(code) {
                        Image(uiImage: image).interpolation(.none).resizable().scaledToFit()
                            .padding(Spacing.m).background(Color.white)
                            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
                            .accessibilityLabel("Invite QR code")
                            .captureShield()
                        ShareLink(item: code) { Text("Send the code instead") }.buttonStyle(SecondaryButtonStyle())
                        Button("Copy code") { UIPasteboard.general.string = code }.buttonStyle(SecondaryButtonStyle())
                    } else if failed {
                        Text("The invite couldn't be made. Check that you're still in the family and try again.")
                            .font(Typeface.body).foregroundStyle(Palette.dangerText)
                        Button("Try again") { Task { await make() } }.buttonStyle(PrimaryButtonStyle())
                    } else {
                        ProgressView().frame(maxWidth: .infinity)
                    }
                    Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle())
                }
                .padding(.bottom, Spacing.l)
            }
        }
        .task { await make() }
    }

    private func make() async {
        failed = false
        code = await model.issueInvite()
        failed = code == nil
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var revealing = false
    @State private var notice: String?
    @State private var name = ""
    @State private var leaving = false
    @State private var testingAlert = false

    var body: some View {
        NavigationStack {
            Screen {
                List {
                    Section {
                        Button("Show recovery phrase") { reveal() }.font(Typeface.body).foregroundStyle(Palette.text)
                    } footer: {
                        Text("You'll confirm it's you first. Anyone who sees these words can take your place in the family.")
                            .font(Typeface.small)
                    }
                    .listRowBackground(Palette.surface)
                    Section("You") {
                        HStack {
                            TextField("Your name", text: $name).font(Typeface.body).submitLabel(.done)
                                .onSubmit { Task { await model.rename(name) } }
                            if name != (model.profile?.displayName ?? "") {
                                Button("Save") { Task { await model.rename(name) } }.font(Typeface.bodyStrong).foregroundStyle(Palette.amber)
                            }
                        }
                        ColourChooser()
                        LabeledContent("Member ID") { Text(model.memberId ?? "").font(Typeface.small).textSelection(.enabled) }
                    }
                    .font(Typeface.body).listRowBackground(Palette.surface)
                    Section {
                        Toggle("Share my location", isOn: Binding(get: { !model.sharingPaused },
                                                                  set: { on in Task { await model.setSharingPaused(!on) } }))
                            .tint(Palette.amber)
                        if !model.sharingPaused {
                            LabeledContent("While the app is closed") {
                                if model.locationAuthorization == .authorizedAlways {
                                    Text("On").font(Typeface.small)
                                } else {
                                    Button("Allow") { model.allowBackgroundLocation() }
                                        .font(Typeface.bodyStrong).foregroundStyle(Palette.amber)
                                }
                            }
                        }
                        if model.locationAuthorization == .denied || model.locationAuthorization == .restricted {
                            Text("Location is off for this app in iPhone Settings, so your family can't see where you are.")
                                .font(Typeface.small).foregroundStyle(Palette.warningText)
                        }
                    } footer: {
                        Text(model.sharingPaused
                             ? "Paused: this phone sends no location, and won't start again by itself. You still see your family and can chat."
                             : model.locationAuthorization == .authorizedAlways
                                ? "Your family sees where you are, also while the app is closed: this phone sends a new position when you move about 150 m or more. It's encrypted on this phone; no server can read it."
                                : "While the app is open, your family sees where you are. Allow it while closed so they still see you after you put the phone away. It's encrypted on this phone; no server can read it.")
                            .font(Typeface.small)
                    }
                    .font(Typeface.body).listRowBackground(Palette.surface)
                    if let alert = model.emergency.current(now: Int64(Date().timeIntervalSince1970 * 1000)) {
                        Section {
                            NavigationLink {
                                CrashAlertStatusView(alertId: alert.id)
                            } label: {
                                LabeledContent("Crash alert") {
                                    Text("Sent \(Date(timeIntervalSince1970: Double(alert.raisedAtMs) / 1000).formatted(date: .omitted, time: .shortened))")
                                        .font(Typeface.small)
                                }
                            }
                        } footer: {
                            Text("It keeps being sent until each phone confirms it, for up to a day.").font(Typeface.small)
                        }
                        .font(Typeface.body).listRowBackground(Palette.surface)
                    }
                    if case .member(let group) = model.nodeState {
                        Section {
                            NavigationLink { PlacesView(group: group) } label: {
                                LabeledContent("Places & alerts") {
                                    Text(model.places.places.isEmpty ? "None" : "\(model.places.places.count)").font(Typeface.small)
                                }
                            }
                        }
                        .font(Typeface.body).listRowBackground(Palette.surface)
                    }
                    #if DEBUG
                    Section {
                        Button("Send a test crash alert") { testingAlert = true }.font(Typeface.body)
                    } header: { Text("Crash alert (debug)").font(Typeface.label) } footer: {
                        Text("Debug builds only. Sends a real crash alert to every family member, as crash detection would.")
                            .font(Typeface.small)
                    }
                    .listRowBackground(Palette.surface)
                    #endif
                    Section {
                        NavigationLink {
                            SecurityLogView()
                        } label: {
                            LabeledContent("Security log") {
                                Text(model.securityLog.events.isEmpty ? "Nothing unusual" : "\(model.securityLog.events.count)")
                                    .font(Typeface.small)
                            }
                        }
                    } footer: {
                        Text("Things this phone refused that might be someone outside the family trying to get in. You can send it as a report.")
                            .font(Typeface.small)
                    }
                    .font(Typeface.body).listRowBackground(Palette.surface)
                    Section {
                        Button("Leave family", role: .destructive) { leaving = true }.font(Typeface.body)
                    }
                    .listRowBackground(Palette.surface)
                    Section {
                        Link("Privacy policy", destination: AppLinks.privacyPolicy)
                        LabeledContent("Version") { Text(Bundle.main.versionLine).font(Typeface.small) }
                    } header: { Text("About").font(Typeface.label) }
                    .font(Typeface.body).listRowBackground(Palette.surface)
                }
                .scrollContentBackground(.hidden)
                .padding(.horizontal, -Spacing.m)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $revealing) { RevealPhraseView(words: model.recoveryPhrase()) }
            .onAppear { name = model.profile?.displayName ?? "" }
            .confirmationDialog("Send a crash alert to the whole family?", isPresented: $testingAlert, titleVisibility: .visible) {
                Button("Send alert", role: .destructive) { Task { await model.raiseCrashAlert() } }
            } message: {
                Text("Everyone's phone will show that you may have been in an accident.")
            }
            .confirmationDialog("Leave the family?", isPresented: $leaving, titleVisibility: .visible) {
                Button("Leave", role: .destructive) { Task { await model.leaveFamily() } }
            } message: {
                Text("This phone stops sharing and forgets the family. To come back you'll need a new invite. Your recovery phrase stays the same.")
            }
            .alert("Recovery phrase", isPresented: Binding(get: { notice != nil }, set: { if !$0 { notice = nil } })) {
                Button("OK", role: .cancel) {}
            } message: { Text(notice ?? "") }
        }
    }

    private func reveal() {
        Task {
            switch await DeviceOwner.confirm(reason: "Confirm it's you to see your recovery phrase") {
            case .confirmed: revealing = true
            case .cancelled: break
            case .noPasscode: notice = "Set a passcode in iPhone Settings first. The recovery phrase is only shown after the phone confirms it's you."
            case .failed(let why): notice = why
            }
        }
    }
}

struct RevealPhraseView: View {
    @Environment(\.dismiss) private var dismiss
    @State var words: [String]

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Your recovery phrase").font(Typeface.headline).padding(.top, Spacing.l)
                PhraseGrid(words: words).captureShield()
                Spacer()
                Button("Done") { dismiss() }.buttonStyle(PrimaryButtonStyle())
            }
            .padding(.bottom, Spacing.l)
        }
        // Rule 5: the words don't outlive the screen.
        .onDisappear { words = [] }
    }
}

/// The twelve person colours; picking one is signed and sent to the family.
struct ColourChooser: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let me = currentMember
        let mine = PersonPalette.forMember(memberId: model.memberId ?? "", colorHue: me?.colorHue)
        VStack(alignment: .leading, spacing: Spacing.s) {
            Text("Your colour").font(Typeface.label).foregroundStyle(Palette.secondaryText)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible()), count: 6), spacing: Spacing.s) {
                ForEach(PersonPalette.colors, id: \.name) { c in
                    Button { Task { await model.setColor(c) } } label: {
                        Circle().fill(Color(hex: c.fill)).frame(width: 36, height: 36)
                            .overlay(Circle().stroke(Palette.text, lineWidth: c == mine ? 3 : 0).padding(-4))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(c.name + (c == mine ? ", chosen" : ""))
                }
            }
        }
        .padding(.vertical, Spacing.xs)
    }

    private var currentMember: FamilyMember? {
        if case .member(let g) = model.nodeState { return g.findMember(byId: model.memberId ?? "") }
        return nil
    }
}

/// The phone's security log, newest first, and a button that hands a plain-text report to the
/// share sheet: Mail, Messages, Files or a monitoring app. Nothing is sent without the user.
struct SecurityLogView: View {
    @EnvironmentObject private var model: AppModel
    @State private var report: String?

    var body: some View {
        Screen {
            List {
                if model.securityLog.events.isEmpty {
                    Text("Nothing unusual recorded in the last 30 days.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                        .listRowBackground(Palette.surface)
                } else {
                    ForEach(model.securityLog.events.reversed()) { event in
                        SecurityEventRow(event: event, name: name(event.memberId))
                    }
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .padding(.horizontal, -Spacing.m)
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: Spacing.s) {
                if let report {
                    ShareLink(item: report, subject: Text("FamilySafety security report"),
                              message: Text("Security report from FamilySafety")) {
                        Text("Send report")
                    }
                    .buttonStyle(PrimaryButtonStyle())
                }
                Text("The report lists what was refused, when and from whom. No messages, locations or keys.")
                    .font(Typeface.small).foregroundStyle(Palette.secondaryText).multilineTextAlignment(.center)
            }
            .padding(Spacing.m).background(Palette.background)
        }
        .navigationTitle("Security log")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: model.securityLog) { report = await model.securityReport() }
    }

    private func name(_ memberId: String?) -> String {
        guard let memberId else { return "Unknown sender" }
        if case .member(let group) = model.nodeState, let m = group.findMember(byId: memberId) { return m.displayName }
        return "Not in the family (\(memberId.prefix(8)))"
    }
}

struct SecurityEventRow: View {
    let event: SecurityEvent
    let name: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(event.kind.title).font(Typeface.bodyStrong).foregroundStyle(colour)
            Text("\(name) · \(when)\(event.count > 1 ? " · \(event.count) times" : "")")
                .font(Typeface.small).foregroundStyle(Palette.secondaryText)
            if let detail = event.detail {
                Text(detail).font(Typeface.small).foregroundStyle(Palette.secondaryText)
            }
        }
        .padding(.vertical, Spacing.xs)
        .listRowBackground(Palette.background)
        .listRowSeparatorTint(Palette.hairline)
    }

    private var colour: Color {
        switch event.kind.severity {
        case .high: return Palette.dangerText
        case .medium: return Palette.warningText
        case .low: return Palette.text
        }
    }

    private var when: String {
        Date(timeIntervalSince1970: Double(event.lastAtMs) / 1000).formatted(.relative(presentation: .named))
    }
}

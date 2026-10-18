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
    var needsUpdate = false

    var body: some View {
        HStack(spacing: Spacing.m) {
            PersonAvatar(name: member.displayName, memberId: member.memberId, colorHue: member.colorHue)
            VStack(alignment: .leading, spacing: 2) {
                Text(isMe ? "\(member.displayName) (you)" : member.displayName)
                    .font(Typeface.title)
                    .foregroundStyle(Palette.personText(PersonPalette.forMember(memberId: member.memberId, colorHue: member.colorHue)))
                Text(detail).font(Typeface.small).foregroundStyle(Palette.secondaryText)
            }
            Spacer()
        }
        .padding(.vertical, Spacing.xs)
        .listRowBackground(Palette.background)
        .listRowSeparatorTint(Palette.hairline)
    }

    private var detail: String {
        if isMe { return "This phone" }
        if needsUpdate { return "Needs to update the app to stay in sync" }
        guard let status else { return "Not heard from yet" }
        if status.isOnline { return "Online" }
        let date = Date(timeIntervalSince1970: Double(status.timestamp) / 1000)
        return "Last seen " + date.formatted(.relative(presentation: .named))
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
                        Button("Leave family", role: .destructive) { leaving = true }.font(Typeface.body)
                    }
                    .listRowBackground(Palette.surface)
                }
                .scrollContentBackground(.hidden)
                .padding(.horizontal, -Spacing.m)
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .sheet(isPresented: $revealing) { RevealPhraseView(words: model.recoveryPhrase()) }
            .onAppear { name = model.profile?.displayName ?? "" }
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

import FamilySafetyCore
import SwiftUI

/// Signed in, but not in a family yet.
struct NoFamilyView: View {
    @State private var creating = false
    @State private var joining = false

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Spacer()
                Text("Join your family").font(Typeface.display)
                Text("Ask someone already in your family to open Invite on their phone, then scan their code. Or start a new family and invite them.")
                    .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                Spacer()
                Button("Scan an invite") { joining = true }.buttonStyle(PrimaryButtonStyle())
                Button("Start a new family") { creating = true }.buttonStyle(SecondaryButtonStyle())
            }
            .padding(.bottom, Spacing.l)
        }
        .sheet(isPresented: $creating) { CreateFamilyView() }
        .sheet(isPresented: $joining) { JoinView() }
    }
}

struct CreateFamilyView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""
    @State private var busy = false

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Name your family").font(Typeface.headline).padding(.top, Spacing.l)
                Text("Everyone in it sees this name.").font(Typeface.body).foregroundStyle(Palette.secondaryText)
                TextField("For example, The Riveras", text: $name).fieldStyle()
                Spacer()
                Button(busy ? "Creating…" : "Create family") {
                    busy = true
                    Task { await model.createFamily(name: name.trimmingCharacters(in: .whitespaces)); busy = false; dismiss() }
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(busy || name.trimmingCharacters(in: .whitespaces).isEmpty)
            }
            .padding(.bottom, Spacing.l)
        }
    }
}

/// Scan an invite QR, or paste the code if it came by message.
struct JoinView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State private var pasted = ""
    @State private var problem: String?

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Scan an invite").font(Typeface.headline).padding(.top, Spacing.l)
                QRScanner { code in submit(code) }
                    .frame(height: 280)
                    .clipShape(RoundedRectangle(cornerRadius: Radius.control))
                Text("Or paste a code someone sent you").font(Typeface.label).foregroundStyle(Palette.secondaryText)
                TextField("Invite code", text: $pasted, axis: .vertical).fieldStyle()
                    .textInputAutocapitalization(.never).autocorrectionDisabled().lineLimit(1...4)
                if let problem { Text(problem).font(Typeface.label).foregroundStyle(Palette.dangerText) }
                Spacer()
                Button("Join with this code") { submit(pasted) }.buttonStyle(PrimaryButtonStyle())
                    .disabled(pasted.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
            .padding(.bottom, Spacing.l)
        }
    }

    private func submit(_ code: String) {
        Task {
            model.lastError = nil
            if await model.join(code: code) { dismiss() } else { problem = model.lastError; model.lastError = nil }
        }
    }
}

/// Waiting for the inviter. Both phones show the same six digits.
struct PendingJoinView: View {
    @EnvironmentObject private var model: AppModel
    let familyName: String
    let inviterName: String
    let fingerprint: String

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Spacer()
                Text("Waiting for \(inviterName)").font(Typeface.headline)
                Text("\(inviterName) needs to approve you on their phone to let you into \(familyName). Check that their screen shows this code:")
                    .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                Text(JoinV2.displayFingerprint(fingerprint))
                    .font(Typeface.codeLarge).foregroundStyle(Palette.amber)
                    .frame(maxWidth: .infinity).padding(.vertical, Spacing.l)
                    .background(Palette.surface).clipShape(RoundedRectangle(cornerRadius: Radius.control))
                    .accessibilityLabel("Your code: \(fingerprint.map(String.init).joined(separator: " "))")
                ConnectionLine()
                Spacer()
                Button("Cancel") { Task { await model.cancelJoin() } }.buttonStyle(SecondaryButtonStyle())
            }
            .padding(.bottom, Spacing.l)
        }
    }
}

/// A plain notice with one way forward: turned down, or removed.
struct EndedView: View {
    @EnvironmentObject private var model: AppModel
    let title: String
    let detail: String

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Spacer()
                Text(title).font(Typeface.headline)
                Text(detail).font(Typeface.body).foregroundStyle(Palette.secondaryText)
                Spacer()
                Button("Scan another invite") { Task { await model.cancelJoin() } }.buttonStyle(PrimaryButtonStyle())
            }
            .padding(.bottom, Spacing.l)
        }
    }
}

/// One quiet line about the relay connection.
struct ConnectionLine: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        HStack(spacing: Spacing.s) {
            Circle().fill(colour).frame(width: 8, height: 8)
            Text(words).font(Typeface.small).foregroundStyle(Palette.secondaryText)
        }
        .accessibilityElement(children: .combine)
    }
    private var colour: Color {
        switch model.connection {
        case .connected: return Palette.goodIndicator
        case .connecting: return Palette.warningText
        case .disconnected: return Palette.dangerIndicator
        }
    }
    private var words: String {
        switch model.connection {
        case .connected: return "Connected"
        case .connecting: return "Connecting…"
        case .disconnected: return "Offline. Retrying."
        }
    }
}

import FamilySafetyCore
import QuickLook
import SwiftUI

/// The family's shared files (B5). Essential files download on their own; the rest when tapped.
struct FilesView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    @State private var preview: URL?
    @State private var picking = false
    @State private var deleting: FileLibrary.Entry?
    @State private var query = ""
    /// Set when a submitted search opens a vault; the key never leaves VaultScreen.
    @State private var vaultCode: String?

    var body: some View {
        NavigationStack {
            Screen {
                if model.files.visible.isEmpty && query.isEmpty {
                    Text("Files shared in \(group.groupName) appear here. They're encrypted so only your family can open them.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                        .multilineTextAlignment(.center).frame(maxHeight: .infinity)
                } else {
                    List(filtered) { entry in
                        Button { open(entry) } label: { FileRow(entry: entry, group: group) }
                            .listRowBackground(Palette.background)
                            .contextMenu {
                                Button(entry.file.isEssential ? "Only download when opened" : "Keep on every phone") {
                                    Task { await model.setEssential(entry.file.fileId, !entry.file.isEssential) }
                                }
                                Button("Delete for everyone", role: .destructive) { deleting = entry }
                            }
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, -Spacing.m)
                }
            }
            .navigationTitle("Files")
            .navigationBarTitleDisplayMode(.inline)
            // Search, and the way into the vault. Typing filters; submitting 16+ characters opens a vault
            // for that text. There is no other prompt anywhere: a prompt would prove a vault exists. Any
            // such text opens one, so submitting distinguishes nothing (spec §6.8).
            .searchable(text: $query, prompt: "Search files")
            .onSubmit(of: .search) {
                let typed = query.trimmingCharacters(in: .whitespacesAndNewlines)
                if typed.count >= VaultCrypto.minPassphraseLength { query = ""; vaultCode = typed }
            }
            .navigationDestination(isPresented: Binding(get: { vaultCode != nil }, set: { if !$0 { vaultCode = nil } })) {
                if let code = vaultCode { VaultScreen(code: code) }
            }
            .quickLookPreview($preview)
            .safeAreaInset(edge: .bottom) {
                Button("Add a file") { picking = true }.buttonStyle(PrimaryButtonStyle())
                    .padding(Spacing.m).background(Palette.background)
            }
            .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { result in
                if case .success(let url) = result { Task { await model.uploadFile(url) } }
            }
            .confirmationDialog("Delete \"\(deleting?.file.name ?? "")\" for everyone in the family?",
                                isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }),
                                titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    if let id = deleting?.file.fileId { Task { await model.deleteFile(id) } }
                }
            } message: {
                Text("It's removed from every phone in the family, including yours.")
            }
        }
    }

    private var filtered: [FileLibrary.Entry] {
        let q = query.trimmingCharacters(in: .whitespaces)
        return q.isEmpty ? model.files.visible : model.files.visible.filter { $0.file.name.localizedCaseInsensitiveContains(q) }
    }

    private func open(_ entry: FileLibrary.Entry) {
        Task {
            if entry.state == .complete { preview = await model.openFile(entry.file.fileId) }
            else { await model.requestFile(entry.file.fileId) }
        }
    }
}

private struct FileRow: View {
    @EnvironmentObject private var model: AppModel
    let entry: FileLibrary.Entry
    let group: GroupDefinition

    var body: some View {
        HStack(spacing: Spacing.m) {
            Image(systemName: icon).font(.system(size: 22)).frame(width: 40, height: 40)
                .background(RoundedRectangle(cornerRadius: Radius.control).fill(Palette.raised))
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(entry.file.name).font(Typeface.title).lineLimit(1)
                Text(detail).font(Typeface.small).foregroundStyle(Palette.secondaryText).lineLimit(1)
            }
            Spacer()
            if entry.file.isEssential {
                Text("Kept on every phone").font(Typeface.small).foregroundStyle(Palette.secondaryText)
                    .padding(.horizontal, Spacing.s).padding(.vertical, 2)
                    .background(Capsule().stroke(Palette.hairline))
            }
        }
        .foregroundStyle(Palette.text)
        .padding(.vertical, Spacing.xs)
    }

    private var icon: String {
        let m = entry.file.mimeType
        if m.hasPrefix("image/") { return "photo" }
        if m == "application/pdf" { return "doc.richtext" }
        if m.hasPrefix("video/") { return "film" }
        return "doc"
    }

    private var detail: String {
        let size = ByteCountFormatter.string(fromByteCount: entry.file.sizeBytes, countStyle: .file)
        let who = group.findMember(byId: entry.file.uploaderMemberId)?.displayName ?? "A former member"
        switch entry.state {
        case .complete: return "\(size) · from \(who)"
        case .downloading: return "Downloading \(entry.chunksHeld) of \(entry.file.chunkCount) pieces · \(size)"
        case .notDownloaded: return "\(size) · from \(who) · tap to download"
        }
    }
}

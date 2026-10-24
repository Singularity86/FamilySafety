import FamilySafetyCore
import QuickLook
import SwiftUI

/// The family's shared files (B5). Essential files download on their own; the rest when tapped.
struct FilesView: View {
    @EnvironmentObject private var model: AppModel
    let group: GroupDefinition
    @State private var preview: URL?

    var body: some View {
        NavigationStack {
            Screen {
                if model.files.visible.isEmpty {
                    Text("Files shared in \(group.groupName) appear here. They're encrypted so only your family can open them.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                        .multilineTextAlignment(.center).frame(maxHeight: .infinity)
                } else {
                    List(model.files.visible) { entry in
                        Button { open(entry) } label: { FileRow(entry: entry, group: group) }
                            .listRowBackground(Palette.background)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .padding(.horizontal, -Spacing.m)
                }
            }
            .navigationTitle("Files")
            .navigationBarTitleDisplayMode(.inline)
            .quickLookPreview($preview)
        }
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

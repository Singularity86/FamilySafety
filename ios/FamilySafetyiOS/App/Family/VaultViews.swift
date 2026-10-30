import FamilySafetyCore
import PDFKit
import SwiftUI
import UniformTypeIdentifiers

/// A vault, opened by whatever was submitted in the Files search box (B6, spec §6.8). Every code opens
/// one; most are empty, and an empty vault is shown as just that: never as a wrong code.
struct VaultScreen: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    let code: String
    @State private var key: SymmetricKeyBox?
    @State private var view: FamilyNode.VaultView?
    @State private var picking = false
    @State private var confirmingFirst = false
    @State private var removing: VaultItem?
    @State private var showRemoved = false
    @State private var preview: VaultDocument?
    @State private var fetching: String?
    @State private var lastTouch = Date()

    /// The key is dropped after this long untouched (Android: 3 minutes).
    static let idleSeconds: TimeInterval = 180

    var body: some View {
        ZStack {
            Screen {
                List {
                    if let view {
                        if view.items.isEmpty {
                            Text("Nothing is here yet").font(Typeface.body).foregroundStyle(Palette.secondaryText)
                                .listRowBackground(Palette.background)
                        }
                        ForEach(view.items) { item in
                            Button { open(item) } label: { row(item, fetchingNow: fetching == item.id) }
                                .listRowBackground(Palette.background)
                                .swipeActions { Button("Remove", role: .destructive) { removing = item } }
                        }
                        if !view.removed.isEmpty {
                            Section {
                                Button(showRemoved ? "Hide removed (\(view.removed.count))" : "Removed (\(view.removed.count))") {
                                    showRemoved.toggle()
                                }.font(Typeface.body)
                                if showRemoved {
                                    ForEach(view.removed) { item in
                                        HStack {
                                            VStack(alignment: .leading) {
                                                Text(item.payload.name).font(Typeface.title)
                                                Text("Removed — still recoverable").font(Typeface.small).foregroundStyle(Palette.secondaryText)
                                            }
                                            Spacer()
                                            Button("Put back") { Task { touch(); self.view = await model.setVaultItem(key!, item, active: true) } }
                                                .font(Typeface.bodyStrong)
                                        }
                                    }
                                }
                            }
                            .listRowBackground(Palette.background)
                        }
                    }
                    // Shown for every vault, full or empty, with no dismissal: a note that appeared for some
                    // vaults and not others would itself say which ones are real (spec §6.8).
                    Section {} footer: {
                        Text("What you typed is the only thing protecting anything kept here. It is not stored on any phone or any server, so it can never be reset or recovered — and anyone who guesses it sees exactly what you see. Several unrelated words are far harder to guess than one.")
                            .font(Typeface.small).foregroundStyle(Palette.secondaryText)
                    }
                }
                .listStyle(.plain)
                .scrollContentBackground(.hidden)
                .padding(.horizontal, -Spacing.m)
            }
            .safeAreaInset(edge: .bottom) {
                if view != nil {
                    Button("Add it here") { touch(); if view?.items.isEmpty ?? true { confirmingFirst = true } else { picking = true } }
                        .buttonStyle(PrimaryButtonStyle()).padding(Spacing.m).background(Palette.background)
                }
            }
            // Kept out of the app switcher snapshot.
            if scenePhase != .active { Palette.background.ignoresSafeArea() }
        }
        .navigationTitle("Files")
        .navigationBarTitleDisplayMode(.inline)
        // Not Quick Look: its share button would export the document (spec §6.8: no export).
        .sheet(item: $preview) { doc in VaultDocumentView(doc: doc) }
        .fileImporter(isPresented: $picking, allowedContentTypes: [.item]) { result in
            guard case .success(let url) = result, let key else { return }
            Task { if let v = await model.addToVault(key, url: url) { view = v } }
        }
        .alert("Start a new collection?", isPresented: $confirmingFirst) {
            Button("Go ahead") { picking = true }
            Button("Go back", role: .cancel) { leave() }
        } message: {
            Text("This is empty, so what you add will start a new collection here. If you expected to see things already, go back and try again — what you typed decides which collection you get.\n\nIf you go ahead, what you typed becomes the only way back to it. Nothing can reset it, so make it something long that only your family would say.")
        }
        .confirmationDialog("Remove \"\(removing?.payload.name ?? "")\"?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
                            titleVisibility: .visible) {
            Button("Remove", role: .destructive) {
                if let item = removing, let key { Task { touch(); view = await model.setVaultItem(key, item, active: false) } }
            }
        } message: {
            Text("It stops showing here for everyone. You can put it back from Removed.")
        }
        .task {
            // Argon2id runs off the main thread and takes the same time whatever the code opens.
            guard let k = await model.vaultKey(code: code) else { leave(); return }
            key = k
            view = await model.openVault(k)
        }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: 15_000_000_000)
                if Date().timeIntervalSince(lastTouch) > Self.idleSeconds { leave(); return }
            }
        }
        .onChange(of: scenePhase) { _, phase in if phase == .background { leave() } }
        .onDisappear { key = nil; view = nil; model.closeVault() }
    }

    private func row(_ item: VaultItem, fetchingNow: Bool) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(item.payload.name).font(Typeface.title).foregroundStyle(Palette.text)
            let added = Date(timeIntervalSince1970: Double(item.payload.addedAt) / 1000).formatted(date: .abbreviated, time: .omitted)
            Text(fetchingNow ? "Fetching from the family…" : "\(item.payload.sizeBytes / 1024) KB · \(added)")
                .font(Typeface.small).foregroundStyle(Palette.secondaryText)
        }
        .padding(.vertical, Spacing.xs)
    }

    private func open(_ item: VaultItem) {
        touch()
        Task {
            if let url = await model.vaultFile(item) {
                fetching = nil
                preview = VaultDocument(url: url, name: item.payload.name, mimeType: item.payload.mimeType)
            } else { fetching = item.id }
        }
    }

    private func touch() { lastTouch = Date() }

    private func leave() {
        key = nil
        view = nil
        model.closeVault()
        dismiss()
    }
}


struct VaultDocument: Identifiable {
    let url: URL
    let name: String
    let mimeType: String
    var id: URL { url }
}

/// Shows a vault document with nothing that exports it: no share, no save, no open-in.
private struct VaultDocumentView: View {
    @Environment(\.dismiss) private var dismiss
    let doc: VaultDocument

    private var type: UTType? { UTType(mimeType: doc.mimeType) ?? UTType(filenameExtension: doc.url.pathExtension) }

    var body: some View {
        NavigationStack {
            Group {
                if type?.conforms(to: .pdf) == true, let pdf = PDFDocument(url: doc.url) {
                    PDFPage(document: pdf)
                } else if type?.conforms(to: .image) == true, let image = UIImage(contentsOfFile: doc.url.path) {
                    ScrollView([.horizontal, .vertical]) { Image(uiImage: image).resizable().scaledToFit() }
                } else if type?.conforms(to: .text) == true, let text = try? String(contentsOf: doc.url, encoding: .utf8) {
                    ScrollView { Text(text).font(Typeface.code).frame(maxWidth: .infinity, alignment: .leading).padding(Spacing.m) }
                } else {
                    Text("This kind of file can't be shown here.").font(Typeface.body).foregroundStyle(Palette.secondaryText).padding(Spacing.l)
                }
            }
            .navigationTitle(doc.name)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

private struct PDFPage: UIViewRepresentable {
    let document: PDFDocument
    func makeUIView(context: Context) -> PDFView {
        let v = PDFView()
        v.document = document
        v.autoScales = true
        return v
    }
    func updateUIView(_ v: PDFView, context: Context) {}
}

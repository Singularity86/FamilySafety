import FamilySafetyCore
import SwiftUI

/// First run: make a new identity (name → phrase → confirm) or restore one from a phrase.
struct OnboardingFlow: View {
    enum Step: Hashable { case name, phrase, confirm, restore }

    @EnvironmentObject private var model: AppModel
    @State private var path: [Step] = []
    @State private var name = ""
    @State private var words: [String] = []

    var body: some View {
        NavigationStack(path: $path) {
            WelcomeView(onNew: { path = [.name] }, onRestore: { path = [.restore] })
                .navigationDestination(for: Step.self) { step in
                    switch step {
                    case .name:
                        NameView(name: $name) {
                            words = (try? Bip39.generate12WordMnemonic()) ?? []
                            path.append(.phrase)
                        }
                    case .phrase:
                        PhraseView(words: words) { path.append(.confirm) }
                    case .confirm:
                        ConfirmPhraseView(words: words) { finish(mnemonic: words.joined(separator: " ")) }
                    case .restore:
                        RestoreView(name: $name) { finish(mnemonic: $0) }
                    }
                }
        }
        .tint(Palette.amber)
    }

    private func finish(mnemonic: String) {
        do { try model.saveProfile(mnemonic: mnemonic, displayName: name.trimmingCharacters(in: .whitespacesAndNewlines)) } catch {
            model.lastError = "Your identity couldn't be saved on this phone. Nothing was lost; try again."
        }
    }
}

struct WelcomeView: View {
    let onNew: () -> Void
    let onRestore: () -> Void

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Spacer()
                Text("Jibaro Family Safety").font(Typeface.display)
                Text("See where your family is, talk to them, and get an alert if one of them crashes. Everything is encrypted on the phones; nobody else can read it.")
                    .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                Spacer()
                Button("Get started", action: onNew).buttonStyle(PrimaryButtonStyle())
                Button("I have a recovery phrase", action: onRestore).buttonStyle(SecondaryButtonStyle())
                Link("Privacy policy", destination: AppLinks.privacyPolicy)
                    .font(Typeface.small).tint(Palette.secondaryText)
                    .frame(maxWidth: .infinity)
            }
            .padding(.bottom, Spacing.l)
        }
    }
}

struct NameView: View {
    @Binding var name: String
    let onNext: () -> Void

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("What should your family call you?").font(Typeface.headline)
                TextField("Your name", text: $name).fieldStyle().textContentType(.givenName).submitLabel(.next)
                    .onSubmit { if valid { onNext() } }
                Spacer()
                Button("Next", action: onNext).buttonStyle(PrimaryButtonStyle()).disabled(!valid)
            }
            .padding(.vertical, Spacing.l)
        }
    }

    private var valid: Bool { !name.trimmingCharacters(in: .whitespaces).isEmpty }
}

/// The twelve recovery words, numbered, in the mono face.
struct PhraseGrid: View {
    let words: [String]
    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible()), GridItem(.flexible())], alignment: .leading, spacing: Spacing.s) {
            ForEach(Array(words.enumerated()), id: \.offset) { index, word in
                HStack(spacing: Spacing.s) {
                    Text("\(index + 1)").font(Typeface.small).foregroundStyle(Palette.secondaryText).frame(width: 20, alignment: .trailing)
                    Text(word).font(Typeface.code)
                }
                .padding(.vertical, Spacing.xs)
            }
        }
        .padding(Spacing.m)
        .background(Palette.surface)
        .clipShape(RoundedRectangle(cornerRadius: Radius.control))
    }
}

struct PhraseView: View {
    let words: [String]
    let onNext: () -> Void

    var body: some View {
        Screen {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Text("Your recovery phrase").font(Typeface.headline)
                    Text("Write these 12 words on paper, in order, and keep them somewhere safe. They are the only way back into your family if you lose this phone. Anyone who has them can take your place, so never photograph or share them.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                    PhraseGrid(words: words).captureShield()
                    Button("I've written them down", action: onNext).buttonStyle(PrimaryButtonStyle())
                }
                .padding(.vertical, Spacing.l)
            }
        }
    }
}

/// Asks for three of the words, so a phrase that wasn't written down is caught now.
struct ConfirmPhraseView: View {
    let words: [String]
    let onDone: () -> Void
    @State private var positions: [Int] = []
    @State private var answers: [String] = ["", "", ""]
    @State private var wrong = false

    var body: some View {
        Screen {
            VStack(alignment: .leading, spacing: Spacing.m) {
                Text("Check your words").font(Typeface.headline)
                Text("Type these words from your paper.").font(Typeface.body).foregroundStyle(Palette.secondaryText)
                ForEach(Array(positions.enumerated()), id: \.offset) { i, position in
                    TextField("Word \(position + 1)", text: $answers[i]).fieldStyle()
                        .textInputAutocapitalization(.never).autocorrectionDisabled().font(Typeface.code)
                }
                if wrong {
                    Text("At least one word doesn't match. Check your paper and the word numbers.")
                        .font(Typeface.label).foregroundStyle(Palette.dangerText)
                }
                Spacer()
                Button("Confirm") {
                    let ok = positions.enumerated().allSatisfy { i, p in answers[i].trimmingCharacters(in: .whitespaces).lowercased() == words[p] }
                    if ok { onDone() } else { wrong = true }
                }
                .buttonStyle(PrimaryButtonStyle())
            }
            .padding(.vertical, Spacing.l)
            .captureShield()
        }
        .onAppear { if positions.isEmpty { positions = Array((0..<words.count).shuffled().prefix(3)).sorted() } }
    }
}

struct RestoreView: View {
    @Binding var name: String
    let onDone: (String) -> Void
    @State private var phrase = ""
    @State private var problem: String?

    var body: some View {
        Screen {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.m) {
                    Text("Restore from your phrase").font(Typeface.headline)
                    Text("Type your 12 words in order, separated by spaces. Then ask someone in your family for an invite: you'll be let straight back in.")
                        .font(Typeface.body).foregroundStyle(Palette.secondaryText)
                    TextEditor(text: $phrase)
                        .font(Typeface.code).frame(minHeight: 120).scrollContentBackground(.hidden)
                        .padding(Spacing.s).background(Palette.surface)
                        .overlay(RoundedRectangle(cornerRadius: Radius.control).stroke(Palette.outline, lineWidth: 1))
                        .clipShape(RoundedRectangle(cornerRadius: Radius.control))
                        .textInputAutocapitalization(.never).autocorrectionDisabled()
                        .captureShield()
                    TextField("Your name", text: $name).fieldStyle()
                    if let problem { Text(problem).font(Typeface.label).foregroundStyle(Palette.dangerText) }
                    Button("Restore") { restore() }.buttonStyle(PrimaryButtonStyle())
                        .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.vertical, Spacing.l)
            }
        }
    }

    private func restore() {
        let words = phrase.lowercased().split(whereSeparator: { $0.isWhitespace }).map(String.init)
        let normalized = words.joined(separator: " ")
        guard words.count == 12 else { problem = "That's \(words.count) words; a recovery phrase has 12."; return }
        // A phrase that fails the BIP-39 checksum has a typo (Android 1.13.2 rejects it too).
        guard (try? Bip39.validateMnemonic(normalized)) == true else {
            problem = "One of the words is misspelled or out of order. Check each one against your paper."
            return
        }
        onDone(normalized)
    }
}

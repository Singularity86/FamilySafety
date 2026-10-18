import CoreText
import FamilySafetyCore
import SwiftUI
import UIKit

/// Porch Light (DESIGN.md): pine neutrals, one amber accent per screen, people as the colour.
/// Values are copied from Android's theme files; if they disagree, Android is what ships.
enum Palette {
    static let background = dynamic(dark: 0x101A15, light: 0xF2F5F0)
    static let surface = dynamic(dark: 0x16231C, light: 0xFFFFFF)
    static let raised = dynamic(dark: 0x1D2C24, light: 0xE7EDE6)
    static let text = dynamic(dark: 0xEEF2EC, light: 0x15231B)
    static let secondaryText = dynamic(dark: 0xA3B2A8, light: 0x536358)
    static let disabledText = dynamic(dark: 0x5E6E63, light: 0x97A69B)
    static let outline = dynamic(dark: 0x64796B, light: 0x7A8C7F)
    static let hairline = dynamic(dark: 0x2C3D33, light: 0xD3DDD4)
    /// The porch light: the single most important thing on a screen.
    static let amber = dynamic(dark: 0xE8C858, light: 0x8A6A0F)
    static let onAmber = dynamic(dark: 0x0F1A14, light: 0xFFFFFF)
    static let goodText = dynamic(dark: 0x35B378, light: 0x1B7E4D)
    static let goodIndicator = dynamic(dark: 0x35B378, light: 0x219A5E)
    static let warningText = dynamic(dark: 0xE2B45F, light: 0xA36000)
    static let dangerText = dynamic(dark: 0xFF5B66, light: 0xC32B36)
    static let dangerIndicator = dynamic(dark: 0xFF5B66, light: 0xE03E4A)

    static func dynamic(dark: UInt32, light: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    /// A person's colour as text: the fill on dark, the darker shade on light.
    static func personText(_ c: PersonColor) -> Color { dynamic(dark: c.fill, light: c.textOnLight) }
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
                  blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

extension Color {
    init(hex: UInt32) { self.init(uiColor: UIColor(hex: hex)) }
}

/// Bitter for headings, Atkinson Hyperlegible Next for everything read or tapped, Atkinson
/// Hyperlegible Mono for codes, fingerprints and recovery words. Bundled, never fetched.
enum Typeface {
    static func registerBundledFonts() {
        let urls = Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        for url in urls { CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil) }
    }

    static let display = Font.custom("Bitter-Bold", size: 28, relativeTo: .largeTitle)
    static let headline = Font.custom("Bitter-SemiBold", size: 22, relativeTo: .title2)
    static let title = Font.custom("AtkinsonHyperlegibleNext-SemiBold", size: 17, relativeTo: .headline)
    static let body = Font.custom("AtkinsonHyperlegibleNext-Regular", size: 15, relativeTo: .body)
    static let bodyStrong = Font.custom("AtkinsonHyperlegibleNext-SemiBold", size: 15, relativeTo: .body)
    static let label = Font.custom("AtkinsonHyperlegibleNext-Medium", size: 13, relativeTo: .subheadline)
    static let small = Font.custom("AtkinsonHyperlegibleNext-Regular", size: 12, relativeTo: .caption)
    static let code = Font.custom("AtkinsonHyperlegibleMono-Regular", size: 16, relativeTo: .body)
    static let codeLarge = Font.custom("AtkinsonHyperlegibleMono-SemiBold", size: 28, relativeTo: .title)
}

enum Spacing {
    static let xs: CGFloat = 4, s: CGFloat = 8, m: CGFloat = 16, l: CGFloat = 24, xl: CGFloat = 32, xxl: CGFloat = 48
}

/// Corner radius by role: 10 for anything tapped or holding content, 20 for things that slide
/// over the screen, circles for people, pills only for small tags.
enum Radius {
    static let control: CGFloat = 10
    static let sheet: CGFloat = 20
}

/// The screen's porch light: one per screen.
struct PrimaryButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Typeface.bodyStrong)
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(Palette.onAmber)
            .background(Palette.amber.opacity(isEnabled ? (configuration.isPressed ? 0.8 : 1) : 0.4))
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
    }
}

struct SecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(Typeface.bodyStrong)
            .frame(maxWidth: .infinity, minHeight: 48)
            .foregroundStyle(Palette.text)
            .background(configuration.isPressed ? Palette.raised : Color.clear)
            .overlay(RoundedRectangle(cornerRadius: Radius.control).stroke(Palette.outline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
    }
}

/// A screen's ground and gutters.
struct Screen<Content: View>: View {
    let content: Content
    init(@ViewBuilder content: () -> Content) { self.content = content() }
    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            content.padding(.horizontal, Spacing.m)
        }
        .foregroundStyle(Palette.text)
    }
}

/// A person: a circle in their colour with a dark initial.
struct PersonAvatar: View {
    let name: String
    let memberId: String
    let colorHue: Float?
    var size: CGFloat = 40

    var body: some View {
        let colour = PersonPalette.forMember(memberId: memberId, colorHue: colorHue)
        Circle()
            .fill(Color(hex: colour.fill))
            .frame(width: size, height: size)
            .overlay(
                Text(String(name.trimmingCharacters(in: .whitespaces).prefix(1)).uppercased())
                    .font(.custom("AtkinsonHyperlegibleNext-Bold", size: size * 0.42))
                    .foregroundStyle(Color(hex: PersonPalette.onPersonColor))
            )
            .accessibilityHidden(true)
    }
}

/// A plain text field with the control radius and outline.
struct FieldStyle: ViewModifier {
    func body(content: Content) -> some View {
        content
            .font(Typeface.body)
            .padding(.horizontal, Spacing.m)
            .frame(minHeight: 48)
            .background(Palette.surface)
            .overlay(RoundedRectangle(cornerRadius: Radius.control).stroke(Palette.outline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: Radius.control))
    }
}

extension View {
    func fieldStyle() -> some View { modifier(FieldStyle()) }
}

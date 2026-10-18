import Foundation

/// One of the twelve person colours (DESIGN.md, Android PersonColors.kt). Colours are 0xRRGGBB.
public struct PersonColor: Equatable, Sendable {
    public let name: String
    /// Hue on the perceptual (OKLCH) wheel the palette was chosen on.
    public let hue: Float
    /// The HSL hue written to `FamilyMember.colorHue` when someone picks this colour.
    public let storedHue: Float
    public let fill: UInt32
    public let textOnLight: UInt32
}

/// Port of Android's `PersonPalette`, so a person has the same colour on both platforms.
/// Every place a person's colour appears goes through `forMember`.
public enum PersonPalette {
    /// Initials and marks drawn on a fill.
    public static let onPersonColor: UInt32 = 0x0F1A14

    public static let colors: [PersonColor] = [
        PersonColor(name: "Red", hue: 20, storedHue: 359, fill: 0xFB8083, textOnLight: 0xA8353E),
        PersonColor(name: "Tangerine", hue: 46, storedHue: 20, fill: 0xF68953, textOnLight: 0xA14200),
        PersonColor(name: "Marigold", hue: 72, storedHue: 37, fill: 0xE49921, textOnLight: 0x885700),
        PersonColor(name: "Olive", hue: 98, storedHue: 52, fill: 0xC4AB11, textOnLight: 0x736300),
        PersonColor(name: "Lime", hue: 126, storedHue: 81, fill: 0x93BB48, textOnLight: 0x516F00),
        PersonColor(name: "Jade", hue: 152, storedHue: 140, fill: 0x54C57A, textOnLight: 0x00773B),
        PersonColor(name: "Teal", hue: 178, storedHue: 172, fill: 0x00C6AB, textOnLight: 0x007463),
        PersonColor(name: "Lagoon", hue: 206, storedHue: 185, fill: 0x00C1D2, textOnLight: 0x00717B),
        PersonColor(name: "Sky", hue: 232, storedHue: 196, fill: 0x08BAF8, textOnLight: 0x006C93),
        PersonColor(name: "Periwinkle", hue: 268, storedHue: 224, fill: 0x87A7FF, textOnLight: 0x3F5BB8),
        PersonColor(name: "Orchid", hue: 306, storedHue: 270, fill: 0xC290F5, textOnLight: 0x7947A5),
        PersonColor(name: "Rose", hue: 342, storedHue: 321, fill: 0xEA82C5, textOnLight: 0x99387B),
    ]

    public static func forMember(memberId: String, colorHue: Float?) -> PersonColor {
        if let colorHue { return forHue(colorHue) }
        let index = UInt64(UInt32(bitPattern: javaHashCode(memberId))) % UInt64(colors.count)
        return colors[Int(index)]
    }

    public static func forHue(_ storedHue: Float) -> PersonColor {
        let h = perceptualHueOfLegacy(storedHue)
        return colors.min { a, b in distance(a.hue, h) < distance(b.hue, h) }!
    }

    /// Java's `String.hashCode()` over UTF-16 code units, as Android computes it.
    static func javaHashCode(_ s: String) -> Int32 {
        s.utf16.reduce(Int32(0)) { $0 &* 31 &+ Int32($1) }
    }

    private static func distance(_ a: Float, _ b: Float) -> Float {
        let d = abs(a - b)
        return min(d, 360 - d)
    }

    /// Older builds' colour for a stored hue — HSL(h, 0.55, 0.45) — as an OKLCH hue in degrees.
    static func perceptualHueOfLegacy(_ storedHue: Float) -> Float {
        let h = (storedHue.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360) / 360
        let l: Float = 0.45, s: Float = 0.55
        let q = l + s - l * s, p = 2 * l - q
        func channel(_ t0: Float) -> Float {
            var t = t0
            if t < 0 { t += 1 }
            if t > 1 { t -= 1 }
            if t < 1 / 6 { return p + (q - p) * 6 * t }
            if t < 1 / 2 { return q }
            if t < 2 / 3 { return p + (q - p) * (2 / 3 - t) * 6 }
            return p
        }
        func linear(_ c: Float) -> Double {
            c <= 0.04045 ? Double(c) / 12.92 : pow((Double(c) + 0.055) / 1.055, 2.4)
        }
        let r = linear(channel(h + 1 / 3)), g = linear(channel(h)), b = linear(channel(h - 1 / 3))
        let lm = cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
        let mm = cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
        let sm = cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
        let a = 1.9779984951 * lm - 2.4285922050 * mm + 0.4505937099 * sm
        let bb = 0.0259040371 * lm + 0.7827717662 * mm - 0.8086757660 * sm
        return Float((atan2(bb, a) * 180 / .pi + 360).truncatingRemainder(dividingBy: 360))
    }
}

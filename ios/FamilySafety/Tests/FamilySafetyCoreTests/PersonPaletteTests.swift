import XCTest
@testable import FamilySafetyCore

/// Mirrors Android's PersonPaletteTest, so a person is the same colour on both platforms.
final class PersonPaletteTests: XCTestCase {
    func test_choosingAColourStoresAHueThatComesBackAsTheSameColour() {
        for c in PersonPalette.colors { XCTAssertEqual(PersonPalette.forHue(c.storedHue), c, c.name) }
    }

    func test_storedHuesAreReadOnTheOldWheel() {
        XCTAssertEqual(PersonPalette.forHue(0).name, "Red")
        let presets: [Float: String] = [0: "Red", 30: "Marigold", 60: "Olive", 90: "Lime", 140: "Jade", 180: "Lagoon",
                                        210: "Periwinkle", 240: "Periwinkle", 270: "Orchid", 300: "Rose", 330: "Rose"]
        for (hue, name) in presets { XCTAssertEqual(PersonPalette.forHue(hue).name, name, "preset \(hue)") }
    }

    func test_huesWrap() {
        XCTAssertEqual(PersonPalette.forHue(10), PersonPalette.forHue(370))
        XCTAssertEqual(PersonPalette.forHue(350), PersonPalette.forHue(-10))
    }

    func test_javaHashCodeMatchesAndroid() {
        // "hello".hashCode() == 99162322 and "polygenelubricants".hashCode() == Int.MIN_VALUE in Java.
        XCTAssertEqual(PersonPalette.javaHashCode("hello"), 99_162_322)
        XCTAssertEqual(PersonPalette.javaHashCode("polygenelubricants"), Int32.min)
    }

    func test_automaticColoursSpreadEvenly() {
        var counts: [String: Int] = [:]
        for i in 0..<1200 {
            let id = MemberIdDerivation.deriveMemberId(fromEd25519PublicKeyBytes: Array("member-\(i)".utf8))
            counts[PersonPalette.forMember(memberId: id, colorHue: nil).name, default: 0] += 1
        }
        XCTAssertEqual(counts.count, 12)
        XCTAssertTrue(counts.values.allSatisfy { (50...150).contains($0) }, "\(counts)")
    }
}

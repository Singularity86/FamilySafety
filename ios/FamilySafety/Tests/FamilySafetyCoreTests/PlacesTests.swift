import XCTest
@testable import FamilySafetyCore

/// B6: place and speed alerts, matching Android's GeofenceMonitor.
final class PlacesTests: XCTestCase {
    private let home = Place(id: "home", name: "Home", latitude: 37.7700, longitude: -122.4300, radiusMeters: 100)
    private let t0: Int64 = 1_800_000_000_000

    /// A fix `meters` north of home.
    private func at(_ meters: Double, _ who: String = "dana", speed: Float? = nil, t: Int64) -> LocationUpdate {
        LocationUpdate(memberId: who, latitude: 37.7700 + meters / 111_195, longitude: -122.4300, accuracy: 10, timestamp: t, speed: speed)
    }

    func test_arriveAndLeave_withAMarginAtTheEdge() {
        var w = PlaceWatch()
        w.save(home)
        XCTAssertEqual(w.evaluate(at(500, t: t0), now: t0), [])
        XCTAssertEqual(w.evaluate(at(50, t: t0 + 1), now: t0 + 1), [.entered(memberId: "dana", place: home)])
        XCTAssertEqual(w.evaluate(at(60, t: t0 + 2), now: t0 + 2), [], "still inside, no repeat")
        XCTAssertEqual(w.evaluate(at(120, t: t0 + 3 * 60_000), now: t0 + 3 * 60_000), [], "within the 25 m margin: not left yet")
        XCTAssertEqual(w.evaluate(at(140, t: t0 + 4 * 60_000), now: t0 + 4 * 60_000), [.left(memberId: "dana", place: home)])
    }

    func test_changesAtMostEveryTwoMinutes() {
        var w = PlaceWatch()
        w.save(home)
        _ = w.evaluate(at(10, t: t0), now: t0)
        XCTAssertEqual(w.evaluate(at(500, t: t0 + 60_000), now: t0 + 60_000), [], "a quick bounce out is held back")
        XCTAssertEqual(w.inside["dana"], ["home"])
        XCTAssertEqual(w.evaluate(at(500, t: t0 + 2 * 60_000), now: t0 + 2 * 60_000), [.left(memberId: "dana", place: home)])
    }

    func test_watchedMembersAndDisabledPlaces() {
        var w = PlaceWatch()
        var school = home
        school = Place(id: "school", name: "School", latitude: home.latitude, longitude: home.longitude, radiusMeters: 100,
                       watchedMemberIds: ["ben"], alertOnExit: false)
        w.save(school)
        XCTAssertEqual(w.evaluate(at(10, "dana", t: t0), now: t0), [], "School watches Ben only")
        XCTAssertEqual(w.evaluate(at(10, "ben", t: t0), now: t0), [.entered(memberId: "ben", place: school)])
        XCTAssertEqual(w.evaluate(at(900, "ben", t: t0 + 300_000), now: t0 + 300_000), [], "leaving School isn't announced")
        var off = home
        off.isEnabled = false
        var w2 = PlaceWatch()
        w2.save(off)
        XCTAssertEqual(w2.evaluate(at(10, t: t0), now: t0), [])
    }

    func test_speed_overTheThreshold_atMostEveryFiveMinutes() {
        var w = PlaceWatch()
        XCTAssertEqual(w.evaluate(at(0, speed: 50, t: t0), now: t0), [], "off by default")
        w.speedAlertsOn = true
        w.speedThresholdMph = 70
        XCTAssertEqual(w.evaluate(at(0, speed: 30, t: t0), now: t0), [], "67 mph")
        XCTAssertEqual(w.evaluate(at(0, speed: 35, t: t0 + 1), now: t0 + 1), [.speeding(memberId: "dana", mph: 78, thresholdMph: 70)])
        XCTAssertEqual(w.evaluate(at(0, speed: 35, t: t0 + 60_000), now: t0 + 60_000), [])
        XCTAssertEqual(w.evaluate(at(0, speed: 35, t: t0 + 301_000), now: t0 + 301_000).count, 1)
    }

    func test_insideSurvivesARelaunch_soNobodyIsReannounced() throws {
        var w = PlaceWatch()
        w.save(home)
        _ = w.evaluate(at(10, t: t0), now: t0)
        let slot = InMemorySlot()
        try slot.writeJSON(w)
        var reopened = try XCTUnwrap(slot.readJSON(PlaceWatch.self))
        XCTAssertEqual(reopened.evaluate(at(12, t: t0 + 10 * 60_000), now: t0 + 10 * 60_000), [])
    }

    func test_removingAPlaceOrAMember() {
        var w = PlaceWatch()
        w.save(Place(id: "p", name: "P", latitude: 0, longitude: 0, radiusMeters: 50, watchedMemberIds: ["ben", "gone"]))
        _ = w.evaluate(LocationUpdate(memberId: "gone", latitude: 0, longitude: 0, accuracy: 5, timestamp: 1), now: 1)
        w.keepOnly(["ben"])
        XCTAssertEqual(w.places.first?.watchedMemberIds, ["ben"])
        XCTAssertNil(w.inside["gone"])
        w.remove("p")
        XCTAssertTrue(w.places.isEmpty)
    }
}

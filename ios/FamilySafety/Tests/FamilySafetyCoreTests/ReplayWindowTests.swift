import XCTest
@testable import FamilySafetyCore

/// 3.4's receive window on iOS, with the owner's future bound.
final class ReplayWindowTests: XCTestCase {
    private let now: Int64 = 1_800_000_000_000
    private func body(_ seq: Int64, ts: Int64? = nil) -> SequencedBody { SequencedBody(seq: seq, ts: ts ?? now, body: "{}") }

    func test_eachSeqOnce_reorderingTolerated() {
        var w = ReplayWindow()
        let base = now * 1000
        XCTAssertEqual(w.check(sender: "s", body: body(base + 11), nowMs: now), .accept)
        XCTAssertEqual(w.check(sender: "s", body: body(base + 10), nowMs: now), .accept, "an earlier seq from another topic")
        XCTAssertEqual(w.check(sender: "s", body: body(base + 10), nowMs: now), .replay)
        XCTAssertEqual(w.check(sender: "s", body: body(base + 11), nowMs: now), .replay)
    }

    func test_survivesARestartWithoutDroppingALateLowerSeq() throws {
        var w = ReplayWindow()
        let base = now * 1000
        _ = w.check(sender: "s", body: body(base + 11), nowMs: now)
        var restored = try JSONDecoder().decode(ReplayWindow.self, from: JSONEncoder().encode(w))
        XCTAssertEqual(restored.check(sender: "s", body: body(base + 10), nowMs: now), .accept)
        XCTAssertEqual(restored.check(sender: "s", body: body(base + 11), nowMs: now), .replay)
    }

    func test_aClockAYearAheadIsDroppedAndDoesNotMoveTheWindow() {
        var w = ReplayWindow()
        let yearAhead = now + 365 * 24 * 60 * 60 * 1000
        XCTAssertEqual(w.check(sender: "s", body: body(yearAhead * 1000, ts: yearAhead), nowMs: now), .fromTheFuture)
        XCTAssertNil(w.hwm["s"])
        XCTAssertEqual(w.check(sender: "s", body: body(now * 1000), nowMs: now), .accept, "the fixed clock still gets through")
    }

    func test_tooOld_andDowngrade() {
        var w = ReplayWindow()
        XCTAssertEqual(w.check(sender: "s", body: body(1, ts: now - ReplayWindow.maxAgeMs - 1), nowMs: now), .tooOld)
        XCTAssertEqual(w.check(sender: "s", body: nil, nowMs: now), .accept, "plain bodies are fine before any sequenced one")
        _ = w.check(sender: "s", body: body(now * 1000), nowMs: now)
        XCTAssertEqual(w.check(sender: "s", body: nil, nowMs: now), .unsequencedAfterSequenced)
    }

    func test_windowEvictsTheSmallestIntoTheFloor() {
        var w = ReplayWindow()
        let base = now * 1000
        for i in 0..<Int64(ReplayWindow.seenCap + 1) { XCTAssertEqual(w.check(sender: "s", body: body(base + i), nowMs: now), .accept) }
        XCTAssertEqual(w.check(sender: "s", body: body(base), nowMs: now), .replay, "evicted into the floor")
    }

    func test_counterIsClockSeededAndStrictlyIncreasing() {
        var c = SequenceCounter()
        let a = c.next(nowMs: now), b = c.next(nowMs: now), d = c.next(nowMs: now - 5_000)
        XCTAssertEqual(a, now * 1000)
        XCTAssertTrue(a < b && b < d)
    }

    func test_parseRequiresIntegers() {
        XCTAssertEqual(SequencedBody.parse(#"{"seq":5,"ts":6,"body":"x"}"#), SequencedBody(seq: 5, ts: 6, body: "x"))
        XCTAssertNil(SequencedBody.parse(#"{"seq":5.5,"ts":6,"body":"x"}"#))
        XCTAssertNil(SequencedBody.parse(#"{"messageId":"m"}"#))
    }
}

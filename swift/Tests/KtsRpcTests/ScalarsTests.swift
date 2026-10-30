import XCTest
@testable import KtsRpc

final class ScalarsTests: XCTestCase {
    func testInstantAcceptsAnyNumberOfFractionDigits() throws {
        let base = try XCTUnwrap(Rpc.Instant(rawValue: "2026-10-30T09:12:44Z").date)
        for (raw, fraction) in [
            ("2026-10-30T09:12:44.5Z", 0.5),
            ("2026-10-30T09:12:44.123Z", 0.123),
            ("2026-10-30T09:12:44.123456Z", 0.123456),
            ("2026-10-30T09:12:44.123456789Z", 0.123456789),
        ] {
            let date = try XCTUnwrap(Rpc.Instant(rawValue: raw).date, raw)
            XCTAssertEqual(date.timeIntervalSince(base), fraction, accuracy: 1e-6, raw)
        }
    }

    func testOffsetDateTimeKeepsTheOffset() throws {
        let local = try XCTUnwrap(Rpc.OffsetDateTime(rawValue: "2026-10-30T11:12:44.123+02:00").date)
        let utc = try XCTUnwrap(Rpc.Instant(rawValue: "2026-10-30T09:12:44.123Z").date)
        XCTAssertEqual(local.timeIntervalSince(utc), 0, accuracy: 1e-6)
    }

    func testInstantRoundTripsThroughDate() throws {
        let date = Date(timeIntervalSince1970: 1_800_000_000.25)
        let parsed = try XCTUnwrap(Rpc.Instant(date).date)
        XCTAssertEqual(parsed.timeIntervalSince1970, date.timeIntervalSince1970, accuracy: 1e-3)
    }

    func testLocalDateComponentsAndOrder() throws {
        let date = Rpc.LocalDate(year: 2026, month: 3, day: 7)
        XCTAssertEqual(date.rawValue, "2026-03-07")
        XCTAssertEqual(date.components, DateComponents(year: 2026, month: 3, day: 7))
        XCTAssertLessThan(date, Rpc.LocalDate(rawValue: "2026-11-01"))

        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Almaty"))
        let start = try XCTUnwrap(date.startOfDay(in: calendar))
        XCTAssertEqual(Rpc.LocalDate(start, calendar: calendar), date)
    }

    func testScalarsAreBareJSONStrings() throws {
        struct Box: Codable, Equatable {
            let at: Rpc.Instant
            let day: Rpc.LocalDate
        }
        let json = #"{"at":"2026-10-30T09:12:44Z","day":"2026-10-31"}"#
        let box = try JSONDecoder().decode(Box.self, from: Data(json.utf8))
        XCTAssertEqual(box, Box(at: Rpc.Instant(rawValue: "2026-10-30T09:12:44Z"), day: Rpc.LocalDate(year: 2026, month: 10, day: 31)))
        let encoded = try JSONDecoder().decode(Box.self, from: JSONEncoder().encode(box))
        XCTAssertEqual(encoded, box)
    }
}

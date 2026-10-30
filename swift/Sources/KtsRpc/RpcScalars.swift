import Foundation

// The date types of SwiftGenerator.defaultMappings, kept as the strings kotlinx.serialization
// writes and parsed on demand.

extension Rpc {
    /// `kotlin.time.Instant`: ISO 8601 in UTC with 0–9 fraction digits
    /// (`2026-10-30T09:12:44.123456Z`). Kept as sent; parsed on demand.
    public struct Instant: RawRepresentable, Codable, Hashable, Sendable, Comparable {
        public let rawValue: String

        public init(rawValue: String) { self.rawValue = rawValue }

        public init(_ date: Date) {
            rawValue = ISO8601Parsing.format(date)
        }

        public var date: Date? { ISO8601Parsing.parse(rawValue) }

        public static func < (lhs: Instant, rhs: Instant) -> Bool {
            (lhs.date ?? .distantPast) < (rhs.date ?? .distantPast)
        }

        public init(from decoder: Decoder) throws {
            rawValue = try decoder.singleValueContainer().decode(String.self)
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            try c.encode(rawValue)
        }
    }

    /// `java.time.OffsetDateTime` as an ISO 8601 string: `2026-10-30T09:12:44.123+02:00`.
    public struct OffsetDateTime: RawRepresentable, Codable, Hashable, Sendable {
        public let rawValue: String

        public init(rawValue: String) { self.rawValue = rawValue }

        public var date: Date? { ISO8601Parsing.parse(rawValue) }

        public init(from decoder: Decoder) throws {
            rawValue = try decoder.singleValueContainer().decode(String.self)
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            try c.encode(rawValue)
        }
    }

    /// kotlinx-datetime `LocalDate`: `yyyy-MM-dd`, a calendar day without a time zone.
    public struct LocalDate: RawRepresentable, Codable, Hashable, Sendable, Comparable {
        public let rawValue: String

        public init(rawValue: String) { self.rawValue = rawValue }

        public init(year: Int, month: Int, day: Int) {
            rawValue = String(format: "%04d-%02d-%02d", year, month, day)
        }

        /// The day `date` falls on in `calendar`'s time zone.
        public init(_ date: Date, calendar: Calendar) {
            let c = calendar.dateComponents([.year, .month, .day], from: date)
            self.init(year: c.year ?? 1970, month: c.month ?? 1, day: c.day ?? 1)
        }

        public var components: DateComponents? {
            let parts = rawValue.split(separator: "-").compactMap { Int($0) }
            guard parts.count == 3 else { return nil }
            return DateComponents(year: parts[0], month: parts[1], day: parts[2])
        }

        /// Start of the day in `calendar`'s time zone.
        public func startOfDay(in calendar: Calendar) -> Date? {
            components.flatMap { calendar.date(from: $0) }
        }

        // yyyy-MM-dd sorts lexicographically in date order.
        public static func < (lhs: LocalDate, rhs: LocalDate) -> Bool { lhs.rawValue < rhs.rawValue }

        public init(from decoder: Decoder) throws {
            rawValue = try decoder.singleValueContainer().decode(String.self)
        }

        public func encode(to encoder: Encoder) throws {
            var c = encoder.singleValueContainer()
            try c.encode(rawValue)
        }
    }
}

/// ISO 8601 with any number of fraction digits (Foundation's parsers accept at most three).
enum ISO8601Parsing {
    private static let withoutFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    private static let withFraction: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func parse(_ string: String) -> Date? {
        guard let dot = string.firstIndex(of: ".") else {
            return withoutFraction.date(from: string)
        }
        let digitsEnd = string[string.index(after: dot)...].firstIndex { !$0.isNumber } ?? string.endIndex
        let digits = string[string.index(after: dot)..<digitsEnd]
        guard let base = withoutFraction.date(from: String(string[..<dot]) + String(string[digitsEnd...])),
              let fraction = Double("0." + digits)
        else { return nil }
        return base.addingTimeInterval(fraction)
    }

    static func format(_ date: Date) -> String {
        withFraction.string(from: date)
    }
}

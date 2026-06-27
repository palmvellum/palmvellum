import Foundation

/// Single source of truth for timestamps. Produces ISO-8601 UTC instants
/// (with fractional seconds) compatible with Supabase `timestamptz` and the
/// other clients' `Clock.nowIso()`.
public enum Clock {
    private static let formatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    public static func nowIso(_ date: Date = Date()) -> String {
        formatter.string(from: date)
    }

    /// Tolerant parse of the timestamp shapes the server returns
    /// (`...Z`, `...+00:00`, with or without fractional seconds).
    public static func parse(_ s: String) -> Date? {
        let withFrac = ISO8601DateFormatter()
        withFrac.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let d = withFrac.date(from: s) { return d }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: s)
    }
}

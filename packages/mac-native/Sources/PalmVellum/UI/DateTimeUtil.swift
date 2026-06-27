import Foundation
import PalmKit

/// Local-timezone date helpers for the Date Book (month grid, agenda buckets,
/// labels). Events are stored as ISO-8601 UTC instants; everything here works
/// in the user's local calendar for display.
enum DTU {
    static var cal: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        return c
    }

    static func parse(_ iso: String) -> Date { Clock.parse(iso) ?? Date() }
    static func iso(_ date: Date) -> String { Clock.nowIso(date) }

    static func startOfDay(_ date: Date) -> Date { cal.startOfDay(for: date) }

    static func dayKey(_ date: Date) -> String {
        let c = cal.dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", c.year ?? 0, c.month ?? 0, c.day ?? 0)
    }

    static func addMonths(_ date: Date, _ n: Int) -> Date {
        cal.date(byAdding: .month, value: n, to: date) ?? date
    }

    static func addDays(_ date: Date, _ n: Int) -> Date {
        cal.date(byAdding: .day, value: n, to: date) ?? date
    }

    static func nextDays(_ count: Int, from: Date = Date()) -> [Date] {
        let base = startOfDay(from)
        return (0..<count).map { addDays(base, $0) }
    }

    /// 6x7 = 42-day grid covering the month that `anchor` falls in.
    static func monthGrid(_ anchor: Date, weekStartsMonday: Bool) -> [Date] {
        let c = cal
        let comps = c.dateComponents([.year, .month], from: anchor)
        guard let firstOfMonth = c.date(from: comps) else { return [] }
        let weekday = c.component(.weekday, from: firstOfMonth) // 1=Sun ... 7=Sat
        let shift = weekStartsMonday ? (weekday + 5) % 7 : weekday - 1
        guard let start = c.date(byAdding: .day, value: -shift, to: firstOfMonth) else { return [] }
        return (0..<42).compactMap { c.date(byAdding: .day, value: $0, to: start) }
    }

    static func sameDay(_ a: Date, _ b: Date) -> Bool {
        cal.isDate(a, inSameDayAs: b)
    }

    static func isToday(_ d: Date) -> Bool { cal.isDateInToday(d) }

    static func isWeekend(_ d: Date) -> Bool {
        let wd = cal.component(.weekday, from: d)
        return wd == 1 || wd == 7
    }

    static func monthOf(_ d: Date) -> Int { cal.component(.month, from: d) }

    // MARK: Labels

    private static func fmt(_ template: String) -> DateFormatter {
        let f = DateFormatter()
        f.locale = .current
        f.timeZone = .current
        f.setLocalizedDateFormatFromTemplate(template)
        return f
    }

    static func timeLabel(_ d: Date) -> String { fmt("jmm").string(from: d) }
    static func dateLabel(_ d: Date) -> String { fmt("EEE MMM d").string(from: d) }
    static func longDateLabel(_ d: Date) -> String { fmt("EEEE MMM d yyyy").string(from: d) }
    static func monthTitle(_ d: Date) -> String { fmt("MMMM yyyy").string(from: d) }
    static func dayNumber(_ d: Date) -> String { "\(cal.component(.day, from: d))" }

    static var weekdayHeaders: [String] {
        ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]
    }
}

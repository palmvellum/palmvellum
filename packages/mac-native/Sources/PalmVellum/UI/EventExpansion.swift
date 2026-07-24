import Foundation
import PalmKit

/// One concrete occurrence shown in the Date Book — either a real event
/// (possibly a repeat instance) or a dated open to-do surfaced as a read-only
/// all-day `(TO DO)` pseudo-event.
struct Occurrence: Identifiable {
    let id: String
    let title: String
    let date: Date          // local start instant of this occurrence
    let allDay: Bool
    let isTodo: Bool
    let event: EventRecord? // the base event (nil for to-dos)
    let todoId: String?

    var timeLabel: String { allDay ? "all-day" : DTU.timeLabel(date) }
}

enum EventExpansion {
    private static func freqOf(_ rule: String?) -> String? {
        guard let rule, let range = rule.range(of: "FREQ=", options: .caseInsensitive) else { return nil }
        let tail = rule[range.upperBound...]
        let token = tail.prefix { $0.isLetter }.uppercased()
        return ["DAILY", "WEEKLY", "MONTHLY", "YEARLY"].contains(token) ? token : nil
    }

    private static func step(_ d: Date, _ freq: String) -> Date {
        switch freq {
        case "DAILY": return DTU.addDays(d, 1)
        case "WEEKLY": return DTU.addDays(d, 7)
        case "MONTHLY": return DTU.addMonths(d, 1)
        case "YEARLY": return DTU.cal.date(byAdding: .year, value: 1, to: d) ?? d
        default: return DTU.addDays(d, 1)
        }
    }

    /// Expand events + dated open todos into occurrences within [start, end].
    static func expand(
        events: [EventRecord],
        todos: [PalmRecord],
        start: Date,
        end: Date
    ) -> [Occurrence] {
        var out: [Occurrence] = []

        for e in events {
            let base = DTU.parse(e.startAt)
            if let freq = freqOf(e.repeatRule) {
                var cur = base
                var guardN = 0
                // Fast-forward to the window.
                while cur < start && guardN < 2000 { cur = step(cur, freq); guardN += 1 }
                while cur <= end && guardN < 2000 {
                    out.append(Occurrence(
                        id: "\(e.id)@\(DTU.dayKey(cur))",
                        title: e.title, date: cur, allDay: e.allDay,
                        isTodo: false, event: e, todoId: nil))
                    cur = step(cur, freq); guardN += 1
                }
            } else if base >= start && base <= end {
                out.append(Occurrence(
                    id: e.id, title: e.title, date: base, allDay: e.allDay,
                    isTodo: false, event: e, todoId: nil))
            }
        }

        let dueFmt = DateFormatter()
        dueFmt.dateFormat = "yyyy-MM-dd"
        // Locked to Hong Kong time (UTC+8) for Date Book day-bucketing.
        dueFmt.timeZone = TimeZone(identifier: "Asia/Hong_Kong")!
        for t in todos {
            let f = TodoFields(from: t.metadata)
            guard !f.palmCompleted, !f.palmDueDate.isEmpty,
                  let day = dueFmt.date(from: f.palmDueDate) else { continue }
            let d = DTU.startOfDay(day)
            if d >= DTU.startOfDay(start) && d <= end {
                out.append(Occurrence(
                    id: "todo-\(t.id)", title: "(TO DO) \(t.body ?? "")",
                    date: d, allDay: true, isTodo: true, event: nil, todoId: t.id))
            }
        }

        return out.sorted { a, b in
            if a.date != b.date { return a.date < b.date }
            if a.allDay != b.allDay { return a.allDay && !b.allDay }
            return a.title < b.title
        }
    }

    static func occurrences(_ all: [Occurrence], on day: Date) -> [Occurrence] {
        all.filter { DTU.sameDay($0.date, day) }
    }
}

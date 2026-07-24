import Foundation

/// One parsed VEVENT. `startIso`/`endIso` are ISO-8601 UTC instants.
public struct IcsEvent {
    public let uid: String?
    public let summary: String
    public let startIso: String
    public let endIso: String?
    public let allDay: Bool
    public let location: String?
    public let description: String?
}

/// Minimal iCalendar (RFC 5545) reader — enough to import VEVENTs from a file
/// or a subscribed feed. Faithful port of the Android `Ics.kt` / PWA `ics.ts`.
public enum Ics {
    public static func parse(_ text: String) -> [IcsEvent] {
        let lines = unfold(text)
        var out: [IcsEvent] = []
        var inEvent = false
        var uid: String?
        var summary = ""
        var location: String?
        var description: String?
        var start: (String, Bool)?
        var end: (String, Bool)?

        for line in lines {
            if line == "BEGIN:VEVENT" {
                inEvent = true; uid = nil; summary = ""; location = nil
                description = nil; start = nil; end = nil
            } else if line == "END:VEVENT" {
                if inEvent, let s = start {
                    out.append(IcsEvent(
                        uid: uid,
                        summary: summary.isEmpty ? "(untitled)" : summary,
                        startIso: s.0, endIso: end?.0, allDay: s.1,
                        location: (location?.isEmpty == false) ? location : nil,
                        description: (description?.isEmpty == false) ? description : nil))
                }
                inEvent = false
            } else if inEvent {
                guard let (name, params, value) = splitProp(line) else { continue }
                switch name.uppercased() {
                case "UID": uid = value
                case "SUMMARY": summary = unescape(value)
                case "LOCATION": location = unescape(value)
                case "DESCRIPTION": description = unescape(value)
                case "DTSTART": start = parseDt(params: params, value: value)
                case "DTEND": end = parseDt(params: params, value: value)
                default: break
                }
            }
        }
        return out
    }

    /// RFC 5545 line unfolding: a leading space/tab continues the prior line.
    private static func unfold(_ text: String) -> [String] {
        var res: [String] = []
        for raw in text.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = String(raw).replacingOccurrences(of: "\r", with: "")
            if (line.hasPrefix(" ") || line.hasPrefix("\t")) && !res.isEmpty {
                res[res.count - 1] += String(line.dropFirst())
            } else {
                res.append(line)
            }
        }
        return res
    }

    /// "NAME;PARAM=x:value" → (NAME, "PARAM=x", "value").
    private static func splitProp(_ line: String) -> (String, String, String)? {
        guard let colon = line.firstIndex(of: ":") else { return nil }
        let left = String(line[line.startIndex..<colon])
        let value = String(line[line.index(after: colon)...])
        if let semi = left.firstIndex(of: ";") {
            return (String(left[left.startIndex..<semi]), String(left[left.index(after: semi)...]), value)
        }
        return (left, "", value)
    }

    private static func unescape(_ v: String) -> String {
        v.replacingOccurrences(of: "\\n", with: "\n")
            .replacingOccurrences(of: "\\N", with: "\n")
            .replacingOccurrences(of: "\\,", with: ",")
            .replacingOccurrences(of: "\\;", with: ";")
            .replacingOccurrences(of: "\\\\", with: "\\")
    }

    /// Returns (instantIso, allDay) or nil if unparseable.
    private static func parseDt(params: String, value: String) -> (String, Bool)? {
        let isDate = params.range(of: "VALUE=DATE", options: .caseInsensitive) != nil
            || (value.count == 8 && !value.contains("T"))
        let tzid = params.range(of: "TZID=", options: .caseInsensitive).map { r -> String in
            let tail = params[r.upperBound...]
            return String(tail.prefix { $0 != ";" })
        }

        if isDate {
            // An all-day DATE is timezone-independent, so pin it to UTC
            // midnight (NOT .current). Local midnight would shift the UTC
            // date back a day for positive-offset zones like HK (Apple shows
            // it one day early) and disagree with the PWA / Android clients,
            // making the same subscribed event flip-flop its date on each
            // cross-client sync. Canonical string MUST byte-match the other
            // clients: "YYYY-MM-DDT00:00:00.000Z".
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd"
            f.timeZone = TimeZone(identifier: "UTC")
            guard f.date(from: value) != nil, value.count == 8 else { return nil }
            let y = value.prefix(4)
            let m = value.dropFirst(4).prefix(2)
            let d = value.dropFirst(6).prefix(2)
            return ("\(y)-\(m)-\(d)T00:00:00.000Z", true)
        } else {
            let basic = value.hasSuffix("Z") ? String(value.dropLast()) : value
            let f = DateFormatter()
            f.dateFormat = "yyyyMMdd'T'HHmmss"
            if value.hasSuffix("Z") {
                f.timeZone = TimeZone(identifier: "UTC")
            } else if let tzid, let z = TimeZone(identifier: tzid) {
                f.timeZone = z
            } else {
                // Floating datetime (no Z, no TZID): interpret in Hong Kong time (UTC+8).
                f.timeZone = TimeZone(identifier: "Asia/Hong_Kong")!
            }
            guard let d = f.date(from: basic) else { return nil }
            return (Clock.nowIso(d), false)
        }
    }
}

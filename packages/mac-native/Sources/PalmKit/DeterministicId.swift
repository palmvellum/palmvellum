import Foundation

/// Deterministic IDs for calendar subscriptions and imported ICS events.
///
/// Cross-device de-dup depends on every client computing the *same* ID for
/// the same feed/event. The PWA (`javaHashCode`), Android (`String.hashCode`)
/// and Go all use Java's 31-based UTF-16 string hash, so we must replicate it
/// exactly: iterate UTF-16 code units, multiply-add with Int32 overflow wrap.
public enum DeterministicId {
    public static func javaHash(_ s: String) -> Int32 {
        var h: Int32 = 0
        for u in s.utf16 {
            h = 31 &* h &+ Int32(u)
        }
        return h
    }

    /// Calendar-subscription record id — matches `"calsub" + abs(url.hashCode())`.
    public static func calsub(url: String) -> String {
        "calsub" + String(abs(Int(javaHash(url))))
    }

    /// Imported ICS event id — matches `"ics" + abs((url + "|" + key).hashCode())`.
    public static func ics(url: String, key: String) -> String {
        "ics" + String(abs(Int(javaHash(url + "|" + key))))
    }
}

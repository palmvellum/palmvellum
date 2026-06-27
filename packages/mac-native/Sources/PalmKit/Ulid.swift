import Foundation

/// Crockford Base32 ULID — 26 chars, 48-bit ms timestamp + 80 random bits.
/// Lexicographically sortable by creation time. Matches the ID scheme used
/// by the PWA (`src/lib/ulid.ts`), the Android app (`data/Ulid.kt`) and the
/// Go sync-cli so that client-generated IDs never collide across devices.
public enum Ulid {
    private static let enc = Array("0123456789ABCDEFGHJKMNPQRSTVWXYZ")

    public static func new(date: Date = Date()) -> String {
        let ms = UInt64((date.timeIntervalSince1970 * 1000).rounded())
        var chars = [Character](repeating: "0", count: 26)

        // 48-bit time → first 10 base32 chars (most-significant first).
        var t = ms
        var i = 9
        while i >= 0 {
            chars[i] = enc[Int(t & 0x1F)]
            t >>= 5
            i -= 1
        }
        // 80 bits of randomness → last 16 chars.
        for j in 10..<26 {
            chars[j] = enc[Int.random(in: 0..<32)]
        }
        return String(chars)
    }
}

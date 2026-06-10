package dev.tatliving.palmvellum.organizers.data

import java.security.SecureRandom

/**
 * ULID — Crockford base32, time-ordered. Same id scheme as the PWA
 * (packages/pwa/src/lib/ulid.ts) so locally-created rows interop with
 * the cloud once sync (P2) is enabled. Client-side, works fully offline.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun new(): String {
        val sb = StringBuilder(26)
        var time = System.currentTimeMillis()
        val timeChars = CharArray(10)
        for (i in 9 downTo 0) {
            timeChars[i] = ENCODING[(time and 0x1F).toInt()]
            time = time shr 5
        }
        sb.append(timeChars)
        repeat(16) { sb.append(ENCODING[random.nextInt(32)]) }
        return sb.toString()
    }
}

/** ISO-8601 UTC instant — the timestamp format used across all rows. */
object Clock {
    fun nowIso(): String = java.time.Instant.now().toString()
}

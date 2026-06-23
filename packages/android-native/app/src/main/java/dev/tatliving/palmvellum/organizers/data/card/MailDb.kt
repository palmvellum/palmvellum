package dev.tatliving.palmvellum.organizers.data.card

/**
 * Encode/decode PalmOS Mail's MailDB records — a Kotlin port of
 * `packages/palm-engine/maildb/maildb.go`. PalmVellum uses Mail one-way: cloud
 * "mail" digests are written into the Palm Inbox (category 0) to read on-device.
 *
 * Record layout (pilot-link mail.c):
 *   0-1 packed date ((year-1904)<<9 | month<<5 | day; 0 = none)
 *   2   hour
 *   3   minute
 *   4   flags (bit7 read, …)
 *   5   reserved
 *   6.. eight NUL-terminated fields in order: subject, from, to, cc, bcc,
 *       replyTo, sentTo, body (absent field = single 0x00).
 */

private const val MAIL_FLAG_READ = 0x80

class Mail(
    var uniqueId: Int = 0,
    var category: Int = 0,
    var year: Int = 0,
    var month: Int = 0,
    var day: Int = 0,
    var hour: Int = 0,
    var min: Int = 0,
    var read: Boolean = false,
    var subject: String = "",
    var from: String = "",
    var to: String = "",
    var cc: String = "",
    var bcc: String = "",
    var replyTo: String = "",
    var sentTo: String = "",
    var body: String = "",
)

object MailDb {

    private fun packDate(y: Int, m: Int, d: Int): Int {
        if (y < 1904 || m < 1 || d < 1) return 0
        return ((y - 1904) shl 9) or (m shl 5) or d
    }

    fun decode(db: PalmDb): List<Mail> = db.records.mapNotNull { r ->
        decodeOne(r.data)?.apply {
            uniqueId = r.uniqueId
            category = r.attributes and 0x0F
        }
    }

    private fun decodeOne(b: ByteArray): Mail? {
        if (b.size < 6) return null
        val m = Mail()
        val packed = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
        if (packed != 0) {
            m.year = (packed ushr 9) + 1904
            m.month = (packed ushr 5) and 0x0F
            m.day = packed and 0x1F
        }
        m.hour = b[2].toInt() and 0xFF
        m.min = b[3].toInt() and 0xFF
        m.read = (b[4].toInt() and MAIL_FLAG_READ) != 0
        var p = 6
        val fields = arrayOf<(String) -> Unit>(
            { m.subject = it }, { m.from = it }, { m.to = it }, { m.cc = it },
            { m.bcc = it }, { m.replyTo = it }, { m.sentTo = it }, { m.body = it },
        )
        for (set in fields) {
            if (p >= b.size) break
            if (b[p].toInt() == 0) { p++; continue }
            val (raw, n) = readCStr(b, p)
            set(PalmCharset.fromPalm(raw))
            p += n
        }
        return m
    }

    fun encode(mails: List<Mail>): MutableList<PalmRecord> = mails.map { m ->
        PalmRecord(uniqueId = m.uniqueId, attributes = m.category and 0x0F, data = encodeOne(m))
    }.toMutableList()

    private fun encodeOne(m: Mail): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val d = packDate(m.year, m.month, m.day)
        out.write(d ushr 8); out.write(d and 0xFF)
        out.write(m.hour and 0xFF); out.write(m.min and 0xFF)
        out.write(if (m.read) MAIL_FLAG_READ else 0)
        out.write(0) // reserved
        for (s in listOf(m.subject, m.from, m.to, m.cc, m.bcc, m.replyTo, m.sentTo, m.body)) {
            if (s.isEmpty()) { out.write(0); continue }
            out.write(PalmCharset.toPalm(s)); out.write(0)
        }
        return out.toByteArray()
    }

    /** Empty MailDB shell; [appInfo] (folder categories) reused verbatim, or default when empty. */
    fun newDb(appInfo: ByteArray): PalmDb = PalmDb(
        name = "MailDB",
        type = "DATA".toByteArray(Charsets.US_ASCII),
        creator = "mail".toByteArray(Charsets.US_ASCII),
        appInfo = if (appInfo.isNotEmpty()) appInfo else MemoAppInfo.default().encode(),
    )
}

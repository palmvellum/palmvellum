package dev.tatliving.palmvellum.organizers.data.card

/**
 * Encode/decode PalmOS Date Book's DatebookDB records — a Kotlin port of
 * `packages/palm-engine/datebookdb/datebookdb.go`.
 *
 * Record layout (pilot-link datebook.c):
 *   0   startHour (0xFF/0xFF start = untimed/all-day)
 *   1   startMin
 *   2   endHour
 *   3   endMin
 *   4-5 packed date ((year-1904)<<9 | month<<5 | day)
 *   6   flags (alarm 0x40, repeat 0x20, note 0x10, exceptions 0x08, desc 0x04)
 *   7   gap
 *   8.. optional blocks per flags: alarm(2), repeat(8), exceptions(2+2n),
 *       description(NUL-term), note(NUL-term)
 * Repeat/exception blocks are preserved verbatim for byte-exact round-trips.
 */

private const val DB_FLAG_ALARM = 0x40
private const val DB_FLAG_REPEAT = 0x20
private const val DB_FLAG_NOTE = 0x10
private const val DB_FLAG_EXCEPT = 0x08
private const val DB_FLAG_DESC = 0x04

class Appointment(
    var uniqueId: Int = 0,
    var untimed: Boolean = false,
    var startHour: Int = 0,
    var startMin: Int = 0,
    var endHour: Int = 0,
    var endMin: Int = 0,
    var year: Int = 0,
    var month: Int = 0,
    var day: Int = 0,
    var description: String = "",
    var note: String = "",
    var hasAlarm: Boolean = false,
    var alarmAdvance: Int = 0,
    var alarmUnit: Int = 0, // 0=minutes, 1=hours, 2=days
    var repeatRaw: ByteArray = ByteArray(0),
    var exceptionsRaw: ByteArray = ByteArray(0),
)

object DatebookDb {

    private fun packDate(y: Int, m: Int, d: Int): Int {
        if (y < 1904 || m < 1 || d < 1) return 0
        return ((y - 1904) shl 9) or (m shl 5) or d
    }

    private fun unpackDate(v: Int): Triple<Int, Int, Int> =
        Triple((v ushr 9) + 1904, (v ushr 5) and 0x0F, v and 0x1F)

    fun decode(db: PalmDb): List<Appointment> = db.records.mapNotNull { r ->
        decodeOne(r.data)?.apply { uniqueId = r.uniqueId }
    }

    private fun decodeOne(b: ByteArray): Appointment? {
        if (b.size < 8) return null
        val a = Appointment()
        a.startHour = b[0].toInt() and 0xFF
        a.startMin = b[1].toInt() and 0xFF
        a.endHour = b[2].toInt() and 0xFF
        a.endMin = b[3].toInt() and 0xFF
        a.untimed = (b[0].toInt() and 0xFF) == 0xFF && (b[1].toInt() and 0xFF) == 0xFF
        val (y, m, d) = unpackDate(((b[4].toInt() and 0xFF) shl 8) or (b[5].toInt() and 0xFF))
        a.year = y; a.month = m; a.day = d
        val flags = b[6].toInt() and 0xFF
        var p = 8
        if (flags and DB_FLAG_ALARM != 0) {
            if (p + 2 > b.size) return a
            a.hasAlarm = true
            a.alarmAdvance = b[p].toInt() and 0xFF
            a.alarmUnit = b[p + 1].toInt() and 0xFF
            p += 2
        }
        if (flags and DB_FLAG_REPEAT != 0) {
            if (p + 8 > b.size) return a
            a.repeatRaw = b.copyOfRange(p, p + 8)
            p += 8
        }
        if (flags and DB_FLAG_EXCEPT != 0) {
            if (p + 2 > b.size) return a
            val n = ((b[p].toInt() and 0xFF) shl 8) or (b[p + 1].toInt() and 0xFF)
            var end = p + 2 + 2 * n
            if (end > b.size) end = b.size
            a.exceptionsRaw = b.copyOfRange(p, end)
            p = end
        }
        if (flags and DB_FLAG_DESC != 0) {
            val (raw, n) = readCStr(b, p)
            a.description = PalmCharset.fromPalm(raw)
            p += n
        }
        if (flags and DB_FLAG_NOTE != 0) {
            val (raw, _) = readCStr(b, p)
            a.note = PalmCharset.fromPalm(raw)
        }
        return a
    }

    fun encode(appts: List<Appointment>): MutableList<PalmRecord> = appts.map { a ->
        PalmRecord(uniqueId = a.uniqueId, attributes = 0, data = encodeOne(a))
    }.toMutableList()

    private fun encodeOne(a: Appointment): ByteArray {
        var flags = 0
        if (a.hasAlarm) flags = flags or DB_FLAG_ALARM
        if (a.repeatRaw.size == 8) flags = flags or DB_FLAG_REPEAT
        if (a.exceptionsRaw.size >= 2) flags = flags or DB_FLAG_EXCEPT
        if (a.description.isNotEmpty()) flags = flags or DB_FLAG_DESC
        if (a.note.isNotEmpty()) flags = flags or DB_FLAG_NOTE

        val out = java.io.ByteArrayOutputStream()
        if (a.untimed) {
            out.write(0xFF); out.write(0xFF); out.write(0xFF); out.write(0xFF)
        } else {
            out.write(a.startHour and 0xFF); out.write(a.startMin and 0xFF)
            out.write(a.endHour and 0xFF); out.write(a.endMin and 0xFF)
        }
        val d = packDate(a.year, a.month, a.day)
        out.write(d ushr 8); out.write(d and 0xFF)
        out.write(flags)
        out.write(0) // gap

        if (a.hasAlarm) { out.write(a.alarmAdvance and 0xFF); out.write(a.alarmUnit and 0xFF) }
        if (a.repeatRaw.size == 8) out.write(a.repeatRaw)
        if (a.exceptionsRaw.size >= 2) out.write(a.exceptionsRaw)
        if (a.description.isNotEmpty()) { out.write(PalmCharset.toPalm(a.description)); out.write(0) }
        if (a.note.isNotEmpty()) { out.write(PalmCharset.toPalm(a.note)); out.write(0) }
        return out.toByteArray()
    }

    fun newDb(appInfo: ByteArray): PalmDb = PalmDb(
        name = "DatebookDB",
        type = "DATA".toByteArray(Charsets.US_ASCII),
        creator = "date".toByteArray(Charsets.US_ASCII),
        appInfo = appInfo,
    )
}

/** Read a NUL-terminated slice from [b] starting at [off]; returns (bytes, consumed). */
internal fun readCStr(b: ByteArray, off: Int): Pair<ByteArray, Int> {
    var i = off
    while (i < b.size && b[i].toInt() != 0) i++
    val raw = b.copyOfRange(off, i)
    return raw to (i - off + if (i < b.size) 1 else 0)
}

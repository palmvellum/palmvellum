package dev.tatliving.palmvellum.organizers.data.card

/**
 * Encode/decode PalmOS To Do List's ToDoDB records and AppInfo block — a Kotlin
 * port of `packages/palm-engine/tododb/tododb.go`.
 *
 * Each todo record:
 *   0   2  dueDate (packed Palm DateType, 0xFFFF = none)
 *            bits 0..4 day(1..31), bits 5..8 month(1..12), bits 9..15 year-1904
 *   2   1  priority — bit7 = completed, bits 0..6 = priority (1..5)
 *   3   *  description (NUL-terminated)
 *   ..  *  notes (NUL-terminated, optional)
 * Category index = record-entry attributes low nibble (same as memos).
 */

const val TODO_APPINFO_LEN = 282

/** A due date as a plain calendar triple (no time zone — Palm dates are date-only). */
data class PalmDate(val year: Int, val month: Int, val day: Int) {
    /** ISO yyyy-MM-dd, matching the cloud metadata format. */
    fun iso(): String = "%04d-%02d-%02d".format(year, month, day)

    companion object {
        fun fromIso(s: String): PalmDate? {
            val p = s.split("-")
            if (p.size != 3) return null
            val y = p[0].toIntOrNull() ?: return null
            val m = p[1].toIntOrNull() ?: return null
            val d = p[2].toIntOrNull() ?: return null
            return PalmDate(y, m, d)
        }
    }
}

class Todo(
    var uniqueId: Int = 0,
    var category: Int = 0,
    var dueDate: PalmDate? = null,
    var priority: Int = 0, // 1..5; clamped on encode
    var completed: Boolean = false,
    var description: String = "",
    var notes: String = "",
)

/** ToDo AppInfo reuses the 276-byte category block, preserving bytes 276.. verbatim. */
class TodoAppInfo(
    val categories: MemoAppInfo,
    var tail: ByteArray = ByteArray(0),
) {
    fun encode(): ByteArray {
        val base = categories.encode() // 280 bytes
        val out = ByteArray(TODO_APPINFO_LEN)
        base.copyInto(out, 0, 0, minOf(base.size, 280))
        if (tail.isNotEmpty()) {
            val copyLen = minOf(tail.size, TODO_APPINFO_LEN - 276)
            tail.copyInto(out, 276, 0, copyLen)
        }
        return out
    }

    companion object {
        fun parse(b: ByteArray): TodoAppInfo? {
            if (b.size < 276) return null
            val cats = MemoAppInfo.parse(b) ?: return null
            val tail = if (b.size > 276) b.copyOfRange(276, b.size) else ByteArray(0)
            return TodoAppInfo(cats, tail)
        }

        fun default(): TodoAppInfo = TodoAppInfo(MemoAppInfo.default(), ByteArray(TODO_APPINFO_LEN - 276))
    }
}

object ToDoDb {

    private fun decodeDate(d: Int): PalmDate? {
        if (d == 0xFFFF) return null
        val year = (d ushr 9) + 1904
        val month = (d ushr 5) and 0xF
        val day = d and 0x1F
        if (month == 0 || day == 0) return null
        return PalmDate(year, month, day)
    }

    private fun encodeDate(t: PalmDate?): Int {
        if (t == null) return 0xFFFF
        val y = t.year - 1904
        if (y < 0 || y > 127) return 0xFFFF
        return (y shl 9) or (t.month shl 5) or t.day
    }

    fun decodeRecord(data: ByteArray): Todo {
        require(data.size >= 4) { "tododb: short record ${data.size} bytes" }
        val t = Todo()
        t.dueDate = decodeDate(((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF))
        val prioByte = data[2].toInt() and 0xFF
        t.completed = prioByte and 0x80 != 0
        t.priority = prioByte and 0x7F

        var descEnd = 3
        while (descEnd < data.size && data[descEnd].toInt() != 0) descEnd++
        t.description = PalmCharset.fromPalm(data.copyOfRange(3, descEnd))

        if (descEnd + 1 < data.size) {
            val notesStart = descEnd + 1
            var notesEnd = notesStart
            while (notesEnd < data.size && data[notesEnd].toInt() != 0) notesEnd++
            t.notes = PalmCharset.fromPalm(data.copyOfRange(notesStart, notesEnd))
        }
        return t
    }

    fun encodeRecord(t: Todo): ByteArray {
        val due = encodeDate(t.dueDate)
        var prio = t.priority and 0x7F
        if (prio == 0) prio = 1 else if (prio > 5) prio = 5
        if (t.completed) prio = prio or 0x80
        val desc = PalmCharset.toPalm(t.description)
        val notes = if (t.notes.isNotEmpty()) PalmCharset.toPalm(t.notes) else ByteArray(0)
        val out = ByteArray(2 + 1 + desc.size + 1 + notes.size + 1)
        out[0] = (due ushr 8).toByte()
        out[1] = due.toByte()
        out[2] = prio.toByte()
        desc.copyInto(out, 3, 0, desc.size)
        // NUL after description already 0; notes follow, then trailing NUL (already 0).
        notes.copyInto(out, 3 + desc.size + 1, 0, notes.size)
        return out
    }

    fun decode(db: PalmDb): List<Todo> = db.records.map { r ->
        decodeRecord(r.data).apply {
            uniqueId = r.uniqueId
            category = r.attributes and 0x0F
        }
    }

    fun encode(todos: List<Todo>): MutableList<PalmRecord> = todos.map { t ->
        PalmRecord(uniqueId = t.uniqueId, attributes = t.category and 0x0F, data = encodeRecord(t))
    }.toMutableList()

    fun newDb(ai: TodoAppInfo): PalmDb = PalmDb(
        name = "ToDoDB",
        type = "DATA".toByteArray(Charsets.US_ASCII),
        creator = "todo".toByteArray(Charsets.US_ASCII),
        appInfo = ai.encode(),
    )
}

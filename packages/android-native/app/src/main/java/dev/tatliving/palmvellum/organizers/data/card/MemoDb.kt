package dev.tatliving.palmvellum.organizers.data.card

/**
 * Encode/decode PalmOS Memo Pad's MemoDB records and AppInfo block — a Kotlin
 * port of `packages/palm-engine/memodb/memodb.go`.
 *
 * Each memo record is a NUL-terminated text string. The category is the low
 * nibble of the record-entry attributes byte (0..15). The AppInfo block carries
 * the 16 category names + unique IDs.
 *
 * AppInfo layout (280 bytes):
 *   0      2      renamedCategories bitmask (UInt16)
 *   2      16*16  categoryLabels (NUL-padded)
 *   258    16     categoryUniqIDs (UInt8 each)
 *   274    1      lastUniqueID
 *   275    1      padding
 *   276    2      reserved
 *   278    2      sortByPriority
 */

const val MEMO_NUM_CATEGORIES = 16
const val MEMO_CATEGORY_NAME_LEN = 16
const val MEMO_APPINFO_LEN = 280

class MemoCategory(
    var name: String = "",
    var uniqId: Int = 0,
    var renamed: Boolean = false,
)

class MemoAppInfo {
    val categories: Array<MemoCategory> = Array(MEMO_NUM_CATEGORIES) { MemoCategory() }
    var lastUniqueId: Int = 0
    var sortByPriority: Boolean = false

    fun findByName(name: String): Int? {
        for (i in categories.indices) if (categories[i].name == name) return i
        return null
    }

    /** Ensure a category exists; returns its slot index, mutating if a slot is allocated. */
    fun ensureCategory(name: String): Int {
        findByName(name)?.let { return it }
        // Slots 1..14 are user slots (0 = Unfiled, 15 system-reserved).
        for (i in 1 until MEMO_NUM_CATEGORIES - 1) {
            if (categories[i].name.isEmpty()) {
                lastUniqueId++
                categories[i].name = name
                categories[i].uniqId = lastUniqueId
                categories[i].renamed = true
                return i
            }
        }
        return 0 // out of slots -> Unfiled
    }

    fun categoryName(idx: Int): String {
        if (idx < 0 || idx >= MEMO_NUM_CATEGORIES) return "Unfiled"
        val n = categories[idx].name
        return if (n.isEmpty()) "Unfiled" else n
    }

    fun encode(): ByteArray {
        val out = ByteArray(MEMO_APPINFO_LEN)
        var renamed = 0
        for (i in categories.indices) if (categories[i].renamed) renamed = renamed or (1 shl i)
        putU16(out, 0, renamed)
        for (i in categories.indices) {
            val off = 2 + i * MEMO_CATEGORY_NAME_LEN
            var name = PalmCharset.toPalm(categories[i].name)
            if (name.size > MEMO_CATEGORY_NAME_LEN - 1) name = name.copyOf(MEMO_CATEGORY_NAME_LEN - 1)
            name.copyInto(out, off, 0, name.size)
            out[2 + MEMO_NUM_CATEGORIES * MEMO_CATEGORY_NAME_LEN + i] = categories[i].uniqId.toByte()
        }
        out[2 + MEMO_NUM_CATEGORIES * MEMO_CATEGORY_NAME_LEN + MEMO_NUM_CATEGORIES] = lastUniqueId.toByte()
        if (sortByPriority) putU16(out, 278, 1)
        return out
    }

    companion object {
        fun parse(b: ByteArray): MemoAppInfo? {
            if (b.size < 276) return null
            val ai = MemoAppInfo()
            val renamed = u16(b, 0)
            for (i in 0 until MEMO_NUM_CATEGORIES) {
                val off = 2 + i * MEMO_CATEGORY_NAME_LEN
                val end = off + MEMO_CATEGORY_NAME_LEN
                var nameEnd = off
                while (nameEnd < end && b[nameEnd].toInt() != 0) nameEnd++
                ai.categories[i].name = PalmCharset.fromPalm(b.copyOfRange(off, nameEnd))
                ai.categories[i].uniqId = b[2 + MEMO_NUM_CATEGORIES * MEMO_CATEGORY_NAME_LEN + i].toInt() and 0xFF
                ai.categories[i].renamed = (renamed and (1 shl i)) != 0
            }
            ai.lastUniqueId = b[2 + MEMO_NUM_CATEGORIES * MEMO_CATEGORY_NAME_LEN + MEMO_NUM_CATEGORIES].toInt() and 0xFF
            if (b.size >= 280) ai.sortByPriority = u16(b, 278) != 0
            return ai
        }

        /** PalmOS factory categories: Unfiled / Personal / Business. */
        fun default(): MemoAppInfo = MemoAppInfo().apply {
            categories[0] = MemoCategory("Unfiled", 0)
            categories[1] = MemoCategory("Personal", 1)
            categories[2] = MemoCategory("Business", 2)
            lastUniqueId = 15
        }

        private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
        private fun putU16(b: ByteArray, o: Int, v: Int) { b[o] = (v ushr 8).toByte(); b[o + 1] = v.toByte() }
    }
}

class Memo(
    var uniqueId: Int = 0,
    var category: Int = 0,
    var text: String = "",
)

object MemoDb {

    /** Decode all memo records from a parsed MemoDB. */
    fun decode(db: PalmDb): List<Memo> = db.records.map { r ->
        var txt = r.data
        var end = txt.size
        while (end > 0 && txt[end - 1].toInt() == 0) end--
        Memo(
            uniqueId = r.uniqueId,
            category = r.attributes and 0x0F,
            text = PalmCharset.fromPalm(txt.copyOfRange(0, end)),
        )
    }

    /** Turn memos into record entries: category in the low nibble, NUL-terminated bytes. */
    fun encode(memos: List<Memo>): MutableList<PalmRecord> = memos.map { m ->
        val enc = PalmCharset.toPalm(m.text)
        val data = ByteArray(enc.size + 1)
        enc.copyInto(data, 0, 0, enc.size) // trailing byte stays 0 (NUL terminator)
        PalmRecord(uniqueId = m.uniqueId, attributes = m.category and 0x0F, data = data)
    }.toMutableList()

    /** Empty MemoDB skeleton with the standard name/type/creator + encoded AppInfo. */
    fun newDb(ai: MemoAppInfo): PalmDb = PalmDb(
        name = "MemoDB",
        type = "DATA".toByteArray(Charsets.US_ASCII),
        creator = "memo".toByteArray(Charsets.US_ASCII),
        appInfo = ai.encode(),
    )
}

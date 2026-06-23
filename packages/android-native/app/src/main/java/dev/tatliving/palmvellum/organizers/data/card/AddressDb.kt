package dev.tatliving.palmvellum.organizers.data.card

/**
 * Encode/decode PalmOS Address Book's AddressDB records — a Kotlin port of
 * `packages/palm-engine/addressdb/addressdb.go`.
 *
 * Record layout (pilot-link address.c):
 *   0-3 phoneflag = pl0..pl4 (4 bits each) | showPhone<<20
 *   4-7 contents bitmask (bit v ⇒ entry[v] present; 19 fields)
 *   8   company string offset (for list sort)
 *   9.. present entries, index order 0..18, each NUL-terminated
 * Field indices: 0 last,1 first,2 company,3..7 phone1..5,8 address,9 city,
 *   10 state,11 zip,12 country,13 title,14..17 custom1..4,18 note.
 */

private const val ADDR_NUM_ENTRIES = 19
private const val E_LAST = 0
private const val E_FIRST = 1
private const val E_COMPANY = 2
private const val E_PHONE1 = 3
private const val E_ADDRESS = 8
private const val E_CITY = 9
private const val E_STATE = 10
private const val E_ZIP = 11
private const val E_COUNTRY = 12
private const val E_TITLE = 13
private const val E_CUSTOM1 = 14
private const val E_NOTE = 18

/** AddressDB AppInfo size: 278 category block + label table + tail. */
const val ADDR_APPINFO_LEN = 638

private val PHONE_LABEL_NAMES = arrayOf("Work", "Home", "Fax", "Other", "E-mail", "Main", "Pager", "Mobile")

private fun phoneLabelIndex(name: String): Int {
    val i = PHONE_LABEL_NAMES.indexOf(name)
    return if (i >= 0) i else 0
}

class AddressPhone(var label: String = "", var value: String = "")

class Contact(
    var uniqueId: Int = 0,
    var category: Int = 0,
    var last: String = "",
    var first: String = "",
    var company: String = "",
    var title: String = "",
    var phones: MutableList<AddressPhone> = mutableListOf(), // up to 5, slot order
    var showPhone: Int = 0,
    var address: String = "",
    var city: String = "",
    var state: String = "",
    var zip: String = "",
    var country: String = "",
    var note: String = "",
    var custom: Array<String> = arrayOf("", "", "", ""),
) {
    fun displayName(): String = when {
        last.isNotEmpty() && first.isNotEmpty() -> "$first $last"
        last.isNotEmpty() -> last
        first.isNotEmpty() -> first
        company.isNotEmpty() -> company
        else -> "(no name)"
    }
}

object AddressDb {

    fun decode(db: PalmDb): List<Contact> = db.records.mapNotNull { r ->
        decodeOne(r.data)?.apply {
            uniqueId = r.uniqueId
            category = r.attributes and 0x0F
        }
    }

    private fun decodeOne(b: ByteArray): Contact? {
        if (b.size < 9) return null
        val c = Contact()
        val phoneflag = u32(b, 0)
        val pl = intArrayOf(
            ((phoneflag ushr 0) and 0x0F).toInt(),
            ((phoneflag ushr 4) and 0x0F).toInt(),
            ((phoneflag ushr 8) and 0x0F).toInt(),
            ((phoneflag ushr 12) and 0x0F).toInt(),
            ((phoneflag ushr 16) and 0x0F).toInt(),
        )
        c.showPhone = ((phoneflag ushr 20) and 0x0F).toInt()
        val contents = u32(b, 4)

        val entries = Array(ADDR_NUM_ENTRIES) { "" }
        var p = 9
        for (v in 0 until ADDR_NUM_ENTRIES) {
            if (contents and (1L shl v) == 0L) continue
            val (raw, n) = readCStr(b, p)
            entries[v] = PalmCharset.fromPalm(raw)
            p += n
            if (p > b.size) p = b.size
        }

        c.last = entries[E_LAST]; c.first = entries[E_FIRST]; c.company = entries[E_COMPANY]
        c.address = entries[E_ADDRESS]; c.city = entries[E_CITY]; c.state = entries[E_STATE]
        c.zip = entries[E_ZIP]; c.country = entries[E_COUNTRY]; c.title = entries[E_TITLE]
        c.note = entries[E_NOTE]
        c.custom = arrayOf(entries[E_CUSTOM1], entries[E_CUSTOM1 + 1], entries[E_CUSTOM1 + 2], entries[E_CUSTOM1 + 3])
        for (slot in 0 until 5) {
            val value = entries[E_PHONE1 + slot]
            if (value.isEmpty()) continue
            c.phones.add(AddressPhone(PHONE_LABEL_NAMES[pl[slot]], value))
        }
        return c
    }

    fun encode(contacts: List<Contact>): MutableList<PalmRecord> = contacts.map { c ->
        PalmRecord(uniqueId = c.uniqueId, attributes = c.category and 0x0F, data = encodeOne(c))
    }.toMutableList()

    private fun encodeOne(c: Contact): ByteArray {
        val entries = arrayOfNulls<ByteArray>(ADDR_NUM_ENTRIES)
        fun set(i: Int, s: String) { if (s.isNotEmpty()) entries[i] = PalmCharset.toPalm(s) }
        set(E_LAST, c.last); set(E_FIRST, c.first); set(E_COMPANY, c.company)
        set(E_ADDRESS, c.address); set(E_CITY, c.city); set(E_STATE, c.state)
        set(E_ZIP, c.zip); set(E_COUNTRY, c.country); set(E_TITLE, c.title); set(E_NOTE, c.note)
        for (i in 0 until 4) set(E_CUSTOM1 + i, c.custom.getOrElse(i) { "" })

        val pl = IntArray(5)
        var slot = 0
        while (slot < 5 && slot < c.phones.size) {
            pl[slot] = phoneLabelIndex(c.phones[slot].label)
            set(E_PHONE1 + slot, c.phones[slot].value)
            slot++
        }

        var contents = 0L
        for (v in 0 until ADDR_NUM_ENTRIES) if (entries[v] != null) contents = contents or (1L shl v)

        val phoneflag = (pl[0].toLong()) or (pl[1].toLong() shl 4) or (pl[2].toLong() shl 8) or
            (pl[3].toLong() shl 12) or (pl[4].toLong() shl 16) or (c.showPhone.toLong() shl 20)

        val head = ByteArray(9)
        putU32(head, 0, phoneflag)
        putU32(head, 4, contents)
        val body = java.io.ByteArrayOutputStream()
        var companyOffset = 0
        var pos = 9
        for (v in 0 until ADDR_NUM_ENTRIES) {
            val e = entries[v] ?: continue
            if (v == E_COMPANY) companyOffset = pos - 8
            body.write(e); body.write(0)
            pos += e.size + 1
        }
        head[8] = companyOffset.toByte()
        return head + body.toByteArray()
    }

    /** Stock 638-byte AppInfo (categories + 22 field labels); used only when the card has none. */
    fun defaultAppInfo(): ByteArray {
        val buf = ByteArray(ADDR_APPINFO_LEN)
        val mem = MemoAppInfo.default()
        mem.categories[1] = MemoCategory("Business", 1, true)
        mem.categories[2] = MemoCategory("Personal", 2, true)
        mem.categories[3] = MemoCategory("QuickList", 3, true)
        mem.encode().copyInto(buf, 0, 0, 278)
        for (i in STD_LABELS.indices) {
            val off = 282 + i * 16
            val l = STD_LABELS[i].toByteArray(Charsets.US_ASCII)
            l.copyInto(buf, off, 0, minOf(l.size, 15))
        }
        return buf
    }

    /**
     * Empty AddressDB shell. The card's own AppInfo (638 bytes, with the 22
     * field labels) is reused verbatim; anything shorter is rebuilt from stock
     * so the Palm restore won't fault (a 280-byte memo-shaped block crashes it).
     */
    fun newDb(appInfo: ByteArray): PalmDb {
        val ai = if (appInfo.size < ADDR_APPINFO_LEN) defaultAppInfo() else appInfo
        return PalmDb(
            name = "AddressDB",
            type = "DATA".toByteArray(Charsets.US_ASCII),
            creator = "addr".toByteArray(Charsets.US_ASCII),
            appInfo = ai,
        )
    }

    private val STD_LABELS = arrayOf(
        "Last name", "First name", "Company", "Work", "Home", "Fax", "Other",
        "E-mail", "Address", "City", "State", "Zip Code", "Country", "Title",
        "Custom 1", "Custom 2", "Custom 3", "Custom 4", "Note", "Main", "Pager", "Mobile",
    )

    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 0xFF) shl 24) or ((b[o + 1].toLong() and 0xFF) shl 16) or
            ((b[o + 2].toLong() and 0xFF) shl 8) or (b[o + 3].toLong() and 0xFF)

    private fun putU32(b: ByteArray, o: Int, v: Long) {
        b[o] = (v ushr 24).toByte(); b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte(); b[o + 3] = v.toByte()
    }
}

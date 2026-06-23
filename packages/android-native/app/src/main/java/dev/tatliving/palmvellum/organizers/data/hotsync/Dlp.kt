package dev.tatliving.palmvellum.organizers.data.hotsync

import java.io.ByteArrayOutputStream

/**
 * DLP — Desktop Link Protocol. The command layer of HotSync, carried over
 * PADP. The host issues requests; the device replies with a matching response
 * carrying an error code and zero or more arguments.
 *
 * Request:  [cmd][argc] then argc args.
 * Response: [cmd|0x80][argc][error:2 BE] then argc args.
 *
 * Argument wire format:
 *   tiny  : id in 0x00..0x7F      -> [id][len:1][data]
 *   short : id with bit 0x80 set  -> [id&0x3F | 0x80][pad:1][len:2 BE][data]
 *
 * Command/arg layouts follow the classic PalmOS DLP 1.x contract as
 * implemented by pilot-link / coldsync. This stack has not yet been validated
 * against physical hardware (see docs/cross-platform-desktop-sync-feasibility.md);
 * the magic numbers are the documented ones and are the first thing to check if
 * a real device misbehaves.
 */
class Dlp(private val transport: DlpTransport) {

    class Arg(val id: Int, val data: ByteArray)
    class Response(val cmd: Int, val error: Int, val args: List<Arg>) {
        val ok: Boolean get() = error == 0
        fun arg(id: Int): Arg? = args.firstOrNull { it.id == id }
        fun firstArg(): Arg? = args.firstOrNull()
    }

    /** Issue a request and return the parsed response (which may carry error != 0). */
    fun exec(cmd: Int, args: List<Arg> = emptyList()): Response {
        val req = ByteArrayOutputStream()
        req.write(cmd)
        req.write(args.size)
        for (a in args) writeArg(req, a)
        transport.send(req.toByteArray())
        return parseResponse(transport.receive())
    }

    /** Like [exec] but throws on a non-zero DLP error. */
    fun execOk(cmd: Int, args: List<Arg> = emptyList()): Response {
        val r = exec(cmd, args)
        if (!r.ok) throw HotSyncException("DLP cmd 0x${cmd.toString(16)} failed: error ${r.error}")
        return r
    }

    private fun writeArg(out: ByteArrayOutputStream, a: Arg) {
        if (a.data.size <= 0xFF) { // tiny
            out.write(a.id and 0x3F)
            out.write(a.data.size)
            out.write(a.data)
        } else { // short
            out.write((a.id and 0x3F) or 0x80)
            out.write(0)
            out.write(a.data.size ushr 8)
            out.write(a.data.size and 0xFF)
            out.write(a.data)
        }
    }

    private fun parseResponse(b: ByteArray): Response {
        if (b.size < 4) throw HotSyncException("DLP: short response ${b.size} bytes")
        val cmd = (b[0].toInt() and 0xFF) and 0x7F
        val argc = b[1].toInt() and 0xFF
        val error = ((b[2].toInt() and 0xFF) shl 8) or (b[3].toInt() and 0xFF)
        val args = ArrayList<Arg>(argc)
        var p = 4
        repeat(argc) {
            if (p >= b.size) return@repeat
            val first = b[p].toInt() and 0xFF
            if (first and 0x80 == 0) { // tiny
                val id = first and 0x3F
                val len = b[p + 1].toInt() and 0xFF
                val data = b.copyOfRange(p + 2, minOf(p + 2 + len, b.size))
                args.add(Arg(id, data))
                p += 2 + len
            } else if (first and 0xC0 == 0x80) { // short
                val id = first and 0x3F
                val len = ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)
                val data = b.copyOfRange(p + 4, minOf(p + 4 + len, b.size))
                args.add(Arg(id, data))
                p += 4 + len
            } else { // long (0xC0): id + 4-byte len
                val id = first and 0x3F
                val len = ((b[p + 2].toInt() and 0xFF) shl 24) or ((b[p + 3].toInt() and 0xFF) shl 16) or
                    ((b[p + 4].toInt() and 0xFF) shl 8) or (b[p + 5].toInt() and 0xFF)
                val data = b.copyOfRange(p + 6, minOf(p + 6 + len, b.size))
                args.add(Arg(id, data))
                p += 6 + len
            }
        }
        return Response(cmd, error, args)
    }

    // ─── Commands ──────────────────────────────────────────────────────────

    /** A record pulled by index. [category] is 0..15; [attributes] is the DLP record flags byte. */
    class DlpRecord(val recordId: Int, val attributes: Int, val category: Int, val data: ByteArray)

    fun readUserInfo() = execOk(CMD_READ_USER_INFO)

    /** ReadSysInfo carries the host DLP version (1.4, per pilot-link) as its only arg. */
    fun readSysInfo() = exec(CMD_READ_SYS_INFO, listOf(Arg(0x20, byteArrayOf(0x01, 0x40))))

    fun openConduit() = execOk(CMD_OPEN_CONDUIT)

    /** Open a database by name; returns its handle. [mode] OR-combines OPEN_* flags. */
    fun openDb(name: String, mode: Int): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(2 + nameBytes.size + 1)
        data[0] = 0 // card 0
        data[1] = mode.toByte()
        nameBytes.copyInto(data, 2, 0, nameBytes.size)
        // trailing NUL already 0
        val r = exec(CMD_OPEN_DB, listOf(Arg(0x20, data)))
        if (!r.ok) throw HotSyncException("openDb($name) failed: error ${r.error}")
        val handle = r.firstArg()?.data?.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw HotSyncException("openDb($name): no handle in response")
        return handle
    }

    fun closeDb(handle: Int) {
        runCatching { execOk(CMD_CLOSE_DB, listOf(Arg(0x20, byteArrayOf(handle.toByte())))) }
    }

    /** Delete a database by name (best-effort: a missing DB is fine). */
    fun deleteDb(name: String) {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(2 + nameBytes.size + 1)
        // data[0] = cardNo 0, data[1] = padding 0, then NUL-terminated name.
        nameBytes.copyInto(data, 2)
        runCatching { exec(CMD_DELETE_DB, listOf(Arg(0x20, data))) }
    }

    /**
     * Create a database on card 0 and return its handle. [creator]/[type] are
     * 4-byte tags, [flags] the database attribute word, [version] the DB version.
     */
    fun createDb(name: String, creator: ByteArray, type: ByteArray, flags: Int, version: Int): Int {
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(tag4(creator))
        out.write(tag4(type))
        out.write(0) // cardNo
        out.write(0) // padding1
        out.write(flags ushr 8); out.write(flags and 0xFF)       // dbFlags (BE)
        out.write(version ushr 8); out.write(version and 0xFF)   // version (BE)
        out.write(nameBytes); out.write(0)                       // NUL-terminated name
        val r = exec(CMD_CREATE_DB, listOf(Arg(0x20, out.toByteArray())))
        if (!r.ok) throw HotSyncException("createDb($name) failed: error ${r.error}")
        return r.firstArg()?.data?.firstOrNull()?.toInt()?.and(0xFF)
            ?: throw HotSyncException("createDb($name): no handle in response")
    }

    /** Write one resource (.prc) into an open database. */
    fun writeResource(handle: Int, type: ByteArray, id: Int, data: ByteArray) {
        val out = ByteArrayOutputStream()
        out.write(handle)
        out.write(0) // padding1
        out.write(tag4(type))
        out.write(id ushr 8); out.write(id and 0xFF)            // resource id (BE)
        out.write(data.size ushr 8); out.write(data.size and 0xFF) // data length (BE)
        out.write(data)
        execOk(CMD_WRITE_RESOURCE, listOf(Arg(0x20, out.toByteArray())))
    }

    /** Coerce a tag to exactly 4 bytes (space-padded / truncated). */
    private fun tag4(t: ByteArray): ByteArray =
        if (t.size == 4) t else ByteArray(4) { i -> if (i < t.size) t[i] else ' '.code.toByte() }

    /**
     * Number of records in an open database (ReadOpenDBInfo, DLP 1.0 — supported
     * on every Palm OS). Returns -1 if the device won't report it, signalling the
     * caller to fall back to probing by index. Reading exactly this many records
     * avoids a past-the-end ReadRecordByIndex, which some databases answer with
     * silence (a USB read timeout) rather than a clean notFound error.
     */
    fun readOpenDbInfo(handle: Int): Int {
        val r = exec(CMD_READ_OPEN_DB_INFO, listOf(Arg(0x20, byteArrayOf(handle.toByte()))))
        if (!r.ok) return -1
        val d = r.firstArg()?.data ?: return -1
        if (d.size < 2) return -1
        return ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
    }

    /** Read the AppInfo block (categories etc.), or null if the DB has none. */
    fun readAppBlock(handle: Int): ByteArray? {
        val req = byteArrayOf(handle.toByte(), 0, 0, 0, 0xFF.toByte(), 0xFF.toByte())
        val r = exec(CMD_READ_APP_BLOCK, listOf(Arg(0x20, req)))
        if (!r.ok) return null
        val d = r.firstArg()?.data ?: return null
        // Layout: [size:2][data]. Tolerate a missing prefix.
        return if (d.size > 2) d.copyOfRange(2, d.size) else null
    }

    /** Read the record at [index], or null when the index is past the end. */
    fun readRecordByIndex(handle: Int, index: Int): DlpRecord? {
        val req = ByteArray(8)
        req[0] = handle.toByte()
        req[1] = 0
        req[2] = (index ushr 8).toByte()
        req[3] = index.toByte()
        // offset 4-5 = 0; length 6-7 = 0xFFFF (whole record)
        req[6] = 0xFF.toByte()
        req[7] = 0xFF.toByte()
        val r = exec(CMD_READ_RECORD, listOf(Arg(0x21, req)))
        if (!r.ok) return null // notFound -> past last record
        val d = r.firstArg()?.data ?: return null
        if (d.size < 10) return null
        val recordId = ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or
            ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        // d[4-5] = index, d[6-7] = size
        val attributes = d[8].toInt() and 0xFF
        val category = d[9].toInt() and 0x0F
        val data = d.copyOfRange(10, d.size)
        return DlpRecord(recordId, attributes, category, data)
    }

    /** Write a record; returns the (possibly newly assigned) record id. */
    fun writeRecord(handle: Int, recordId: Int, attributes: Int, category: Int, data: ByteArray): Int {
        val buf = ByteArray(8 + data.size)
        buf[0] = handle.toByte()
        buf[1] = 0x80.toByte() // mandatory marker
        buf[2] = (recordId ushr 24).toByte()
        buf[3] = (recordId ushr 16).toByte()
        buf[4] = (recordId ushr 8).toByte()
        buf[5] = recordId.toByte()
        buf[6] = attributes.toByte()
        buf[7] = (category and 0x0F).toByte()
        data.copyInto(buf, 8, 0, data.size)
        val r = execOk(CMD_WRITE_RECORD, listOf(Arg(0x20, buf)))
        val d = r.firstArg()?.data
        return if (d != null && d.size >= 4) {
            ((d[0].toInt() and 0xFF) shl 24) or ((d[1].toInt() and 0xFF) shl 16) or
                ((d[2].toInt() and 0xFF) shl 8) or (d[3].toInt() and 0xFF)
        } else recordId
    }

    /** Overwrite the AppInfo block (categories etc.). Best-effort: errors are swallowed. */
    fun writeAppBlock(handle: Int, block: ByteArray) {
        val buf = ByteArray(4 + block.size)
        buf[0] = handle.toByte()
        buf[1] = 0 // reserved
        buf[2] = (block.size ushr 8).toByte()
        buf[3] = block.size.toByte()
        block.copyInto(buf, 4, 0, block.size)
        runCatching { execOk(CMD_WRITE_APP_BLOCK, listOf(Arg(0x20, buf))) }
    }

    fun resetSyncFlags(handle: Int) {
        runCatching { execOk(CMD_RESET_SYNC_FLAGS, listOf(Arg(0x20, byteArrayOf(handle.toByte())))) }
    }

    fun addSyncLogEntry(text: String) {
        val t = text.toByteArray(Charsets.US_ASCII)
        val data = ByteArray(t.size + 1)
        t.copyInto(data, 0, 0, t.size)
        runCatching { execOk(CMD_ADD_SYNC_LOG, listOf(Arg(0x20, data))) }
    }

    fun endOfSync(status: Int = 0) {
        runCatching {
            execOk(CMD_END_OF_SYNC, listOf(Arg(0x20, byteArrayOf((status ushr 8).toByte(), status.toByte()))))
        }
    }

    companion object {
        // Open modes (combine with OR).
        const val OPEN_READ = 0x80
        const val OPEN_WRITE = 0x40
        const val OPEN_EXCLUSIVE = 0x20
        const val OPEN_SECRET = 0x10

        // DLP command ids.
        private const val CMD_READ_USER_INFO = 0x10
        private const val CMD_READ_SYS_INFO = 0x12
        private const val CMD_OPEN_DB = 0x17
        private const val CMD_CREATE_DB = 0x18
        private const val CMD_CLOSE_DB = 0x19
        private const val CMD_DELETE_DB = 0x1A
        private const val CMD_READ_APP_BLOCK = 0x1B
        private const val CMD_WRITE_APP_BLOCK = 0x1C
        private const val CMD_READ_RECORD = 0x20
        private const val CMD_WRITE_RECORD = 0x21
        private const val CMD_WRITE_RESOURCE = 0x24
        private const val CMD_RESET_SYNC_FLAGS = 0x27
        private const val CMD_ADD_SYNC_LOG = 0x2A
        private const val CMD_READ_OPEN_DB_INFO = 0x2B
        private const val CMD_OPEN_CONDUIT = 0x2E
        private const val CMD_END_OF_SYNC = 0x2F
    }
}

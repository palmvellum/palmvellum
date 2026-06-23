package dev.tatliving.palmvellum.organizers.data.hotsync

import dev.tatliving.palmvellum.organizers.data.card.PalmDb
import dev.tatliving.palmvellum.organizers.data.card.PalmInstallParser
import dev.tatliving.palmvellum.organizers.data.card.PalmRecord

/**
 * One live HotSync session over a [PalmTransport]: the CMP handshake, then
 * database read/write via DLP. Higher layers (the conduit) drive the cloud
 * merge between [readDb] and [writeBack].
 *
 * A session is single-use and not thread-safe. Run it off the main thread.
 */
class HotSyncSession(transport: PalmTransport, private val stack: UsbStack = UsbStack.NET_SYNC) {

    // The DLP datagram transport depends on the device's protocol stack:
    // NetSync (most USB Palms) or the serial SLP/PADP/CMP stack (a few).
    private val dlpTransport: DlpTransport = when (stack) {
        UsbStack.NET_SYNC -> NetSyncStream(transport)
        UsbStack.SERIAL -> PadpStream(SlpFramer(transport))
    }
    private val dlp = Dlp(dlpTransport)

    /** A database as read off the device: records (deleted ones filtered) + AppInfo. */
    class DeviceDb(val name: String, val appInfo: ByteArray?, val records: List<PalmRecord>)

    /** Opening handshake (per stack), then the standard session-start DLP calls. */
    fun open() {
        when (val t = dlpTransport) {
            is NetSyncStream -> t.doHandshake()
            is PadpStream -> Cmp.handshake(t)
        }
        // Session-start niceties — tolerated if a device is fussy; the real work
        // is the DB read/write below, which surfaces its own errors.
        runCatching { dlp.readSysInfo() }
        runCatching { dlp.readUserInfo() }
        runCatching { dlp.openConduit() }
    }

    /**
     * Read every live record of [name] into a [DeviceDb], or null if it can't be
     * opened. Follows palm-sync's proven order: open, ask how many records the DB
     * holds (ReadOpenDBInfo), read the AppInfo block, then read exactly that many
     * records — never probing past the end, which can hang on some databases.
     * [trace] (optional) reports each step so a stall can be localised precisely.
     */
    fun readDb(name: String, trace: ((String) -> Unit)? = null): DeviceDb? {
        val handle = runCatching { dlp.openDb(name, Dlp.OPEN_READ or Dlp.OPEN_SECRET) }.getOrNull()
            ?: return null
        trace?.invoke("$name: opened (handle=$handle)")
        try {
            val count = runCatching { dlp.readOpenDbInfo(handle) }.getOrDefault(-1)
            trace?.invoke(if (count >= 0) "$name: $count record(s) reported" else "$name: record count unavailable, probing")
            val appInfo = dlp.readAppBlock(handle)
            trace?.invoke("$name: appInfo ${appInfo?.let { "${it.size} bytes" } ?: "none"}")
            val records = ArrayList<PalmRecord>()
            fun keep(rec: Dlp.DlpRecord) {
                // Skip records the device has marked deleted or archived.
                if (rec.attributes and (ATTR_DELETED or ATTR_ARCHIVED) != 0) return
                records.add(
                    PalmRecord(
                        uniqueId = rec.recordId and 0xFFFFFF,
                        attributes = rec.category and 0x0F,
                        data = rec.data,
                    ),
                )
            }
            if (count >= 0) {
                for (index in 0 until count) {
                    trace?.invoke("$name: reading record ${index + 1}/$count")
                    val rec = dlp.readRecordByIndex(handle, index) ?: continue
                    keep(rec)
                }
            } else {
                // Fallback for a device that won't report its record count: probe
                // by index until a notFound error breaks the loop (legacy path).
                var index = 0
                while (true) {
                    trace?.invoke("$name: probing record ${index + 1}")
                    val rec = dlp.readRecordByIndex(handle, index) ?: break
                    index++
                    keep(rec)
                }
            }
            return DeviceDb(name, appInfo, records)
        } finally {
            dlp.closeDb(handle)
        }
    }

    /**
     * Write [records] back into [name], opening read-write. If [appInfo] is
     * given it is written first (so cloud-created categories get names). Each
     * record is written by its unique id (create-or-update); sync flags are then
     * cleared. Records removed in the cloud are NOT deleted here (v1 is
     * last-write-wins additive — see the feasibility doc).
     */
    fun writeBack(name: String, records: List<PalmRecord>, appInfo: ByteArray?) {
        val handle = dlp.openDb(name, Dlp.OPEN_READ or Dlp.OPEN_WRITE or Dlp.OPEN_SECRET)
        try {
            if (appInfo != null) dlp.writeAppBlock(handle, appInfo)
            for (r in records) {
                dlp.writeRecord(
                    handle = handle,
                    recordId = r.uniqueId,
                    attributes = 0,
                    category = r.attributes and 0x0F,
                    data = r.data,
                )
            }
            dlp.resetSyncFlags(handle)
        } finally {
            dlp.closeDb(handle)
        }
    }

    /** Build a [PalmDb] view of a [DeviceDb] for the codecs that expect one. */
    fun asPalmDb(db: DeviceDb, creator: String): PalmDb = PalmDb(
        name = db.name,
        type = "DATA".toByteArray(Charsets.US_ASCII),
        creator = creator.toByteArray(Charsets.US_ASCII),
        appInfo = db.appInfo ?: ByteArray(0),
        records = db.records.toMutableList(),
    )

    /**
     * True while the underlying datagram stream is still responding. After a
     * NetSync read times out the link is desynced and considered dead; the
     * conduit checks this to skip its remaining databases instead of hanging on
     * each one. The serial stack has no such signal, so it always reports alive.
     */
    fun linkAlive(): Boolean = (dlpTransport as? NetSyncStream)?.dead != true

    /**
     * Install a `.prc`/`.pdb` file onto the device: delete any existing database
     * of the same name, create it, write its AppInfo, then write every resource
     * (PRC) or record (PDB). Returns the installed database name; throws on
     * failure. Reports progress via [log].
     */
    fun installFile(bytes: ByteArray, log: (String) -> Unit): String {
        val f = PalmInstallParser.parse(bytes)
        val kind = if (f.isResource) "prc" else "pdb"
        log("Installing ${f.name} ($kind, ${f.entryCount} ${if (f.isResource) "resource" else "record"}(s))")
        dlp.deleteDb(f.name)
        val handle = dlp.createDb(f.name, f.creator, f.type, f.attributes, f.version)
        try {
            f.appInfo?.takeIf { it.isNotEmpty() }?.let { dlp.writeAppBlock(handle, it) }
            if (f.isResource) {
                f.resources.forEachIndexed { i, r ->
                    log("${f.name}: resource ${i + 1}/${f.resources.size}")
                    dlp.writeResource(handle, r.type, r.id, r.data)
                }
            } else {
                val recs = f.pdb!!.records
                recs.forEachIndexed { i, r ->
                    log("${f.name}: record ${i + 1}/${recs.size}")
                    // Keep the 'secret' flag + category; drop transient dirty/busy/delete bits.
                    dlp.writeRecord(handle, r.uniqueId, r.attributes and 0x10, r.attributes and 0x0F, r.data)
                }
            }
        } finally {
            dlp.closeDb(handle)
        }
        return f.name
    }

    fun log(text: String) = dlp.addSyncLogEntry(text)

    fun finish() = dlp.endOfSync(0)

    companion object {
        private const val ATTR_DELETED = 0x80
        private const val ATTR_ARCHIVED = 0x08
    }
}

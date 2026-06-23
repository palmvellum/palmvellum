package dev.tatliving.palmvellum.organizers.data.hotsync

/**
 * PADP — Packet Assembly/Disassembly Protocol, carried in SLP type=2 frames.
 * Provides reliable, fragmented, ACKed delivery for the DLP conversation above.
 *
 * PADP header (4 bytes):
 *   0  type  (1=DATA, 2=ACK, 4=TICKLE, 8=ABORT)
 *   1  flags (0x80=FIRST, 0x40=LAST, 0x20=MEMERROR)
 *   2-3 size/offset (UInt16 BE): total transfer size on a FIRST packet, else
 *       the running byte offset of this fragment; ACKs echo it back.
 *
 * Transaction ids: the side that originates a transfer owns the txid; the
 * other side echoes it in each ACK. We own the txid for host-initiated sends
 * (CMP init, every DLP request) and echo the device's txid when it speaks
 * first (the CMP wakeup, every DLP response).
 */

private const val PADP_DATA = 0x01
private const val PADP_ACK = 0x02
private const val PADP_TICKLE = 0x04

private const val PADP_FIRST = 0x80
private const val PADP_LAST = 0x40

/** Max payload bytes per PADP fragment (PalmOS uses 1K data fragments). */
private const val PADP_MAX_FRAGMENT = 1024

class PadpStream(private val slp: SlpFramer) : DlpTransport {

    /** Host-owned transaction id, advanced once per host-initiated transfer. */
    var nextXid: Int = 1
        private set

    /** Default per-operation timeout. HotSync sessions are interactive but vintage-slow. */
    var timeoutMs: Long = 20_000

    /** Transaction id of the most recently received device-initiated transfer. */
    var lastRxTxid: Int = 0
        private set

    /** Seed the host txid from the device's first packet (the CMP wakeup). */
    fun seedXidFrom(deviceTxid: Int) {
        nextXid = advance(deviceTxid)
    }

    /**
     * Send [payload] as one host-initiated PADP transfer (fragmenting as
     * needed) using the next host txid, waiting for an ACK per fragment.
     */
    override fun send(payload: ByteArray) {
        val xid = nextXid
        sendWithTxid(payload, xid)
        nextXid = advance(xid)
    }

    private fun sendWithTxid(payload: ByteArray, xid: Int) {
        if (payload.isEmpty()) {
            sendFragment(xid, PADP_FIRST or PADP_LAST, payload.size, ByteArray(0))
            awaitAck(xid)
            return
        }
        var offset = 0
        var first = true
        while (offset < payload.size) {
            val end = minOf(offset + PADP_MAX_FRAGMENT, payload.size)
            val frag = payload.copyOfRange(offset, end)
            val last = end >= payload.size
            var flags = 0
            if (first) flags = flags or PADP_FIRST
            if (last) flags = flags or PADP_LAST
            // size field: total on the FIRST packet, running offset thereafter.
            val sizeField = if (first) payload.size else offset
            sendFragment(xid, flags, sizeField, frag)
            awaitAck(xid)
            offset = end
            first = false
        }
    }

    private fun sendFragment(xid: Int, flags: Int, sizeField: Int, frag: ByteArray) {
        val pkt = ByteArray(4 + frag.size)
        pkt[0] = PADP_DATA.toByte()
        pkt[1] = flags.toByte()
        pkt[2] = (sizeField ushr 8).toByte()
        pkt[3] = sizeField.toByte()
        frag.copyInto(pkt, 4, 0, frag.size)
        slp.send(SLP_SOCKET_DLP, SLP_SOCKET_DLP, SLP_TYPE_PADP, xid, pkt)
    }

    private fun awaitAck(xid: Int) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val p = slp.receive(deadline) ?: throw HotSyncException("PADP: timed out waiting for ACK")
            if (p.type != SLP_TYPE_PADP || p.data.size < 2) continue
            val ptype = p.data[0].toInt() and 0xFF
            if (ptype == PADP_TICKLE) continue // keep-alive; ignore
            if (ptype == PADP_ACK && p.txid == xid) return
            // Anything else mid-send is unexpected; keep waiting until deadline.
        }
    }

    /**
     * Receive one device-initiated PADP transfer (the CMP wakeup, or a DLP
     * response), reassembling fragments and ACKing each. Returns the payload.
     */
    override fun receive(): ByteArray {
        val deadline = System.currentTimeMillis() + timeoutMs
        val out = java.io.ByteArrayOutputStream()
        var sawFirst = false
        while (true) {
            val p = slp.receive(deadline) ?: throw HotSyncException("PADP: timed out waiting for data")
            if (p.type != SLP_TYPE_PADP || p.data.size < 4) continue
            val ptype = p.data[0].toInt() and 0xFF
            val flags = p.data[1].toInt() and 0xFF
            val sizeField = ((p.data[2].toInt() and 0xFF) shl 8) or (p.data[3].toInt() and 0xFF)
            when (ptype) {
                PADP_TICKLE -> continue
                PADP_ACK -> continue // stray ACK; ignore
                PADP_DATA -> {
                    // ACK this fragment (echo type=ACK, flags, size, txid).
                    val ack = byteArrayOf(
                        PADP_ACK.toByte(), flags.toByte(),
                        (sizeField ushr 8).toByte(), sizeField.toByte(),
                    )
                    slp.send(SLP_SOCKET_DLP, SLP_SOCKET_DLP, SLP_TYPE_PADP, p.txid, ack)
                    if (flags and PADP_FIRST != 0) {
                        sawFirst = true
                        lastRxTxid = p.txid
                        out.reset()
                    }
                    if (!sawFirst) continue
                    out.write(p.data, 4, p.data.size - 4)
                    if (flags and PADP_LAST != 0) return out.toByteArray()
                }
                else -> continue
            }
        }
    }

    /** Advance a txid, skipping 0 and 0xFF which the protocol reserves. */
    private fun advance(x: Int): Int {
        var n = (x + 1) and 0xFF
        if (n == 0 || n == 0xFF) n = 1
        return n
    }
}

package dev.tatliving.palmvellum.organizers.data.hotsync

import java.io.ByteArrayOutputStream

/**
 * NetSync — the protocol stack most USB Palm/CLIE devices speak (Sony T/SJ/NR,
 * Palm m5xx, Tungsten, Zire, m125/m130, …). Unlike the serial stack there is no
 * SLP/PADP/CMP: DLP datagrams ride directly in a tiny framed envelope, and the
 * session opens with a fixed three-step "magic" handshake.
 *
 * Datagram header (6 bytes), then the payload:
 *   0    dataType (1)
 *   1    transaction id
 *   2-5  payload length (UInt32 BE)
 *
 * Ported from palm-sync's `net-sync-protocol.ts` / `sync-connections.ts` — the
 * implementation the desktop "PalmVellum on Mac" build uses over USB.
 */
class NetSyncStream(private val transport: PalmTransport) : DlpTransport {

    /** Per-write transaction id, incremented like the reference (`(xid+1)%255 || 1`). */
    private var xid: Int = 0

    /** Overall per-operation deadline budget. Vintage USB links are slow. */
    var timeoutMs: Long = 30_000

    /**
     * Once a receive times out, the request/response stream is desynced and the
     * link is almost certainly gone. Mark it dead so later [receive] calls fail
     * fast (instead of each waiting the full [timeoutMs]) — the conduit can then
     * skip its remaining databases and still send a best-effort EndOfSync.
     */
    var dead: Boolean = false
        private set

    private val inbox = ArrayDeque<Byte>()
    private val chunk = ByteArray(4096)

    override fun send(payload: ByteArray) {
        xid = (xid + 1) % 0xFF
        if (xid == 0) xid = 1
        val frame = ByteArray(6 + payload.size)
        frame[0] = 1 // data type
        frame[1] = xid.toByte()
        frame[2] = (payload.size ushr 24).toByte()
        frame[3] = (payload.size ushr 16).toByte()
        frame[4] = (payload.size ushr 8).toByte()
        frame[5] = payload.size.toByte()
        payload.copyInto(frame, 6, 0, payload.size)
        transport.write(frame)
    }

    override fun receive(): ByteArray {
        if (dead) throw HotSyncException("NetSync: link is no longer responding")
        val deadline = System.currentTimeMillis() + timeoutMs
        val header = readN(6, deadline)
        val len = ((header[2].toInt() and 0xFF) shl 24) or
            ((header[3].toInt() and 0xFF) shl 16) or
            ((header[4].toInt() and 0xFF) shl 8) or
            (header[5].toInt() and 0xFF)
        if (len == 0) return ByteArray(0)
        return try {
            readN(len, deadline)
        } catch (e: HotSyncException) {
            // Surface the header so a stalled response is diagnosable: the device
            // announced a `len`-byte payload (dataType=header[0]) but never sent it.
            val hex = header.joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
            throw HotSyncException("NetSync: header [$hex] announced $len-byte payload, none arrived", e)
        }
    }

    /**
     * The fixed NetSync opening handshake: the device sends three datagrams and
     * expects two canned responses interleaved. Contents are model-independent
     * "magic"; only the exchange order matters.
     */
    fun doHandshake() {
        receive()                 // device request 1
        send(HANDSHAKE_RESPONSE_1)
        receive()                 // device request 2
        send(HANDSHAKE_RESPONSE_2)
        receive()                 // device request 3
    }

    private fun readN(n: Int, deadlineMs: Long): ByteArray {
        while (inbox.size < n) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) {
                dead = true
                throw HotSyncException("NetSync: timed out waiting for data (got ${inbox.size} of $n bytes)")
            }
            val got = transport.read(chunk, remaining.coerceAtMost(2000L).toInt())
            for (i in 0 until got) inbox.addLast(chunk[i])
        }
        val out = ByteArrayOutputStream(n)
        repeat(n) { out.write(inbox.removeFirst().toInt() and 0xFF) }
        return out.toByteArray()
    }

    companion object {
        // Canned server responses (payloads; the 6-byte header is added on send).
        private val HANDSHAKE_RESPONSE_1 = byteArrayOf(
            0x12, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,
            0x24, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x3c, 0x00, 0x3c, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0xc0.toByte(), 0xa8.toByte(), 0x01, 0x21, 0x04, 0x27, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
        private val HANDSHAKE_RESPONSE_2 = byteArrayOf(
            0x13, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x20, 0x00, 0x00, 0x00,
            0x20, 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0x00, 0x3c, 0x00, 0x3c, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        )
    }
}

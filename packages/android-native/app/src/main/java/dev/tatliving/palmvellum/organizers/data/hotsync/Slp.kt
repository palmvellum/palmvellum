package dev.tatliving.palmvellum.organizers.data.hotsync

/**
 * SLP — Serial Link Protocol, the bottom HotSync framing layer.
 *
 * Frame:
 *   0    0xBE  preamble
 *   1    0xEF
 *   2    0xED
 *   3    dest socket
 *   4    src  socket
 *   5    type (0=RDCAP,1=RMP,2=PADP,3=loopback)
 *   6-7  dataLen (UInt16 BE)
 *   8    txid
 *   9    header checksum = sum(bytes 0..8) & 0xFF
 *   10.. data[dataLen]
 *   end  CRC16 (UInt16 BE) over bytes 0 .. 9+dataLen
 */

/** SLP packet type for PADP traffic (DLP rides on PADP). */
const val SLP_TYPE_PADP = 0x02

/** The well-known socket pair used by the Desktop Link (DLP) conversation. */
const val SLP_SOCKET_DLP = 0x03

class SlpPacket(
    val dest: Int,
    val src: Int,
    val type: Int,
    val txid: Int,
    val data: ByteArray,
)

/**
 * Frames SLP packets over a [PalmTransport]. Inbound bytes are buffered and
 * resynchronised on the 0xBE 0xEF 0xED preamble, so partial / noisy reads are
 * tolerated. Single-threaded: one HotSync session uses one framer.
 */
class SlpFramer(private val transport: PalmTransport) {

    private val inbox = ArrayDeque<Byte>()
    private val chunk = ByteArray(4096)

    fun send(dest: Int, src: Int, type: Int, txid: Int, data: ByteArray) {
        val frame = ByteArray(10 + data.size + 2)
        frame[0] = 0xBE.toByte()
        frame[1] = 0xEF.toByte()
        frame[2] = 0xED.toByte()
        frame[3] = dest.toByte()
        frame[4] = src.toByte()
        frame[5] = type.toByte()
        frame[6] = (data.size ushr 8).toByte()
        frame[7] = data.size.toByte()
        frame[8] = txid.toByte()
        var sum = 0
        for (i in 0..8) sum += frame[i].toInt() and 0xFF
        frame[9] = (sum and 0xFF).toByte()
        data.copyInto(frame, 10, 0, data.size)
        val crc = crc16(frame, 0, 10 + data.size)
        frame[10 + data.size] = (crc ushr 8).toByte()
        frame[10 + data.size + 1] = crc.toByte()
        transport.write(frame)
    }

    /**
     * Read the next complete SLP frame, blocking up to [deadlineMs] (epoch
     * millis). Returns null only if the deadline passes with no full frame.
     * Corrupt frames (bad checksum / CRC) are skipped.
     */
    fun receive(deadlineMs: Long): SlpPacket? {
        while (true) {
            // Resync to preamble.
            while (inbox.size >= 3) {
                if (peek(0) == 0xBE && peek(1) == 0xEF && peek(2) == 0xED) break
                inbox.removeFirst()
            }
            if (inbox.size < 10) {
                if (!fill(deadlineMs)) return null
                continue
            }
            // We have at least a 10-byte header candidate.
            if (!(peek(0) == 0xBE && peek(1) == 0xEF && peek(2) == 0xED)) continue
            val dataLen = (peek(6) shl 8) or peek(7)
            val total = 10 + dataLen + 2
            if (inbox.size < total) {
                if (!fill(deadlineMs)) return null
                continue
            }
            val frame = ByteArray(total)
            for (i in 0 until total) frame[i] = inbox.removeFirst()

            // Validate header checksum.
            var sum = 0
            for (i in 0..8) sum += frame[i].toInt() and 0xFF
            if ((sum and 0xFF) != (frame[9].toInt() and 0xFF)) continue // skip corrupt
            // Validate CRC over bytes 0..(9+dataLen).
            val crc = crc16(frame, 0, 10 + dataLen)
            val gotCrc = ((frame[10 + dataLen].toInt() and 0xFF) shl 8) or (frame[10 + dataLen + 1].toInt() and 0xFF)
            if (crc != gotCrc) continue

            return SlpPacket(
                dest = frame[3].toInt() and 0xFF,
                src = frame[4].toInt() and 0xFF,
                type = frame[5].toInt() and 0xFF,
                txid = frame[8].toInt() and 0xFF,
                data = frame.copyOfRange(10, 10 + dataLen),
            )
        }
    }

    private fun peek(i: Int): Int = inbox.elementAt(i).toInt() and 0xFF

    /** Block for more bytes until some arrive or the deadline passes. */
    private fun fill(deadlineMs: Long): Boolean {
        while (true) {
            val remaining = deadlineMs - System.currentTimeMillis()
            if (remaining <= 0) return false
            val n = transport.read(chunk, remaining.coerceAtMost(2000L).toInt())
            if (n > 0) {
                for (i in 0 until n) inbox.addLast(chunk[i])
                return true
            }
            // n == 0 -> timeout slice; loop until the overall deadline.
        }
    }

    companion object {
        /** CRC-16/XMODEM (poly 0x1021, init 0x0000) — the SLP frame check. */
        fun crc16(b: ByteArray, off: Int, len: Int): Int {
            var crc = 0
            for (i in off until off + len) {
                crc = crc xor ((b[i].toInt() and 0xFF) shl 8)
                for (j in 0 until 8) {
                    crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                    crc = crc and 0xFFFF
                }
            }
            return crc and 0xFFFF
        }
    }
}

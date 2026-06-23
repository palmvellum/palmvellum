package dev.tatliving.palmvellum.organizers.data.hotsync

/**
 * A raw byte pipe to a docked Palm. Over USB this is a pair of bulk endpoints;
 * the HotSync protocol stack (SLP / PADP / CMP / DLP) runs on top, treating the
 * device as a serial-like stream — the same model pilot-link uses for USB Palms.
 */
interface PalmTransport {
    /**
     * Read up to [buf].size bytes, blocking up to [timeoutMs]. Returns the
     * number of bytes read (0 on timeout, never throws on timeout). Throws
     * [HotSyncException] on a hard transport error / disconnect.
     */
    fun read(buf: ByteArray, timeoutMs: Int): Int

    /** Write all of [data], blocking until sent. Throws on transport error. */
    fun write(data: ByteArray)

    fun close()
}

/** Any failure in the HotSync transport or protocol stack. */
class HotSyncException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The layer that carries DLP datagrams. Two implementations exist — [PadpStream]
 * (serial stack: SLP/PADP/CMP) and [NetSyncStream] (NetSync stack) — so [Dlp]
 * runs unchanged over whichever protocol the docked device speaks.
 */
interface DlpTransport {
    /** Send one DLP request payload. */
    fun send(payload: ByteArray)
    /** Receive one DLP response payload. */
    fun receive(): ByteArray
}

/** Which HotSync protocol stack a USB device speaks on top of the bulk pipe. */
enum class UsbStack { NET_SYNC, SERIAL }

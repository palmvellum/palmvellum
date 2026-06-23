package dev.tatliving.palmvellum.organizers.data.hotsync

/**
 * CMP — Connection Management Protocol, the HotSync opening handshake.
 *
 * When the user presses HotSync the device sends a CMP *wakeup* (its protocol
 * version + maximum baud). The host replies with a CMP *init*; after that the
 * DLP command conversation begins. Over USB the baud rate is nominal, so we
 * never ask the device to change it.
 *
 * CMP packet (10 bytes):
 *   0    type   (1=wakeup, 2=init, 3=abort)
 *   1    flags  (0x80 = change baud — we leave it clear)
 *   2    version major
 *   3    version minor
 *   4-5  reserved
 *   6-9  baud rate (UInt32 BE)
 */
object Cmp {

    private const val TYPE_WAKEUP = 0x01
    private const val TYPE_INIT = 0x02

    // Desktop Link / CMP version we advertise (PalmOS-era desktops speak 1.x).
    private const val VER_MAJOR = 0x01
    private const val VER_MINOR = 0x01

    /**
     * Perform the wakeup→init handshake on [padp]. Returns the device's
     * advertised baud rate (informational). Throws if the first packet is not
     * a CMP wakeup.
     */
    fun handshake(padp: PadpStream): Long {
        val wake = padp.receive()
        if (wake.size < 10 || (wake[0].toInt() and 0xFF) != TYPE_WAKEUP) {
            throw HotSyncException("CMP: expected wakeup, got ${wake.size} bytes type ${if (wake.isEmpty()) -1 else wake[0].toInt() and 0xFF}")
        }
        val baud = ((wake[6].toLong() and 0xFF) shl 24) or
            ((wake[7].toLong() and 0xFF) shl 16) or
            ((wake[8].toLong() and 0xFF) shl 8) or
            (wake[9].toLong() and 0xFF)

        // The device spoke first; align the host txid to follow its wakeup.
        padp.seedXidFrom(padp.lastRxTxid)

        val init = ByteArray(10)
        init[0] = TYPE_INIT.toByte()
        init[1] = 0 // don't change baud
        init[2] = VER_MAJOR.toByte()
        init[3] = VER_MINOR.toByte()
        // reserved 4-5 = 0; echo the device baud back in 6-9.
        init[6] = (baud ushr 24).toByte()
        init[7] = (baud ushr 16).toByte()
        init[8] = (baud ushr 8).toByte()
        init[9] = baud.toByte()
        padp.send(init)
        return baud
    }
}

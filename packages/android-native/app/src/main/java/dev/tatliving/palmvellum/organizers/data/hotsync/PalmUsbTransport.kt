package dev.tatliving.palmvellum.organizers.data.hotsync

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager

/**
 * [PalmTransport] over an Android USB host connection to a docked Palm/CLIE.
 *
 * A Palm in its cradle enumerates as a USB device the moment the user presses
 * HotSync. We claim the interface that exposes a bulk IN + bulk OUT endpoint
 * pair and treat it as a byte pipe for the SLP/PADP/CMP/DLP stack — the same
 * model pilot-link uses for USB Palms.
 *
 * The vendor "connection info" control requests differ across Palm/Sony/
 * Handspring models and are only an optimisation (they report which port maps
 * to which endpoint). We issue the common ones best-effort and otherwise fall
 * back to the first bulk endpoint pair found in the descriptors, which works
 * for the single-port HotSync devices this targets. NB: untested on hardware —
 * see docs/cross-platform-desktop-sync-feasibility.md.
 */
class PalmUsbTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
) : PalmTransport {

    override fun read(buf: ByteArray, timeoutMs: Int): Int {
        // Read at most one max-packet per transfer, exactly like palm-sync's
        // transferIn(ep, 64). A single large request HANGS whenever the device's
        // transfer is an exact multiple of the max packet size: with no trailing
        // short packet (or ZLP) to mark the end, the host controller keeps
        // waiting for more and the read times out (seen on a 64-byte Date Book
        // record). A one-packet request always completes once the packet lands.
        val want = if (buf.size < epIn.maxPacketSize) buf.size else epIn.maxPacketSize
        val n = connection.bulkTransfer(epIn, buf, want, timeoutMs)
        // bulkTransfer returns -1 on timeout (and on error); the framing layer's
        // overall deadline distinguishes a slow link from a dead one.
        return if (n < 0) 0 else n
    }

    override fun write(data: ByteArray) {
        var offset = 0
        while (offset < data.size) {
            val len = data.size - offset
            val n = connection.bulkTransfer(epOut, data.copyOfRange(offset, offset + len), len, WRITE_TIMEOUT_MS)
            if (n < 0) throw HotSyncException("USB write failed at offset $offset")
            offset += n
            if (n == 0) throw HotSyncException("USB write stalled")
        }
    }

    override fun close() {
        runCatching { connection.releaseInterface(iface) }
        runCatching { connection.close() }
    }

    /** The opened pipe plus which protocol stack this device speaks + a label. */
    class Opened(val transport: PalmUsbTransport, val stack: UsbStack, val label: String)

    companion object {
        private const val WRITE_TIMEOUT_MS = 5000

        // USB vendor ids of the AAA-era handhelds this project targets.
        private const val VID_PALM = 0x0830
        private const val VID_SONY = 0x054C
        private const val VID_HANDSPRING = 0x082D
        private const val VID_ACEECA = 0x4766
        private val PALM_VENDORS = setOf(VID_PALM, VID_SONY, VID_HANDSPRING, VID_ACEECA)

        // Palm USB control requests (vendor). Values per pilot-link / palm-sync.
        private const val REQ_GET_NUM_BYTES_AVAILABLE = 1
        private const val REQ_GET_CONNECTION_INFO = 3
        private const val REQ_GET_EXT_CONNECTION_INFO = 4

        // The handful of devices that speak the SERIAL stack instead of NetSync.
        private val SERIAL_DEVICES = setOf(0x054C_0038, 0x082D_0100)
        // Early Sony CLIE devices need a standard GET_CONFIGURATION/GET_INTERFACE init.
        private val EARLY_SONY = setOf(0x054C_0038, 0x054C_009A)

        private fun usbId(d: UsbDevice) = (d.vendorId shl 16) or d.productId

        /** Does this look like a Palm/CLIE HotSync device? */
        fun isPalmDevice(d: UsbDevice): Boolean = d.vendorId in PALM_VENDORS

        /** First attached device that looks like a Palm, or null. */
        fun findPalmDevice(manager: UsbManager): UsbDevice? =
            manager.deviceList.values.firstOrNull { isPalmDevice(it) }

        /** The protocol stack a device speaks. Most USB Palms are NetSync. */
        private fun stackFor(d: UsbDevice): UsbStack =
            if (usbId(d) in SERIAL_DEVICES) UsbStack.SERIAL else UsbStack.NET_SYNC

        /**
         * Open [device] (permission must already be granted), claim the bulk
         * pipe, run the device's USB init, and report the protocol stack to use.
         * [log] receives diagnostic lines. Throws [HotSyncException] on failure.
         */
        fun open(manager: UsbManager, device: UsbDevice, log: (String) -> Unit = {}): Opened {
            val connection = manager.openDevice(device)
                ?: throw HotSyncException("Could not open USB device (permission revoked?)")

            // Find an interface exposing a bulk IN and a bulk OUT endpoint.
            // The HotSync endpoints are the bulk pair with a 64-byte max packet
            // size (a device may expose other bulk endpoints too) — match
            // palm-sync's descriptor-based selection.
            var iface: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                val bulks = (0 until intf.endpointCount).map { intf.getEndpoint(it) }
                    .filter { it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
                bulks.forEach { log("  ep ${it.address.hex()} dir=${if (it.direction == UsbConstants.USB_DIR_IN) "in" else "out"} maxPkt=${it.maxPacketSize}") }
                val ins = bulks.filter { it.direction == UsbConstants.USB_DIR_IN }
                val outs = bulks.filter { it.direction == UsbConstants.USB_DIR_OUT }
                log("iface[$i] cls=${intf.interfaceClass} eps=${intf.endpointCount} bulkIns=${ins.size} bulkOuts=${outs.size}")
                if (ins.isNotEmpty() && outs.isNotEmpty() && iface == null) {
                    iface = intf
                    epIn = ins.firstOrNull { it.maxPacketSize == 64 } ?: ins.first()
                    epOut = outs.firstOrNull { it.maxPacketSize == 64 } ?: outs.first()
                }
            }
            if (iface == null || epIn == null || epOut == null) {
                connection.close()
                throw HotSyncException("No bulk IN/OUT endpoint pair on this device")
            }
            if (!connection.claimInterface(iface, true)) {
                connection.close()
                throw HotSyncException("Could not claim the USB interface")
            }

            // Run the device's USB init. Some devices won't start sending the
            // HotSync handshake until these have been issued; GetExtConnectionInfo
            // also reports the true HotSync endpoint numbers.
            val initEps = runUsbInit(connection, device, epOut, log)
            if (initEps != null) {
                val (inNum, outNum) = initEps
                val in2 = findBulkEndpoint(iface, inNum, true)
                val out2 = findBulkEndpoint(iface, outNum, false)
                if (in2 != null && out2 != null) {
                    log("Using endpoints from GetExtConnectionInfo: in=${in2.address.hex()} out=${out2.address.hex()}")
                    epIn = in2; epOut = out2
                } else {
                    log("GetExtConnectionInfo endpoints in=$inNum out=$outNum not found; keeping descriptor endpoints")
                }
            }
            log("Final endpoints: in=${epIn.address.hex()} out=${epOut.address.hex()}")

            val label = "0x%04x:0x%04x".format(device.vendorId, device.productId) +
                (device.productName?.let { " ($it)" } ?: "")
            return Opened(PalmUsbTransport(connection, iface, epIn, epOut), stackFor(device), label)
        }

        private fun Int.hex() = "0x%02x".format(this)

        private fun findBulkEndpoint(iface: UsbInterface, number: Int, dirIn: Boolean): UsbEndpoint? {
            for (e in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                val isIn = ep.direction == UsbConstants.USB_DIR_IN
                if (isIn == dirIn && (ep.address and 0x0F) == number) return ep
            }
            return null
        }

        /**
         * Per-device USB initialization, ported from palm-sync. GENERIC: query
         * GetExtConnectionInfo (returns true endpoint numbers), GetConnectionInfo,
         * then GetNumBytesAvailable — older devices expect these before they start
         * sending. EARLY_SONY: a standard GET_CONFIGURATION/GET_INTERFACE dance.
         * Returns (inEndpointNumber, outEndpointNumber) if discovered, else null.
         */
        private fun runUsbInit(
            conn: UsbDeviceConnection,
            device: UsbDevice,
            epOut: UsbEndpoint,
            log: (String) -> Unit,
        ): Pair<Int, Int>? {
            if (usbId(device) in EARLY_SONY) {
                val inStd = UsbConstants.USB_DIR_IN // standard | device recipient = 0x80
                val b = ByteArray(2)
                log("EARLY_SONY init: GET_CONFIGURATION=${conn.controlTransfer(inStd, 0x08, 0, 0, b, 1, 1000)} GET_INTERFACE=${conn.controlTransfer(inStd, 0x0A, 0, 0, b, 1, 1000)}")
                return null
            }
            // GENERIC init.
            val inVendorDevice = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR // 0xC0
            val ext = ByteArray(20)
            val nExt = conn.controlTransfer(inVendorDevice, REQ_GET_EXT_CONNECTION_INFO, 0, 0, ext, ext.size, 2000)
            log("GetExtConnectionInfo -> $nExt bytes${if (nExt > 0) ": " + ext.copyOf(nExt).toHex() else ""}")
            var eps: Pair<Int, Int>? = null
            if (nExt >= 8) {
                // numPorts, hasDifferentEndpoints, then port[0]: type[4], portNumber, endpoints(in<<4|out), pad[2]
                val hasDifferent = ext[1].toInt() and 0xFF
                val portNumber = ext[6].toInt() and 0xFF
                val endpointsByte = ext[7].toInt() and 0xFF
                eps = if (hasDifferent != 0) {
                    ((endpointsByte ushr 4) and 0x0F) to (endpointsByte and 0x0F)
                } else {
                    portNumber to portNumber
                }
                log("Parsed endpoints in=${eps.first} out=${eps.second} (hasDifferent=$hasDifferent)")
            }
            val conn2 = ByteArray(20)
            val nConn = conn.controlTransfer(inVendorDevice, REQ_GET_CONNECTION_INFO, 0, 0, conn2, conn2.size, 2000)
            log("GetConnectionInfo -> $nConn bytes")
            // GetNumBytesAvailable, addressed to the OUT endpoint; result ignored.
            val inVendorEndpoint = UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_VENDOR or 0x02 // 0xC2
            val nb = ByteArray(2)
            val outEpNumber = if (eps != null) eps.second else (epOut.address and 0x0F)
            val nNb = conn.controlTransfer(inVendorEndpoint, REQ_GET_NUM_BYTES_AVAILABLE, 0, outEpNumber, nb, 2, 1000)
            log("GetNumBytesAvailable(ep=$outEpNumber) -> $nNb bytes")
            return eps
        }

        private fun ByteArray.toHex(): String = joinToString(" ") { "%02x".format(it.toInt() and 0xFF) }
    }
}

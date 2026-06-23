package dev.tatliving.palmvellum.organizers.data.hotsync

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** Runtime USB-device permission, bridged to a suspend call. */
object UsbPermission {

    private const val ACTION = "dev.tatliving.palmvellum.organizers.USB_PERMISSION"

    /**
     * Ensure the app may talk to [device], prompting the user if needed.
     * Returns true once permission is held. Safe to call from a coroutine on
     * any dispatcher (it suspends until the system dialog is answered).
     */
    suspend fun ensure(context: Context, manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true
        val app = context.applicationContext
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != ACTION) return
                    runCatching { app.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (cont.isActive) cont.resume(granted)
                }
            }
            val filter = IntentFilter(ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }
            cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }

            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pi = PendingIntent.getBroadcast(app, 0, Intent(ACTION).setPackage(app.packageName), flags)
            manager.requestPermission(device, pi)
        }
    }
}

package dev.tatliving.palmvellum.organizers

import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.tatliving.palmvellum.organizers.ui.PalmVellumRoot

class MainActivity : ComponentActivity() {

    /**
     * Cosmo physical-keyboard handler. The Compose root installs this; it
     * returns true to consume a shortcut. Unhandled keys fall through to normal
     * text input. Null on the standard build (no hardware keyboard contract).
     */
    var keyHandler: ((KeyEvent) -> Boolean)? = null

    /**
     * Bumped each time the app is launched (or brought forward) by plugging in a
     * Palm/CLIE cradle — the USB_DEVICE_ATTACHED intent. The Compose root watches
     * this and jumps straight to the HotSync screen so the user can press Start
     * without hunting for the tile. Both builds have HotSync, so both auto-open.
     */
    var hotSyncRequest by mutableStateOf(0)
        private set

    private fun consumeUsbAttach(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            hotSyncRequest++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeUsbAttach(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (BuildConfig.COSMO) {
            keyHandler?.let { if (it(event)) return true }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The Cosmo Communicator is used open, in landscape, with its physical
        // keyboard along the bottom edge. Lock the Cosmo build to landscape so
        // the layout always matches the clamshell posture.
        if (BuildConfig.COSMO) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        // Dark scrim over the (dark) Palm title bar => light status-bar icons.
        // Native edge-to-edge: Compose handles insets, so there is no
        // Capacitor WebView status-bar overlap problem here.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        consumeUsbAttach(intent)
        setContent {
            PalmVellumRoot()
        }
    }
}

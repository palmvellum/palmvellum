package dev.tatliving.palmvellum.organizers

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tatliving.palmvellum.organizers.ui.PalmVellumRoot

class MainActivity : ComponentActivity() {
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
        setContent {
            PalmVellumRoot()
        }
    }
}

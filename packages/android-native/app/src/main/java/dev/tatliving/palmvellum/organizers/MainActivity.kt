package dev.tatliving.palmvellum.organizers

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.tatliving.palmvellum.organizers.ui.PalmVellumRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
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

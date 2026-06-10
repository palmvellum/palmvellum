package dev.tatliving.palmvellum.organizers

import android.app.Application
import dev.tatliving.palmvellum.organizers.data.Graph

class PalmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
    }
}

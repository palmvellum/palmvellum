package dev.tatliving.palmvellum.organizers

import android.app.Application
import dev.tatliving.palmvellum.organizers.data.Graph
import dev.tatliving.palmvellum.organizers.ui.i18n.I18n

class PalmApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        I18n.init(this)
    }
}

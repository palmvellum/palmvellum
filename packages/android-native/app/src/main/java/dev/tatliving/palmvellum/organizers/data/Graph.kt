package dev.tatliving.palmvellum.organizers.data

import android.content.Context
import androidx.room.Room
import dev.tatliving.palmvellum.organizers.data.local.PalmDatabase
import dev.tatliving.palmvellum.organizers.data.sync.SessionStore
import dev.tatliving.palmvellum.organizers.data.sync.SupabaseRest
import dev.tatliving.palmvellum.organizers.data.sync.SyncEngine

/**
 * Minimal manual service locator — no DI framework. Initialised once from
 * PalmApp.onCreate(); ViewModels / Compose read Graph.* directly.
 */
object Graph {
    lateinit var repo: PalmRepository
        private set
    lateinit var session: SessionStore
        private set
    lateinit var sync: SyncEngine
        private set

    fun init(context: Context) {
        val app = context.applicationContext
        val db = Room.databaseBuilder(app, PalmDatabase::class.java, "palmvellum.db")
            .fallbackToDestructiveMigration()
            .build()
        repo = PalmRepository(db.eventDao(), db.recordDao(), db.draftDao())
        session = SessionStore(app)
        val rest = SupabaseRest(session)
        sync = SyncEngine(db.eventDao(), db.recordDao(), db.conflictDao(), db.draftDao(), session, rest)
    }
}

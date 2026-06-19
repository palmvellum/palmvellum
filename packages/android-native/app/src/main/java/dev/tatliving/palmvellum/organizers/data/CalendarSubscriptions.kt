package dev.tatliving.palmvellum.organizers.data

import android.content.Context
import dev.tatliving.palmvellum.organizers.data.local.EventEntity
import dev.tatliving.palmvellum.organizers.data.local.RecordEntity
import dev.tatliving.palmvellum.organizers.data.model.PalmJson
import dev.tatliving.palmvellum.organizers.util.Ics
import dev.tatliving.palmvellum.organizers.util.Net
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.abs

/** A read-only calendar subscription — a name plus an iCal (.ics) feed URL,
 *  e.g. a Google Calendar's "Secret address in iCal format". */
@Serializable
data class CalSub(val name: String, val url: String)

/**
 * The subscription LIST is a cloud-synced record (type='calsub') so it
 * converges across devices and with the web app — body holds the URL,
 * metadata holds the display name. A deterministic id ("calsub" + the URL's
 * hash) means subscribing to the same feed on two devices de-dupes to one row.
 * Java/Kotlin String.hashCode matches the web's javaHashCode, so both clients
 * produce the same id for a given URL.
 */
object CalSubs {
    private fun idFor(url: String): String = "calsub" + abs(url.hashCode())

    private fun RecordEntity.toCalSub(): CalSub {
        val name = runCatching {
            PalmJson.decodeFromString<Map<String, String>>(metadataJson)["name"]
        }.getOrNull()
        return CalSub(name = name?.ifBlank { null } ?: (body ?: ""), url = body ?: "")
    }

    /** Live list of subscriptions, newest first. */
    fun observe(): Flow<List<CalSub>> =
        Graph.repo.observeRecords("calsub").map { recs -> recs.map { it.toCalSub() } }

    /** One-shot snapshot (used by the refresher). */
    suspend fun listOnce(): List<CalSub> =
        Graph.repo.observeRecords("calsub").first().map { it.toCalSub() }

    suspend fun add(name: String, url: String) {
        val id = idFor(url)
        val now = Clock.nowIso()
        val existing = Graph.repo.getRecord(id)
        val base = existing ?: RecordEntity(id = id, type = "calsub", createdAt = now, updatedAt = now)
        Graph.repo.saveRecord(
            base.copy(
                type = "calsub",
                body = url,
                metadataJson = PalmJson.encodeToString(mapOf("name" to name)),
                deletedAt = null,
            ),
        )
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }

    suspend fun remove(url: String) {
        Graph.repo.deleteRecord(idFor(url))
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
    }
}

/** Device-local refresh cadence for the subscriptions (not synced — each
 *  device decides how often to poll its feeds). */
class CalSubStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cal_subs", Context.MODE_PRIVATE)

    /** Auto-update interval in hours; 0 = off. */
    fun intervalHours(): Int = prefs.getInt("interval_h", 0)
    fun setIntervalHours(h: Int) = prefs.edit().putInt("interval_h", h).apply()
}

/** One-off import of a .ics document's VEVENTs as new events. */
object IcsImport {
    suspend fun importText(text: String): Int {
        val parsed = Ics.parse(text)
        parsed.forEach { e ->
            val now = Clock.nowIso()
            Graph.repo.saveEvent(
                EventEntity(
                    id = Ulid.new(),
                    title = e.summary,
                    startAt = e.startIso,
                    endAt = e.endIso,
                    allDay = e.allDay,
                    location = e.location,
                    notes = e.description,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
        return parsed.size
    }
}

/** Fetches every subscribed iCal feed and upserts its events. Read-only: events
 *  removed upstream are not deleted locally (a deliberate simplification). */
object CalendarSync {
    /** Returns the number of events added/updated, or a failure with the message. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun refresh(context: Context): Result<Int> {
        val subs = CalSubs.listOnce()
        if (subs.isEmpty()) return Result.success(0)
        var changed = 0
        var lastError: String? = null
        for (sub in subs) {
            val fetched = Net.getText(sub.url)
            if (fetched.isFailure) {
                lastError = fetched.exceptionOrNull()?.message
                continue
            }
            val text = fetched.getOrThrow()
            for (e in Ics.parse(text)) {
                val key = e.uid ?: (e.summary + e.startIso)
                val id = "ics" + abs((sub.url + "|" + key).hashCode())
                val existing = Graph.repo.getEvent(id)
                // Skip unchanged rows so re-fetching doesn't churn the sync queue.
                if (existing != null &&
                    existing.title == e.summary &&
                    existing.startAt == e.startIso &&
                    existing.endAt == e.endIso &&
                    existing.allDay == e.allDay &&
                    existing.location == e.location &&
                    existing.notes == e.description &&
                    existing.deletedAt == null
                ) {
                    continue
                }
                val now = Clock.nowIso()
                Graph.repo.saveEvent(
                    EventEntity(
                        id = id,
                        title = e.summary,
                        startAt = e.startIso,
                        endAt = e.endIso,
                        allDay = e.allDay,
                        location = e.location,
                        notes = e.description,
                        source = "ics-sub",
                        createdAt = existing?.createdAt ?: now,
                        updatedAt = now,
                    ),
                )
                changed++
            }
        }
        if (Graph.sync.isSignedIn) Graph.sync.syncNow()
        return lastError?.let { Result.failure(Exception(it)) } ?: Result.success(changed)
    }
}

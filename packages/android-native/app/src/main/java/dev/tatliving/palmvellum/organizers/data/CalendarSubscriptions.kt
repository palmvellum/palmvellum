package dev.tatliving.palmvellum.organizers.data

import android.content.Context
import dev.tatliving.palmvellum.organizers.data.local.EventEntity
import dev.tatliving.palmvellum.organizers.data.model.PalmJson
import dev.tatliving.palmvellum.organizers.util.Ics
import dev.tatliving.palmvellum.organizers.util.Net
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.math.abs

/** A read-only calendar subscription — a name plus an iCal (.ics) feed URL,
 *  e.g. a Google Calendar's "Secret address in iCal format". */
@Serializable
data class CalSub(val name: String, val url: String)

/** Persists the user's calendar subscriptions in SharedPreferences (local only;
 *  the events they pull in do sync, but the subscription list itself is per-device). */
class CalSubStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cal_subs", Context.MODE_PRIVATE)

    fun list(): List<CalSub> = runCatching {
        PalmJson.decodeFromString<List<CalSub>>(prefs.getString("list", "[]") ?: "[]")
    }.getOrDefault(emptyList())

    private fun save(list: List<CalSub>) {
        prefs.edit().putString("list", PalmJson.encodeToString(list)).apply()
    }

    fun add(sub: CalSub) {
        if (list().any { it.url == sub.url }) return
        save(list() + sub)
    }

    fun remove(url: String) = save(list().filterNot { it.url == url })
}

/** Fetches every subscribed iCal feed and upserts its events. Read-only: events
 *  removed upstream are not deleted locally (a deliberate simplification). */
object CalendarSync {
    /** Returns the number of events added/updated, or a failure with the message. */
    suspend fun refresh(context: Context): Result<Int> {
        val subs = CalSubStore(context).list()
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

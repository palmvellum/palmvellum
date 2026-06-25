package dev.tatliving.palmvellum.organizers.data.hotsync

import dev.tatliving.palmvellum.organizers.data.Clock
import dev.tatliving.palmvellum.organizers.data.Ulid
import dev.tatliving.palmvellum.organizers.data.sync.SupabaseRest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The slice of the cloud `records` table the HotSync conduit needs — a Kotlin
 * mirror of the Go engine's `cloud.Client` (packages/palm-engine/cloud), talking
 * straight to PostgREST via [SupabaseRest]. Identity is the Palm record's
 * 24-bit unique id encoded as a `device_id` of "memo:<hex>" / "todo:<hex>",
 * upserted on (user_id, device_id) so re-sorts stay idempotent.
 */
class PalmCloud(private val rest: SupabaseRest, private val userId: String) {

    class Row(
        val id: String,
        val type: String,
        val body: String,
        val deviceId: String?,
        val aiStatus: String?,
        val aiResponse: String?,
        val metadata: JsonObject?,
        val createdAt: String? = null,
    )

    /** Cloud row id for a device_id, or null if none exists yet. */
    suspend fun findByDevice(deviceId: String): String? {
        val arr = rest.select("records", "user_id=eq.$userId&device_id=eq.$deviceId&select=id&limit=1").getOrThrow()
        return arr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /** All of the user's live records (deleted excluded). */
    suspend fun listForUser(): List<Row> {
        val arr = rest.select(
            "records",
            "user_id=eq.$userId&deleted_at=is.null" +
                "&select=id,type,body,device_id,ai_status,ai_response,metadata&limit=20000",
        ).getOrThrow()
        return arr.map { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            Row(
                id = s("id") ?: "",
                type = s("type") ?: "",
                body = s("body") ?: "",
                deviceId = s("device_id"),
                aiStatus = s("ai_status"),
                aiResponse = s("ai_response"),
                metadata = o["metadata"] as? JsonObject,
            )
        }
    }

    /** Insert a new record. [extra] adds/overrides columns (e.g. ai_status). Returns the new id. */
    suspend fun insert(
        type: String,
        body: String,
        deviceId: String,
        metadata: JsonObject,
        extra: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    ): String {
        val now = Clock.nowIso()
        val id = Ulid.new()
        val row = buildJson {
            put("id", id)
            put("user_id", userId)
            put("type", type)
            put("posture", "open")
            put("body", body)
            put("source", "palm")
            put("device_id", deviceId)
            put("metadata", metadata)
            put("created_at", now)
            put("updated_at", now)
            extra.forEach { (k, v) -> put(k, v) }
        }
        rest.upsert("records", listOf(row)).getOrThrow()
        return id
    }

    /** Patch an existing record by id. */
    suspend fun update(id: String, patch: Map<String, kotlinx.serialization.json.JsonElement>) {
        val row = buildJson {
            patch.forEach { (k, v) -> put(k, v) }
            put("updated_at", Clock.nowIso())
        }
        rest.patch("records", "id=eq.$id", row).getOrThrow()
    }

    /** Live records of one [type] (e.g. "contact", "mail"). */
    suspend fun listByType(type: String): List<Row> {
        val arr = rest.select(
            "records",
            "user_id=eq.$userId&type=eq.$type&deleted_at=is.null" +
                "&select=id,type,body,device_id,ai_status,ai_response,metadata,created_at&limit=20000",
        ).getOrThrow()
        return arr.map { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            Row(
                id = s("id") ?: "", type = s("type") ?: "", body = s("body") ?: "",
                deviceId = s("device_id"), aiStatus = s("ai_status"), aiResponse = s("ai_response"),
                metadata = o["metadata"] as? JsonObject, createdAt = s("created_at"),
            )
        }
    }

    // ── events table (Date Book) ───────────────────────────────────────────

    class Event(
        val id: String,
        val title: String,
        val startAt: String?,
        val endAt: String?,
        val allDay: Boolean,
        val notes: String?,
        val alarmMinutes: Int?,
        val deviceId: String?,
        val source: String?,
    )

    suspend fun findEventByDevice(deviceId: String): String? {
        val arr = rest.select("events", "user_id=eq.$userId&device_id=eq.$deviceId&select=id&limit=1").getOrThrow()
        return arr.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull
    }

    /**
     * The user's own Date Book events. Calendar-feed events (ics-sub / ics-import)
     * are excluded server-side so a large subscribed calendar isn't downloaded on
     * every HotSync (it would never be written to the device anyway). The `or`
     * keeps rows whose source is null (legacy / device-origin events).
     */
    suspend fun listEventsForUser(): List<Event> {
        val arr = rest.select(
            "events",
            "user_id=eq.$userId&deleted_at=is.null" +
                "&or=(source.is.null,source.not.in.(ics-sub,ics-import))" +
                "&select=id,title,start_at,end_at,all_day,notes,alarm_minutes,device_id,source&limit=20000",
        ).getOrThrow()
        return arr.map { el ->
            val o = el.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            Event(
                id = s("id") ?: "", title = s("title") ?: "",
                startAt = s("start_at"), endAt = s("end_at"),
                allDay = o["all_day"]?.jsonPrimitive?.booleanOrNull ?: false,
                notes = s("notes"),
                alarmMinutes = o["alarm_minutes"]?.jsonPrimitive?.intOrNull,
                deviceId = s("device_id"),
                source = s("source"),
            )
        }
    }

    suspend fun insertEvent(patch: Map<String, kotlinx.serialization.json.JsonElement>): String {
        val now = Clock.nowIso()
        val id = Ulid.new()
        val row = buildJson {
            put("id", id)
            put("user_id", userId)
            put("source", "palm")
            put("created_at", now)
            put("updated_at", now)
            patch.forEach { (k, v) -> put(k, v) }
        }
        rest.upsert("events", listOf(row)).getOrThrow()
        return id
    }

    suspend fun updateEvent(id: String, patch: Map<String, kotlinx.serialization.json.JsonElement>) {
        val row = buildJson {
            patch.forEach { (k, v) -> put(k, v) }
            put("updated_at", Clock.nowIso())
        }
        rest.patch("events", "id=eq.$id", row).getOrThrow()
    }

    private inline fun buildJson(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        kotlinx.serialization.json.buildJsonObject(block)
}

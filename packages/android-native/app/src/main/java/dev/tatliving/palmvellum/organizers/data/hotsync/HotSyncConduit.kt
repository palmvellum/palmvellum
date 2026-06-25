package dev.tatliving.palmvellum.organizers.data.hotsync

import dev.tatliving.palmvellum.organizers.data.card.AddressDb
import dev.tatliving.palmvellum.organizers.data.card.AddressPhone
import dev.tatliving.palmvellum.organizers.data.card.Appointment
import dev.tatliving.palmvellum.organizers.data.card.Contact
import dev.tatliving.palmvellum.organizers.data.card.DatebookDb
import dev.tatliving.palmvellum.organizers.data.card.Mail
import dev.tatliving.palmvellum.organizers.data.card.MailDb
import dev.tatliving.palmvellum.organizers.data.card.Memo
import dev.tatliving.palmvellum.organizers.data.card.MemoAppInfo
import dev.tatliving.palmvellum.organizers.data.card.MemoDb
import dev.tatliving.palmvellum.organizers.data.card.PalmDate
import dev.tatliving.palmvellum.organizers.data.card.PalmDb
import dev.tatliving.palmvellum.organizers.data.card.ToDoDb
import dev.tatliving.palmvellum.organizers.data.card.Todo
import dev.tatliving.palmvellum.organizers.data.card.TodoAppInfo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * The cloud half of a USB HotSync: push the device's Memo Pad + To Do databases
 * to Supabase, then regenerate them from cloud state and write them back to the
 * device. A faithful Kotlin port of the on-device-verified Go engine
 * (`packages/palm-engine/sync/sync.go`), reusing the existing [HotSyncSession]
 * for transport and [PalmCloud] for the `records` table.
 *
 * Identity & idempotency match the desktop contract: each Palm record's 24-bit
 * unique id maps to a cloud device_id "memo:<hex>" / "todo:<hex>". Pull is
 * last-write-wins (cloud authoritative); records deleted in the cloud are left
 * on the device for v1 (see docs/cross-platform-desktop-sync-feasibility.md).
 *
 * Databases covered: Memo + To Do + Date Book (events table) + Address +
 * Mail (cloud→device only). Palm-local times are converted via [zone].
 */
class HotSyncConduit(
    private val cloud: PalmCloud,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /**
     * Run a full round-trip on an opened [session], reporting progress via [log].
     *
     * Each database is synced independently: a failure in one (a codec bug, a
     * missing DB, or a USB read timeout) is logged and the others still run.
     * Memo Pad + To Do are the on-hardware-proven conduits and go first; the
     * newer Date Book / Address / Mail conduits follow. If the USB link itself
     * dies mid-run (a NetSync timeout desyncs the stream), the remaining
     * databases are skipped — there is no point hammering a dead link — and the
     * caller still sends a clean EndOfSync.
     */
    fun run(session: HotSyncSession, log: (String) -> Unit) {
        step(session, log, "Memo Pad") { syncMemo(session, log) }
        step(session, log, "To Do") { syncTodo(session, log) }
        step(session, log, "Date Book") { syncDatebook(session, log) }
        step(session, log, "Address") { syncAddress(session, log) }
        step(session, log, "Mail") { syncMail(session, log) }
    }

    /** Run one database's sync in isolation; log + swallow any failure. */
    private inline fun step(
        session: HotSyncSession,
        log: (String) -> Unit,
        name: String,
        block: () -> Unit,
    ) {
        if (!session.linkAlive()) {
            log("$name: skipped — USB link lost")
            return
        }
        try {
            block()
        } catch (e: Exception) {
            log("$name: sync failed — ${e.message ?: e.toString()}")
            if (!session.linkAlive()) log("USB link lost; skipping remaining databases")
        }
    }

    // ── Memo Pad ──
    private fun syncMemo(session: HotSyncSession, log: (String) -> Unit) {
        val memoDev = session.readDb("MemoDB", log)
        if (memoDev == null) { log("(no Memo Pad on device)"); return }
        log("Memo Pad: read ${memoDev.records.size} record(s)")
        val ai = memoDev.appInfo?.let { MemoAppInfo.parse(it) } ?: MemoAppInfo.default()
        val palmDb = session.asPalmDb(memoDev, "memo")
        val pushed = memoPush(palmDb, ai)
        log("Memo Pad -> cloud: +${pushed.inserted} new, ~${pushed.updated} updated, ${pushed.skipped} skipped")
        val (memos, mergedAi) = memoPull(ai)
        session.writeBack("MemoDB", MemoDb.encode(memos), mergedAi.encode())
        log("Memo Pad <- cloud: wrote ${memos.size} memo(s)")
    }

    // ── To Do List ──
    private fun syncTodo(session: HotSyncSession, log: (String) -> Unit) {
        val todoDev = session.readDb("ToDoDB", log)
        if (todoDev == null) { log("(no To Do List on device)"); return }
        log("To Do: read ${todoDev.records.size} record(s)")
        val ai = todoDev.appInfo?.let { TodoAppInfo.parse(it) } ?: TodoAppInfo.default()
        val palmDb = session.asPalmDb(todoDev, "todo")
        val pushed = todoPush(palmDb, ai)
        log("To Do -> cloud: +${pushed.inserted} new, ~${pushed.updated} updated, ${pushed.skipped} skipped")
        val (todos, mergedAi) = todoPull(ai)
        session.writeBack("ToDoDB", ToDoDb.encode(todos), mergedAi.encode())
        log("To Do <- cloud: wrote ${todos.size} todo(s)")
    }

    // ── Date Book (events table) ──
    private fun syncDatebook(session: HotSyncSession, log: (String) -> Unit) {
        log("Date Book: opening DatebookDB…")
        val dateDev = session.readDb("DatebookDB", log)
        if (dateDev == null) { log("(no Date Book on device)"); return }
        log("Date Book: read ${dateDev.records.size} record(s)")
        val palmDb = session.asPalmDb(dateDev, "date")
        val pushed = datebookPush(palmDb)
        log("Date Book -> cloud: +${pushed.inserted} new, ~${pushed.updated} updated, ${pushed.skipped} skipped")
        val appts = datebookPull()
        // Date Book categories aren't modelled; keep the device's AppInfo as-is.
        session.writeBack("DatebookDB", DatebookDb.encode(appts), null)
        log("Date Book <- cloud: wrote ${appts.size} appointment(s)")
    }

    // ── Address (records type=contact) ──
    private fun syncAddress(session: HotSyncSession, log: (String) -> Unit) {
        log("Address: opening AddressDB…")
        val addrDev = session.readDb("AddressDB", log)
        if (addrDev == null) { log("(no Address Book on device)"); return }
        log("Address: read ${addrDev.records.size} record(s)")
        val ai = addrDev.appInfo?.let { MemoAppInfo.parse(it) } // categories only
        val palmDb = session.asPalmDb(addrDev, "addr")
        val pushed = addressPush(palmDb, ai)
        log("Address -> cloud: +${pushed.inserted} new, ~${pushed.updated} updated, ${pushed.skipped} skipped")
        val contacts = addressPull(ai)
        // Preserve the device's 638-byte AppInfo verbatim (field labels) by
        // not rewriting it — categories are only mapped to existing slots.
        session.writeBack("AddressDB", AddressDb.encode(contacts), null)
        log("Address <- cloud: wrote ${contacts.size} contact(s)")
    }

    // ── Mail (cloud → device only) ──
    private fun syncMail(session: HotSyncSession, log: (String) -> Unit) {
        log("Mail: opening MailDB…")
        val mailDev = session.readDb("MailDB", log)
        if (mailDev == null) { log("(no Mail on device)"); return }
        val mails = mailPull()
        session.writeBack("MailDB", MailDb.encode(mails), null)
        log("Mail <- cloud: wrote ${mails.size} digest(s) to Inbox")
    }

    class PushResult(var inserted: Int = 0, var updated: Int = 0, var skipped: Int = 0)

    // ─── Memo ────────────────────────────────────────────────────────────

    private fun classifyMemo(ai: MemoAppInfo, m: Memo): Pair<String, String> {
        val catName = ai.categoryName(m.category)
        return if (catName == "AI") "aiquery" to catName else "thought" to catName
    }

    private fun memoPush(db: PalmDb, ai: MemoAppInfo): PushResult {
        val res = PushResult()
        val memos = MemoDb.decode(db)
        for (m in memos) {
            if (m.text.isBlank()) { res.skipped++; continue }
            var body = m.text
            val sepAt = body.indexOf(AI_SEPARATOR)
            if (sepAt >= 0) body = body.substring(0, sepAt)
            body = body.trimEnd('\n')

            val (cloudType, catName) = classifyMemo(ai, m)
            val deviceId = "memo:%06x".format(m.uniqueId)
            val meta = buildJsonObject {
                put("palm_memo_uid", JsonPrimitive("%06x".format(m.uniqueId)))
                put("palm_category_idx", JsonPrimitive(m.category))
                put("palm_category_name", JsonPrimitive(catName))
            }
            val existing = runBlockingCloud { cloud.findByDevice(deviceId) }
            if (existing == null) {
                val extra = if (cloudType == "aiquery") mapOf("ai_status" to JsonPrimitive("pending")) else emptyMap()
                runBlockingCloud { cloud.insert(cloudType, body, deviceId, meta, extra) }
                res.inserted++
            } else {
                runBlockingCloud { cloud.update(existing, mapOf("body" to JsonPrimitive(body), "metadata" to meta)) }
                res.updated++
            }
        }
        return res
    }

    private fun memoPull(deviceAi: MemoAppInfo): Pair<List<Memo>, MemoAppInfo> {
        val rows = runBlockingCloud { cloud.listForUser() }
        val ai = deviceAi // mutate the device's own AppInfo so categories stay valid on-device
        val aiCatIdx = ai.ensureCategory("AI")
        var maxUid = maxDeviceUid(rows, "memo:")
        val backfills = ArrayList<Pair<String, String>>()
        val memos = ArrayList<Memo>()
        for (r in rows) {
            if (r.type != "aiquery" && r.type != "thought") continue
            if (r.deviceId != null && !r.deviceId.startsWith("memo:")) continue
            var body = r.body
            if (r.type == "aiquery" && !r.aiResponse.isNullOrEmpty()) body = body + AI_SEPARATOR + r.aiResponse
            val category = when {
                r.type == "aiquery" -> aiCatIdx
                else -> metaCategory(r.metadata)?.takeIf { it.isNotEmpty() && it != "AI" }?.let { ai.ensureCategory(it) } ?: 0
            }
            val uid: Int
            if (r.deviceId != null) {
                uid = r.deviceId.removePrefix("memo:").toIntOrNull(16) ?: 0
            } else {
                maxUid++
                uid = maxUid
                backfills.add(r.id to "memo:%06x".format(uid))
            }
            memos.add(Memo(uid, category, body))
        }
        backfills.forEach { (id, dev) -> runBlockingCloud { cloud.update(id, mapOf("device_id" to JsonPrimitive(dev))) } }
        return memos to ai
    }

    // ─── To Do ───────────────────────────────────────────────────────────

    private fun todoPush(db: PalmDb, ai: TodoAppInfo): PushResult {
        val res = PushResult()
        val todos = ToDoDb.decode(db)
        for (t in todos) {
            if (t.description.isBlank()) { res.skipped++; continue }
            val catName = ai.categories.categoryName(t.category)
            val deviceId = "todo:%06x".format(t.uniqueId)
            val meta = buildJsonObject {
                put("palm_todo_uid", JsonPrimitive("%06x".format(t.uniqueId)))
                put("palm_category_idx", JsonPrimitive(t.category))
                put("palm_category_name", JsonPrimitive(catName))
                put("palm_priority", JsonPrimitive(t.priority))
                put("palm_due_date", JsonPrimitive(t.dueDate?.iso() ?: ""))
                put("palm_completed", JsonPrimitive(t.completed))
                put("palm_notes", JsonPrimitive(t.notes))
            }
            val existing = runBlockingCloud { cloud.findByDevice(deviceId) }
            if (existing == null) {
                runBlockingCloud { cloud.insert("todo", t.description, deviceId, meta) }
                res.inserted++
            } else {
                runBlockingCloud { cloud.update(existing, mapOf("body" to JsonPrimitive(t.description), "metadata" to meta)) }
                res.updated++
            }
        }
        return res
    }

    private fun todoPull(deviceAi: TodoAppInfo): Pair<List<Todo>, TodoAppInfo> {
        val rows = runBlockingCloud { cloud.listForUser() }
        val ai = deviceAi
        var maxUid = maxDeviceUid(rows, "todo:")
        val backfills = ArrayList<Pair<String, String>>()
        val todos = ArrayList<Todo>()
        for (r in rows) {
            if (r.type != "todo") continue
            if (r.deviceId != null && !r.deviceId.startsWith("todo:")) continue
            val md = r.metadata
            val uid: Int
            if (r.deviceId != null) {
                uid = r.deviceId.removePrefix("todo:").toIntOrNull(16) ?: 0
            } else {
                maxUid++
                uid = maxUid
                backfills.add(r.id to "todo:%06x".format(uid))
            }
            val catName = metaString(md, "palm_category_name")
            val category = if (!catName.isNullOrEmpty()) ai.categories.ensureCategory(catName) else 0
            todos.add(
                Todo(
                    uniqueId = uid,
                    category = category,
                    dueDate = metaString(md, "palm_due_date")?.let { PalmDate.fromIso(it) },
                    priority = metaInt(md, "palm_priority") ?: 0,
                    completed = metaBool(md, "palm_completed") ?: false,
                    description = r.body,
                    notes = metaString(md, "palm_notes") ?: "",
                ),
            )
        }
        backfills.forEach { (id, dev) -> runBlockingCloud { cloud.update(id, mapOf("device_id" to JsonPrimitive(dev))) } }
        return todos to ai
    }

    // ─── Date Book (events table) ──────────────────────────────────────────

    private fun datebookPush(db: PalmDb): PushResult {
        val res = PushResult()
        for (a in DatebookDb.decode(db)) {
            val title = a.description.trim()
            if (title.isBlank()) { res.skipped++; continue }
            val deviceId = "date:%06x".format(a.uniqueId)
            val notes: JsonElement = if (a.note.isNotEmpty()) JsonPrimitive(a.note) else JsonNull
            val alarm: JsonElement = if (a.hasAlarm) JsonPrimitive(a.alarmAdvance * alarmUnitMinutes(a.alarmUnit)) else JsonNull
            val timed = !a.untimed
            val startIso = if (a.untimed) isoAllDay(a.year, a.month, a.day)
            else isoTimed(a.year, a.month, a.day, a.startHour, a.startMin)
            val endIso: JsonElement = if (timed && (a.endHour > a.startHour || (a.endHour == a.startHour && a.endMin >= a.startMin)))
                JsonPrimitive(isoTimed(a.year, a.month, a.day, a.endHour, a.endMin)) else JsonNull

            val existing = runBlockingCloud { cloud.findEventByDevice(deviceId) }
            if (existing == null) {
                val patch = mutableMapOf<String, JsonElement>(
                    "device_id" to JsonPrimitive(deviceId),
                    "title" to JsonPrimitive(clip(title, 256)),
                    "notes" to notes,
                    "alarm_minutes" to alarm,
                    "all_day" to JsonPrimitive(a.untimed),
                    "start_at" to JsonPrimitive(startIso),
                    "end_at" to endIso,
                )
                runBlockingCloud { cloud.insertEvent(patch) }
                res.inserted++
            } else {
                // Don't let an untimed Palm record flatten a richer cloud time.
                val patch = mutableMapOf<String, JsonElement>(
                    "title" to JsonPrimitive(clip(title, 256)),
                    "notes" to notes,
                    "alarm_minutes" to alarm,
                )
                if (timed) {
                    patch["start_at"] = JsonPrimitive(startIso)
                    patch["end_at"] = endIso
                    patch["all_day"] = JsonPrimitive(false)
                }
                runBlockingCloud { cloud.updateEvent(existing, patch) }
                res.updated++
            }
        }
        return res
    }

    private fun datebookPull(): List<Appointment> {
        // Calendar-feed events (a subscribed .ics calendar or one-off import) are
        // read-only and must NEVER be written onto a Palm: a single feed can hold
        // thousands of events and would inflate DatebookDB past what the vintage
        // device can hold, hanging the next HotSync. Feed events carry no
        // device_id, so without this guard the backfill below would assign each a
        // fresh date: UID, push it to the device, and persist the date: id back to
        // the cloud. Mirrors the Go engine guard (palm-engine/sync/pim.go
        // isFeedEventSource).
        // Only carry a rolling [now-1mo, now+1yr] window on the device; the cloud
        // keeps the full history. The vintage Palm has limited RAM, so a large
        // back-catalogue of past/far-future events would inflate DatebookDB and
        // hang HotSync. Out-of-window events stay in the cloud (and are not
        // back-filled) and re-appear on-device once they enter the window.
        // Mirrors the Go engine (palm-engine/sync/pim.go inDatebookWindow).
        val nowZ = java.time.ZonedDateTime.now(zone)
        val windowLo = nowZ.minusMonths(1).toInstant()
        val windowHi = nowZ.plusYears(1).toInstant()
        val events = runBlockingCloud { cloud.listEventsForUser() }
            .filterNot { it.source == "ics-sub" || it.source == "ics-import" }
            .filter { e ->
                val st = parseInstant(e.startAt) ?: return@filter false
                !st.isBefore(windowLo) && !st.isAfter(windowHi)
            }
        var maxUid = 0
        for (e in events) {
            val d = e.deviceId ?: continue
            if (!d.startsWith("date:")) continue
            val u = d.removePrefix("date:").toIntOrNull(16) ?: continue
            if (u > maxUid) maxUid = u
        }
        val backfills = ArrayList<Pair<String, String>>()
        val appts = ArrayList<Appointment>()
        for (e in events) {
            if (e.deviceId != null && !e.deviceId.startsWith("date:")) continue
            val uid: Int
            if (e.deviceId != null) {
                uid = e.deviceId.removePrefix("date:").toIntOrNull(16) ?: 0
            } else {
                maxUid++; uid = maxUid
                backfills.add(e.id to "date:%06x".format(uid))
            }
            val start = parseInstant(e.startAt)?.atZone(zone) ?: continue
            val a = Appointment(
                uniqueId = uid, year = start.year, month = start.monthValue, day = start.dayOfMonth,
                description = e.title, note = e.notes ?: "",
            )
            if (e.allDay) {
                a.untimed = true
            } else {
                a.startHour = start.hour; a.startMin = start.minute
                val end = parseInstant(e.endAt)?.atZone(zone)
                if (end != null) { a.endHour = end.hour; a.endMin = end.minute }
                else { a.endHour = start.hour; a.endMin = start.minute }
            }
            e.alarmMinutes?.let {
                a.hasAlarm = true
                val (adv, unit) = compactAlarm(it)
                a.alarmAdvance = adv; a.alarmUnit = unit
            }
            appts.add(a)
        }
        backfills.forEach { (id, dev) -> runBlockingCloud { cloud.updateEvent(id, mapOf("device_id" to JsonPrimitive(dev))) } }
        return appts
    }

    private fun alarmUnitMinutes(unit: Int): Int = when (unit) { 1 -> 60; 2 -> 1440; else -> 1 }

    private fun compactAlarm(min: Int): Pair<Int, Int> = when {
        min <= 0 -> 0 to 0
        min % 1440 == 0 -> (min / 1440) to 2
        min % 60 == 0 -> (min / 60) to 1
        else -> min to 0
    }

    // ─── Address (records type=contact) ────────────────────────────────────

    private fun contactMetadata(c: Contact, catName: String): JsonObject = buildJsonObject {
        put("palm_first_name", JsonPrimitive(c.first))
        put("palm_last_name", JsonPrimitive(c.last))
        put("palm_company", JsonPrimitive(c.company))
        put("palm_title", JsonPrimitive(c.title))
        put("palm_phones", JsonArray(c.phones.map { p ->
            buildJsonObject { put("label", JsonPrimitive(p.label)); put("value", JsonPrimitive(p.value)) }
        }))
        put("palm_address", JsonPrimitive(c.address))
        put("palm_city", JsonPrimitive(c.city))
        put("palm_state", JsonPrimitive(c.state))
        put("palm_zip", JsonPrimitive(c.zip))
        put("palm_country", JsonPrimitive(c.country))
        put("palm_notes", JsonPrimitive(c.note))
        put("palm_category_name", JsonPrimitive(catName))
    }

    private fun addressPush(db: PalmDb, ai: MemoAppInfo?): PushResult {
        val res = PushResult()
        for (ct in AddressDb.decode(db)) {
            if (ct.displayName() == "(no name)" && ct.phones.isEmpty()) { res.skipped++; continue }
            val catName = ai?.categoryName(ct.category) ?: "Unfiled"
            val deviceId = "addr:%06x".format(ct.uniqueId)
            val meta = contactMetadata(ct, catName)
            val existing = runBlockingCloud { cloud.findByDevice(deviceId) }
            if (existing == null) {
                runBlockingCloud { cloud.insert("contact", ct.displayName(), deviceId, meta) }
                res.inserted++
            } else {
                runBlockingCloud { cloud.update(existing, mapOf("body" to JsonPrimitive(ct.displayName()), "metadata" to meta)) }
                res.updated++
            }
        }
        return res
    }

    private fun addressPull(ai: MemoAppInfo?): List<Contact> {
        val rows = runBlockingCloud { cloud.listByType("contact") }
        var maxUid = maxDeviceUid(rows, "addr:")
        val backfills = ArrayList<Pair<String, String>>()
        val contacts = ArrayList<Contact>()
        for (r in rows) {
            if (r.deviceId != null && !r.deviceId.startsWith("addr:")) continue
            val uid: Int
            if (r.deviceId != null) {
                uid = r.deviceId.removePrefix("addr:").toIntOrNull(16) ?: 0
            } else {
                maxUid++; uid = maxUid
                backfills.add(r.id to "addr:%06x".format(uid))
            }
            val ct = contactFromMetadata(r.metadata)
            ct.uniqueId = uid
            // Map to an existing category only; unknown names fall to Unfiled (0).
            metaCategory(r.metadata)?.let { name ->
                ai?.findByName(name)?.let { ct.category = it }
            }
            contacts.add(ct)
        }
        backfills.forEach { (id, dev) -> runBlockingCloud { cloud.update(id, mapOf("device_id" to JsonPrimitive(dev))) } }
        return contacts
    }

    private fun contactFromMetadata(md: JsonObject?): Contact {
        val c = Contact()
        if (md == null) return c
        c.first = metaString(md, "palm_first_name") ?: ""
        c.last = metaString(md, "palm_last_name") ?: ""
        c.company = metaString(md, "palm_company") ?: ""
        c.title = metaString(md, "palm_title") ?: ""
        c.address = metaString(md, "palm_address") ?: ""
        c.city = metaString(md, "palm_city") ?: ""
        c.state = metaString(md, "palm_state") ?: ""
        c.zip = metaString(md, "palm_zip") ?: ""
        c.country = metaString(md, "palm_country") ?: ""
        c.note = metaString(md, "palm_notes") ?: ""
        (md["palm_phones"] as? JsonArray)?.forEach { el ->
            val o = el as? JsonObject ?: return@forEach
            val value = o["value"]?.jsonPrimitive?.contentOrNull ?: ""
            if (value.isNotEmpty()) {
                c.phones.add(AddressPhone(o["label"]?.jsonPrimitive?.contentOrNull ?: "", value))
            }
        }
        return c
    }

    // ─── Mail (cloud → device only) ────────────────────────────────────────

    private fun mailPull(): List<Mail> {
        val rows = runBlockingCloud { cloud.listByType("mail") }
        var maxUid = maxDeviceUid(rows, "mail:")
        val backfills = ArrayList<Pair<String, String>>()
        val mails = ArrayList<Mail>()
        // Only the last few days of mail go to the Palm — old digests are noise on
        // a storage-limited handheld. The cloud keeps the full history.
        val cutoff = Instant.now().minus(MAIL_SYNC_DAYS.toLong(), ChronoUnit.DAYS)
        for (r in rows) {
            if (r.deviceId != null && !r.deviceId.startsWith("mail:")) continue
            val created = parseInstant(r.createdAt)
            if (created != null && created.isBefore(cutoff)) continue
            val uid: Int
            if (r.deviceId != null) {
                uid = r.deviceId.removePrefix("mail:").toIntOrNull(16) ?: 0
            } else {
                maxUid++; uid = maxUid
                backfills.add(r.id to "mail:%06x".format(uid))
            }
            val subject = metaString(r.metadata, "mail_subject")?.ifEmpty { null } ?: "(digest)"
            val from = metaString(r.metadata, "mail_source_name") ?: ""
            val m = Mail(uniqueId = uid, category = 0, subject = subject, from = from, body = r.body)
            created?.atZone(zone)?.let { t ->
                m.year = t.year; m.month = t.monthValue; m.day = t.dayOfMonth
                m.hour = t.hour; m.min = t.minute
            }
            mails.add(m)
        }
        backfills.forEach { (id, dev) -> runBlockingCloud { cloud.update(id, mapOf("device_id" to JsonPrimitive(dev))) } }
        return mails
    }

    // ─── time helpers ──────────────────────────────────────────────────────

    private fun isoTimed(y: Int, m: Int, d: Int, hh: Int, mm: Int): String =
        ZonedDateTime.of(y, m, d, hh, mm, 0, 0, zone).toInstant().toString()

    private fun isoAllDay(y: Int, m: Int, d: Int): String =
        ZonedDateTime.of(y, m, d, 0, 0, 0, 0, zone).toInstant().toString()

    /** Parse a cloud timestamp (ISO instant or with offset) to an Instant. */
    private fun parseInstant(s: String?): Instant? {
        if (s.isNullOrBlank()) return null
        return runCatching { Instant.parse(s) }
            .recoverCatching { OffsetDateTime.parse(s).toInstant() }
            .getOrNull()
    }

    private fun clip(s: String, n: Int): String = if (s.length <= n) s else s.substring(0, n)

    // ─── helpers ─────────────────────────────────────────────────────────

    private fun metaCategory(md: JsonObject?): String? = metaString(md, "palm_category_name")
    private fun metaString(md: JsonObject?, k: String): String? = md?.get(k)?.jsonPrimitive?.contentOrNull
    private fun metaInt(md: JsonObject?, k: String): Int? = md?.get(k)?.jsonPrimitive?.intOrNull
    private fun metaBool(md: JsonObject?, k: String): Boolean? = md?.get(k)?.jsonPrimitive?.booleanOrNull

    private fun maxDeviceUid(rows: List<PalmCloud.Row>, prefix: String): Int {
        var max = 0
        for (r in rows) {
            val d = r.deviceId ?: continue
            if (!d.startsWith(prefix)) continue
            val uid = d.removePrefix(prefix).toIntOrNull(16) ?: continue
            if (uid > max) max = uid
        }
        return max
    }

    // The conduit runs on a background thread (Dispatchers.IO via the caller);
    // the SupabaseRest calls are suspend, so bridge them with runBlocking. This
    // is intentional: the whole HotSync session is a single sequential script.
    private fun <T> runBlockingCloud(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    companion object {
        /** Divides the user's question from the AI answer inside a single memo. */
        const val AI_SEPARATOR = "\n-- AI --\n"

        /** How many days of mail history are written to the Palm (cloud keeps all). */
        const val MAIL_SYNC_DAYS = 5
    }
}

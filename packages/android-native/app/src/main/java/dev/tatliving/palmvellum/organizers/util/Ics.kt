package dev.tatliving.palmvellum.organizers.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One parsed VEVENT. [startIso]/[endIso] are ISO-8601 instants (UTC, "…Z"). */
data class IcsEvent(
    val uid: String?,
    val summary: String,
    val startIso: String,
    val endIso: String?,
    val allDay: Boolean,
    val location: String?,
    val description: String?,
)

/** Minimal iCalendar (RFC 5545) reader — enough to import VEVENTs from a file
 *  or a subscribed calendar feed. Ignores VTIMEZONE blocks; TZID values are
 *  resolved against the JVM tz database when possible.
 *
 *  Recurrence (RRULE) IS expanded: a recurring VEVENT yields one IcsEvent per
 *  occurrence, each with a synthetic uid "<uid>@<yyyymmdd>" so the caller's
 *  deterministic id is unique and stable per occurrence. Kept byte-for-byte in
 *  step with the PWA `ics.ts` so the same feed materialises identical event
 *  ids on every client. */
object Ics {
    private val basicDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    // Open-ended (no UNTIL/COUNT) rules materialise this far into the future;
    // past occurrences are always kept from DTSTART. Mirrors ics.ts.
    private const val RRULE_FUTURE_DAYS = 730L
    private const val RRULE_MAX_OCCURRENCES = 2000

    fun parse(text: String): List<IcsEvent> {
        val lines = unfold(text)
        val out = mutableListOf<IcsEvent>()
        var inEvent = false
        var uid: String? = null
        var summary = ""
        var location: String? = null
        var description: String? = null
        var start: Pair<String, Boolean>? = null
        var end: Pair<String, Boolean>? = null
        var rrule: String? = null
        val exdates = mutableListOf<String>()
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true; uid = null; summary = ""; location = null
                    description = null; start = null; end = null
                    rrule = null; exdates.clear()
                }
                line == "END:VEVENT" -> {
                    val s = start
                    if (inEvent && s != null) {
                        val ev = IcsEvent(
                            uid = uid,
                            summary = summary.ifBlank { "(untitled)" },
                            startIso = s.first,
                            endIso = end?.first,
                            allDay = s.second,
                            location = location?.ifBlank { null },
                            description = description?.ifBlank { null },
                        )
                        val rr = rrule
                        if (rr != null) out.addAll(expandRrule(ev, rr, exdates)) else out.add(ev)
                    }
                    inEvent = false
                }
                inEvent -> {
                    val (name, params, value) = splitProp(line) ?: continue
                    when (name.uppercase()) {
                        "UID" -> uid = value
                        "SUMMARY" -> summary = unescape(value)
                        "LOCATION" -> location = unescape(value)
                        "DESCRIPTION" -> description = unescape(value)
                        "DTSTART" -> start = parseDt(params, value)
                        "DTEND" -> end = parseDt(params, value)
                        "RRULE" -> rrule = value
                        "EXDATE" -> value.split(",").forEach { v ->
                            val d = v.trim().take(8)
                            if (Regex("^\\d{8}$").matches(d)) exdates.add(d)
                        }
                    }
                }
            }
        }
        return out
    }

    /** Expand one recurring VEVENT into its occurrences. Handles the FREQ /
     *  INTERVAL / COUNT / UNTIL / BYMONTHDAY subset present in real feeds. */
    private fun expandRrule(base: IcsEvent, rrule: String, exdates: List<String>): List<IcsEvent> {
        val parts = HashMap<String, String>()
        for (kv in rrule.split(";")) {
            val eq = kv.indexOf('=')
            if (eq > 0) parts[kv.substring(0, eq).uppercase()] = kv.substring(eq + 1)
        }
        val freq = (parts["FREQ"] ?: "").uppercase()
        if (freq !in setOf("DAILY", "WEEKLY", "MONTHLY", "YEARLY")) return listOf(base)

        val interval = (parts["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val count = parts["COUNT"]?.toIntOrNull()
        val untilMs = parts["UNTIL"]?.let { untilToMs(it) }
        val byMonthDay = parts["BYMONTHDAY"]?.toIntOrNull()

        val baseInstant = runCatching { Instant.parse(base.startIso) }.getOrNull() ?: return listOf(base)
        val baseMs = baseInstant.toEpochMilli()
        val baseZdt = baseInstant.atZone(ZoneOffset.UTC)
        val futureCapMs = Instant.now().toEpochMilli() + RRULE_FUTURE_DAYS * 86_400_000L
        val stopMs = if (untilMs != null) minOf(untilMs, futureCapMs) else futureCapMs
        val exSet = exdates.toHashSet()

        val out = mutableListOf<IcsEvent>()
        for (n in 0 until RRULE_MAX_OCCURRENCES) {
            if (count != null && n >= count) break
            val occZdt = advance(baseZdt, freq, interval.toLong() * n, byMonthDay)
            val occMs = occZdt.toInstant().toEpochMilli()
            if (occMs > stopMs + 86_400_000L) break
            val ymd = String.format(
                Locale.ROOT, "%04d%02d%02d",
                occZdt.year, occZdt.monthValue, occZdt.dayOfMonth,
            )
            if (exSet.contains(ymd)) continue
            val shift = occMs - baseMs
            out.add(
                base.copy(
                    uid = base.uid?.let { "$it@$ymd" },
                    startIso = Instant.ofEpochMilli(baseMs + shift).toString(),
                    endIso = base.endIso?.let {
                        Instant.ofEpochMilli(Instant.parse(it).toEpochMilli() + shift).toString()
                    },
                ),
            )
        }
        return out.ifEmpty { listOf(base) }
    }

    /** DTSTART advanced by [step] periods of [freq], in UTC. java.time clamps
     *  an overflowing day of month (Jan-31 monthly → Feb-28). */
    private fun advance(base: ZonedDateTime, freq: String, step: Long, byMonthDay: Int?): ZonedDateTime {
        val stepped = when (freq) {
            "DAILY" -> base.plusDays(step)
            "WEEKLY" -> base.plusWeeks(step)
            "MONTHLY" -> base.plusMonths(step)
            else -> base.plusYears(step) // YEARLY
        }
        return if (byMonthDay != null && (freq == "MONTHLY" || freq == "YEARLY")) {
            stepped.withDayOfMonth(byMonthDay.coerceIn(1, stepped.toLocalDate().lengthOfMonth()))
        } else {
            stepped
        }
    }

    /** UNTIL is a DATE (yyyymmdd) or a UTC/local date-time. Returns epoch ms. */
    private fun untilToMs(v: String): Long? = runCatching {
        if (Regex("^\\d{8}$").matches(v)) {
            LocalDate.parse(v, DateTimeFormatter.BASIC_ISO_DATE)
                .atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
        } else {
            val m = Regex("^(\\d{4})(\\d{2})(\\d{2})T(\\d{2})(\\d{2})(\\d{2})Z?$").find(v) ?: return null
            val (y, mo, d, h, mi, s) = m.destructured
            LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.toInt(), mi.toInt(), s.toInt())
                .toInstant(ZoneOffset.UTC).toEpochMilli()
        }
    }.getOrNull()

    /** RFC 5545 line unfolding: a leading space/tab continues the prior line. */
    private fun unfold(text: String): List<String> {
        val res = mutableListOf<String>()
        for (l in text.split("\n")) {
            val line = l.trimEnd('\r')
            if ((line.startsWith(" ") || line.startsWith("\t")) && res.isNotEmpty()) {
                res[res.lastIndex] = res.last() + line.substring(1)
            } else {
                res.add(line)
            }
        }
        return res
    }

    /** "NAME;PARAM=x:value" → (NAME, "PARAM=x", "value"). */
    private fun splitProp(line: String): Triple<String, String, String>? {
        val colon = line.indexOf(':')
        if (colon < 0) return null
        val left = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val semi = left.indexOf(';')
        return if (semi < 0) {
            Triple(left, "", value)
        } else {
            Triple(left.substring(0, semi), left.substring(semi + 1), value)
        }
    }

    private fun unescape(v: String): String =
        v.replace("\\n", "\n").replace("\\N", "\n")
            .replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** Returns (instantIso, allDay) or null if unparseable. */
    private fun parseDt(params: String, value: String): Pair<String, Boolean>? {
        val isDate = params.contains("VALUE=DATE", ignoreCase = true) ||
            (value.length == 8 && !value.contains("T"))
        val tzid = Regex("TZID=([^;]+)", RegexOption.IGNORE_CASE).find(params)?.groupValues?.get(1)
        return runCatching {
            if (isDate) {
                // An all-day DATE is timezone-independent, so pin it to UTC
                // midnight (NOT the device zone). Storing local midnight would
                // shift the UTC date back a day for positive-offset zones like
                // HK and make Apple Calendar show every all-day event one day
                // early — and would disagree with the PWA / mac clients, which
                // pin to UTC midnight, causing the same subscribed event to
                // flip-flop its date each time a different client syncs.
                // Canonical string MUST byte-match the other clients:
                // "YYYY-MM-DDT00:00:00.000Z".
                val d = LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE)
                // Locale.ROOT → guaranteed ASCII digits, byte-identical on every
                // device locale (some locales would render non-ASCII digits).
                String.format(Locale.ROOT, "%04d-%02d-%02dT00:00:00.000Z", d.year, d.monthValue, d.dayOfMonth) to true
            } else {
                val basic = value.removeSuffix("Z")
                val ldt = LocalDateTime.parse(basic, basicDateTime)
                val zone = when {
                    value.endsWith("Z") -> ZoneOffset.UTC
                    tzid != null -> runCatching { ZoneId.of(tzid) }.getOrDefault(ZoneId.of("Asia/Hong_Kong"))
                    else -> ZoneId.of("Asia/Hong_Kong")
                }
                ldt.atZone(zone).toInstant().toString() to false
            }
        }.getOrNull()
    }
}

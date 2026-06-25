package dev.tatliving.palmvellum.organizers.util

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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
 *  or a subscribed calendar feed. Ignores recurrence/timezone VTIMEZONE blocks;
 *  TZID values are resolved against the JVM tz database when possible. */
object Ics {
    private val basicDateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

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
        for (line in lines) {
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true; uid = null; summary = ""; location = null
                    description = null; start = null; end = null
                }
                line == "END:VEVENT" -> {
                    val s = start
                    if (inEvent && s != null) {
                        out.add(
                            IcsEvent(
                                uid = uid,
                                summary = summary.ifBlank { "(untitled)" },
                                startIso = s.first,
                                endIso = end?.first,
                                allDay = s.second,
                                location = location?.ifBlank { null },
                                description = description?.ifBlank { null },
                            ),
                        )
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
                    }
                }
            }
        }
        return out
    }

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
                    tzid != null -> runCatching { ZoneId.of(tzid) }.getOrDefault(ZoneId.systemDefault())
                    else -> ZoneId.systemDefault()
                }
                ldt.atZone(zone).toInstant().toString() to false
            }
        }.getOrNull()
    }
}

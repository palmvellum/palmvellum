package dev.tatliving.palmvellum.organizers.util

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Date/time helpers. Events store an ISO-8601 UTC instant; display and
 *  editing happen in the device's local zone. */
object DT {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    fun nowDate(): LocalDate = LocalDate.now(zone)
    fun nowTime(): LocalTime = LocalTime.now(zone).withSecond(0).withNano(0)

    fun toIso(date: LocalDate, time: LocalTime): String =
        date.atTime(time).atZone(zone).toInstant().toString()

    private fun parse(iso: String): LocalDateTime? =
        // Server timestamptz comes back with a numeric offset
        // ("2026-06-17T10:00:00+00:00"), which Instant.parse rejects — it only
        // accepts a "Z" suffix (what our own toIso() emits). Before this was
        // lenient, every server-pulled event fell through to nowDate() and the
        // whole calendar collapsed onto today. OffsetDateTime handles both forms.
        runCatching { OffsetDateTime.parse(iso).atZoneSameInstant(zone).toLocalDateTime() }
            .recoverCatching { Instant.parse(iso).atZone(zone).toLocalDateTime() }
            .recoverCatching { LocalDateTime.parse(iso) }
            .getOrNull()

    fun dayLabel(iso: String): String = parse(iso)?.toLocalDate()?.format(dayFmt) ?: iso
    fun timeLabel(iso: String): String = parse(iso)?.toLocalTime()?.format(timeFmt) ?: ""
    fun dateOf(iso: String): LocalDate = parse(iso)?.toLocalDate() ?: nowDate()
    fun timeOf(iso: String): LocalTime = parse(iso)?.toLocalTime() ?: nowTime()
    fun fmtDate(d: LocalDate): String = d.format(dateFmt)
    fun fmtTime(t: LocalTime): String = t.format(timeFmt)

    // ── Calendar grid (week starts Sunday — the Palm classic) ──────────
    private val monthTitleFmt = DateTimeFormatter.ofPattern("MMMM yyyy")
    private val monthShortFmt = DateTimeFormatter.ofPattern("MMM")
    private val weekdayFullFmt = DateTimeFormatter.ofPattern("EEEE")

    /** Single-letter weekday headers, Sunday-first (mirrors the PWA). */
    val DOW_SHORT = listOf("S", "M", "T", "W", "T", "F", "S")

    fun isToday(d: LocalDate): Boolean = d == nowDate()
    fun isWeekend(d: LocalDate): Boolean =
        d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    /** 0 = Sunday … 6 = Saturday (java DayOfWeek is Mon=1..Sun=7). */
    private fun sundayIndex(d: LocalDate): Int = d.dayOfWeek.value % 7

    fun startOfWeek(d: LocalDate): LocalDate = d.minusDays(sundayIndex(d).toLong())
    fun startOfMonth(d: LocalDate): LocalDate = d.withDayOfMonth(1)

    /** 42 days (6 weeks) covering the month of [anchor], starting on a Sunday. */
    fun monthGrid(anchor: LocalDate): List<LocalDate> {
        val first = startOfWeek(startOfMonth(anchor))
        return (0L until 42L).map { first.plusDays(it) }
    }

    /** The 7 days of the week containing [anchor], Sunday-first. */
    fun weekDays(anchor: LocalDate): List<LocalDate> {
        val s = startOfWeek(anchor)
        return (0L until 7L).map { s.plusDays(it) }
    }

    /** Monday–Friday (一至五) of the week containing [anchor]. */
    fun workWeekDays(anchor: LocalDate): List<LocalDate> {
        val monday = anchor.minusDays(((anchor.dayOfWeek.value + 6) % 7).toLong())
        return (0L until 5L).map { monday.plusDays(it) }
    }

    /** [count] consecutive days starting at [start] (default today). */
    fun nextDays(start: LocalDate = nowDate(), count: Int = 7): List<LocalDate> =
        (0L until count.toLong()).map { start.plusDays(it) }

    fun monthTitle(d: LocalDate): String = d.format(monthTitleFmt)

    /** e.g. "Jun 8 – 14" or "Jun 29 – Jul 5". */
    fun weekTitle(anchor: LocalDate): String {
        val s = startOfWeek(anchor)
        val e = s.plusDays(6)
        return if (s.month == e.month) {
            "${s.format(monthShortFmt)} ${s.dayOfMonth} – ${e.dayOfMonth}"
        } else {
            "${s.format(monthShortFmt)} ${s.dayOfMonth} – ${e.format(monthShortFmt)} ${e.dayOfMonth}"
        }
    }

    fun weekdayFull(d: LocalDate): String = d.format(weekdayFullFmt)
}

fun pickDate(context: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    DatePickerDialog(
        context,
        { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth,
    ).show()
}

fun pickTime(context: Context, initial: LocalTime, onPick: (LocalTime) -> Unit) {
    TimePickerDialog(
        context,
        { _, h, min -> onPick(LocalTime.of(h, min)) },
        initial.hour, initial.minute, true,
    ).show()
}

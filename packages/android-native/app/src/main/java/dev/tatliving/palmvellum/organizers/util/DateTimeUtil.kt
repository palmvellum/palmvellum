package dev.tatliving.palmvellum.organizers.util

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
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
        runCatching { Instant.parse(iso).atZone(zone).toLocalDateTime() }.getOrNull()

    fun dayLabel(iso: String): String = parse(iso)?.toLocalDate()?.format(dayFmt) ?: iso
    fun timeLabel(iso: String): String = parse(iso)?.toLocalTime()?.format(timeFmt) ?: ""
    fun dateOf(iso: String): LocalDate = parse(iso)?.toLocalDate() ?: nowDate()
    fun timeOf(iso: String): LocalTime = parse(iso)?.toLocalTime() ?: nowTime()
    fun fmtDate(d: LocalDate): String = d.format(dateFmt)
    fun fmtTime(t: LocalTime): String = t.format(timeFmt)
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

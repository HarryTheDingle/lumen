package com.notdxbi.dxbi.tv.phone

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Thread-safe cached date formatters (java.time isn't available on Android 7). */
object Dates {
    private val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    private fun local(pattern: String, locale: Locale? = null) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, locale ?: Locale.getDefault())
    }

    private fun utc(pattern: String) = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat(pattern, Locale.US).apply { timeZone = UTC }
    }

    private val utcDay = utc("yyyy-MM-dd")
    private val utcIso = utc("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
    private val localDay = local("yyyy-MM-dd", Locale.US)
    private val shortDay = local("d MMM")
    private val mediumDay = local("d MMM yyyy")
    private val longDay = local("d MMMM yyyy")
    private val monthYear = local("MMMM yyyy")
    private val weekdayDayMonth = local("EEEE d MMMM")
    private val usMonth = local("MMMM", Locale.US)
    private val usFullDate = local("EEEE MMMM d, yyyy", Locale.US)
    private val clockTime = local("HH:mm")

    fun parseUtcDay(s: String): Long? = runCatching { utcDay.get()!!.parse(s)?.time }.getOrNull()
    fun parseUtcIso(s: String): Long? = runCatching { utcIso.get()!!.parse(s)?.time }.getOrNull()
    fun parseLocalDay(s: String): Date? = runCatching { localDay.get()!!.parse(s) }.getOrNull()
    fun formatLocalDay(date: Date = Date()): String = localDay.get()!!.format(date)
    fun formatShortDay(millis: Long): String = shortDay.get()!!.format(Date(millis))
    fun formatMediumDay(date: Date): String = mediumDay.get()!!.format(date)
    fun formatLongDay(date: Date): String = longDay.get()!!.format(date)
    fun formatMonthYear(date: Date): String = monthYear.get()!!.format(date)
    fun formatWeekdayDayMonth(millis: Long): String = weekdayDayMonth.get()!!.format(Date(millis))
    fun formatUsMonth(date: Date): String = usMonth.get()!!.format(date)
    fun parseUsFullDate(s: String): Long? = runCatching { usFullDate.get()!!.parse(s)?.time }.getOrNull()
    fun formatTime(date: Date = Date()): String = clockTime.get()!!.format(date)
}

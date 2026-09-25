package com.example.clubmate.ui.components

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Human-friendly times for lists and chats. */
object TimeFormat {

    fun time(timestamp: Long): String =
        SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))

    fun date(timestamp: Long): String =
        SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))

    fun dateTime(timestamp: Long): String =
        SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(timestamp))

    /** For chat lists: "9:41 AM", "Yesterday", "Mon", "12 Mar" or "12/03/24". */
    fun listTimestamp(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        if (timestamp <= 0) return ""
        val days = daysBetween(timestamp, now)
        return when {
            days == 0 -> time(timestamp)
            days == 1 -> "Yesterday"
            days in 2..6 -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(timestamp))
            sameYear(timestamp, now) -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    /** For day separators in a chat: "Today", "Yesterday", "Monday", "12 March 2026". */
    fun dayLabel(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val days = daysBetween(timestamp, now)
        return when {
            days == 0 -> "Today"
            days == 1 -> "Yesterday"
            days in 2..6 -> SimpleDateFormat("EEEE", Locale.getDefault()).format(Date(timestamp))
            sameYear(timestamp, now) -> SimpleDateFormat("d MMMM", Locale.getDefault()).format(Date(timestamp))
            else -> SimpleDateFormat("d MMMM yyyy", Locale.getDefault()).format(Date(timestamp))
        }
    }

    fun sameDay(a: Long, b: Long): Boolean = daysBetween(a, b) == 0

    private fun startOfDay(timestamp: Long): Long = Calendar.getInstance().apply {
        timeInMillis = timestamp
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun daysBetween(earlier: Long, later: Long): Int =
        Math.round((startOfDay(later) - startOfDay(earlier)) / 86_400_000.0).toInt()

    private fun sameYear(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
    }
}

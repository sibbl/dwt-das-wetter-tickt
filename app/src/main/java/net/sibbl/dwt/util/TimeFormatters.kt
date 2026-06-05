package net.sibbl.dwt.util

import android.content.Context
import net.sibbl.dwt.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.absoluteValue

object TimeFormatters {
    private val absoluteFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun absoluteTime(timestampMillis: Long): String {
        return absoluteFormatter.format(
            Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
        )
    }

    fun relativeOffset(context: Context, selectedMillis: Long, nowMillis: Long): String {
        val deltaMinutes = ((selectedMillis - nowMillis) / 60_000L).toInt()
        if (deltaMinutes == 0) {
            return context.getString(R.string.time_now)
        }
        val absoluteMinutes = deltaMinutes.absoluteValue
        val hours = absoluteMinutes / 60
        val minutes = absoluteMinutes % 60
        val durationText = if (hours > 0) {
            context.getString(R.string.time_hours_minutes_format, hours, minutes)
        } else {
            context.getString(R.string.time_minutes_format, minutes)
        }
        return if (deltaMinutes < 0) {
            context.getString(R.string.time_past_format, durationText)
        } else {
            context.getString(R.string.time_future_format, durationText)
        }
    }

    fun compactOffset(selectedMillis: Long, nowMillis: Long): String {
        val deltaMinutes = ((selectedMillis - nowMillis) / 60_000L).toInt()
        val absoluteMinutes = deltaMinutes.absoluteValue
        val hours = absoluteMinutes / 60
        val minutes = absoluteMinutes % 60
        val sign = if (deltaMinutes < 0) "-" else "+"
        return "($sign$hours:%02dh)".format(minutes)
    }
}

package de.sibbl.dwdradar.util

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.max

object QuarterHourRefreshPolicy {
    fun nextRefreshDelayMillis(nowMillis: Long): Long {
        val now = Instant.ofEpochMilli(nowMillis)
            .atZone(ZoneId.systemDefault())
        val nextQuarterMinute = ((now.minute / 15) + 1) * 15
        val baseHour = now.truncatedTo(ChronoUnit.HOURS)
        val nextQuarter = if (nextQuarterMinute >= 60) {
            baseHour.plusHours(1)
        } else {
            baseHour.plusMinutes(nextQuarterMinute.toLong())
        }
        return max(1_000L, ChronoUnit.MILLIS.between(now, nextQuarter))
    }
}


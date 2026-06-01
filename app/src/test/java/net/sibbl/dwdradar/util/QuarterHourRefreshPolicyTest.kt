package net.sibbl.dwdradar.util

import com.google.common.truth.Truth.assertThat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Test

class QuarterHourRefreshPolicyTest {
    @Test
    fun nextRefreshDelayMillis_targetsNextQuarterHour() {
        val now = atSystemZoneMillis(2026, 1, 3, 10, 7, 0)

        val delay = QuarterHourRefreshPolicy.nextRefreshDelayMillis(now)

        assertThat(delay).isEqualTo(8 * 60 * 1000L)
    }

    @Test
    fun nextRefreshDelayMillis_rollsOverToNextHour() {
        val now = atSystemZoneMillis(2026, 1, 3, 10, 59, 59)

        val delay = QuarterHourRefreshPolicy.nextRefreshDelayMillis(now)

        assertThat(delay).isEqualTo(1_000L)
    }

    @Test
    fun nextRefreshDelayMillis_waitsFullQuarterWhenAlreadyOnQuarter() {
        val now = atSystemZoneMillis(2026, 1, 3, 10, 15, 0)

        val delay = QuarterHourRefreshPolicy.nextRefreshDelayMillis(now)

        assertThat(delay).isEqualTo(15 * 60 * 1000L)
    }

    private fun atSystemZoneMillis(
        year: Int,
        month: Int,
        dayOfMonth: Int,
        hour: Int,
        minute: Int,
        second: Int
    ): Long {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute, second)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }
}

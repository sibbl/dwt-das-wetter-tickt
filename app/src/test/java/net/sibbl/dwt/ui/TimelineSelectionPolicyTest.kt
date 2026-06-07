package net.sibbl.dwt.ui

import com.google.common.truth.Truth.assertThat
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import org.junit.Test

class TimelineSelectionPolicyTest {
    private val timeline = RadarTimeline(
        frames = listOf(
            frame(timestampMillis = 1_000L),
            frame(timestampMillis = 2_000L),
            frame(timestampMillis = 3_000L)
        ),
        nowTimestampMillis = 3_000L,
        nowFrameIndex = 2
    )

    @Test
    fun selectNow_usesFreshTimelineNowInsteadOfPreviousTimestamp() {
        val selectedIndex = selectedIndexAfterTimelineRefresh(
            timeline = timeline,
            previousTimestamp = 1_000L,
            selectNow = true
        )

        assertThat(selectedIndex).isEqualTo(2)
    }

    @Test
    fun preserveSelection_keepsClosestPreviousTimestamp() {
        val selectedIndex = selectedIndexAfterTimelineRefresh(
            timeline = timeline,
            previousTimestamp = 1_100L,
            selectNow = false
        )

        assertThat(selectedIndex).isEqualTo(0)
    }

    private fun frame(timestampMillis: Long) = RadarFrameReference(
        timestampMillis = timestampMillis,
        assetPath = "$timestampMillis.zip",
        layerKey = "PRECIPITATION",
        timeStepMillis = 1_000L,
        sectionStartMillis = 1_000L,
        sectionEndMillis = 4_000L
    )
}

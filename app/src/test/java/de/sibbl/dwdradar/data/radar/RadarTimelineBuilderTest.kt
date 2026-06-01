package de.sibbl.dwdradar.data.radar

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RadarTimelineBuilderTest {
    private val builder = RadarTimelineBuilder()

    @Test
    fun build_createsSortedTimelineAndNearestNowFrame() {
        val overview = RadarOverviewDto(
            now = 980L,
            data = listOf(
                RadarSectionDto(
                    start = 900L,
                    end = 1800L,
                    files = mapOf(
                        RadarBackend.PRECIPITATION_LAYER to RadarFileDto(
                            file = "future.zip",
                            timeStep = 300L
                        )
                    )
                ),
                RadarSectionDto(
                    start = 0L,
                    end = 900L,
                    files = mapOf(
                        RadarBackend.PRECIPITATION_LAYER to RadarFileDto(
                            file = "past.zip",
                            timeStep = 300L
                        ),
                        "WIND" to RadarFileDto(
                            file = "wind.zip",
                            timeStep = 300L
                        )
                    )
                )
            )
        )

        val timeline = builder.build(overview = overview, fallbackNowMillis = 5_000L)

        assertThat(timeline.frames.map { it.timestampMillis })
            .containsExactly(0L, 300L, 600L, 900L, 1_200L, 1_500L)
            .inOrder()
        assertThat(timeline.frames.map { it.assetPath })
            .containsExactly("past.zip", "past.zip", "past.zip", "future.zip", "future.zip", "future.zip")
            .inOrder()
        assertThat(timeline.nowTimestampMillis).isEqualTo(980L)
        assertThat(timeline.nowFrameIndex).isEqualTo(3)
    }

    @Test
    fun build_usesFallbackNowWhenOverviewNowMissing() {
        val overview = RadarOverviewDto(
            now = null,
            data = listOf(
                RadarSectionDto(
                    start = 0L,
                    end = 600L,
                    files = mapOf(
                        RadarBackend.PRECIPITATION_LAYER to RadarFileDto(
                            file = "radar.zip",
                            timeStep = 300L
                        )
                    )
                )
            )
        )

        val timeline = builder.build(overview = overview, fallbackNowMillis = 290L)

        assertThat(timeline.nowTimestampMillis).isEqualTo(290L)
        assertThat(timeline.nowFrameIndex).isEqualTo(1)
    }
}

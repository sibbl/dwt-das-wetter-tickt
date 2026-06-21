package net.sibbl.dwt.data.radar

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

    @Test
    fun build_keepsHigherResolutionFrameWhenForecastRangesOverlap() {
        val overview = RadarOverviewDto(
            now = 600L,
            data = listOf(
                RadarSectionDto(
                    start = 0L,
                    end = 900L,
                    files = mapOf(
                        RadarBackend.PRECIPITATION_LAYER to RadarFileDto(
                            file = "fine.zip",
                            timeStep = 300L
                        )
                    )
                ),
                RadarSectionDto(
                    start = 600L,
                    end = 3_000L,
                    files = mapOf(
                        RadarBackend.PRECIPITATION_LAYER to RadarFileDto(
                            file = "coarse.zip",
                            timeStep = 1_200L
                        )
                    )
                )
            )
        )

        val timeline = builder.build(overview = overview, fallbackNowMillis = 0L)

        assertThat(timeline.frames.map { it.timestampMillis })
            .containsExactly(0L, 300L, 600L, 1_800L)
            .inOrder()
        assertThat(timeline.frames.map { it.assetPath })
            .containsExactly("fine.zip", "fine.zip", "fine.zip", "coarse.zip")
            .inOrder()
        assertThat(timeline.frames[2].timeStepMillis).isEqualTo(300L)
    }

    @Test
    fun build_combinesLightningMeasurementsAndForecasts() {
        val builder = RadarTimelineBuilder(RadarBackend.lightningLayers)
        val overview = RadarOverviewDto(
            now = 600L,
            data = listOf(
                RadarSectionDto(
                    start = 0L,
                    end = 600L,
                    files = mapOf(
                        RadarBackend.LIGHTNING_MEASUREMENT_LAYER to RadarFileDto(
                            file = "measurement.zip",
                            timeStep = 300L
                        )
                    )
                ),
                RadarSectionDto(
                    start = 600L,
                    end = 1_200L,
                    files = mapOf(
                        RadarBackend.LIGHTNING_FORECAST_LAYER to RadarFileDto(
                            file = "forecast.zip",
                            timeStep = 300L
                        )
                    )
                )
            )
        )

        val timeline = builder.build(overview, fallbackNowMillis = 0L)

        assertThat(timeline.frames.map { it.assetPath })
            .containsExactly("measurement.zip", "measurement.zip", "forecast.zip", "forecast.zip")
            .inOrder()
        assertThat(timeline.frames.map { it.layerKey })
            .containsExactly(
                RadarBackend.LIGHTNING_MEASUREMENT_LAYER,
                RadarBackend.LIGHTNING_MEASUREMENT_LAYER,
                RadarBackend.LIGHTNING_FORECAST_LAYER,
                RadarBackend.LIGHTNING_FORECAST_LAYER
            )
            .inOrder()
    }

    @Test
    fun build_prefersLightningForecastFromDwdForecastBoundaryWhenSectionsOverlap() {
        val builder = RadarTimelineBuilder(RadarBackend.lightningLayers)
        val overview = RadarOverviewDto(
            now = 1_000L,
            lastBlitzMeasurement = 1_200L,
            firstBlitzForecast = 900L,
            data = listOf(
                RadarSectionDto(
                    start = 600L,
                    end = 1_500L,
                    files = mapOf(
                        RadarBackend.LIGHTNING_MEASUREMENT_LAYER to RadarFileDto(
                            file = "measurement.zip",
                            timeStep = 300L
                        ),
                        RadarBackend.LIGHTNING_FORECAST_LAYER to RadarFileDto(
                            file = "forecast.zip",
                            timeStep = 300L
                        )
                    )
                )
            )
        )

        val timeline = builder.build(overview, fallbackNowMillis = 0L)

        assertThat(timeline.frames.map { it.timestampMillis })
            .containsExactly(600L, 900L, 1_200L)
            .inOrder()
        assertThat(timeline.frames.map { it.layerKey })
            .containsExactly(
                RadarBackend.LIGHTNING_MEASUREMENT_LAYER,
                RadarBackend.LIGHTNING_FORECAST_LAYER,
                RadarBackend.LIGHTNING_FORECAST_LAYER
            )
            .inOrder()
    }
}

package net.sibbl.dwt.data.radar

import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import kotlin.math.absoluteValue

class RadarTimelineBuilder(
    private val layerKeys: List<String> = listOf(RadarBackend.PRECIPITATION_LAYER)
) {
    constructor(layerKey: String) : this(listOf(layerKey))

    fun build(overview: RadarOverviewDto, fallbackNowMillis: Long): RadarTimeline {
        val framesByTimestamp = sortedMapOf<Long, RadarFrameReference>()

        overview.data
            .sortedBy { it.start }
            .forEach { section ->
                layerKeys.forEach { layerKey ->
                    val file = section.files[layerKey] ?: return@forEach
                    generateSequence(section.start) { current ->
                        current + file.timeStep
                    }
                        .takeWhile { timestamp -> timestamp < section.end }
                        .forEach { timestamp ->
                            val candidate = RadarFrameReference(
                                timestampMillis = timestamp,
                                assetPath = file.file,
                                timeStepMillis = file.timeStep,
                                sectionStartMillis = section.start,
                                sectionEndMillis = section.end,
                                layerKey = layerKey
                            )
                            val existing = framesByTimestamp[timestamp]
                            if (existing == null || shouldReplaceFrame(existing, candidate, overview)) {
                                framesByTimestamp[timestamp] = candidate
                            }
                        }
                }
            }

        val frames = framesByTimestamp.values.toList()
        if (frames.isEmpty()) {
            return RadarTimeline(
                frames = emptyList(),
                nowTimestampMillis = fallbackNowMillis,
                nowFrameIndex = 0
            )
        }

        val nowMillis = overview.now ?: fallbackNowMillis
        val nowFrameIndex = frames.indices.minBy { index ->
            (frames[index].timestampMillis - nowMillis).absoluteValue
        }

        return RadarTimeline(
            frames = frames,
            nowTimestampMillis = nowMillis,
            nowFrameIndex = nowFrameIndex
        )
    }

    private fun shouldReplaceFrame(
        existing: RadarFrameReference,
        candidate: RadarFrameReference,
        overview: RadarOverviewDto
    ): Boolean {
        if (existing.layerKey != candidate.layerKey &&
            existing.layerKey.isLightningLayer() &&
            candidate.layerKey.isLightningLayer()
        ) {
            return candidate.layerKey == preferredLightningLayer(candidate.timestampMillis, overview)
        }
        return candidate.timeStepMillis <= existing.timeStepMillis
    }

    private fun preferredLightningLayer(timestampMillis: Long, overview: RadarOverviewDto): String {
        val firstForecast = overview.firstBlitzForecast
        if (firstForecast != null && timestampMillis >= firstForecast) {
            return RadarBackend.LIGHTNING_FORECAST_LAYER
        }
        val lastMeasurement = overview.lastBlitzMeasurement
        if (lastMeasurement != null && timestampMillis > lastMeasurement) {
            return RadarBackend.LIGHTNING_FORECAST_LAYER
        }
        return RadarBackend.LIGHTNING_MEASUREMENT_LAYER
    }

    private fun String.isLightningLayer(): Boolean {
        return this == RadarBackend.LIGHTNING_MEASUREMENT_LAYER ||
            this == RadarBackend.LIGHTNING_FORECAST_LAYER
    }
}

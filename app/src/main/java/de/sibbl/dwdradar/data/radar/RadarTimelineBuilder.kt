package de.sibbl.dwdradar.data.radar

import de.sibbl.dwdradar.model.RadarFrameReference
import de.sibbl.dwdradar.model.RadarTimeline
import kotlin.math.absoluteValue

class RadarTimelineBuilder(
    private val layerKey: String = RadarBackend.PRECIPITATION_LAYER
) {
    fun build(overview: RadarOverviewDto, fallbackNowMillis: Long): RadarTimeline {
        val framesByTimestamp = sortedMapOf<Long, RadarFrameReference>()

        overview.data
            .sortedBy { it.start }
            .forEach { section ->
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
                            sectionEndMillis = section.end
                        )
                        val existing = framesByTimestamp[timestamp]
                        if (existing == null || candidate.timeStepMillis <= existing.timeStepMillis) {
                            framesByTimestamp[timestamp] = candidate
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
}

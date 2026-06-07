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
                val layerEntry = layerKeys.firstNotNullOfOrNull { layerKey ->
                    section.files[layerKey]?.let { file -> layerKey to file }
                } ?: return@forEach
                val (layerKey, file) = layerEntry
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

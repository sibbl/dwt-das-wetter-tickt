package de.sibbl.dwdradar.model

data class RadarTimeline(
    val frames: List<RadarFrameReference>,
    val nowTimestampMillis: Long,
    val nowFrameIndex: Int
) {
    val isEmpty: Boolean
        get() = frames.isEmpty()

    fun frameAt(index: Int): RadarFrameReference {
        return frames[index.coerceIn(0, frames.lastIndex)]
    }
}


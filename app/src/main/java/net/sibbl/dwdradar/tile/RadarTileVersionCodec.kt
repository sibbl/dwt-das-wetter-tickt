package net.sibbl.dwdradar.tile

import net.sibbl.dwdradar.model.RadarFrameReference

object RadarTileVersionCodec {
    private const val PREFIX = "radar"
    private const val SEPARATOR = "|"

    fun encode(reference: RadarFrameReference): String {
        return listOf(
            PREFIX,
            reference.timestampMillis,
            reference.timeStepMillis,
            reference.sectionStartMillis,
            reference.sectionEndMillis,
            reference.assetPath
        ).joinToString(SEPARATOR)
    }

    fun decode(version: String): RadarFrameReference? {
        val parts = version.split(SEPARATOR, limit = 6)
        if (parts.size != 6 || parts.first() != PREFIX) {
            return null
        }

        return RadarFrameReference(
            timestampMillis = parts[1].toLongOrNull() ?: return null,
            timeStepMillis = parts[2].toLongOrNull() ?: return null,
            sectionStartMillis = parts[3].toLongOrNull() ?: return null,
            sectionEndMillis = parts[4].toLongOrNull() ?: return null,
            assetPath = parts[5]
        )
    }
}

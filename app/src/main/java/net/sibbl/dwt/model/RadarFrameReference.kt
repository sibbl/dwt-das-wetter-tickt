package net.sibbl.dwt.model

data class RadarFrameReference(
    val timestampMillis: Long,
    val assetPath: String,
    val timeStepMillis: Long,
    val sectionStartMillis: Long,
    val sectionEndMillis: Long,
    val layerKey: String = "PRECIPITATION"
)

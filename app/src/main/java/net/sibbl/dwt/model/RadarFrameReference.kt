package net.sibbl.dwt.model

@kotlinx.serialization.Serializable
data class RadarFrameReference(
    val timestampMillis: Long,
    val assetPath: String,
    val timeStepMillis: Long,
    val sectionStartMillis: Long,
    val sectionEndMillis: Long,
    val layerKey: String = "PRECIPITATION"
)

package de.sibbl.dwdradar.data.radar

import kotlinx.serialization.Serializable

@Serializable
data class RadarOverviewDto(
    val now: Long? = null,
    val data: List<RadarSectionDto> = emptyList()
)

@Serializable
data class RadarSectionDto(
    val start: Long,
    val end: Long,
    val files: Map<String, RadarFileDto> = emptyMap()
)

@Serializable
data class RadarFileDto(
    val file: String,
    val timeStep: Long
)

@Serializable
data class RadarFrameBoundsDto(
    val upperLatitude: Double = 0.0,
    val upperLongitude: Double = 0.0,
    val lowerLatitude: Double = 0.0,
    val lowerLongitude: Double = 0.0
)


package de.sibbl.dwdradar.ui

import de.sibbl.dwdradar.data.radar.RadarBitmapFrame
import de.sibbl.dwdradar.model.GeoPoint
import de.sibbl.dwdradar.model.MapCamera
import de.sibbl.dwdradar.model.RadarTimeline
import de.sibbl.dwdradar.model.ZoomPreset

data class RadarUiState(
    val isLoading: Boolean = true,
    val isPlaying: Boolean = false,
    val timeline: RadarTimeline = RadarTimeline(
        frames = emptyList(),
        nowTimestampMillis = System.currentTimeMillis(),
        nowFrameIndex = 0
    ),
    val selectedFrameIndex: Int = 0,
    val selectedFrame: RadarBitmapFrame? = null,
    val frameLoadProgress: Map<Long, Float> = emptyMap(),
    val germanyOutlines: List<List<GeoPoint>> = emptyList(),
    val mapCamera: MapCamera,
    val userLocation: GeoPoint? = null,
    val errorMessage: String? = null
) {
    val zoomPreset: ZoomPreset
        get() = mapCamera.zoomPreset
}

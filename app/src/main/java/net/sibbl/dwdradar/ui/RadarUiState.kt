package net.sibbl.dwdradar.ui

import net.sibbl.dwdradar.data.radar.RadarBitmapFrame
import net.sibbl.dwdradar.model.GeoPoint
import net.sibbl.dwdradar.model.MapCamera
import net.sibbl.dwdradar.model.RadarTimeline
import net.sibbl.dwdradar.model.ZoomPreset

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
    val rainLayerVisible: Boolean = true,
    val cloudLayerVisible: Boolean = true,
    val cloudTimeline: RadarTimeline = RadarTimeline(
        frames = emptyList(),
        nowTimestampMillis = System.currentTimeMillis(),
        nowFrameIndex = 0
    ),
    val selectedCloudFrame: RadarBitmapFrame? = null,
    val frameLoadProgress: Map<Long, Float> = emptyMap(),
    val germanyOutlines: List<List<GeoPoint>> = emptyList(),
    val mapCamera: MapCamera,
    val layerMenuExpanded: Boolean = false,
    val layerMenuScrollDp: Float = 0f,
    val userLocation: GeoPoint? = null,
    val isLocationLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val zoomPreset: ZoomPreset
        get() = mapCamera.zoomPreset
}

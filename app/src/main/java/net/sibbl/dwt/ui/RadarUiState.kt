package net.sibbl.dwt.ui

import net.sibbl.dwt.data.radar.RadarBitmapFrame
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.MapCamera
import net.sibbl.dwt.model.RadarTimeline
import net.sibbl.dwt.model.ZoomPreset

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
    val lightningLayerVisible: Boolean = true,
    val cloudTimeline: RadarTimeline = RadarTimeline(
        frames = emptyList(),
        nowTimestampMillis = System.currentTimeMillis(),
        nowFrameIndex = 0
    ),
    val selectedCloudFrame: RadarBitmapFrame? = null,
    val lightningTimeline: RadarTimeline = RadarTimeline(
        frames = emptyList(),
        nowTimestampMillis = System.currentTimeMillis(),
        nowFrameIndex = 0
    ),
    val selectedLightningFrame: RadarBitmapFrame? = null,
    val frameLoadProgress: Map<Long, Float> = emptyMap(),
    val germanyOutlines: List<List<GeoPoint>> = emptyList(),
    val mapCamera: MapCamera,
    val layerMenuExpanded: Boolean = false,
    val layerMenuScrollDp: Float = 0f,
    val userLocation: GeoPoint? = null,
    val isLocationLoading: Boolean = false,
    val decodedRainFrameTimestamps: Set<Long> = emptySet(),
    val decodedCloudFrameTimestamps: Set<Long> = emptySet(),
    val decodedLightningFrameTimestamps: Set<Long> = emptySet(),
    val errorMessage: String? = null
) {
    val zoomPreset: ZoomPreset
        get() = mapCamera.zoomPreset
}

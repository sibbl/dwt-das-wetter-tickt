package net.sibbl.dwt.storage

import android.content.Context
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.MapCamera
import net.sibbl.dwt.model.ZoomPreset

class MapCameraPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restoreMapCamera(): MapCamera {
        return MapCamera(
            center = restoreCenter() ?: RadarBackend.defaultBounds.center,
            zoomPreset = restoreZoomPreset()
        )
    }

    fun hasSavedCenter(): Boolean {
        return restoreCenter() != null
    }

    fun save(camera: MapCamera) {
        prefs.edit()
            .putString(KEY_LATITUDE, camera.center.latitude.toString())
            .putString(KEY_LONGITUDE, camera.center.longitude.toString())
            .putString(KEY_ZOOM_PRESET, camera.zoomPreset.name)
            .apply()
    }

    private fun restoreCenter(): GeoPoint? {
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val restoredPoint = GeoPoint(latitude = latitude, longitude = longitude)
        return restoredPoint.takeIf(RadarBackend.defaultBounds::contains)
    }

    private fun restoreZoomPreset(): ZoomPreset {
        val zoomName = prefs.getString(KEY_ZOOM_PRESET, ZoomPreset.MID.name) ?: ZoomPreset.MID.name
        return runCatching {
            ZoomPreset.valueOf(zoomName)
        }.getOrDefault(ZoomPreset.MID)
    }

    private companion object {
        const val PREFS_NAME = "radar_prefs"
        const val KEY_LATITUDE = "map_center_latitude"
        const val KEY_LONGITUDE = "map_center_longitude"
        const val KEY_ZOOM_PRESET = "map_zoom_preset"
    }
}

package net.sibbl.dwdradar.storage

import android.content.Context
import net.sibbl.dwdradar.data.radar.RadarBackend
import net.sibbl.dwdradar.model.GeoPoint

class UserLocationPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restore(): GeoPoint? {
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        val restoredPoint = GeoPoint(latitude = latitude, longitude = longitude)
        return restoredPoint.takeIf(RadarBackend.defaultBounds::contains)
    }

    fun save(location: GeoPoint) {
        prefs.edit()
            .putString(KEY_LATITUDE, location.latitude.toString())
            .putString(KEY_LONGITUDE, location.longitude.toString())
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "radar_prefs"
        const val KEY_LATITUDE = "user_location_latitude"
        const val KEY_LONGITUDE = "user_location_longitude"
    }
}

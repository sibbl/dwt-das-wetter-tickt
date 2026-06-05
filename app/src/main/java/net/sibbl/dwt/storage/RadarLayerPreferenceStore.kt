package net.sibbl.dwt.storage

import android.content.Context

class RadarLayerPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun restore(): RadarLayerPreferences {
        return RadarLayerPreferences(
            rainVisible = prefs.getBoolean(KEY_RAIN_VISIBLE, true),
            cloudVisible = prefs.getBoolean(KEY_CLOUD_VISIBLE, true)
        )
    }

    fun saveRainVisible(visible: Boolean) {
        prefs.edit()
            .putBoolean(KEY_RAIN_VISIBLE, visible)
            .apply()
    }

    fun saveCloudVisible(visible: Boolean) {
        prefs.edit()
            .putBoolean(KEY_CLOUD_VISIBLE, visible)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "radar_layer_prefs"
        const val KEY_RAIN_VISIBLE = "rain_layer_visible"
        const val KEY_CLOUD_VISIBLE = "cloud_layer_visible"
    }
}

data class RadarLayerPreferences(
    val rainVisible: Boolean,
    val cloudVisible: Boolean
)

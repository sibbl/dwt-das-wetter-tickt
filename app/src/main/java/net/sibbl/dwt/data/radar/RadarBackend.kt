package net.sibbl.dwt.data.radar

import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint

object RadarBackend {
    private const val BASE_URL = "https://app-prod-static.warnwetter.de/v16/"

    const val OVERVIEW_URL = "${BASE_URL}animation_overview_v2.json"
    const val PRECIPITATION_LAYER = "PRECIPITATION"
    const val CLOUD_LAYER = "CLOUD"

    val defaultBounds = GeoBounds(
        southWest = GeoPoint(latitude = 43.75, longitude = 0.0),
        northEast = GeoPoint(latitude = 57.0, longitude = 17.0)
    )

    fun assetUrl(path: String): String = BASE_URL + path
}

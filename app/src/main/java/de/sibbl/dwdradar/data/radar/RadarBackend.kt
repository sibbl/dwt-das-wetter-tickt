package de.sibbl.dwdradar.data.radar

import de.sibbl.dwdradar.model.GeoBounds
import de.sibbl.dwdradar.model.GeoPoint

object RadarBackend {
    private const val BASE_URL = "https://app-prod-static.warnwetter.de/v16/"

    const val OVERVIEW_URL = "${BASE_URL}animation_overview_v2.json"
    const val PRECIPITATION_LAYER = "PRECIPITATION"

    val defaultBounds = GeoBounds(
        southWest = GeoPoint(latitude = 43.75, longitude = 0.0),
        northEast = GeoPoint(latitude = 57.0, longitude = 17.0)
    )

    fun assetUrl(path: String): String = BASE_URL + path
}


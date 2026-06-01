package net.sibbl.dwdradar.model

data class GeoBounds(
    val southWest: GeoPoint,
    val northEast: GeoPoint
) {
    val center: GeoPoint
        get() = GeoPoint(
            latitude = (southWest.latitude + northEast.latitude) / 2.0,
            longitude = (southWest.longitude + northEast.longitude) / 2.0
        )

    fun contains(point: GeoPoint): Boolean {
        return point.latitude in southWest.latitude..northEast.latitude &&
            point.longitude in southWest.longitude..northEast.longitude
    }
}


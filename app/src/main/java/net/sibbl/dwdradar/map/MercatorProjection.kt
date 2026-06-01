package net.sibbl.dwdradar.map

import net.sibbl.dwdradar.model.GeoPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

object MercatorProjection {
    private const val MAX_LATITUDE = 85.05112878

    fun project(point: GeoPoint): ProjectedPoint {
        val latitude = point.latitude.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)
        val longitude = point.longitude
        val x = (longitude + 180.0) / 360.0
        val sinLatitude = sin(latitude * PI / 180.0)
        val y = 0.5 - (ln((1.0 + sinLatitude) / (1.0 - sinLatitude)) / (4.0 * PI))
        return ProjectedPoint(x = x, y = y)
    }

    fun unproject(point: ProjectedPoint): GeoPoint {
        val longitude = point.x * 360.0 - 180.0
        val latitudeRadians = atan(exp((0.5 - point.y) * 2.0 * PI)) * 2.0 - PI / 2.0
        val latitude = latitudeRadians * 180.0 / PI
        return GeoPoint(latitude = latitude, longitude = longitude)
    }
}


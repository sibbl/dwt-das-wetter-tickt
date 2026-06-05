package net.sibbl.dwt.map

import net.sibbl.dwt.model.GeoPoint

object GeographicProjection {
    fun project(point: GeoPoint): ProjectedPoint {
        return ProjectedPoint(
            x = (point.longitude + 180.0) / 360.0,
            y = (90.0 - point.latitude) / 360.0
        )
    }

    fun unproject(point: ProjectedPoint): GeoPoint {
        return GeoPoint(
            latitude = 90.0 - point.y * 360.0,
            longitude = point.x * 360.0 - 180.0
        )
    }
}

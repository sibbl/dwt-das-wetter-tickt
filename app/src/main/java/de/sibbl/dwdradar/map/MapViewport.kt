package de.sibbl.dwdradar.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import de.sibbl.dwdradar.data.radar.RadarBackend
import de.sibbl.dwdradar.model.GeoBounds
import de.sibbl.dwdradar.model.GeoPoint
import de.sibbl.dwdradar.model.MapCamera
import kotlin.math.max
import kotlin.math.min

class MapViewport(
    private val mapRect: Rect,
    private val camera: MapCamera
) {
    private val fullSouthWest = MercatorProjection.project(RadarBackend.defaultBounds.southWest)
    private val fullNorthEast = MercatorProjection.project(RadarBackend.defaultBounds.northEast)
    private val cameraCenter = MercatorProjection.project(camera.center)
    private val projectedWidth = fullNorthEast.x - fullSouthWest.x
    private val projectedHeight = fullSouthWest.y - fullNorthEast.y
    private val pixelsPerProjectedUnit = min(
        mapRect.width / projectedWidth.toFloat(),
        mapRect.height / projectedHeight.toFloat()
    ) * camera.zoomPreset.scaleMultiplier

    fun toScreen(point: GeoPoint): Offset {
        val projectedPoint = MercatorProjection.project(point)
        val dx = ((projectedPoint.x - cameraCenter.x) * pixelsPerProjectedUnit).toFloat()
        val dy = ((projectedPoint.y - cameraCenter.y) * pixelsPerProjectedUnit).toFloat()
        return Offset(
            x = mapRect.center.x + dx,
            y = mapRect.center.y + dy
        )
    }

    fun radarRect(bounds: GeoBounds): Rect {
        val topLeft = toScreen(GeoPoint(bounds.northEast.latitude, bounds.southWest.longitude))
        val bottomRight = toScreen(GeoPoint(bounds.southWest.latitude, bounds.northEast.longitude))
        return Rect(
            left = min(topLeft.x, bottomRight.x),
            top = min(topLeft.y, bottomRight.y),
            right = max(topLeft.x, bottomRight.x),
            bottom = max(topLeft.y, bottomRight.y)
        )
    }

    fun panCenterBy(deltaX: Float, deltaY: Float): GeoPoint {
        val translated = ProjectedPoint(
            x = cameraCenter.x - (deltaX / pixelsPerProjectedUnit),
            y = cameraCenter.y - (deltaY / pixelsPerProjectedUnit)
        )
        val geoPoint = MercatorProjection.unproject(translated)
        return clampToRadarBounds(geoPoint)
    }

    private fun clampToRadarBounds(point: GeoPoint): GeoPoint {
        return GeoPoint(
            latitude = point.latitude.coerceIn(
                RadarBackend.defaultBounds.southWest.latitude,
                RadarBackend.defaultBounds.northEast.latitude
            ),
            longitude = point.longitude.coerceIn(
                RadarBackend.defaultBounds.southWest.longitude,
                RadarBackend.defaultBounds.northEast.longitude
            )
        )
    }
}


package net.sibbl.dwdradar.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.sibbl.dwdradar.data.radar.RadarBackend
import net.sibbl.dwdradar.model.GeoBounds
import net.sibbl.dwdradar.model.GeoPoint
import net.sibbl.dwdradar.model.MapCamera
import kotlin.math.max
import kotlin.math.min

class MapViewport(
    private val mapRect: Rect,
    private val camera: MapCamera
) {
    private val fullSouthWest = RadolanProjection.project(RadarBackend.defaultBounds.southWest)
    private val fullNorthEast = RadolanProjection.project(RadarBackend.defaultBounds.northEast)
    private val cameraCenter = RadolanProjection.project(camera.center)
    private val projectedWidth = fullNorthEast.x - fullSouthWest.x
    private val projectedHeight = fullNorthEast.y - fullSouthWest.y
    private val pixelsPerProjectedUnit = min(
        mapRect.width / projectedWidth.toFloat(),
        mapRect.height / projectedHeight.toFloat()
    ) * camera.zoomPreset.scaleMultiplier

    fun toScreen(point: GeoPoint): Offset {
        val projectedPoint = RadolanProjection.project(point)
        val dx = ((projectedPoint.x - cameraCenter.x) * pixelsPerProjectedUnit).toFloat()
        val dy = -((projectedPoint.y - cameraCenter.y) * pixelsPerProjectedUnit).toFloat()
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
            y = cameraCenter.y + (deltaY / pixelsPerProjectedUnit)
        )
        val geoPoint = RadolanProjection.unproject(translated)
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


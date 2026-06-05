package net.sibbl.dwt.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.MapCamera
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

class MapViewport(
    private val mapRect: Rect,
    private val camera: MapCamera
) {
    private val cameraCenter = GeographicProjection.project(camera.center)
    private val visibleProjectedWidth = camera.zoomPreset.visibleWidthKilometers /
        (EARTH_CIRCUMFERENCE_KILOMETERS * cos(camera.center.latitude * PI / 180.0))
    private val pixelsPerProjectedUnit = mapRect.width / visibleProjectedWidth.toFloat()

    fun toScreen(point: GeoPoint): Offset {
        val projectedPoint = GeographicProjection.project(point)
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
        val geoPoint = GeographicProjection.unproject(translated)
        return clampToRadarBounds(geoPoint)
    }

    internal fun projectedPointAtScreenX(screenX: Float): ProjectedPoint {
        return ProjectedPoint(
            x = cameraCenter.x + ((screenX - mapRect.center.x) / pixelsPerProjectedUnit),
            y = cameraCenter.y
        )
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

    private companion object {
        const val EARTH_CIRCUMFERENCE_KILOMETERS = 40_075.016686
    }
}

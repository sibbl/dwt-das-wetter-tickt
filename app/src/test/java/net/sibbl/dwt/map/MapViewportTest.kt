package net.sibbl.dwt.map

import androidx.compose.ui.geometry.Rect
import com.google.common.truth.Truth.assertThat
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.MapCamera
import net.sibbl.dwt.model.ZoomPreset
import kotlin.math.abs
import org.junit.Test

class MapViewportTest {
    private val mapRect = Rect(0f, 0f, 384f, 384f)

    @Test
    fun radarBounds_matchProjectedGeographicCornersAtEveryZoom() {
        for (zoom in ZoomPreset.entries) {
            val viewport = viewport(zoom)
            val radarRect = viewport.radarRect(RadarBackend.defaultBounds)
            val northWest = viewport.toScreen(GeoPoint(57.0, 0.0))
            val southEast = viewport.toScreen(GeoPoint(43.75, 17.0))

            assertThat(radarRect.left).isWithin(0.001f).of(northWest.x)
            assertThat(radarRect.top).isWithin(0.001f).of(northWest.y)
            assertThat(radarRect.right).isWithin(0.001f).of(southEast.x)
            assertThat(radarRect.bottom).isWithin(0.001f).of(southEast.y)
        }
    }

    @Test
    fun zoomPreset_visibleWidthMatchesItsKilometerLabel() {
        for (zoom in ZoomPreset.entries) {
            val viewport = viewport(zoom)
            val left = GeographicProjection.unproject(viewport.projectedPointAtScreenX(mapRect.left))
            val right = GeographicProjection.unproject(viewport.projectedPointAtScreenX(mapRect.right))
            val visibleWidth = longitudeDistanceKilometers(
                latitude = RadarBackend.defaultBounds.center.latitude,
                longitudeDelta = right.longitude - left.longitude
            )

            assertThat(abs(visibleWidth - zoom.visibleWidthKilometers)).isLessThan(0.1)
        }
    }

    @Test
    fun geographicBoundsCenter_matchesRasterPixelCenter() {
        val viewport = viewport(ZoomPreset.MID)

        val center = viewport.toScreen(RadarBackend.defaultBounds.center)

        assertThat(center.x).isWithin(0.001f).of(mapRect.center.x)
        assertThat(center.y).isWithin(0.001f).of(mapRect.center.y)
    }

    private fun viewport(zoom: ZoomPreset): MapViewport {
        return MapViewport(
            mapRect = mapRect,
            camera = MapCamera(center = RadarBackend.defaultBounds.center, zoomPreset = zoom)
        )
    }

    private fun longitudeDistanceKilometers(latitude: Double, longitudeDelta: Double): Double {
        return 40_075.016686 * kotlin.math.cos(Math.toRadians(latitude)) * longitudeDelta / 360.0
    }
}

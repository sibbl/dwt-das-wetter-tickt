package net.sibbl.dwt.map

import com.google.common.truth.Truth.assertThat
import net.sibbl.dwt.model.GeoPoint
import org.junit.Test

class GeographicProjectionTest {
    @Test
    fun roundTrip_preservesCoordinates() {
        val point = GeoPoint(latitude = 51.3397, longitude = 12.3731)

        val result = GeographicProjection.unproject(GeographicProjection.project(point))

        assertThat(result.latitude).isWithin(0.000001).of(point.latitude)
        assertThat(result.longitude).isWithin(0.000001).of(point.longitude)
    }

    @Test
    fun latitudeRows_areLinearLikeLegacyRasterPixels() {
        val north = GeographicProjection.project(GeoPoint(latitude = 57.0, longitude = 0.0))
        val middle = GeographicProjection.project(GeoPoint(latitude = 50.375, longitude = 0.0))
        val south = GeographicProjection.project(GeoPoint(latitude = 43.75, longitude = 0.0))

        assertThat(middle.y).isWithin(0.000001).of((north.y + south.y) / 2.0)
    }
}

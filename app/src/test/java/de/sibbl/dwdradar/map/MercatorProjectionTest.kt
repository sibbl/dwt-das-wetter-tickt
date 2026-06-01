package de.sibbl.dwdradar.map

import com.google.common.truth.Truth.assertThat
import de.sibbl.dwdradar.model.GeoPoint
import kotlin.math.abs
import org.junit.Test

class MercatorProjectionTest {
    @Test
    fun projectAndUnproject_roundTripBackToOriginalPoint() {
        val berlin = GeoPoint(latitude = 52.52, longitude = 13.405)

        val projected = MercatorProjection.project(berlin)
        val unprojected = MercatorProjection.unproject(projected)

        assertThat(abs(unprojected.latitude - berlin.latitude)).isLessThan(0.000001)
        assertThat(abs(unprojected.longitude - berlin.longitude)).isLessThan(0.000001)
    }

    @Test
    fun project_clampsLatitudesBeyondMercatorLimit() {
        val clamped = MercatorProjection.project(GeoPoint(latitude = 90.0, longitude = 10.0))
        val maxSupported = MercatorProjection.project(GeoPoint(latitude = 85.05112878, longitude = 10.0))

        assertThat(clamped.x).isEqualTo(maxSupported.x)
        assertThat(clamped.y).isEqualTo(maxSupported.y)
    }
}

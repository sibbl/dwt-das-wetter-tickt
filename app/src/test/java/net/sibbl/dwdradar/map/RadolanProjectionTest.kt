package net.sibbl.dwdradar.map

import com.google.common.truth.Truth.assertThat
import net.sibbl.dwdradar.model.GeoPoint
import kotlin.math.abs
import org.junit.Test

class RadolanProjectionTest {
    @Test
    fun projectAndUnproject_roundTripBackToOriginalPoint() {
        val points = listOf(
            GeoPoint(latitude = 52.52, longitude = 13.405), // Berlin
            GeoPoint(latitude = 48.135, longitude = 11.582), // Munich
            GeoPoint(latitude = 54.323, longitude = 10.122)  // Kiel
        )

        for (point in points) {
            val projected = RadolanProjection.project(point)
            val unprojected = RadolanProjection.unproject(projected)

            assertThat(abs(unprojected.latitude - point.latitude)).isLessThan(0.000001)
            assertThat(abs(unprojected.longitude - point.longitude)).isLessThan(0.000001)
        }
    }
}

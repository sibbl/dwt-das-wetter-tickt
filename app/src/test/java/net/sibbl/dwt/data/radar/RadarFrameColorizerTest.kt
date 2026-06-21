package net.sibbl.dwt.data.radar

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class RadarFrameColorizerTest {
    @Test
    fun decodeLightningPoints_readsBigEndianLatitudeLongitudePoint() {
        val bounds = RadarBackend.defaultBounds
        val pointBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.BIG_ENDIAN)
            .putFloat(bounds.center.latitude.toFloat())
            .putFloat(bounds.center.longitude.toFloat())
            .array()

        val points = RadarFrameColorizer.decodeLightningPoints(pointBytes, bounds)

        assertEquals(1, points.size)
        assertEquals(bounds.center.latitude, points.single().latitude, 0.001)
        assertEquals(bounds.center.longitude, points.single().longitude, 0.001)
    }

    @Test
    fun lightningForecastColorForIntensity_mapsDwdIntensityClasses() {
        assertEquals(0, RadarFrameColorizer.lightningForecastColorForIntensity(0))
        assertEquals(0xFFFFFE00.toInt(), RadarFrameColorizer.lightningForecastColorForIntensity(85))
        assertEquals(0xFFFF3C00.toInt(), RadarFrameColorizer.lightningForecastColorForIntensity(170))
        assertEquals(0xFFFF3C00.toInt(), RadarFrameColorizer.lightningForecastColorForIntensity(255))
    }
}

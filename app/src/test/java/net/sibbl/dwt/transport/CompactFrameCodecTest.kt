package net.sibbl.dwt.transport

import android.graphics.Bitmap
import java.io.*
import java.nio.ByteBuffer
import kotlin.random.Random
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.data.radar.RadarFrameColorizer
import net.sibbl.dwt.model.RadarFrameReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactFrameCodecTest {
    @Test fun `all layers roundtrip exact native pixels with representative densities`() {
        val random = Random(42)
        for (density in listOf("empty", "typical", "dense")) {
            val source = Bitmap.createBitmap(384, 384, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(384 * 384) { i ->
                when (density) {
                    "empty" -> 0
                    "typical" -> if ((i / 384 / 24 + i % 384 / 24) % 4 == 0) 0xff000000.toInt() or random.nextInt(256) * 0x010101 else 0
                    else -> 0xff000000.toInt() or random.nextInt(256) * 0x010101
                }
            }
            source.setPixels(pixels, 0, 384, 0, 0, 384, 384)
            val pointCount = when (density) { "empty" -> 0; "typical" -> 50; else -> 3000 }
            val points = ByteBuffer.allocate(pointCount * 8).apply {
                repeat(pointCount) { putFloat(44f + random.nextFloat() * 12); putFloat(random.nextFloat() * 17) }
            }.array()
            val layers = mapOf(
                "rain" to RadarFrameColorizer.colorizePrecipitation(source.copy(Bitmap.Config.ARGB_8888, true)),
                "cloud" to RadarFrameColorizer.colorizeCloud(source.copy(Bitmap.Config.ARGB_8888, true)),
                "measurements" to RadarFrameColorizer.renderLightningMeasurements(points, RadarBackend.defaultBounds),
                "forecast" to RadarFrameColorizer.renderLightningForecast(source)
            )
            for ((layer, bitmap) in layers) {
                val png = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG, 100, this) }.toByteArray()
                val start = System.nanoTime()
                val encoded = if (layer == "measurements") CompactFrameCodec.encodeMeasurements(bitmap, points, RadarBackend.defaultBounds) else CompactFrameCodec.encode(bitmap)
                val encodedAt = System.nanoTime()
                val decoded = CompactFrameCodec.decode(encoded, RadarBackend.defaultBounds)
                val finished = System.nanoTime()
                assertEquals(bitmap.width, decoded.width)
                assertEquals(bitmap.height, decoded.height)
                val expectedPixels = IntArray(384 * 384); val actualPixels = IntArray(384 * 384)
                bitmap.getPixels(expectedPixels, 0, 384, 0, 0, 384, 384)
                decoded.getPixels(actualPixels, 0, 384, 0, 0, 384, 384)
                assertArrayEquals("$layer/$density", expectedPixels, actualPixels)
                assertTrue(encoded.size <= png.size + 1)
                println("CODEC $layer $density pngResponse=${png.size + 48} compactResponse=${encoded.size + 14} mode=${encoded[0]} encodeMs=${(encodedAt-start)/1e6} decodeMs=${(finished-encodedAt)/1e6}")
            }
        }
    }

    @Test fun `binary requests preserve all identity fields and reduce header size`() {
        for (layer in RadarFrameWire.layers) {
            val reference = RadarFrameReference(1500, "radar/frame.zip", 500, 1000, 2000, layer)
            val bytes = ByteArrayOutputStream().apply { RadarFrameWire.writeCompactRequest(DataOutputStream(this), reference, true) }.toByteArray()
            assertEquals(reference to true, RadarFrameWire.readCompactRequest(DataInputStream(ByteArrayInputStream(bytes))))
            val payload = byteArrayOf(0, 1, 2)
            val envelope = RadarFrameWire.Frame(43.75, 0.0, 57.0, 17.0, 42, payload)
            val frameBytes = ByteArrayOutputStream().apply { RadarFrameWire.writeCompactFrame(DataOutputStream(this), envelope) }.toByteArray()
            val actual = RadarFrameWire.readCompactFrame(DataInputStream(ByteArrayInputStream(frameBytes)))
            assertEquals(envelope.south, actual.south, 0.0)
            assertEquals(envelope.east, actual.east, 0.0)
            assertArrayEquals(payload, actual.png)
            assertEquals(14 + payload.size, frameBytes.size)
        }
    }

    @Test fun `v2 retains nondefault bounds exactly and rejects malformed envelopes`() {
        val frame = RadarFrameWire.Frame(46.1929, 4.6759, 55.5342, 17.1128, 123, byteArrayOf(5,0,1,0,1,0,0,0,0))
        val bytes = ByteArrayOutputStream().apply { RadarFrameWire.writeCompactFrame(DataOutputStream(this), frame) }.toByteArray()
        val actual = RadarFrameWire.readCompactFrame(DataInputStream(ByteArrayInputStream(bytes)))
        assertEquals(frame.south, actual.south, 0.0); assertEquals(frame.west, actual.west, 0.0)
        assertEquals(frame.north, actual.north, 0.0); assertEquals(frame.east, actual.east, 0.0)
        assertEquals(46 + frame.png.size, bytes.size)
        for (bad in listOf(bytes.copyOf(bytes.size-1), bytes.clone().apply { this[0] = 1 }, bytes.clone().apply { this[1] = 2 })) {
            try { RadarFrameWire.readCompactFrame(DataInputStream(ByteArrayInputStream(bad))); fail("Malformed envelope accepted") } catch (_: IOException) { }
        }
        try { RadarFrameWire.readFrame(DataInputStream(ByteArrayInputStream(bytes))); fail("v1 accepted v2") } catch (_: IOException) { }
        try { RadarFrameWire.writeCompactFrame(DataOutputStream(ByteArrayOutputStream()), frame.copy(north = Double.NaN)); fail("NaN accepted") } catch (_: IOException) { }
    }

    @Test fun `malformed streams dimensions palettes and inflated lengths are rejected`() {
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val valid = CompactFrameCodec.encode(bitmap)
        for (bad in listOf(byteArrayOf(), byteArrayOf(99), byteArrayOf(1,0,0,0,1), valid.copyOf(valid.size - 1), valid + byteArrayOf(1))) {
            try { CompactFrameCodec.decode(bad); fail("Accepted malformed stream") } catch (_: IOException) { }
        }
        for (reference in listOf(
            RadarFrameReference(1500, "../file.zip", 500, 1000, 2000, "CLOUD"),
            RadarFrameReference(1500, "file.zip", 500, 1000, 2000, "UNKNOWN")
        )) {
            try { RadarFrameWire.validateCompact(reference); fail("Accepted malformed reference") } catch (_: IOException) { }
        }
    }
}

package net.sibbl.dwt.transport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.RectF
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.data.radar.RadarFrameColorizer
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Opt-in public DWD fixtures, never downloaded during ordinary unit tests. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompanionFixtureBenchmarkTest {
    @Test fun `public fixtures retain every rendered pixel and compare composition cost`() {
        val directory = System.getenv("DWT_FIXTURE_DIR")
        assumeNotNull(directory)
        val bitmaps = linkedMapOf<String, Bitmap>()
        val encodedSizes = linkedMapOf<String, Int>()
        val allBounds = linkedMapOf<String, GeoBounds>()
        for (layer in RadarFrameWire.layers) {
            ZipFile(File(directory, "$layer.zip")).use { zip ->
                val entry = zip.entries().toList().filter { it.name.endsWith(".png") }.sortedBy { it.name }.first()
                val boundsJson = JSONObject(zip.getInputStream(zip.getEntry(entry.name.replace(".png", ".json"))).bufferedReader().use { it.readText() })
                val bounds = if (boundsJson.getDouble("upperLatitude") == 0.0) RadarBackend.defaultBounds else GeoBounds(
                    GeoPoint(boundsJson.getDouble("lowerLatitude"), boundsJson.getDouble("lowerLongitude")),
                    GeoPoint(boundsJson.getDouble("upperLatitude"), boundsJson.getDouble("upperLongitude")))
                allBounds[layer] = bounds
                val header = if (bounds == RadarBackend.defaultBounds) 14 else 46
                val raw = zip.getInputStream(entry).use { it.readBytes() }
                val bitmap = when (layer) {
                    "BLITZ_MEASUREMENT" -> RadarFrameColorizer.renderLightningMeasurements(raw, bounds)
                    else -> BitmapFactory.decodeByteArray(raw, 0, raw.size).let {
                        when (layer) {
                            "CLOUD" -> RadarFrameColorizer.colorizeCloud(it)
                            "BLITZ_FORECAST" -> RadarFrameColorizer.renderLightningForecast(it)
                            else -> RadarFrameColorizer.colorizePrecipitation(it)
                        }
                    }
                }
                val png = png(bitmap)
                val encodeTimes = mutableListOf<Double>(); val decodeTimes = mutableListOf<Double>()
                var size = 0; var mode = 0
                repeat(8) { iteration ->
                    val started = System.nanoTime()
                    val encoded = if (layer == "BLITZ_MEASUREMENT") CompactFrameCodec.encodeMeasurements(bitmap, raw, bounds) else CompactFrameCodec.encode(bitmap)
                    val middle = System.nanoTime()
                    val decoded = CompactFrameCodec.decode(encoded, bounds)
                    val end = System.nanoTime()
                    assertArrayEquals(pixels(bitmap), pixels(decoded))
                    if (iteration > 0) { encodeTimes += (middle-started)/1e6; decodeTimes += (end-middle)/1e6 }
                    size = encoded.size; mode = encoded[0].toInt()
                }
                bitmaps[layer] = bitmap; encodedSizes[layer] = size + header
                println("FIXTURE layer=$layer source=${entry.name} dimensions=${bitmap.width}x${bitmap.height} rawBytes=${raw.size} pngResponse=${png.size+48} compactResponse=${size+header} mode=$mode encodeMedianMs=${encodeTimes.sorted()[3]} decodeMedianMs=${decodeTimes.sorted()[3]}")
            }
        }
        // Same geographic rectangle, full largest raster resolution; no display-size downsample.
        val width = bitmaps.values.maxOf { it.width }; val height = bitmaps.values.maxOf { it.height }
        val composed = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val start = System.nanoTime()
        val canvas = Canvas(composed)
        for (layer in listOf("CLOUD", "PRECIPITATION", "BLITZ_FORECAST")) {
            val bounds = allBounds.getValue(layer)
            val rect = RectF((bounds.southWest.longitude / 17 * width).toFloat(), ((57-bounds.northEast.latitude)/13.25*height).toFloat(),
                (bounds.northEast.longitude/17*width).toFloat(), ((57-bounds.southWest.latitude)/13.25*height).toFloat())
            canvas.drawBitmap(bitmaps.getValue(layer), null, rect, null)
        }
        val composedAt = System.nanoTime()
        val encoded = CompactFrameCodec.encode(composed)
        val encodedAt = System.nanoTime()
        val separate = listOf("CLOUD", "PRECIPITATION", "BLITZ_FORECAST").sumOf { encodedSizes.getValue(it) }
        println("COMPOSITION fullResolution=${width}x${height} separateResponses=$separate composedResponse=${encoded.size+14} composeMs=${(composedAt-start)/1e6} encodeMs=${(encodedAt-composedAt)/1e6} repeatedCloudForecastRainPairSeparate=${separate+encodedSizes.getValue("PRECIPITATION")} composedPairEstimate=${2*(encoded.size+14)}")
    }
    private fun png(bitmap: Bitmap) = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray()
    private fun pixels(bitmap: Bitmap) = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height) }
}

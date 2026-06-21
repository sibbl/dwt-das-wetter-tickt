package net.sibbl.dwt.data.radar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder

object RadarFrameColorizer {
    private val palette = intArrayOf(
        0xFFBEE3F8.toInt(),
        0xFF33FFFF.toInt(),
        0xFF1ACC9A.toInt(),
        0xFF019934.toInt(),
        0xFF4DB31B.toInt(),
        0xFF99CC01.toInt(),
        0xFFCCE601.toInt(),
        0xFFFFFF01.toInt(),
        0xFFFFC401.toInt(),
        0xFFFF8901.toInt(),
        0xFFFF4501.toInt(),
        0xFFFE0000.toInt(),
        0xFFE5004C.toInt(),
        0xFFCC0098.toInt(),
        0xFF6600CB.toInt(),
        0xFF0000FE.toInt()
    )

    fun colorizePrecipitation(source: Bitmap): Bitmap {
        val mutableBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(mutableBitmap.width * mutableBitmap.height)
        mutableBitmap.getPixels(
            pixels,
            0,
            mutableBitmap.width,
            0,
            0,
            mutableBitmap.width,
            mutableBitmap.height
        )

        pixels.indices.forEach { index ->
            pixels[index] = colorizePixel(pixels[index])
        }

        mutableBitmap.setPixels(
            pixels,
            0,
            mutableBitmap.width,
            0,
            0,
            mutableBitmap.width,
            mutableBitmap.height
        )
        return mutableBitmap
    }

    fun colorizeCloud(source: Bitmap): Bitmap {
        val mutableBitmap = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(mutableBitmap.width * mutableBitmap.height)
        mutableBitmap.getPixels(
            pixels,
            0,
            mutableBitmap.width,
            0,
            0,
            mutableBitmap.width,
            mutableBitmap.height
        )

        pixels.indices.forEach { index ->
            pixels[index] = colorizeCloudPixel(pixels[index])
        }

        mutableBitmap.setPixels(
            pixels,
            0,
            mutableBitmap.width,
            0,
            0,
            mutableBitmap.width,
            mutableBitmap.height
        )
        return mutableBitmap
    }

    fun renderLightningMeasurements(source: ByteArray, bounds: GeoBounds): Bitmap {
        val bitmap = Bitmap.createBitmap(LIGHTNING_BITMAP_SIZE, LIGHTNING_BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        if (source.isEmpty()) {
            return bitmap
        }
        val canvas = Canvas(bitmap)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LIGHTNING_YELLOW
            style = Paint.Style.FILL
        }
        val boltPath = Path()
        decodeLightningPoints(source, bounds).forEach { point ->
            val latitude = point.latitude
            val longitude = point.longitude
            val x = (((longitude - bounds.southWest.longitude) /
                (bounds.northEast.longitude - bounds.southWest.longitude)) * LIGHTNING_BITMAP_SIZE).toFloat()
            val y = (((bounds.northEast.latitude - latitude) /
                (bounds.northEast.latitude - bounds.southWest.latitude)) * LIGHTNING_BITMAP_SIZE).toFloat()
            boltPath.setLightningBolt(x, y)
            canvas.drawLightningBolt(boltPath, fillPaint)
        }
        return bitmap
    }

    fun renderLightningForecast(source: Bitmap): Bitmap {
        val bitmap = Bitmap.createBitmap(LIGHTNING_BITMAP_SIZE, LIGHTNING_BITMAP_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        val boltPath = Path()
        var outputY = LIGHTNING_FORECAST_GRID_SIZE / 2
        while (outputY < LIGHTNING_BITMAP_SIZE) {
            var outputX = LIGHTNING_FORECAST_GRID_SIZE / 2
            while (outputX < LIGHTNING_BITMAP_SIZE) {
                val intensity = maxForecastIntensity(
                    source = source,
                    outputX = outputX,
                    outputY = outputY
                )
                val color = lightningForecastColorForIntensity(intensity)
                if (color != Color.TRANSPARENT) {
                    fillPaint.color = color
                    boltPath.setLightningBolt(outputX.toFloat(), outputY.toFloat())
                    canvas.drawLightningBolt(boltPath, fillPaint)
                }
                outputX += LIGHTNING_FORECAST_GRID_SIZE
            }
            outputY += LIGHTNING_FORECAST_GRID_SIZE
        }
        return bitmap
    }

    internal fun lightningForecastColorForIntensity(intensity: Int): Int {
        return when {
            intensity >= LIGHTNING_INTENSE_THRESHOLD -> LIGHTNING_RED
            intensity >= LIGHTNING_THRESHOLD -> LIGHTNING_YELLOW
            else -> TRANSPARENT
        }
    }

    internal fun decodeLightningPoints(source: ByteArray, bounds: GeoBounds): List<GeoPoint> {
        val buffer = ByteBuffer.wrap(source).order(ByteOrder.BIG_ENDIAN)
        return buildList {
            while (buffer.remaining() >= 8) {
                val point = GeoPoint(
                    latitude = buffer.float.toDouble(),
                    longitude = buffer.float.toDouble()
                )
                if (bounds.contains(point)) {
                    add(point)
                }
            }
        }
    }

    private fun colorizePixel(color: Int): Int {
        if (Color.alpha(color) == 0) {
            return Color.TRANSPARENT
        }

        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)

        if (red == 255 && green == 0 && blue == 0) {
            return Color.TRANSPARENT
        }

        val paletteIndex = when {
            red == 0 && green == 0 && blue == 0 -> 0
            else -> {
                val greenBucket = (green / 15).coerceIn(0, 6)
                val blueBoost = when {
                    blue >= 200 -> 4
                    blue >= 6 -> 2
                    blue >= 5 -> 1
                    else -> 0
                }
                (1 + greenBucket + blueBoost).coerceIn(1, palette.lastIndex)
            }
        }

        val baseColor = palette[paletteIndex]
        val alpha = when {
            paletteIndex == 0 -> 112
            paletteIndex < 3 -> 148
            paletteIndex < 6 -> 188
            paletteIndex < 9 -> 224
            else -> 244
        }

        return Color.argb(
            alpha,
            Color.red(baseColor),
            Color.green(baseColor),
            Color.blue(baseColor)
        )
    }

    private fun colorizeCloudPixel(color: Int): Int {
        val sourceAlpha = Color.alpha(color)
        if (sourceAlpha == 0) {
            return Color.TRANSPARENT
        }

        val red = Color.red(color)
        val green = Color.green(color)
        val blue = Color.blue(color)
        val brightness = maxOf(red, green, blue)
        val cloudAmount = (255 - brightness).coerceIn(0, 255)
        if (cloudAmount < 6) {
            return Color.TRANSPARENT
        }

        val alpha = ((42 + (cloudAmount * 0.58f)) * (sourceAlpha / 255f))
            .toInt()
            .coerceIn(0, 178)
        return Color.argb(alpha, 232, 236, 241)
    }

    private fun maxForecastIntensity(source: Bitmap, outputX: Int, outputY: Int): Int {
        val outputLeft = (outputX - LIGHTNING_FORECAST_GRID_SIZE / 2).coerceAtLeast(0)
        val outputTop = (outputY - LIGHTNING_FORECAST_GRID_SIZE / 2).coerceAtLeast(0)
        val outputRight = (outputX + LIGHTNING_FORECAST_GRID_SIZE / 2).coerceAtMost(LIGHTNING_BITMAP_SIZE)
        val outputBottom = (outputY + LIGHTNING_FORECAST_GRID_SIZE / 2).coerceAtMost(LIGHTNING_BITMAP_SIZE)
        val sourceLeft = outputLeft * source.width / LIGHTNING_BITMAP_SIZE
        val sourceTop = outputTop * source.height / LIGHTNING_BITMAP_SIZE
        val sourceRight = (outputRight * source.width / LIGHTNING_BITMAP_SIZE).coerceAtLeast(sourceLeft + 1)
        val sourceBottom = (outputBottom * source.height / LIGHTNING_BITMAP_SIZE).coerceAtLeast(sourceTop + 1)
        var maximum = 0
        for (y in sourceTop until sourceBottom.coerceAtMost(source.height)) {
            for (x in sourceLeft until sourceRight.coerceAtMost(source.width)) {
                maximum = maxOf(maximum, Color.red(source.getPixel(x, y)))
            }
        }
        return maximum
    }

    private fun Canvas.drawLightningBolt(path: Path, fillPaint: Paint) {
        drawPath(path, lightningHaloPaint)
        drawPath(path, fillPaint)
        drawPath(path, lightningStrokePaint)
    }

    private fun Path.setLightningBolt(x: Float, y: Float) {
        reset()
        moveTo(x + 0.7f, y - 6.5f)
        lineTo(x - 3.5f, y + 0.6f)
        lineTo(x - 0.7f, y + 0.6f)
        lineTo(x - 1.8f, y + 6.5f)
        lineTo(x + 3.7f, y - 1.0f)
        lineTo(x + 0.8f, y - 1.0f)
        close()
    }

    private const val LIGHTNING_FORECAST_GRID_SIZE = 12
    private const val LIGHTNING_INTENSE_THRESHOLD = 170
    private const val LIGHTNING_THRESHOLD = 85
    private val LIGHTNING_RED = 0xFFFF3C00.toInt()
    private val LIGHTNING_YELLOW = 0xFFFFFE00.toInt()
    private const val TRANSPARENT = 0
    private const val LIGHTNING_BITMAP_SIZE = 384
    private val lightningHaloPaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xCC000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2.6f
            strokeJoin = Paint.Join.MITER
            strokeCap = Paint.Cap.SQUARE
        }
    }
    private val lightningStrokePaint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 0.9f
            strokeJoin = Paint.Join.MITER
            strokeCap = Paint.Cap.SQUARE
        }
    }
}

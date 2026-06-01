package net.sibbl.dwdradar.data.radar

import android.graphics.Bitmap
import android.graphics.Color

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
}

package net.sibbl.dwt.transport

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.sibbl.dwt.data.radar.RadarFrameColorizer
import net.sibbl.dwt.model.GeoBounds
import java.io.*
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater

/** Exact rendered pixels, including anti-aliasing. No assumed palette or alpha quantization.
 * Choose the smallest complete payload; PNG remains the escape hatch for arbitrary images.
 */
object CompactFrameCodec {
    private const val PNG = 0
    private const val PALETTE = 1
    private const val ALPHA = 2
    private const val TILES = 3
    private const val POINTS = 4
    private const val SOLID = 5
    private const val MAX_PIXELS = 4096 * 4096

    fun encode(bitmap: Bitmap): ByteArray {
        val width = bitmap.width; val height = bitmap.height
        require(width in 1..4096 && height in 1..4096)
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        if (pixels.all { it == pixels[0] }) return payload(SOLID, width, height) { it.writeInt(pixels[0]) }
        val png = ByteArrayOutputStream().apply {
            write(PNG)
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, this))
        }.toByteArray()
        var best = png
        val palette = LinkedHashMap<Int, Int>()
        for (pixel in pixels) {
            palette.getOrPut(pixel) { palette.size }
            if (palette.size > 256) break
        }
        if (palette.size <= 256) {
            val bits = bitsFor(palette.size)
            val packed = ByteArray((pixels.size * bits + 7) / 8)
            pixels.forEachIndexed { i, pixel ->
                val bit = i * bits
                packed[bit / 8] = (packed[bit / 8].toInt() or (palette.getValue(pixel) shl (8 - bits - bit % 8))).toByte()
            }
            val candidate = payload(PALETTE, width, height) { output ->
                output.writeShort(palette.size)
                palette.keys.forEach(output::writeInt)
                output.write(deflate(packed))
            }
            if (candidate.size < best.size) best = candidate
        }
        // Only use constant RGB when it is exact in the rendered bitmap, including transparent pixels.
        val rgb = pixels[0] and 0xffffff
        if (pixels.all { it and 0xffffff == rgb }) {
            val candidate = payload(ALPHA, width, height) { output ->
                output.writeInt(rgb)
                output.write(deflate(ByteArray(pixels.size) { (pixels[it] ushr 24).toByte() }))
            }
            if (candidate.size < best.size) best = candidate
        }
        // Forecasts repeat exact 12px glyph tiles. Store their pixels, not a renderer-dependent symbol.
        if (width % 12 == 0 && height % 12 == 0) {
            val tiles = mutableListOf<List<Int>>()
            val indices = mutableListOf<Int>()
            tileLoop@ for (y in 0 until height step 12) for (x in 0 until width step 12) {
                val tile = List(144) { pixels[(y + it / 12) * width + x + it % 12] }
                var index = tiles.indexOf(tile)
                if (index < 0) { index = tiles.size; tiles += tile }
                if (tiles.size > 4) break@tileLoop
                indices += index
            }
            if (tiles.size <= 4) {
                val candidate = payload(TILES, width, height) { output ->
                    output.writeByte(tiles.size)
                    val raw = ByteArrayOutputStream().apply {
                        val data = DataOutputStream(this)
                        tiles.forEach { tile -> tile.forEach(data::writeInt) }
                        val packed = ByteArray((indices.size + 3) / 4)
                        indices.forEachIndexed { i, value -> packed[i / 4] = (packed[i / 4].toInt() or (value shl (6 - (i % 4) * 2))).toByte() }
                        data.write(packed)
                    }.toByteArray()
                    output.write(deflate(raw))
                }
                if (candidate.size < best.size) best = candidate
            }
        }
        if (best.size > RadarFrameWire.MAX_IMAGE_BYTES) throw IOException("Frame too large")
        return best
    }

    fun encodeMeasurements(bitmap: Bitmap, source: ByteArray, bounds: GeoBounds): ByteArray {
        val raster = encode(bitmap)
        // Preserve float coordinates and draw order, discard only points the existing renderer discards.
        val points = RadarFrameColorizer.decodeLightningPoints(source, bounds)
        if (points.size > 131072) return raster
        val raw = ByteArrayOutputStream().apply {
            val data = DataOutputStream(this)
            points.forEach { data.writeFloat(it.latitude.toFloat()); data.writeFloat(it.longitude.toFloat()) }
        }.toByteArray()
        val candidate = ByteArrayOutputStream().apply {
            val data = DataOutputStream(this)
            data.writeByte(POINTS); data.writeInt(points.size); data.write(deflate(raw))
        }.toByteArray()
        return if (candidate.size < raster.size) candidate else raster
    }

    fun decode(bytes: ByteArray, bounds: GeoBounds? = null): Bitmap {
        if (bytes.size !in 1..RadarFrameWire.MAX_IMAGE_BYTES) throw IOException("Invalid payload size")
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val mode = input.readUnsignedByte()
        if (mode == POINTS) {
            val pointCount = input.readInt()
            if (pointCount !in 0..131072 || bounds == null) throw IOException("Invalid points")
            return RadarFrameColorizer.renderLightningMeasurements(inflate(input.readBytes(), pointCount * 8), bounds)
        }
        if (mode == PNG) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1, options)
            dimensions(options.outWidth, options.outHeight)
            return BitmapFactory.decodeByteArray(bytes, 1, bytes.size - 1)
                ?: throw IOException("Invalid PNG")
        }
        if (mode != PALETTE && mode != ALPHA && mode != TILES && mode != SOLID) throw IOException("Unknown pixel encoding")
        val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
        val count = dimensions(width, height)
        val pixels = IntArray(count)
        if (mode == SOLID) {
            val pixel = input.readInt()
            if (input.available() != 0) throw IOException("Trailing solid pixels")
            pixels.fill(pixel)
        } else if (mode == PALETTE) {
            val size = input.readUnsignedShort()
            if (size !in 1..256) throw IOException("Invalid palette")
            val palette = IntArray(size) { input.readInt() }
            val bits = bitsFor(size)
            val packed = inflate(input.readBytes(), (count * bits + 7) / 8)
            for (i in pixels.indices) {
                val bit = i * bits
                val index = (packed[bit / 8].toInt() ushr (8 - bits - bit % 8)) and ((1 shl bits) - 1)
                if (index >= size) throw IOException("Invalid palette index")
                pixels[i] = palette[index]
            }
        } else if (mode == TILES) {
            if (width % 12 != 0 || height % 12 != 0) throw IOException("Invalid tile dimensions")
            val size = input.readUnsignedByte()
            if (size !in 1..4) throw IOException("Invalid tile count")
            val tileCount = width / 12 * (height / 12)
            val raw = DataInputStream(ByteArrayInputStream(inflate(input.readBytes(), size * 144 * 4 + (tileCount + 3) / 4)))
            val tiles = Array(size) { IntArray(144) { raw.readInt() } }
            val packed = raw.readBytes()
            for (i in 0 until tileCount) {
                val index = (packed[i / 4].toInt() ushr (6 - (i % 4) * 2)) and 3
                if (index >= size) throw IOException("Invalid tile index")
                val x = (i % (width / 12)) * 12; val y = (i / (width / 12)) * 12
                for (j in 0 until 144) pixels[(y + j / 12) * width + x + j % 12] = tiles[index][j]
            }
        } else {
            val rgb = input.readInt()
            if (rgb ushr 24 != 0) throw IOException("Invalid RGB")
            val alpha = inflate(input.readBytes(), count)
            for (i in pixels.indices) pixels[i] = (alpha[i].toInt() shl 24) or rgb
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun dimensions(width: Int, height: Int): Int {
        if (width !in 1..4096 || height !in 1..4096 || width.toLong() * height > MAX_PIXELS) throw IOException("Invalid dimensions")
        return width * height
    }
    private fun bitsFor(size: Int) = when { size <= 2 -> 1; size <= 4 -> 2; size <= 16 -> 4; else -> 8 }
    private fun payload(mode: Int, width: Int, height: Int, body: (DataOutputStream) -> Unit): ByteArray =
        ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { output ->
                output.writeByte(mode); output.writeShort(width); output.writeShort(height); body(output)
            }
        }.toByteArray()
    private fun deflate(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { output ->
        DeflaterOutputStream(output).use { it.write(bytes) }
    }.toByteArray()
    private fun inflate(bytes: ByteArray, expected: Int): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(bytes)
            val result = ByteArray(expected)
            var offset = 0
            while (offset < expected) {
                val read = inflater.inflate(result, offset, expected - offset)
                if (read == 0) throw IOException("Truncated pixel stream")
                offset += read
            }
            if (inflater.inflate(ByteArray(1)) != 0 || !inflater.finished() || inflater.remaining != 0) {
                throw IOException("Invalid pixel stream length")
            }
            return result
        } catch (error: java.util.zip.DataFormatException) {
            throw IOException("Invalid pixel stream", error)
        } finally { inflater.end() }
    }
}

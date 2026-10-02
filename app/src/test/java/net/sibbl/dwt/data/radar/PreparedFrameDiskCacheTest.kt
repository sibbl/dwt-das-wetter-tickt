package net.sibbl.dwt.data.radar

import android.graphics.Bitmap
import android.graphics.Color
import net.sibbl.dwt.model.RadarFrameReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PreparedFrameDiskCacheTest {
    @get:Rule val files = TemporaryFolder()
    private fun frame(time: Long) = RadarBitmapFrame(
        RadarFrameReference(time, "asset-v1.zip", 300000, 0, 3600000),
        Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.argb(112, 128, 64, 32)) },
        RadarBackend.defaultBounds
    )

    @Test fun `processed pixels and bounds survive restart and source changes cannot hit stale data`() {
        val directory = files.newFolder(); val original = frame(1000)
        PreparedFrameDiskCache(directory).write(original)
        val reopened = PreparedFrameDiskCache(directory)
        val loaded = requireNotNull(reopened.read(original.reference))
        assertEquals(original.bounds, loaded.bounds)
        assertEquals(original.bitmap.getPixel(0, 0), loaded.bitmap.getPixel(0, 0))
        assertFalse(reopened.contains(original.reference.copy(assetPath = "asset-v2.zip")))
        assertFalse(reopened.contains(original.reference.copy(layerKey = "CLOUD")))
    }

    @Test fun `byte and entry budgets prune least recently used prepared frames`() {
        val directory = files.newFolder(); val a = frame(1); val b = frame(2); val c = frame(3)
        val first = PreparedFrameDiskCache(directory); first.write(a)
        val one = first.byteCount()
        val cache = PreparedFrameDiskCache(directory, maxBytes = one * 2, maxEntries = 2)
        cache.write(b); assertNotNull(cache.read(a.reference)); cache.write(c)
        assertTrue(cache.byteCount() <= one * 2)
        assertTrue(cache.contains(a.reference)); assertFalse(cache.contains(b.reference)); assertTrue(cache.contains(c.reference))
    }

    @Test fun `corrupt prepared entry is removed and can be regenerated`() {
        val directory = files.newFolder(); val a = frame(1); val cache = PreparedFrameDiskCache(directory)
        cache.write(a); directory.listFiles()!!.single().writeText("broken")
        assertNull(cache.read(a.reference)); assertFalse(cache.contains(a.reference))
        cache.write(a); assertNotNull(cache.read(a.reference))
    }

    @Test fun `old prepared entries expire on reopening`() {
        val directory = files.newFolder(); val a = frame(1)
        PreparedFrameDiskCache(directory, clock = { 1000L }).write(a)
        val nextDay = PreparedFrameDiskCache(directory, clock = { 1000L + 25 * 60 * 60 * 1000 })
        assertFalse(nextDay.contains(a.reference)); assertEquals(0L, nextDay.byteCount())
    }
}

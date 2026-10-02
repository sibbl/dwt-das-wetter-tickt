package net.sibbl.dwt.data.radar

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.sibbl.dwt.model.RadarFrameReference
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadarRepositoryAndroidTest {
    @get:Rule val files = TemporaryFolder()
    private val reference = RadarFrameReference(1000, "rain.zip", 500, 1000, 2000)

    @Test fun `cancelling selected frame preserves one ZIP download and later cache hit`() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val bytes = zipFrames()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val repository = repository(client)
        repository.retainFrames(setOf(reference), setOf(reference))
        try {
            withTimeout(10_000) {
                val first = async(kotlinx.coroutines.Dispatchers.Default) { repository.loadFrame(reference) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                first.cancelAndJoin()
                val second = async { repository.loadFrame(reference) }
                release.countDown()
                val result = second.await()
                assertEquals(32, result.bitmap.width)
                assertEquals(112, Color.alpha(result.bitmap.getPixel(0, 0)))
                assertSame(result, repository.loadFrame(reference))
                assertEquals(1, calls.get())
            }
        } finally { release.countDown(); repository.close() }
    }

    @Test fun `processed companion frame avoids network and provider failure falls back locally`() = runBlocking {
        val calls = AtomicInteger()
        val bytes = zipFrames()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(bytes.toResponseBody()).build()
        }.build()
        val remote = RadarBitmapFrame(reference, Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888), RadarBackend.defaultBounds)
        val companion = repository(client, OptionalFrameProvider { _, _ -> remote })
        val fallback = repository(client, OptionalFrameProvider { _, _ -> throw java.io.IOException("Disconnected") })
        try {
            companion.retainFrames(setOf(reference), setOf(reference))
            fallback.retainFrames(setOf(reference), setOf(reference))
            assertSame(remote, companion.loadFrame(reference))
            assertEquals(0, calls.get())
            assertEquals(32, fallback.loadFrame(reference).bitmap.width)
            assertEquals(1, calls.get())
        } finally { companion.close(); fallback.close() }
    }

    @Test fun `in place rain and cloud colorization preserve immutable input results`() {
        val colors = intArrayOf(Color.BLACK, Color.RED, Color.TRANSPARENT, Color.WHITE)
        for (colorize in listOf<(Bitmap) -> Bitmap>(RadarFrameColorizer::colorizePrecipitation, RadarFrameColorizer::colorizeCloud)) {
            val mutable = Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)
            mutable.setPixels(colors,0,2,0,0,2,2)
            val immutable = mutable.copy(Bitmap.Config.ARGB_8888,false)
            val expected = colorize(immutable)
            val actual = colorize(mutable)
            assertSame(mutable, actual)
            for (y in 0..1) for (x in 0..1) assertEquals(expected.getPixel(x,y), actual.getPixel(x,y))
            assertEquals(Color.BLACK, immutable.getPixel(0,0))
        }
    }

    @Test fun `segment round trips and restart reuse prepared frames without companion or network`() = runBlocking {
        val directory = files.newFolder()
        val calls = AtomicInteger()
        val a = reference
        val b = reference.copy(timestampMillis = 2000, assetPath = "neighbor.zip")
        val client = OkHttpClient.Builder().addInterceptor { error("Unexpected network access") }.build()
        val provider = OptionalFrameProvider { ref, _ ->
            calls.incrementAndGet()
            RadarBitmapFrame(ref, Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }, RadarBackend.defaultBounds)
        }
        fun fresh() = RadarRepository(RuntimeEnvironment.getApplication(), client,
            RadarDiskCache(directory) { 0L }, optionalFrames = provider)
        var repo = fresh()
        try {
            for (ref in listOf(a, b, a, b)) {
                repo.retainFrames(setOf(ref), setOf(ref))
                assertEquals(Color.GREEN, repo.loadFrame(ref).bitmap.getPixel(0, 0))
            }
            assertEquals(2, calls.get())
            repo.close(); repo = fresh()
            repo.retainFrames(setOf(a), setOf(a)); repo.loadFrame(a)
            assertEquals(2, calls.get())
            val updated = a.copy(assetPath = "updated-source.zip")
            repo.retainFrames(setOf(updated), setOf(updated)); repo.loadFrame(updated)
            assertEquals(3, calls.get())
        } finally { repo.close() }
    }

    @Test fun `cancelled companion selection keeps single shared frame preparation alive`() = runBlocking {
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val provider = OptionalFrameProvider { ref, _ ->
            calls.incrementAndGet(); entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            RadarBitmapFrame(ref, Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888), RadarBackend.defaultBounds)
        }
        val repo = repository(OkHttpClient(), provider)
        repo.retainFrames(setOf(reference), setOf(reference))
        try {
            val first = async(kotlinx.coroutines.Dispatchers.Default) { repo.loadFrame(reference) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); first.cancelAndJoin()
            val second = async { repo.loadFrame(reference) }; release.countDown(); second.await()
            assertEquals(1, calls.get()); assertTrue(repo.isFramePrepared(reference))
        } finally { release.countDown(); repo.close() }
    }

    private fun repository(client: OkHttpClient, provider: OptionalFrameProvider? = null) = RadarRepository(
        RuntimeEnvironment.getApplication(), client,
        RadarDiskCache(files.newFolder()) { 0L }, optionalFrames = provider
    )

    private fun zipFrames(): ByteArray {
        val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        val png = ByteArrayOutputStream().apply { bitmap.compress(Bitmap.CompressFormat.PNG,100,this) }.toByteArray()
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { output ->
            output.putNextEntry(ZipEntry("1000.png")); output.write(png); output.closeEntry()
        }
        return zip.toByteArray()
    }
}

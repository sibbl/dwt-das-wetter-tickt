package net.sibbl.dwt.data.radar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class RadarRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val cache: RadarDiskCache = RadarDiskCache(context.cacheDir) { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val optionalFrames: OptionalFrameProvider? = null
) {
    private val overviewMutex = Mutex()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sharedAssets = SharedAssetLoads<File>(downloadScope)
    private val sharedFrames = SharedAssetLoads<RadarBitmapFrame>(downloadScope)
    private val preparedFrames = PreparedFrameDiskCache(cache.preparedDirectory)
    @Volatile private var retainedReferences = emptySet<RadarFrameReference>()
    private val downloadGate = PriorityWorkGate(2)
    private val decodeGate = PriorityWorkGate(1)
    private val optionalFrameGate = PriorityWorkGate(1)
    private val optionalOverlayGate = PriorityWorkGate(1)
    private val frameCache = NavigationFrameCache<RadarFrameReference, RadarBitmapFrame>(
        BITMAP_CACHE_BYTES
    ) { it.bitmap.allocationByteCount.toLong() }

    fun close() = downloadScope.cancel()
    fun cachedFrame(reference: RadarFrameReference): RadarBitmapFrame? = frameCache[reference]
    fun isFramePrepared(reference: RadarFrameReference): Boolean =
        frameCache[reference] != null || preparedFrames.contains(reference)
    fun cachedReferences(): Set<RadarFrameReference> =
        frameCache.keys + retainedReferences.filter(preparedFrames::contains)
    fun retainFrames(retained: Set<RadarFrameReference>, preferredFrames: Set<RadarFrameReference>) {
        retainedReferences = retained
        frameCache.retain(retained, preferredFrames)
        RadarPerformance.event("cache bytes=${frameCache.bytes} frames=${frameCache.keys.size} budget=$BITMAP_CACHE_BYTES")
    }

    suspend fun loadTimeline(
        forceRefresh: Boolean = false,
        layerKey: String = RadarBackend.PRECIPITATION_LAYER
    ): RadarTimeline = loadTimeline(forceRefresh, listOf(layerKey))

    suspend fun loadTimeline(
        forceRefresh: Boolean = false,
        layerKeys: List<String>
    ): RadarTimeline = withContext(Dispatchers.IO) {
        val cached = if (forceRefresh) null else cache.readOverview(maxAgeMillis = OVERVIEW_CACHE_AGE_MILLIS)
        val overviewJson = cached ?: overviewMutex.withLock {
            val secondLook = if (forceRefresh) null else cache.readOverview(maxAgeMillis = OVERVIEW_CACHE_AGE_MILLIS)
            secondLook ?: fetchText(RadarBackend.OVERVIEW_URL).also(cache::writeOverview)
        }
        val overview = json.decodeFromString<RadarOverviewDto>(overviewJson)
        RadarTimelineBuilder(layerKeys = layerKeys).build(
            overview = overview,
            fallbackNowMillis = clock()
        )
    }

    fun isAssetCached(reference: RadarFrameReference): Boolean {
        return cache.assetFile(reference.assetPath).let { file ->
            file.exists() && file.length() > 0L
        }
    }

    suspend fun prefetchAsset(
        reference: RadarFrameReference,
        onProgress: (Float) -> Unit = {}
    ) = withContext(Dispatchers.IO) {
        ensureAsset(reference.assetPath, onProgress = onProgress)
    }

    suspend fun loadFrame(
        reference: RadarFrameReference,
        foreground: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): RadarBitmapFrame = withContext(Dispatchers.IO) {
        frameCache[reference]?.let { onProgress(1f); return@withContext it }
        val key = cacheKey(reference)
        if (foreground) {
            decodeGate.promote(key)
            optionalFrameGate.promote(key)
            optionalOverlayGate.promote(key)
            downloadGate.promote(reference.assetPath)
        }
        sharedFrames.await(key) {
            try { prepareFrame(reference, foreground, onProgress) }
            finally {
                decodeGate.clearPromotion(key)
                optionalFrameGate.clearPromotion(key)
                optionalOverlayGate.clearPromotion(key)
                downloadGate.clearPromotion(reference.assetPath)
            }
        }
            .also { onProgress(1f) }
    }

    private suspend fun prepareFrame(
        reference: RadarFrameReference,
        foreground: Boolean = true,
        onProgress: (Float) -> Unit = {}
    ): RadarBitmapFrame = withContext(Dispatchers.IO) {
        val started = RadarPerformance.now()
        val key = cacheKey(reference)
        var cacheHit = false
        try {
            frameCache[reference]?.let {
                cacheHit = true
                onProgress(1f)
                return@withContext it
            }
            decodeGate.run(key, foreground) {
                preparedFrames.read(reference)?.also { frameCache[reference] = it }
            }?.let {
                RadarPerformance.event("route=prepared time=${reference.timestampMillis} layer=${reference.layerKey}")
                return@withContext it
            }
            if (optionalFrames != null) {
                val gate = if (reference.layerKey == RadarBackend.PRECIPITATION_LAYER) optionalFrameGate else optionalOverlayGate
                gate.run(key, foreground) {
                    // The selected request may have filled the cache while prefetch waited.
                    frameCache[reference] ?: tryOptionalFrame { optionalFrames.load(reference, foreground) }
                        ?.takeIf { it.reference == reference }
                        ?.also { preparedFrames.write(it); frameCache[reference] = it }
                }?.let { frame ->
                    onProgress(1f)
                    return@withContext frame
                }
            }
            RadarPerformance.event("route=watch time=${reference.timestampMillis} foreground=$foreground")
            // Download ownership is independent of this cancellable frame request.
            val assetStarted = RadarPerformance.now()
            var zipFile = ensureAsset(reference.assetPath, foreground = foreground, onProgress = onProgress)
            val assetWait = RadarPerformance.now() - assetStarted
            repeat(2) { attempt ->
                val queued = RadarPerformance.now()
                try {
                    return@withContext decodeGate.run(key, foreground) {
                        val queueMillis = RadarPerformance.now() - queued
                        frameCache[reference]?.let { cacheHit = true; return@run it }
                        val decodeStarted = RadarPerformance.now()
                        val result = withContext(Dispatchers.Default) {
                            decodeFrame(reference, zipFile).also { preparedFrames.write(it); frameCache[reference] = it }
                        }
                        RadarPerformance.event("frame layer=${reference.layerKey} time=${reference.timestampMillis} foreground=$foreground assetWaitMs=$assetWait decodeQueueMs=$queueMillis processMs=${RadarPerformance.now() - decodeStarted} bytes=${result.bitmap.allocationByteCount}")
                        result
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (attempt == 1) throw error
                    frameCache.remove(reference)
                    // Do not hold the decode permit during recovery downloads either.
                    zipFile = ensureAsset(reference.assetPath, forceRefresh = true,
                        foreground = foreground, onProgress = onProgress)
                }
            }
            error("Unreachable frame decode")
        } finally {
            RadarPerformance.event("request layer=${reference.layerKey} time=${reference.timestampMillis} foreground=$foreground cacheHit=$cacheHit totalMs=${RadarPerformance.now() - started} cancelled=${!currentCoroutineContext().isActive}")
        }
    }

    suspend fun measurementSource(reference: RadarFrameReference, foreground: Boolean): ByteArray = withContext(Dispatchers.IO) {
        require(reference.layerKey == RadarBackend.LIGHTNING_MEASUREMENT_LAYER)
        ZipFile(ensureAsset(reference.assetPath, foreground = foreground)).use { zip ->
            val entry = zip.getEntry("${reference.timestampMillis}.png") ?: throw IOException("Missing measurements")
            zip.getInputStream(entry).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > 1024 * 1024) throw IOException("Too many measurements")
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
    }

    private fun decodeFrame(
        reference: RadarFrameReference,
        zipFile: File
    ): RadarBitmapFrame {
        var bounds = RadarBackend.defaultBounds
        val styledBitmap = ZipFile(zipFile).use { zip ->
            bounds = RadarPerformance.measure("bounds", reference) { loadBounds(reference, zip) }
            val entry = zip.getEntry("${reference.timestampMillis}.png")
                ?: throw IOException("Missing frame for ${reference.timestampMillis}")
            if (reference.layerKey == RadarBackend.LIGHTNING_MEASUREMENT_LAYER) {
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                RadarPerformance.measure("color", reference) { RadarFrameColorizer.renderLightningMeasurements(bytes, bounds) }
            } else if (reference.layerKey == RadarBackend.LIGHTNING_FORECAST_LAYER) {
                val decodedBitmap = RadarPerformance.measure("png", reference) { zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                        inMutable = true
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                        ?: throw IOException("Failed to decode lightning forecast for ${reference.timestampMillis}")
                } }
                RadarPerformance.measure("color", reference) { RadarFrameColorizer.renderLightningForecast(decodedBitmap) }
            } else {
                val decodedBitmap = RadarPerformance.measure("png", reference) { zip.getInputStream(entry).use { input ->
                    BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
                        inMutable = true
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
                        ?: throw IOException("Failed to decode PNG frame for ${reference.timestampMillis}")
                } }
                RadarPerformance.measure("color", reference) { when (reference.layerKey) {
                    RadarBackend.CLOUD_LAYER -> RadarFrameColorizer.colorizeCloud(decodedBitmap)
                    else -> RadarFrameColorizer.colorizePrecipitation(decodedBitmap)
                } }
            }
        }
        return RadarBitmapFrame(
            reference = reference,
            bitmap = styledBitmap,
            bounds = bounds
        )
    }

    private fun loadBounds(reference: RadarFrameReference, zip: ZipFile): GeoBounds {
        val boundsEntry = zip.getEntry("${reference.timestampMillis}.json")
            ?: return RadarBackend.defaultBounds
        val boundsDto = zip.getInputStream(boundsEntry).use { input ->
            json.decodeFromString<RadarFrameBoundsDto>(input.bufferedReader().readText())
        }
        return if (boundsDto.upperLatitude == 0.0 && boundsDto.upperLongitude == 0.0) {
            RadarBackend.defaultBounds
        } else {
            GeoBounds(
                southWest = GeoPoint(boundsDto.lowerLatitude, boundsDto.lowerLongitude),
                northEast = GeoPoint(boundsDto.upperLatitude, boundsDto.upperLongitude)
            )
        }
    }

    private suspend fun ensureAsset(
        assetPath: String,
        forceRefresh: Boolean = false,
        foreground: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): File {
        val targetFile = cache.assetFile(assetPath)
        if (!forceRefresh && targetFile.exists() && targetFile.length() > 0L) {
            onProgress(1f)
            return targetFile
        }
        if (foreground) downloadGate.promote(assetPath)
        return sharedAssets.await(assetPath) {
            // Another caller can have finished between the initial check and ownership.
            if (!forceRefresh && targetFile.exists() && targetFile.length() > 0L) {
                return@await targetFile
            }
            val queued = RadarPerformance.now()
            downloadGate.run(assetPath, foreground) {
                RadarPerformance.event("asset key=${targetFile.name} queueMs=${RadarPerformance.now() - queued}")
                fetchBinary(RadarBackend.assetUrl(assetPath), targetFile, onProgress)
                targetFile
            }
        }.also { downloadGate.clearPromotion(assetPath); onProgress(1f) }
    }

    private fun fetchText(url: String): String {
        val request = Request.Builder()
            .url(url)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Failed to fetch $url (${response.code})")
            }
            return response.body?.string()
                ?: throw IOException("Empty body for $url")
        }
    }

    private suspend fun fetchBinary(
        url: String,
        target: File,
        onProgress: (Float) -> Unit
    ) = coroutineScope {
        val request = Request.Builder()
            .url(url)
            .build()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        target.parentFile?.mkdirs()
        onProgress(0f)
        val started = RadarPerformance.now()
        val call = client.newCall(request)
        val cancellationWatcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { call.cancel() }
        }
        try {
            call.awaitResponse().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Failed to fetch $url (${response.code})")
                }
                val body = response.body ?: throw IOException("Empty body for $url")
                val contentLength = body.contentLength().takeIf { it > 0L }
                var bytesReadTotal = 0L
                var lastReportedProgress = 0f

                try {
                    tempFile.outputStream().use { output ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val bytesRead = input.read(buffer)
                                if (bytesRead == -1) {
                                    break
                                }
                                output.write(buffer, 0, bytesRead)
                                bytesReadTotal += bytesRead
                                if (contentLength != null) {
                                    val progress = (bytesReadTotal.toFloat() / contentLength.toFloat())
                                        .coerceIn(0f, 0.98f)
                                    if (progress - lastReportedProgress >= 0.03f) {
                                        lastReportedProgress = progress
                                        onProgress(progress)
                                    }
                                }
                            }
                        }
                    }
                    if (tempFile.length() == 0L) {
                        throw IOException("Empty body for $url")
                    }
                    if (target.exists() && !target.delete()) {
                        throw IOException("Could not replace cached radar asset ${target.name}")
                    }
                    if (!tempFile.renameTo(target)) {
                        tempFile.copyTo(target, overwrite = true)
                        tempFile.delete()
                    }
                    onProgress(1f)
                    RadarPerformance.event("download key=${target.name} bytes=$bytesReadTotal totalMs=${RadarPerformance.now() - started}")
                } catch (throwable: Throwable) {
                    tempFile.delete()
                    throw throwable
                }
            }
        } catch (error: Exception) {
            RadarPerformance.event("download key=${target.name} failed=${error.javaClass.simpleName} totalMs=${RadarPerformance.now() - started}")
            throw error
        } finally {
            cancellationWatcher.cancel()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { response.close() }
            }
        })
    }

    private fun cacheKey(reference: RadarFrameReference): String {
        return reference.toString()
    }

    private companion object {
        const val BITMAP_CACHE_BYTES = 32L * 1024 * 1024
        const val OVERVIEW_CACHE_AGE_MILLIS = 5 * 60 * 1000L
    }
}

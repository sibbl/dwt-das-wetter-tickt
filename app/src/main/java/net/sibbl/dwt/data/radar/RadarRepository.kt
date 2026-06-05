package net.sibbl.dwt.data.radar

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.model.RadarTimeline
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

class RadarRepository(
    context: Context,
    private val client: OkHttpClient = OkHttpClient(),
    private val cache: RadarDiskCache = RadarDiskCache(context.cacheDir) { System.currentTimeMillis() },
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val overviewMutex = Mutex()
    private val assetMutex = Mutex()
    private val bitmapCache = object : LruCache<String, Bitmap>(BITMAP_CACHE_FRAMES) {}

    fun resizeCache(newMaxSize: Int) {
        bitmapCache.resize(newMaxSize)
    }

    suspend fun loadTimeline(
        forceRefresh: Boolean = false,
        layerKey: String = RadarBackend.PRECIPITATION_LAYER
    ): RadarTimeline = withContext(Dispatchers.IO) {
        val cached = if (forceRefresh) null else cache.readOverview(maxAgeMillis = OVERVIEW_CACHE_AGE_MILLIS)
        val overviewJson = cached ?: overviewMutex.withLock {
            val secondLook = if (forceRefresh) null else cache.readOverview(maxAgeMillis = OVERVIEW_CACHE_AGE_MILLIS)
            secondLook ?: fetchText(RadarBackend.OVERVIEW_URL).also(cache::writeOverview)
        }
        val overview = json.decodeFromString<RadarOverviewDto>(overviewJson)
        RadarTimelineBuilder(layerKey = layerKey).build(
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
        onProgress: (Float) -> Unit = {}
    ): RadarBitmapFrame = withContext(Dispatchers.IO) {
        val key = cacheKey(reference)
        val bitmap = bitmapCache.get(key)
        if (bitmap != null) {
            onProgress(1f)
            return@withContext RadarBitmapFrame(
                reference = reference,
                bitmap = bitmap,
                bounds = loadBounds(reference)
            )
        }

        val zipFile = ensureAsset(reference.assetPath, onProgress = onProgress)
        runCatching {
            decodeFrame(reference = reference, zipFile = zipFile, cacheKey = key)
        }.getOrElse { firstError ->
            bitmapCache.remove(key)
            val freshZipFile = ensureAsset(
                assetPath = reference.assetPath,
                forceRefresh = true,
                onProgress = onProgress
            )
            runCatching {
                decodeFrame(reference = reference, zipFile = freshZipFile, cacheKey = key)
            }.getOrElse {
                throw firstError
            }
        }
    }

    private fun decodeFrame(
        reference: RadarFrameReference,
        zipFile: File,
        cacheKey: String
    ): RadarBitmapFrame {
        val decodedBitmap = ZipFile(zipFile).use { zip ->
            zip.getInputStream(
                zip.getEntry("${reference.timestampMillis}.png")
                    ?: throw IOException("Missing PNG frame for ${reference.timestampMillis}")
            ).use { input ->
                BitmapFactory.decodeStream(input)
                    ?: throw IOException("Failed to decode PNG frame for ${reference.timestampMillis}")
            }
        }
        val styledBitmap = when (reference.layerKey) {
            RadarBackend.CLOUD_LAYER -> RadarFrameColorizer.colorizeCloud(decodedBitmap)
            else -> RadarFrameColorizer.colorizePrecipitation(decodedBitmap)
        }
        bitmapCache.put(cacheKey, styledBitmap)
        return RadarBitmapFrame(
            reference = reference,
            bitmap = styledBitmap,
            bounds = loadBounds(reference)
        )
    }

    private fun loadBounds(reference: RadarFrameReference): GeoBounds {
        val zipFile = cache.assetFile(reference.assetPath)
        if (!zipFile.exists()) {
            return RadarBackend.defaultBounds
        }
        return ZipFile(zipFile).use { zip ->
            val boundsEntry = zip.getEntry("${reference.timestampMillis}.json")
                ?: return@use RadarBackend.defaultBounds
            val boundsDto = zip.getInputStream(boundsEntry).use { input ->
                json.decodeFromString<RadarFrameBoundsDto>(input.bufferedReader().readText())
            }
            if (boundsDto.upperLatitude == 0.0 && boundsDto.upperLongitude == 0.0) {
                RadarBackend.defaultBounds
            } else {
                GeoBounds(
                    southWest = GeoPoint(
                        latitude = boundsDto.lowerLatitude,
                        longitude = boundsDto.lowerLongitude
                    ),
                    northEast = GeoPoint(
                        latitude = boundsDto.upperLatitude,
                        longitude = boundsDto.upperLongitude
                    )
                )
            }
        }
    }

    private suspend fun ensureAsset(
        assetPath: String,
        forceRefresh: Boolean = false,
        onProgress: (Float) -> Unit = {}
    ): File {
        val targetFile = cache.assetFile(assetPath)
        if (!forceRefresh && targetFile.exists() && targetFile.length() > 0L) {
            onProgress(1f)
            return targetFile
        }
        return assetMutex.withLock {
            if (!forceRefresh && targetFile.exists() && targetFile.length() > 0L) {
                onProgress(1f)
                return@withLock targetFile
            }
            fetchBinary(
                url = RadarBackend.assetUrl(assetPath),
                target = targetFile,
                onProgress = onProgress
            )
            targetFile
        }
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
    ) {
        val request = Request.Builder()
            .url(url)
            .build()
        val tempFile = File(target.parentFile, "${target.name}.tmp")
        target.parentFile?.mkdirs()
        onProgress(0f)
        client.newCall(request).execute().use { response ->
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
            } catch (throwable: Throwable) {
                tempFile.delete()
                throw throwable
            }
        }
    }

    private fun cacheKey(reference: RadarFrameReference): String {
        return "${reference.assetPath}#${reference.timestampMillis}"
    }

    private companion object {
        const val BITMAP_CACHE_FRAMES = 48
        const val OVERVIEW_CACHE_AGE_MILLIS = 5 * 60 * 1000L
    }
}

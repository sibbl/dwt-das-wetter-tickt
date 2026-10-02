package net.sibbl.dwt.benchmark

import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.CapabilityClient
import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import net.sibbl.dwt.data.radar.RadarDiskCache
import net.sibbl.dwt.data.radar.RadarRepository
import net.sibbl.dwt.model.RadarFrameReference
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okio.BufferedSource
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Same harness for main and optimized builds, real frozen DWD PNG/ZIP data.
 * Only its dedicated app cache subfolder is created; no app data is cleared.
 */
class RadarEmulatorBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val assets = instrumentation.context.assets
    private val args = InstrumentationRegistry.getArguments()
    private val label = args.getString("variant") ?: "unknown"
    private val run = args.getString("sample") ?: "1"
    private val rows = JSONArray()
    private val frames: List<RadarFrameReference> by lazy {
        val array = JSONArray(assets.open("frames.json").bufferedReader().use { it.readText() })
        (0 until array.length()).map { i -> array.getJSONObject(i).let {
            RadarFrameReference(it.getLong("timestampMillis"), it.getString("assetPath"),
                it.getLong("timeStepMillis"), it.getLong("sectionStartMillis"), it.getLong("sectionEndMillis"))
        } }
    }
    private class Counters {
        val requests = AtomicInteger()
        val bytes = AtomicLong()
    }
    private fun repository(case: String, rate: Long, counters: Counters): RadarRepository {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath.substringAfter("/v16/")
            counters.requests.incrementAndGet()
            Thread.sleep(100) // same fixed header latency for both builds
            val raw = assets.open(path)
            val length = raw.available().toLong()
            val body = object : ResponseBody() {
                override fun contentType() = "application/zip".toMediaTypeOrNull()
                override fun contentLength() = length
                private val paced = object : InputStream() {
                    private var readTotal = 0L
                    private val started = System.nanoTime()
                    override fun read(): Int { val b = ByteArray(1); return if (read(b,0,1) == -1) -1 else b[0].toInt() and 255 }
                    override fun read(buffer: ByteArray, offset: Int, count: Int): Int {
                        val n = raw.read(buffer,offset,count)
                        if (n > 0) {
                            readTotal += n
                            counters.bytes.addAndGet(n.toLong())
                            val remaining = started + readTotal * 1_000_000_000L / rate - System.nanoTime()
                            if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining)
                        }
                        return n
                    }
                    override fun close() = raw.close()
                }
                override fun source(): BufferedSource = paced.source().buffer()
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("Fixture").body(body).build()
        }.build()
        val directory = File(context.cacheDir,"radar_emulator_benchmark/$label-$run-$case-${System.nanoTime()}")
        directory.mkdirs()
        val repo = RadarRepository(context, client, RadarDiskCache(directory) { System.currentTimeMillis() })
        try {
            repo.javaClass.getMethod("retainFrames", Set::class.java, Set::class.java)
                .invoke(repo, frames.toSet(), frames.toSet())
        } catch (_: NoSuchMethodException) {
            repo.javaClass.getMethod("resizeCache", Int::class.javaPrimitiveType).invoke(repo, frames.size)
        }
        return repo
    }
    private fun close(repo: RadarRepository) {
        try { repo.javaClass.getMethod("close").invoke(repo) } catch (_: NoSuchMethodException) { }
    }
    private suspend fun loadBackground(repo: RadarRepository, reference: RadarFrameReference) = withContext(Dispatchers.IO) {
        // foreground parameter exists only in the optimized implementation.
        val method = repo.javaClass.methods.firstOrNull { it.name == "loadFrame" && it.parameterCount == 4 }
        if (method == null) repo.loadFrame(reference) else {
            // Invoke the suspend method by bridging its continuation. No code path is replaced.
            kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
                try { method.invoke(repo, reference, false, { _: Float -> }, continuation) }
                catch (error: java.lang.reflect.InvocationTargetException) { throw error.targetException }
            }
        }
    }
    private fun measureMemory(row: JSONObject) {
        val memory = Debug.MemoryInfo(); Debug.getMemoryInfo(memory)
        row.put("pssKiB",memory.totalPss)
        row.put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize())
        row.put("javaHeapBytes",Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
        row.put("maxHeapBytes",Runtime.getRuntime().maxMemory())
        row.put("gcCount",Debug.getRuntimeStat("art.gc.gc-count"))
    }
    private fun record(row: JSONObject, counters: Counters) {
        row.put("variant",label).put("sample",run).put("requests",counters.requests.get()).put("readBytes",counters.bytes.get())
        measureMemory(row); rows.put(row); Log.i("DwtBench",row.toString())
        File(context.filesDir,"emulator-benchmark-$label-$run.json").writeText(rows.toString(2))
    }

    @Test fun benchmark() = runBlocking {
        try {
            val rate = 1024L * 1024
            val counters = Counters()
            val repo = repository("ring",rate,counters)
            try {
                val durations = JSONArray()
                val start = SystemClock.elapsedRealtimeNanos(); val cpu = Process.getElapsedCpuTime()
                for (frame in frames) {
                    val t = SystemClock.elapsedRealtimeNanos()
                    val bitmap = repo.loadFrame(frame).bitmap
                    check(bitmap.width == 1200 && bitmap.height == 1200)
                    durations.put((SystemClock.elapsedRealtimeNanos()-t)/1_000_000.0)
                }
                record(JSONObject().put("case","cold_ring_24").put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                    .put("cpuMs",Process.getElapsedCpuTime()-cpu).put("frameMs",durations),counters)
                val warm = JSONArray(); val warmStart = SystemClock.elapsedRealtimeNanos(); val warmCpu = Process.getElapsedCpuTime()
                for (frame in frames) {
                    val t = SystemClock.elapsedRealtimeNanos(); repo.loadFrame(frame)
                    warm.put((SystemClock.elapsedRealtimeNanos()-t)/1_000_000.0)
                }
                record(JSONObject().put("case","warm_ring_24").put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-warmStart)/1_000_000.0)
                    .put("cpuMs",Process.getElapsedCpuTime()-warmCpu).put("frameMs",warm),counters)
            } finally { close(repo) }
            System.gc(); delay(500)

            val scrollCounters = Counters()
            val scrollRepo = repository("scroll",rate,scrollCounters)
            try {
                val sameAsset = frames.groupBy { it.assetPath }.values.maxBy { it.size }.take(5)
                val start = SystemClock.elapsedRealtimeNanos(); val cpu = Process.getElapsedCpuTime()
                var job: Deferred<*>? = null
                for (i in 0 until 4) {
                    job?.cancel()
                    job = async(Dispatchers.IO) { scrollRepo.loadFrame(sameAsset[i]) }
                    delay(150)
                }
                job?.cancel()
                val selected = SystemClock.elapsedRealtimeNanos()
                scrollRepo.loadFrame(sameAsset[4])
                record(JSONObject().put("case","scroll_5_steps_150ms").put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                    .put("selectedWaitMs",(SystemClock.elapsedRealtimeNanos()-selected)/1_000_000.0)
                    .put("cpuMs",Process.getElapsedCpuTime()-cpu),scrollCounters)
            } finally { close(scrollRepo) }
            System.gc(); delay(500)

            val priorityCounters = Counters()
            val priorityRepo = repository("priority",256L*1024,priorityCounters)
            try {
                val selected = frames[frames.size/2]
                val background = frames.first { it.assetPath != selected.assetPath }
                // Identical initial state: selected ZIP cached but bitmap cold; background ZIP absent.
                priorityRepo.prefetchAsset(selected)
                val beforeRequests=priorityCounters.requests.get(); val beforeBytes=priorityCounters.bytes.get()
                val start = SystemClock.elapsedRealtimeNanos(); val cpu = Process.getElapsedCpuTime()
                val backgroundJob = async(Dispatchers.IO) { loadBackground(priorityRepo,background) }
                delay(150)
                val selectedAt=SystemClock.elapsedRealtimeNanos()
                priorityRepo.loadFrame(selected)
                val wait=(SystemClock.elapsedRealtimeNanos()-selectedAt)/1_000_000.0
                backgroundJob.await()
                record(JSONObject().put("case","selected_while_other_zip_loads").put("selectedWaitMs",wait)
                    .put("elapsedMs",(SystemClock.elapsedRealtimeNanos()-start)/1_000_000.0)
                    .put("cpuMs",Process.getElapsedCpuTime()-cpu).put("preloadRequests",beforeRequests).put("preloadBytes",beforeBytes),priorityCounters)
            } finally { close(priorityRepo) }
        } finally {
            val output=File(context.filesDir,"emulator-benchmark-$label-$run.json")
            output.writeText(rows.toString(2))
        }
    }

    @Test fun inspectDataLayer() {
        val client=Wearable.getNodeClient(context)
        val nodes=Tasks.await(client.connectedNodes,5,TimeUnit.SECONDS)
        val capabilities=Tasks.await(Wearable.getCapabilityClient(context).getCapability("dwt_rain_frames_v1",CapabilityClient.FILTER_REACHABLE),5,TimeUnit.SECONDS)
        Log.i("DwtBench","dataLayer nodes=${nodes.map { it.displayName }} companionNodes=${capabilities.nodes.size}")
    }
}

package net.sibbl.dwt.companion

import android.content.Context
import android.graphics.BitmapFactory
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import net.sibbl.dwt.data.radar.OptionalFrameProvider
import net.sibbl.dwt.data.radar.PriorityWorkGate
import net.sibbl.dwt.data.radar.RadarBackend
import net.sibbl.dwt.data.radar.RadarBitmapFrame
import net.sibbl.dwt.data.radar.RadarPerformance
import net.sibbl.dwt.model.GeoBounds
import net.sibbl.dwt.model.GeoPoint
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.transport.CompactFrameCodec
import net.sibbl.dwt.transport.RadarFrameWire
import net.sibbl.dwt.transport.awaitWearTask

class CompanionFrameClient(context: Context) : OptionalFrameProvider {
    private val context = context.applicationContext
    private val discoveryMutex = Mutex()
    private val rainGate = PriorityWorkGate(1)
    private val overlayGate = PriorityWorkGate(1)
    private data class Target(val id: String, val compact: Boolean)
    private var node: Target? = null
    private var discoverAfter = 0L
    private val retryAfter = java.util.concurrent.atomic.AtomicLongArray(2)
    private val preferences = context.getSharedPreferences("dwt_diagnostics", Context.MODE_PRIVATE)

    override suspend fun load(reference: RadarFrameReference, foreground: Boolean): RadarBitmapFrame? {
        val lane = if (reference.layerKey == RadarBackend.PRECIPITATION_LAYER) 0 else 1
        if (reference.layerKey !in RadarFrameWire.layers ||
            preferences.getBoolean("force_watch", false) || RadarPerformance.now() < retryAfter.get(lane)
        ) return null
        val started = RadarPerformance.now()
        try {
            val target = findNode() ?: return null
            if (!target.compact && reference.layerKey != RadarBackend.PRECIPITATION_LAYER) return null
            val gate = if (reference.layerKey == RadarBackend.PRECIPITATION_LAYER) rainGate else overlayGate
            return gate.run(reference.toString(), foreground) {
                if (preferences.getBoolean("force_watch", false) || RadarPerformance.now() < retryAfter.get(lane)) return@run null
                withTimeout(if (lane == 0) 20_000L else 8_000L) {
                    receive(target, reference, foreground, started)
                }
            }
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (cancelled !is kotlinx.coroutines.TimeoutCancellationException) throw cancelled
            retryAfter.set(lane, RadarPerformance.now() + 15_000L)
            RadarPerformance.event("route=watch fallback=timeout totalMs=${RadarPerformance.now() - started}")
            return null
        } catch (error: Exception) {
            retryAfter.set(lane, RadarPerformance.now() + 15_000L)
            RadarPerformance.event("route=watch fallback=${error.javaClass.simpleName} totalMs=${RadarPerformance.now() - started}")
            return null
        }
    }

    private suspend fun findNode(): Target? = discoveryMutex.withLock {
        val now = RadarPerformance.now()
        if (now < discoverAfter) return@withLock node
        node = withTimeout(1_500L) {
            val capabilities = Wearable.getCapabilityClient(context)
            GoogleApiAvailability.getInstance().checkApiAvailability(capabilities).awaitWearTask()
            val modern = capabilities.getCapability(RadarFrameWire.COMPACT_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitWearTask().nodes.sortedByDescending { it.isNearby }.firstOrNull()
            modern?.let { Target(it.id, true) } ?: capabilities.getCapability(RadarFrameWire.CAPABILITY, CapabilityClient.FILTER_REACHABLE)
                .awaitWearTask().nodes.sortedByDescending { it.isNearby }.firstOrNull()?.let { Target(it.id, false) }
        }
        discoverAfter = now + 15_000L
        RadarPerformance.event("companion available=${node != null}")
        node
    }

    private suspend fun receive(
        target: Target, reference: RadarFrameReference, foreground: Boolean, started: Long
    ): RadarBitmapFrame = coroutineScope {
        val client = Wearable.getChannelClient(context)
        var channel: ChannelClient.Channel? = null
        // Closing the channel interrupts a blocked stream read on timeout/scroll.
        val closer = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            try { kotlinx.coroutines.awaitCancellation() } finally { channel?.let { client.close(it) } }
        }
        try {
            channel = client.openChannel(target.id, if (target.compact) RadarFrameWire.COMPACT_CHANNEL_PATH else RadarFrameWire.CHANNEL_PATH).awaitWearTask { client.close(it) }
            val activeChannel = channel
            var requestBytes = 0
            val frame = withContext(Dispatchers.IO) {
                DataOutputStream(client.getOutputStream(activeChannel).awaitWearTask { it.close() }).use {
                    val request = java.io.ByteArrayOutputStream().apply {
                        val output = DataOutputStream(this)
                        if (target.compact) RadarFrameWire.writeCompactRequest(output, reference, foreground)
                        else RadarFrameWire.writeRequest(output, reference, foreground)
                    }.toByteArray()
                    requestBytes = request.size
                    it.write(request); it.flush()
                }
                DataInputStream(client.getInputStream(activeChannel).awaitWearTask { it.close() }).use { if (target.compact) RadarFrameWire.readCompactFrame(it) else RadarFrameWire.readFrame(it) }
            }
            val decodeStarted = RadarPerformance.now()
            val bitmap = withContext(Dispatchers.Default) {
                if (target.compact) return@withContext CompactFrameCodec.decode(frame.png, GeoBounds(GeoPoint(frame.south, frame.west), GeoPoint(frame.north, frame.east)))
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size, bounds)
                if (bounds.outWidth !in 1..RadarFrameWire.MAX_DIMENSION || bounds.outHeight !in 1..RadarFrameWire.MAX_DIMENSION) {
                    throw IOException("Invalid companion image dimensions")
                }
                BitmapFactory.decodeByteArray(frame.png, 0, frame.png.size)
                    ?: throw IOException("Invalid companion PNG")
            }
            val responseBytes = if (target.compact) RadarFrameWire.compactFrameBytes(frame) else frame.png.size + 48
            RadarPerformance.event("route=companion time=${reference.timestampMillis} foreground=$foreground phoneMs=${frame.phoneMillis} watchDecodeMs=${RadarPerformance.now() - decodeStarted} totalMs=${RadarPerformance.now() - started} requestBytes=$requestBytes responseBytes=$responseBytes applicationBytes=${requestBytes + responseBytes} layer=${reference.layerKey} compact=${target.compact}")
            RadarBitmapFrame(reference, bitmap, GeoBounds(GeoPoint(frame.south, frame.west), GeoPoint(frame.north, frame.east)))
        } finally {
            closer.cancel()
            channel?.let { client.close(it) }
        }
    }
}

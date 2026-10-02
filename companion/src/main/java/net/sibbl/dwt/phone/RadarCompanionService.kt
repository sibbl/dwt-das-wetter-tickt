package net.sibbl.dwt.phone

import android.graphics.Bitmap
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import net.sibbl.dwt.data.radar.PriorityWorkGate
import kotlinx.coroutines.withTimeout
import net.sibbl.dwt.data.radar.RadarPerformance
import net.sibbl.dwt.data.radar.RadarRepository
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.transport.CompactFrameCodec
import net.sibbl.dwt.transport.RadarFrameWire
import net.sibbl.dwt.transport.awaitWearTask

class RadarCompanionService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rainGate = PriorityWorkGate(1)
    private val overlayGate = PriorityWorkGate(1)
    private lateinit var repository: RadarRepository
    private val recent = LinkedHashSet<RadarFrameReference>()

    override fun onCreate() {
        super.onCreate()
        repository = RadarRepository(this)
    }

    // Separate bounded rain/overlay lanes avoid holding rain behind a slow overlay.
    // All channel jobs are cancelled on service destruction; the watch falls back safely.
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val compact = channel.path == RadarFrameWire.COMPACT_CHANNEL_PATH
        if (!compact && channel.path != RadarFrameWire.CHANNEL_PATH) return
        val client = Wearable.getChannelClient(this)
        scope.launch {
            try {
                withTimeout(25_000L) {
                    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { kotlinx.coroutines.awaitCancellation() } finally { client.close(channel) }
                    }
                    try {
                        val started = RadarPerformance.now()
                        val (reference, foreground) = DataInputStream(client.getInputStream(channel).awaitWearTask { it.close() }).use {
                            if (compact) RadarFrameWire.readCompactRequest(it) else RadarFrameWire.readRequest(it)
                        }
                        synchronized(recent) {
                            recent.remove(reference)
                            recent.add(reference)
                            while (recent.size > 12) recent.remove(recent.first())
                            repository.retainFrames(recent.toSet(), setOf(reference))
                        }
                        val gate = if (reference.layerKey == "PRECIPITATION") rainGate else overlayGate
                        gate.run(reference.toString(), foreground) {
                            val frame = repository.loadFrame(reference, foreground)
                            val encodeStarted = RadarPerformance.now()
                            val png = if (compact && reference.layerKey == "BLITZ_MEASUREMENT") {
                                CompactFrameCodec.encodeMeasurements(frame.bitmap, repository.measurementSource(reference, foreground), frame.bounds)
                            } else if (compact) CompactFrameCodec.encode(frame.bitmap) else ByteArrayOutputStream().use { output ->
                                check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                                output.toByteArray()
                            }
                            val phoneMillis = RadarPerformance.now() - started
                            val bounds = frame.bounds
                            var responseBytes = 0
                            DataOutputStream(client.getOutputStream(channel).awaitWearTask { it.close() }).use {
                                val envelope = RadarFrameWire.Frame(
                                    bounds.southWest.latitude, bounds.southWest.longitude,
                                    bounds.northEast.latitude, bounds.northEast.longitude, phoneMillis, png
                                )
                                responseBytes = if (compact) RadarFrameWire.compactFrameBytes(envelope) else png.size + 48
                                if (compact) RadarFrameWire.writeCompactFrame(it, envelope) else RadarFrameWire.writeFrame(it, envelope)
                            }
                            RadarPerformance.event("phone frame time=${reference.timestampMillis} foreground=$foreground processMs=$phoneMillis layer=${reference.layerKey} encodeMs=${phoneMillis - (encodeStarted - started)} responseBytes=$responseBytes compact=$compact totalMs=${RadarPerformance.now() - started}")
                        }
                    } finally { closer.cancel() }
                }
            } catch (error: Exception) {
                RadarPerformance.event("phone failure type=${error.javaClass.simpleName}")
            } finally {
                client.close(channel)
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        repository.close()
        super.onDestroy()
    }
}

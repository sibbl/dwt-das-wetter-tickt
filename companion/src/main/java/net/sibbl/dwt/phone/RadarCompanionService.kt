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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.sibbl.dwt.data.radar.RadarPerformance
import net.sibbl.dwt.data.radar.RadarRepository
import net.sibbl.dwt.model.RadarFrameReference
import net.sibbl.dwt.transport.RadarFrameWire
import net.sibbl.dwt.transport.awaitWearTask

class RadarCompanionService : WearableListenerService() {
    private lateinit var repository: RadarRepository
    private val recent = LinkedHashSet<RadarFrameReference>()

    override fun onCreate() {
        super.onCreate()
        repository = RadarRepository(this)
    }

    // WearableListenerService dispatches this callback off the main thread.
    // Keep the callback alive until the request is answered; no unmanaged work
    // survives the service lifecycle. Channel failure leaves shared ZIPs reusable.
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != RadarFrameWire.CHANNEL_PATH) return
        val client = Wearable.getChannelClient(this)
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(25_000L) {
                    val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                        try { kotlinx.coroutines.awaitCancellation() } finally { client.close(channel) }
                    }
                    try {
                        val started = RadarPerformance.now()
                        val (reference, foreground) = DataInputStream(client.getInputStream(channel).awaitWearTask { it.close() }).use {
                            RadarFrameWire.readRequest(it)
                        }
                        synchronized(recent) {
                            recent.remove(reference)
                            recent.add(reference)
                            while (recent.size > 12) recent.remove(recent.first())
                            repository.retainFrames(recent.toSet(), setOf(reference))
                        }
                        val frame = repository.loadFrame(reference, foreground)
                        val png = ByteArrayOutputStream().use { output ->
                            check(frame.bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                            output.toByteArray()
                        }
                        val phoneMillis = RadarPerformance.now() - started
                        val bounds = frame.bounds
                        DataOutputStream(client.getOutputStream(channel).awaitWearTask { it.close() }).use {
                            RadarFrameWire.writeFrame(it, RadarFrameWire.Frame(
                                bounds.southWest.latitude, bounds.southWest.longitude,
                                bounds.northEast.latitude, bounds.northEast.longitude, phoneMillis, png
                            ))
                        }
                        RadarPerformance.event("phone frame time=${reference.timestampMillis} foreground=$foreground processMs=$phoneMillis pngBytes=${png.size} totalMs=${RadarPerformance.now() - started}")
                    } finally { closer.cancel() }
                }
            }
        } catch (error: Exception) {
            RadarPerformance.event("phone failure type=${error.javaClass.simpleName}")
        } finally {
            client.close(channel)
        }
    }

    override fun onDestroy() {
        repository.close()
        super.onDestroy()
    }
}

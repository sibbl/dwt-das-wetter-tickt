package net.sibbl.dwt.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import net.sibbl.dwt.model.RadarFrameReference

/** Bounded, versioned, one-frame protocol. Coordinates describe the static raster,
 * never the user's location, camera, or viewport.
 */
object RadarFrameWire {
    const val CAPABILITY = "dwt_rain_frames_v1"
    const val CHANNEL_PATH = "/dwt/radar/v1"
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024
    const val MAX_DIMENSION = 4096
    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    data class Frame(
        val south: Double, val west: Double, val north: Double, val east: Double,
        val phoneMillis: Long, val png: ByteArray
    )

    fun writeRequest(output: DataOutputStream, reference: RadarFrameReference, foreground: Boolean) {
        validate(reference)
        output.writeInt(VERSION)
        output.writeBoolean(foreground)
        output.writeUTF(json.encodeToString(reference))
        output.flush()
    }

    fun readRequest(input: DataInputStream): Pair<RadarFrameReference, Boolean> {
        if (input.readInt() != VERSION) throw IOException("Incompatible radar protocol")
        val foreground = input.readBoolean()
        val reference = json.decodeFromString<RadarFrameReference>(input.readUTF())
        validate(reference)
        return reference to foreground
    }

    fun validate(reference: RadarFrameReference) {
        if (reference.layerKey != "PRECIPITATION" ||
            !Regex("(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+\\.zip").matches(reference.assetPath) ||
            reference.assetPath.split('/').any { it == ".." || it == "." } ||
            reference.timeStepMillis <= 0 || reference.sectionStartMillis > reference.timestampMillis ||
            reference.timestampMillis >= reference.sectionEndMillis || reference.timestampMillis < 0
        ) throw IOException("Invalid rain frame request")
    }

    fun writeFrame(output: DataOutputStream, frame: Frame) {
        validateFrame(frame)
        output.writeInt(VERSION)
        output.writeDouble(frame.south); output.writeDouble(frame.west)
        output.writeDouble(frame.north); output.writeDouble(frame.east)
        output.writeLong(frame.phoneMillis)
        output.writeInt(frame.png.size)
        output.write(frame.png)
        output.flush()
    }

    fun readFrame(input: DataInputStream): Frame {
        if (input.readInt() != VERSION) throw IOException("Incompatible radar protocol")
        val south = input.readDouble(); val west = input.readDouble()
        val north = input.readDouble(); val east = input.readDouble()
        val phoneMillis = input.readLong()
        val size = input.readInt()
        if (size !in 1..MAX_IMAGE_BYTES) throw IOException("Invalid image size")
        val frame = Frame(south, west, north, east, phoneMillis, ByteArray(size).also(input::readFully))
        validateFrame(frame)
        return frame
    }

    private fun validateFrame(frame: Frame) {
        if (frame.png.size !in 1..MAX_IMAGE_BYTES || frame.phoneMillis < 0 ||
            !frame.south.isFinite() || !frame.north.isFinite() ||
            !frame.west.isFinite() || !frame.east.isFinite() ||
            frame.south < -90 || frame.north > 90 || frame.south >= frame.north ||
            frame.west < -180 || frame.east > 180 || frame.west >= frame.east
        ) throw IOException("Invalid frame envelope")
    }
}

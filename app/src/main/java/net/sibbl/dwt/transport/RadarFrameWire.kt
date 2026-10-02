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
    const val COMPACT_CAPABILITY = "dwt_all_frames_v2"
    const val COMPACT_CHANNEL_PATH = "/dwt/radar/v2"
    private const val VERSION = 1
    val layers = listOf("PRECIPITATION", "CLOUD", "BLITZ_MEASUREMENT", "BLITZ_FORECAST")
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

    // Binary v2 request avoids repeating JSON field names; every source-identity field is preserved.
    fun writeCompactRequest(output: DataOutputStream, reference: RadarFrameReference, foreground: Boolean) {
        validateCompact(reference)
        output.writeByte(2); output.writeBoolean(foreground)
        output.writeByte(layers.indexOf(reference.layerKey)); output.writeUTF(reference.assetPath)
        output.writeLong(reference.timestampMillis); output.writeLong(reference.timeStepMillis)
        output.writeLong(reference.sectionStartMillis); output.writeLong(reference.sectionEndMillis)
        output.flush()
    }

    fun readCompactRequest(input: DataInputStream): Pair<RadarFrameReference, Boolean> {
        if (input.readUnsignedByte() != 2) throw IOException("Incompatible radar protocol")
        val foreground = input.readBoolean()
        val layer = layers.getOrNull(input.readUnsignedByte()) ?: throw IOException("Invalid layer")
        val path = input.readUTF()
        val reference = RadarFrameReference(input.readLong(), path, input.readLong(), input.readLong(), input.readLong(), layer)
        validateCompact(reference)
        return reference to foreground
    }

    fun validateCompact(reference: RadarFrameReference) {
        if (reference.layerKey !in layers || reference.assetPath.length > 1024) throw IOException("Invalid layer or path")
        validate(reference.copy(layerKey = "PRECIPITATION"))
    }

    // Omit only the exact protocol-default bounds, never round coordinates.
    private fun defaultBounds(frame: Frame) = frame.south == 43.75 && frame.west == 0.0 && frame.north == 57.0 && frame.east == 17.0
    fun compactFrameBytes(frame: Frame) = 14 + (if (defaultBounds(frame)) 0 else 32) + frame.png.size

    fun writeCompactFrame(output: DataOutputStream, frame: Frame) {
        validateFrame(frame)
        output.writeByte(2)
        output.writeBoolean(!defaultBounds(frame))
        if (!defaultBounds(frame)) {
            output.writeDouble(frame.south); output.writeDouble(frame.west)
            output.writeDouble(frame.north); output.writeDouble(frame.east)
        }
        output.writeLong(frame.phoneMillis); output.writeInt(frame.png.size); output.write(frame.png)
        output.flush()
    }

    fun readCompactFrame(input: DataInputStream): Frame {
        if (input.readUnsignedByte() != 2) throw IOException("Incompatible radar protocol")
        val flag = input.readUnsignedByte()
        if (flag !in 0..1) throw IOException("Invalid bounds flag")
        val south = if (flag == 1) input.readDouble() else 43.75
        val west = if (flag == 1) input.readDouble() else 0.0
        val north = if (flag == 1) input.readDouble() else 57.0
        val east = if (flag == 1) input.readDouble() else 17.0
        val millis = input.readLong()
        val size = input.readInt()
        if (size !in 1..MAX_IMAGE_BYTES) throw IOException("Invalid image size")
        return Frame(south, west, north, east, millis, ByteArray(size).also(input::readFully)).also(::validateFrame)
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

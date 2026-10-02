package net.sibbl.dwt.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import net.sibbl.dwt.model.RadarFrameReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RadarFrameWireTest {
    private val reference = RadarFrameReference(1500, "radar/PRECIPITATION.20260928.zip", 500, 1000, 2000)
    @Test fun `rain identity and priority survive protocol roundtrip`() {
        val bytes = ByteArrayOutputStream()
        RadarFrameWire.writeRequest(DataOutputStream(bytes), reference, true)
        val (actual, priority) = RadarFrameWire.readRequest(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(reference, actual)
        assertTrue(priority)
    }

    @Test fun `frame is transferred losslessly with bounds and phone duration`() {
        val frame = RadarFrameWire.Frame(43.75, 0.0, 57.0, 17.0, 123, byteArrayOf(1, 2, 3))
        val bytes = ByteArrayOutputStream()
        RadarFrameWire.writeFrame(DataOutputStream(bytes), frame)
        val actual = RadarFrameWire.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray())))
        assertEquals(frame.south, actual.south, 0.0)
        assertEquals(frame.east, actual.east, 0.0)
        assertEquals(123L, actual.phoneMillis)
        assertArrayEquals(frame.png, actual.png)
    }

    @Test fun `invalid version traversal unsupported layer and invalid time are rejected`() {
        for (bad in listOf(
            reference.copy(assetPath = "../secret.zip"), reference.copy(assetPath = "https://other/file.zip"),
            reference.copy(assetPath = "file.webp"), reference.copy(layerKey = "CLOUD"),
            reference.copy(timeStepMillis = 0), reference.copy(timestampMillis = 2000)
        )) rejected { RadarFrameWire.validate(bad) }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).writeInt(999)
        rejected { RadarFrameWire.readRequest(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
    }

    @Test fun `oversized or truncated response is rejected before bitmap processing`() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply {
            writeInt(1); writeDouble(43.75); writeDouble(0.0); writeDouble(57.0); writeDouble(17.0)
            writeLong(0); writeInt(RadarFrameWire.MAX_IMAGE_BYTES + 1)
        }
        rejected { RadarFrameWire.readFrame(DataInputStream(ByteArrayInputStream(bytes.toByteArray()))) }
        val valid = ByteArrayOutputStream()
        RadarFrameWire.writeFrame(DataOutputStream(valid), RadarFrameWire.Frame(43.75, 0.0, 57.0, 17.0, 0, byteArrayOf(1, 2)))
        rejected { RadarFrameWire.readFrame(DataInputStream(ByteArrayInputStream(valid.toByteArray().dropLast(1).toByteArray()))) }
        rejected { RadarFrameWire.writeFrame(DataOutputStream(ByteArrayOutputStream()), RadarFrameWire.Frame(Double.NaN, 0.0, 57.0, 17.0, 0, byteArrayOf(1))) }
    }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid payload accepted") } catch (_: IOException) { }
    }
}

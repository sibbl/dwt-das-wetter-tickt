package de.sibbl.dwdradar.tile

import com.google.common.truth.Truth.assertThat
import de.sibbl.dwdradar.model.RadarFrameReference
import org.junit.Test

class RadarTileVersionCodecTest {
    @Test
    fun encodeAndDecode_roundTripFrameReference() {
        val reference = RadarFrameReference(
            timestampMillis = 1_700_000_000_000L,
            assetPath = "radar/rad_1699999999999.zip",
            timeStepMillis = 300_000L,
            sectionStartMillis = 1_699_999_700_000L,
            sectionEndMillis = 1_700_000_300_000L
        )

        val decoded = RadarTileVersionCodec.decode(RadarTileVersionCodec.encode(reference))

        assertThat(decoded).isEqualTo(reference)
    }

    @Test
    fun decode_rejectsInvalidVersions() {
        assertThat(RadarTileVersionCodec.decode("broken")).isNull()
    }
}

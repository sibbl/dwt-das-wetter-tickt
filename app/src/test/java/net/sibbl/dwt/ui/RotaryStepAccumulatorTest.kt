package net.sibbl.dwt.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RotaryStepAccumulatorTest {
    @Test
    fun consume_accumulatesSubThresholdTicksBeforeAdvancing() {
        val accumulator = RotaryStepAccumulator(ticksPerStep = 0.15f)

        assertThat(accumulator.consume(0.05f)).isEqualTo(0)
        assertThat(accumulator.consume(0.05f)).isEqualTo(0)
        assertThat(accumulator.consume(0.05f)).isEqualTo(1)
    }

    @Test
    fun consume_handlesNegativeAndMultiStepDeltas() {
        val accumulator = RotaryStepAccumulator(ticksPerStep = 0.15f)

        assertThat(accumulator.consume(-0.31f)).isEqualTo(-2)
        assertThat(accumulator.consume(0.16f)).isEqualTo(1)
    }

    @Test
    fun reset_clearsAccumulatedTicks() {
        val accumulator = RotaryStepAccumulator(ticksPerStep = 0.15f)

        assertThat(accumulator.consume(0.10f)).isEqualTo(0)
        accumulator.reset()

        assertThat(accumulator.consume(0.10f)).isEqualTo(0)
        assertThat(accumulator.consume(0.05f)).isEqualTo(1)
    }
}

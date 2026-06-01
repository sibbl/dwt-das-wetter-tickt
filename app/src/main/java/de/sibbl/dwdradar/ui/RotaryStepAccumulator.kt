package de.sibbl.dwdradar.ui

internal class RotaryStepAccumulator(
    private val ticksPerStep: Float
) {
    private var accumulatedTicks = 0f

    init {
        require(ticksPerStep > 0f) { "ticksPerStep must be > 0" }
    }

    fun consume(deltaTicks: Float): Int {
        accumulatedTicks += deltaTicks
        val epsilon = 1e-5f
        val steps = if (accumulatedTicks >= 0f) {
            ((accumulatedTicks + epsilon) / ticksPerStep).toInt()
        } else {
            ((accumulatedTicks - epsilon) / ticksPerStep).toInt()
        }
        if (steps != 0) {
            accumulatedTicks -= steps * ticksPerStep
        }
        return steps
    }

    fun reset() {
        accumulatedTicks = 0f
    }
}

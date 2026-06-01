package net.sibbl.dwdradar.model

enum class ZoomPreset(
    val scaleMultiplier: Float,
    val visibleWidthKilometers: Int
) {
    NEAR(8.2f, 150),
    MID_NEAR(6.15f, 200),
    MID(4.92f, 250),
    MID_FAR(3.51f, 350),
    FAR(2.46f, 500),
    OVERVIEW(1.64f, 750);

    fun next(): ZoomPreset {
        val currentIndex = ORDER.indexOf(this)
        return ORDER[(currentIndex + 1) % ORDER.size]
    }

    fun zoomedIn(): ZoomPreset {
        val currentIndex = ORDER.indexOf(this)
        return ORDER[(currentIndex + 1).coerceAtMost(ORDER.lastIndex)]
    }

    fun zoomedOut(): ZoomPreset {
        val currentIndex = ORDER.indexOf(this)
        return ORDER[(currentIndex - 1).coerceAtLeast(0)]
    }

    companion object {
        private val ORDER = listOf(OVERVIEW, FAR, MID_FAR, MID, MID_NEAR, NEAR)
    }
}

package net.sibbl.dwt.model

enum class ZoomPreset(
    val visibleWidthKilometers: Int
) {
    NEAR(150),
    MID_NEAR(200),
    MID(250),
    MID_FAR(350),
    FAR(500),
    OVERVIEW(750);

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

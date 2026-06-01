package de.sibbl.dwdradar.model

enum class ZoomPreset(
    val scaleMultiplier: Float
) {
    NEAR(7.25f),
    MID(4.1f),
    FAR(1.75f);

    fun next(): ZoomPreset {
        return when (this) {
            FAR -> MID
            MID -> NEAR
            NEAR -> FAR
        }
    }
}

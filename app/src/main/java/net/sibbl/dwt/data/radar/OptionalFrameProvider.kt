package net.sibbl.dwt.data.radar

import net.sibbl.dwt.model.RadarFrameReference

/** Null selects the standalone pipeline; cancellation must never trigger fallback. */
fun interface OptionalFrameProvider {
    suspend fun load(reference: RadarFrameReference, foreground: Boolean): RadarBitmapFrame?
}

internal suspend fun <T> tryOptionalFrame(load: suspend () -> T?): T? = try {
    load()
} catch (cancelled: kotlinx.coroutines.CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}

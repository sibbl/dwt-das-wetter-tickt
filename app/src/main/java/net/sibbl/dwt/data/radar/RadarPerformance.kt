package net.sibbl.dwt.data.radar

import android.os.SystemClock
import android.util.Log

/** Local opt-in diagnostics: no positions, credentials, or payloads are logged. */
internal object RadarPerformance {
    private const val TAG = "DwtPerf"
    inline fun <T> measure(stage: String, reference: net.sibbl.dwt.model.RadarFrameReference, work: () -> T): T {
        val started = now()
        return try { work() } finally {
            event("stage=$stage layer=${reference.layerKey} time=${reference.timestampMillis} durationMs=${now() - started}")
        }
    }
    fun now(): Long = SystemClock.elapsedRealtime()
    fun event(message: String) {
        if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, message)
    }
}

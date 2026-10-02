package net.sibbl.dwt.ui

import kotlin.math.abs
import kotlin.math.hypot

/** Only gestures starting on the time pill enter this classifier. */
internal class TimePillGesture(private val touchSlop: Float, private val refreshDistance: Float) {
    enum class Action { NOW, REFRESH, NONE }
    private var moved = false
    private var canceled = false
    private var x = 0f
    private var y = 0f

    fun update(offsetX: Float, offsetY: Float): Float {
        x = offsetX
        y = offsetY
        moved = moved || hypot(x, y) >= touchSlop
        return if (!canceled && y > abs(x)) (y / refreshDistance).coerceIn(0f, 1f) else 0f
    }

    fun cancel() { canceled = true }
    fun finish(): Action = when {
        canceled -> Action.NONE
        moved && y >= refreshDistance && y > abs(x) -> Action.REFRESH
        moved -> Action.NONE
        else -> Action.NOW
    }
}

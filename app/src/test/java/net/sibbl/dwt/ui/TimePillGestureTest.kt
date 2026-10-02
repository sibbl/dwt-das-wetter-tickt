package net.sibbl.dwt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TimePillGestureTest {
    @Test fun `tap and slight finger movement only select now`() {
        val gesture = TimePillGesture(8f, 32f)
        gesture.update(2f, 3f)
        assertEquals(TimePillGesture.Action.NOW, gesture.finish())
    }
    @Test fun `downward pull refreshes only after threshold and release`() {
        val gesture = TimePillGesture(8f, 32f)
        assertEquals(0.5f, gesture.update(0f, 16f), 0.001f)
        assertEquals(TimePillGesture.Action.NONE, gesture.finish())
        assertEquals(1f, gesture.update(2f, 35f), 0.001f)
        assertEquals(TimePillGesture.Action.REFRESH, gesture.finish())
    }
    @Test fun `upward sideways and diagonal gestures neither refresh nor select now`() {
        for ((x,y) in listOf(0f to -40f, 40f to 0f, 40f to 35f)) {
            val gesture = TimePillGesture(8f, 32f)
            assertEquals(0f, gesture.update(x,y), 0f)
            assertEquals(TimePillGesture.Action.NONE, gesture.finish())
        }
    }
    @Test fun `returning below threshold and interrupted gestures do not refresh`() {
        val gesture = TimePillGesture(8f, 32f)
        gesture.update(0f, 40f)
        gesture.update(0f, 10f)
        assertEquals(TimePillGesture.Action.NONE, gesture.finish())
        gesture.update(0f, 40f)
        gesture.cancel()
        assertEquals(TimePillGesture.Action.NONE, gesture.finish())
    }
}

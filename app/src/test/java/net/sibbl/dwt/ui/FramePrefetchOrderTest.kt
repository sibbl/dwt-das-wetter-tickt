package net.sibbl.dwt.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePrefetchOrderTest {
    @Test
    fun `selected layer is ordered from center outwards`() {
        val result = orderedLayerItemsForPrefetch(
            items = (0..8).toList(),
            layerStartIndex = 2,
            layerEndExclusive = 8,
            centerIndex = 5
        )

        assertEquals(listOf(5, 6, 4, 7, 3, 2), result)
    }

    @Test
    fun `previous layer is ordered from transition backwards`() {
        val result = orderedLayerItemsForPrefetch(
            items = (0..8).toList(),
            layerStartIndex = 0,
            layerEndExclusive = 4,
            centerIndex = 5
        )

        assertEquals(listOf(3, 2, 1, 0), result)
    }

    @Test
    fun `future layer is ordered from transition forwards`() {
        val result = orderedLayerItemsForPrefetch(
            items = (0..8).toList(),
            layerStartIndex = 6,
            layerEndExclusive = 9,
            centerIndex = 5
        )

        assertEquals(listOf(6, 7, 8), result)
    }
}

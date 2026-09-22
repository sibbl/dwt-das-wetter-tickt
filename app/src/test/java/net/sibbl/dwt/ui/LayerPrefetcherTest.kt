package net.sibbl.dwt.ui

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LayerPrefetcherTest {
    @Test
    fun `all 24 current segments precede the previous and next rings`() = runTest {
        val ready = mutableSetOf<Int>()
        val calls = mutableListOf<Int>()
        val plan = buildLayerPrefetchPlan((0 until 120).toList(), rings(5), 60)
        val prefetcher = LayerPrefetcher<Int>(this, ready::contains) { item ->
            if (item !in 48..71) assertTrue(ready.containsAll((48..71).toList()))
            calls += item
            ready += item
        }

        prefetcher.schedule(plan)
        advanceUntilIdle()

        assertEquals(60, calls.first())
        assertEquals(59, calls[1])
        assertEquals((48..71).toSet(), calls.take(24).toSet())
        assertEquals((24..47).reversed().toList(), calls.subList(24, 48))
        assertEquals((72..95).toList(), calls.takeLast(24))
        assertEquals((24..95).toSet(), ready)
    }

    @Test
    fun `selected segment and enabled overlays are part of the readiness barrier`() = runTest {
        val ready = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        val decoded = CompletableDeferred<Unit>()
        val prefetcher = LayerPrefetcher<String>(this, ready::contains) { item ->
            calls += item
            if (item == "selected lightning") decoded.await()
            ready += item
        }
        prefetcher.schedule(LayerPrefetchPlan(
            listOf("selected rain", "selected cloud", "selected lightning", "rest of ring"),
            listOf("previous", "next")
        ))
        runCurrent()
        assertEquals(listOf("selected rain", "selected cloud", "selected lightning"), calls)
        assertFalse("previous" in ready)

        decoded.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("selected rain", "selected cloud", "selected lightning", "rest of ring", "previous", "next"), calls)
    }

    @Test
    fun `failed current frame is retried before starting either adjacent ring`() = runTest {
        val ready = mutableSetOf<Int>()
        val calls = mutableListOf<Int>()
        var fail = true
        val prefetcher = LayerPrefetcher<Int>(this, ready::contains) { item ->
            calls += item
            if (item == 1 && fail) {
                fail = false
                throw IOException("offline")
            }
            if (item == 0 || item == 4) assertTrue(ready.containsAll(listOf(1, 2, 3)))
            ready += item
        }
        prefetcher.schedule(LayerPrefetchPlan(listOf(2, 1, 3), listOf(0, 4)))
        advanceUntilIdle()
        assertEquals(listOf(2, 1, 3, 1, 0, 4), calls)
    }

    @Test
    fun `permanent current failure never opens adjacent stage and can retry later`() = runTest {
        val ready = mutableSetOf<Int>()
        val calls = mutableListOf<Int>()
        var offline = true
        val prefetcher = LayerPrefetcher<Int>(this, ready::contains) { item ->
            calls += item
            if (item == 1 && offline) throw IOException("offline")
            ready += item
        }
        val plan = LayerPrefetchPlan(listOf(1, 2), listOf(0, 3))
        prefetcher.schedule(plan)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1, 1), calls)
        assertEquals(setOf(2), ready)

        offline = false
        prefetcher.schedule(plan)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 1, 1, 1, 0, 3), calls)
    }

    @Test
    fun `scrubbing within the ring does not restart its in flight load`() = runTest {
        val ready = mutableSetOf<Int>()
        val calls = mutableListOf<Int>()
        val decoded = CompletableDeferred<Unit>()
        val prefetcher = LayerPrefetcher<Int>(this, ready::contains) { item ->
            calls += item
            if (item == 24) decoded.await()
            ready += item
        }
        val items = (0 until 72).toList()
        prefetcher.schedule(buildLayerPrefetchPlan(items, rings(3), 24))
        runCurrent()
        for (center in 25..47) {
            prefetcher.schedule(buildLayerPrefetchPlan(items, rings(3), center))
            runCurrent()
        }
        assertEquals(listOf(24), calls)
        decoded.complete(Unit)
        advanceUntilIdle()
        assertEquals(72, calls.size)
        prefetcher.schedule(buildLayerPrefetchPlan(items, rings(3), 40))
        advanceUntilIdle()
        assertEquals(72, calls.size)
    }

    @Test
    fun `crossing a ring cancels old work and prioritizes the new current ring`() = runTest {
        val ready = mutableSetOf<Int>()
        val calls = mutableListOf<Int>()
        var cancelled = false
        val prefetcher = LayerPrefetcher<Int>(this, ready::contains) { item ->
            calls += item
            if (item == 1 && !cancelled) {
                try { awaitCancellation() } finally { cancelled = true }
            }
            ready += item
        }
        prefetcher.schedule(LayerPrefetchPlan(listOf(1, 2), listOf(0, 3)))
        runCurrent()
        prefetcher.schedule(LayerPrefetchPlan(listOf(3, 4), listOf(2, 5)))
        advanceUntilIdle()
        assertTrue(cancelled)
        assertEquals(listOf(1, 3, 4, 2, 5), calls)
    }

    @Test
    fun `new overlay requirements restart the current barrier during adjacent prefetch`() = runTest {
        val ready = mutableSetOf<String>()
        val calls = mutableListOf<String>()
        var firstAdjacent = true
        val prefetcher = LayerPrefetcher<String>(this, ready::contains) { item ->
            calls += item
            if (item == "previous" && firstAdjacent) {
                firstAdjacent = false
                awaitCancellation()
            }
            ready += item
        }
        prefetcher.schedule(LayerPrefetchPlan(listOf("rain"), listOf("previous", "next")))
        advanceUntilIdle()
        prefetcher.schedule(LayerPrefetchPlan(listOf("rain", "cloud"), listOf("previous", "next")))
        advanceUntilIdle()
        assertEquals(listOf("rain", "previous", "cloud", "previous", "next"), calls)
    }

    @Test
    fun `already decoded items shared across rings are not loaded again`() = runTest {
        val ready = mutableSetOf("selected")
        val calls = mutableListOf<String>()
        val prefetcher = LayerPrefetcher<String>(this, ready::contains) {
            calls += it
            ready += it
        }
        prefetcher.schedule(LayerPrefetchPlan(
            listOf("selected", "cloud", "cloud"), listOf("cloud", "previous", "next")
        ))
        advanceUntilIdle()
        assertEquals(listOf("cloud", "previous", "next"), calls)
    }

    @Test
    fun `edge rings and uneven partitions only retain available neighbors`() {
        val items = (0 until 10).toList()
        val layers = listOf(0..2, 3..6, 7..9)
        assertEquals(LayerPrefetchPlan(listOf(0, 1, 2), listOf(3, 4, 5, 6)), buildLayerPrefetchPlan(items, layers, 0))
        assertEquals(LayerPrefetchPlan(listOf(9, 8, 7), listOf(6, 5, 4, 3)), buildLayerPrefetchPlan(items, layers, 9))
        assertEquals((0..9).toSet(), buildLayerPrefetchPlan(items, layers, 5).retainedItems)
        assertEquals(setOf(0), buildLayerPrefetchPlan(listOf(0), listOf(0..0), 0).retainedItems)
        assertTrue(buildLayerPrefetchPlan(items, emptyList(), 0).retainedItems.isEmpty())
    }

    private fun rings(count: Int) = List(count) { it * 24 until (it + 1) * 24 }
}

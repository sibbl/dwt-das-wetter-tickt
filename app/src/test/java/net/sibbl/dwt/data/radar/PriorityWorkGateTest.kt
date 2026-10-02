package net.sibbl.dwt.data.radar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PriorityWorkGateTest {
    @Test fun `selected work precedes queued background work`() = runTest {
        val gate = PriorityWorkGate(1)
        val unblock = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { gate.run("busy", false) { unblock.await() } }
        runCurrent()
        launch { gate.run("background", false) { order += "background" } }
        launch { gate.run("selected", true) { order += "selected" } }
        runCurrent()
        unblock.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("selected", "background"), order)
    }

    @Test fun `queued shared download is promoted including before worker starts`() = runTest {
        val gate = PriorityWorkGate(1)
        val unblock = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        launch { gate.run("busy", false) { unblock.await() } }
        runCurrent()
        launch { gate.run("background", false) { order += "background" } }
        launch { gate.run("selected", false) { order += "selected" } }
        gate.promote("selected")
        runCurrent()
        unblock.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("selected", "background"), order)
    }

    @Test fun `cancelled queued or dispatched requests do not leak permits`() = runTest {
        val gate = PriorityWorkGate(1)
        val unblock = CompletableDeferred<Unit>()
        val first = launch { gate.run("first", false) { unblock.await() } }
        runCurrent()
        val cancelled = launch { gate.run("cancelled", true) { error("Must not run") } }
        runCurrent()
        cancelled.cancel()
        first.cancel()
        runCurrent()
        assertEquals(42, gate.run("next", true) { 42 })
    }

    @Test fun `failure releases permit and configured concurrency is bounded`() = runTest {
        val gate = PriorityWorkGate(2)
        val unblock = CompletableDeferred<Unit>()
        var active = 0
        var maximum = 0
        val jobs = List(4) { index -> launch {
            try {
                gate.run(index.toString(), false) {
                    active++; maximum = maxOf(maximum, active)
                    try { unblock.await(); if (index == 0) error("Failure") } finally { active-- }
                }
            } catch (_: IllegalStateException) { }
        } }
        runCurrent()
        assertEquals(2, maximum)
        unblock.complete(Unit)
        jobs.forEach { it.join() }
        assertEquals(2, maximum)
        assertEquals(0, active)
        assertEquals(7, gate.run("after", true) { 7 })
    }
}

package net.sibbl.dwt.data.radar

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharedAssetLoadsTest {
    @Test fun `scroll cancellation preserves shared download for another caller`() = runTest {
        val shared = SharedAssetLoads<Int>(kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])))
        val completed = CompletableDeferred<Unit>()
        var downloads = 0
        var ownerCancelled = false
        val first = async {
            shared.await("rain.zip") {
                downloads++
                try { completed.await(); 42 } finally { ownerCancelled = !completed.isCompleted }
            }
        }
        runCurrent()
        first.cancel()
        runCurrent()
        assertFalse(ownerCancelled)
        val second = async { shared.await("rain.zip") { error("Duplicate download") } }
        runCurrent()
        completed.complete(Unit)
        assertEquals(42, second.await())
        assertEquals(1, downloads)
    }

    @Test fun `failed download can retry while different assets run independently`() = runTest {
        val shared = SharedAssetLoads<Int>(kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.SupervisorJob(backgroundScope.coroutineContext[kotlinx.coroutines.Job])))
        try { shared.await("failed") { throw IOException("Offline") } } catch (_: IOException) { }
        assertEquals(2, shared.await("failed") { 2 })
        val unblock = CompletableDeferred<Unit>()
        val first = async { shared.await("first") { unblock.await(); 1 } }
        runCurrent()
        assertEquals(3, shared.await("other") { 3 })
        unblock.complete(Unit)
        assertEquals(1, first.await())
    }
}

package net.sibbl.dwt.data.radar

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OptionalFrameProviderTest {
    @Test fun `absent companion and processing failure select local fallback`() = runTest {
        assertNull(tryOptionalFrame<Int> { null })
        assertNull(tryOptionalFrame<Int> { throw IOException("Companion disconnected") })
        assertEquals(42, tryOptionalFrame { 42 })
    }

    @Test(expected = CancellationException::class)
    fun `scroll cancellation does not start local fallback`() = runTest {
        tryOptionalFrame<Int> { throw CancellationException("New selection") }
    }
}

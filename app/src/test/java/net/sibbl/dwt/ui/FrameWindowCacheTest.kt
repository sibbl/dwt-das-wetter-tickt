package net.sibbl.dwt.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameWindowCacheTest {
    @Test
    fun `adjacent loading and scrubbing retain every current frame`() {
        val cache = FrameWindowCache<Int, String>()
        cache.retain((0 until 72).toSet())
        (24..47).forEach { cache[it] = "decoded $it" }
        // Read a different selected frame between every background insertion.
        (0..23).forEach { cache[it] = "decoded $it"; cache[24 + it] }
        (48..71).forEach { cache[it] = "decoded $it"; cache[it - 24] }
        (24..47).forEach { assertEquals("decoded $it", cache[it]) }
        assertEquals(72, cache.keys.size)
    }

    @Test
    fun `crossing a ring drops distant frames and rejects late stale loads`() {
        val cache = FrameWindowCache<Int, String>()
        cache.retain((0 until 72).toSet())
        (0 until 72).forEach { cache[it] = "decoded $it" }
        cache.retain((24 until 96).toSet())
        cache[0] = "late completion"
        (72 until 96).forEach { cache[it] = "decoded $it" }
        assertNull(cache[0])
        assertEquals((24 until 96).toSet(), cache.keys)
    }

    @Test
    fun `updated asset identity invalidates frames with the same timestamp`() {
        val cache = FrameWindowCache<Pair<Long, String>, String>()
        val old = 123L to "old.zip"
        val fresh = 123L to "new.zip"
        cache.retain(setOf(old))
        cache[old] = "old bitmap"
        cache.retain(setOf(fresh))
        cache[old] = "stale completion"
        assertNull(cache[old])
        assertNull(cache[fresh])
        cache[fresh] = "new bitmap"
        assertEquals("new bitmap", cache[fresh])
    }
}

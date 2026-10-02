package net.sibbl.dwt.data.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationFrameCacheTest {
    @Test fun `byte budget protects full current ring and evicts least recently used neighbors`() {
        val cache = NavigationFrameCache<Int, String>(12) { it.length.toLong() }
        cache.retain((0..5).toSet(), setOf(2, 3))
        cache[2] = "aaaa"; cache[3] = "bbbb"; cache[0] = "cccc"
        cache[1] = "dddd"
        assertNull(cache[0])
        assertEquals(setOf(1, 2, 3), cache.keys)
        assertEquals(12L, cache.bytes)
        cache[2]; cache[1]
        cache[4] = "eeee"
        assertNull(cache[1])
        assertEquals("aaaa", cache[2])
        assertEquals("bbbb", cache[3])
    }

    @Test fun `preferred frames still obey hard byte budget`() {
        val cache = NavigationFrameCache<Int, String>(5) { it.length.toLong() }
        cache.retain(setOf(1, 2, 3), setOf(1, 2))
        cache[1] = "1111"; cache[2] = "2222"; cache[3] = "3333"
        assertEquals(setOf(2), cache.keys)
        assertEquals(4L, cache.bytes)
        cache.retain(setOf(2, 3), setOf(3))
        cache[1] = "late"
        cache[3] = "3333"
        assertEquals(setOf(3), cache.keys)
        assertEquals(4L, cache.bytes)
    }

    @Test fun `many preferred frames and oversized values never exceed byte cap`() {
        val cache = NavigationFrameCache<Int, String>(32) { it.length.toLong() }
        cache.retain((0..100).toSet(), (0..100).toSet())
        for (i in 0..99) {
            cache[i] = "x".repeat(7)
            org.junit.Assert.assertTrue(cache.bytes <= 32)
        }
        cache[100] = "x".repeat(64)
        org.junit.Assert.assertTrue(cache.bytes <= 32)
        assertNull(cache[100])
    }

    @Test fun `asset identities with same time cannot reuse stale frames`() {
        val cache = NavigationFrameCache<String, String>(100) { it.length.toLong() }
        cache.retain(setOf("old#123"), setOf("old#123"))
        cache["old#123"] = "old"
        cache.retain(setOf("new#123"), setOf("new#123"))
        cache["old#123"] = "late old"
        assertNull(cache["old#123"])
        cache["new#123"] = "new"
        assertEquals("new", cache["new#123"])
    }
}

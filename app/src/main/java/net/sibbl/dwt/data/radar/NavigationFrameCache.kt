package net.sibbl.dwt.data.radar

/** Hard bitmap byte budget shared by every layer. Selected frames are preferred;
 * even preferred entries cannot exceed the cap. Never recycle UI-owned bitmaps.
 */
internal class NavigationFrameCache<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var retained = emptySet<K>()
    private var protected = emptySet<K>()
    val keys: Set<K> @Synchronized get() = entries.keys.toSet()
    val bytes: Long @Synchronized get() = entries.values.sumOf(sizeOf)

    @Synchronized fun retain(keys: Set<K>, protectedKeys: Set<K>) {
        retained = keys
        protected = protectedKeys intersect keys
        entries.keys.retainAll(keys)
        trim()
    }

    @Synchronized operator fun get(key: K): V? = entries[key]

    @Synchronized operator fun set(key: K, value: V) {
        if (key !in retained) return
        entries[key] = value
        trim()
    }

    @Synchronized fun remove(key: K) { entries.remove(key) }

    private fun trim() {
        var bytes = entries.values.sumOf(sizeOf)
        val iterator = entries.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in protected) continue
            bytes -= sizeOf(entry.value)
            iterator.remove()
        }
        // A small device must not retain an unbounded protected ring.
        val preferred = entries.entries.iterator()
        while (bytes > maxBytes && preferred.hasNext()) {
            bytes -= sizeOf(preferred.next().value)
            preferred.remove()
        }
    }
}

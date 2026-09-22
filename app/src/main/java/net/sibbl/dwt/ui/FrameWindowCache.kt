package net.sibbl.dwt.ui

/** Keeps the complete navigation window; stale loads cannot evict its frames. */
internal class FrameWindowCache<K, V> {
    private var retainedKeys = emptySet<K>()
    private val entries = mutableMapOf<K, V>()
    val keys: Set<K> get() = entries.keys.toSet()

    fun retain(keys: Set<K>) {
        retainedKeys = keys
        entries.keys.retainAll(keys)
    }

    operator fun get(key: K): V? = entries[key]

    operator fun set(key: K, value: V) {
        if (key in retainedKeys) entries[key] = value
    }
}

package net.sibbl.dwt.data.radar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/** The owner's lifetime, rather than a scroll request, owns each download. */
internal class SharedAssetLoads<T>(private val scope: CoroutineScope) {
    private val lock = Any()
    private val pending = mutableMapOf<String, Deferred<T>>()

    suspend fun await(key: String, load: suspend () -> T): T {
        val task = synchronized(lock) {
            pending[key] ?: scope.async(start = CoroutineStart.LAZY) { load() }.also { created ->
                pending[key] = created
                created.invokeOnCompletion {
                    synchronized(lock) {
                        if (pending[key] === created) pending.remove(key)
                    }
                }
                created.start()
            }
        }
        return task.await()
    }
}

package net.sibbl.dwt.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

internal data class LayerPrefetchPlan<T>(
    val currentLayer: List<T>,
    val adjacentLayers: List<T>
) {
    val retainedItems: Set<T> get() = (currentLayer + adjacentLayers).toSet()
}

internal fun <T> buildLayerPrefetchPlan(
    items: List<T>,
    layers: List<IntRange>,
    centerIndex: Int
): LayerPrefetchPlan<T> {
    val selectedLayerIndex = layers.indexOfFirst { centerIndex in it }
    if (selectedLayerIndex == -1) return LayerPrefetchPlan(emptyList(), emptyList())

    fun orderedItems(range: IntRange): List<T> = orderedLayerItemsForPrefetch(
        items, range.first, range.last + 1, centerIndex
    )

    return LayerPrefetchPlan(
        currentLayer = orderedItems(layers[selectedLayerIndex]),
        adjacentLayers = listOfNotNull(
            layers.getOrNull(selectedLayerIndex - 1),
            layers.getOrNull(selectedLayerIndex + 1)
        ).flatMap(::orderedItems)
    )
}

/** One queue per visual ring, unchanged by scrubbing within that ring. */
internal class LayerPrefetcher<T>(
    private val scope: CoroutineScope,
    private val isReady: (T) -> Boolean,
    private val load: suspend (T) -> Unit
) {
    private var job: Job? = null
    private var currentItems = emptySet<T>()
    private var adjacentItems = emptySet<T>()

    fun schedule(plan: LayerPrefetchPlan<T>) {
        val current = plan.currentLayer.toSet()
        val adjacent = plan.adjacentLayers.toSet() - current
        if (currentItems == current && adjacentItems == adjacent &&
            (job?.isActive == true || plan.retainedItems.all(isReady))
        ) return

        currentItems = current
        adjacentItems = adjacent
        val previousJob = job
        previousJob?.cancel()
        job = scope.launch {
            // Wait for cancelled IO to finish before starting the new ring.
            previousJob?.join()
            if (!prepare(plan.currentLayer)) return@launch
            // No adjacent asset or decode starts until EVERY current frame is ready.
            prepare(plan.adjacentLayers, background = true)
        }
    }

    private suspend fun prepare(items: List<T>, background: Boolean = false): Boolean {
        repeat(3) { attempt ->
            for (item in items.distinct()) {
                currentCoroutineContext().ensureActive()
                if (isReady(item)) continue
                if (background) delay(80L)
                try {
                    load(item)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry missing frames without preventing the rest of this ring loading.
                }
            }
            if (items.all(isReady)) return true
            if (attempt < 2) delay(450L * (attempt + 1))
        }
        return false
    }
}

package net.sibbl.dwt.ui

internal fun <T> orderedLayerItemsForPrefetch(
    items: List<T>,
    layerStartIndex: Int,
    layerEndExclusive: Int,
    centerIndex: Int
): List<T> {
    val startIndex = layerStartIndex.coerceIn(0, items.size)
    val endExclusive = layerEndExclusive.coerceIn(startIndex, items.size)
    if (startIndex == endExclusive) {
        return emptyList()
    }

    val indices = if (centerIndex in startIndex until endExclusive) {
        buildList {
            add(centerIndex)
            var distance = 1
            while (centerIndex - distance >= startIndex || centerIndex + distance < endExclusive) {
                val futureIndex = centerIndex + distance
                if (futureIndex < endExclusive) {
                    add(futureIndex)
                }
                val pastIndex = centerIndex - distance
                if (pastIndex >= startIndex) {
                    add(pastIndex)
                }
                distance++
            }
        }
    } else if (endExclusive <= centerIndex) {
        (endExclusive - 1 downTo startIndex).toList()
    } else {
        (startIndex until endExclusive).toList()
    }

    return indices.map(items::get)
}

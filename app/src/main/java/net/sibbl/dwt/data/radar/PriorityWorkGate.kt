package net.sibbl.dwt.data.radar

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Bounded work, with selected-frame requests ahead of queued background work. */
internal class PriorityWorkGate(private val permits: Int) {
    private data class Ticket(val key: String, var foreground: Boolean, val ready: CompletableDeferred<Unit>)
    private val lock = Any()
    private var active = 0
    private val promoted = mutableSetOf<String>()
    private val runningKeys = mutableSetOf<String>()
    private val waiting = mutableListOf<Ticket>()

    init { require(permits > 0) }

    fun promote(key: String) = synchronized(lock) {
        val tickets = waiting.filter { it.key == key }
        tickets.forEach { it.foreground = true }
        if (tickets.isEmpty() && key !in runningKeys) promoted += key
    }

    fun clearPromotion(key: String) = synchronized(lock) { promoted.remove(key); Unit }

    suspend fun <T> run(key: String, foreground: Boolean, work: suspend () -> T): T {
        val ticket = Ticket(key, foreground, CompletableDeferred())
        synchronized(lock) {
            val wasPromoted = promoted.remove(key)
            ticket.foreground = foreground || wasPromoted
            waiting += ticket
            dispatch()
        }
        try {
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return work()
        } finally {
            synchronized(lock) {
                // A cancelled queued request owns no permit; a dispatched one does.
                if (!waiting.remove(ticket)) {
                    active--
                    runningKeys.remove(ticket.key)
                }
                dispatch()
            }
        }
    }

    private fun dispatch() {
        while (active < permits && waiting.isNotEmpty()) {
            val next = waiting.firstOrNull { it.foreground } ?: waiting.first()
            waiting.remove(next)
            active++
            runningKeys += next.key
            next.ready.complete(Unit)
        }
    }
}

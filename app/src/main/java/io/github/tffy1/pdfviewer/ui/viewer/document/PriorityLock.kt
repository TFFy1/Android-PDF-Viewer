package io.github.tffy1.pdfviewer.ui.viewer.document

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A coroutine mutex that hands the lock to the waiter with the lowest priority value instead of
 * the oldest one (FIFO among equal priorities). Priorities are re-evaluated every time the lock is
 * released, so a request whose page became visible while it waited moves ahead of prefetches.
 *
 * Used to keep at most one render in flight in the (internally serialized) engine, so a newly
 * visible page never waits behind a queue of prefetches or tiles that scrolled away. Cancelled
 * waiters leave the queue immediately.
 */
internal class PriorityLock {
    private class Waiter(val priority: () -> Int, val order: Long) {
        val granted = CompletableDeferred<Unit>()
    }

    private val guard = Any()
    private val waiters = ArrayList<Waiter>()
    private var held = false
    private var nextOrder = 0L

    suspend fun <T> withLock(priority: () -> Int, block: suspend () -> T): T {
        acquire(priority)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(priority: () -> Int) {
        val waiter = synchronized(guard) {
            if (!held) {
                held = true
                return
            }
            Waiter(priority, nextOrder++).also { waiters += it }
        }
        try {
            waiter.granted.await()
        } catch (e: CancellationException) {
            val wasGranted = synchronized(guard) { !waiters.remove(waiter) }
            // The lock was handed to us right as we were cancelled: pass it on.
            if (wasGranted) release()
            throw e
        }
    }

    private fun release() {
        val next = synchronized(guard) {
            val best = waiters.minWithOrNull(compareBy<Waiter>({ it.priority() }, { it.order }))
            if (best == null) {
                held = false
            } else {
                waiters.remove(best)
            }
            best
        }
        next?.granted?.complete(Unit)
    }
}

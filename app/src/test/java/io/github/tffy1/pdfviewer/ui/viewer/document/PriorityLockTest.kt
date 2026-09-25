package io.github.tffy1.pdfviewer.ui.viewer.document

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PriorityLockTest {

    @Test
    fun grantsWaitersByPriorityThenArrival() = runTest {
        val lock = PriorityLock()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        launch { lock.withLock({ 0 }) { order += "first"; gate.await() } }
        runCurrent()
        launch { lock.withLock({ 2 }) { order += "prefetch" } }
        launch { lock.withLock({ 1 }) { order += "tile-a" } }
        launch { lock.withLock({ 1 }) { order += "tile-b" } }
        launch { lock.withLock({ 0 }) { order += "visible" } }
        runCurrent()
        assertEquals(listOf("first"), order)

        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("first", "visible", "tile-a", "tile-b", "prefetch"), order)
    }

    @Test
    fun reevaluatesPriorityWhenReleasing() = runTest {
        val lock = PriorityLock()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()
        var pageBecameVisible = false

        launch { lock.withLock({ 0 }) { gate.await() } }
        runCurrent()
        launch { lock.withLock({ 1 }) { order += "tile" } }
        launch { lock.withLock({ if (pageBecameVisible) 0 else 2 }) { order += "page" } }
        runCurrent()

        pageBecameVisible = true
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("page", "tile"), order)
    }

    @Test
    fun cancelledWaiterIsSkipped() = runTest {
        val lock = PriorityLock()
        val order = mutableListOf<String>()
        val gate = CompletableDeferred<Unit>()

        launch { lock.withLock({ 0 }) { gate.await() } }
        runCurrent()
        val cancelled = launch { lock.withLock({ 0 }) { order += "cancelled" } }
        launch { lock.withLock({ 1 }) { order += "kept" } }
        runCurrent()

        cancelled.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("kept"), order)
    }

    @Test
    fun releasesAfterFailure() = runTest {
        val lock = PriorityLock()
        val result = runCatching { lock.withLock({ 0 }) { error("boom") } }
        assertTrue(result.isFailure)
        assertEquals(42, lock.withLock({ 0 }) { 42 })
    }
}

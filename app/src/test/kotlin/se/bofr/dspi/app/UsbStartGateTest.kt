package se.bofr.dspi.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbStartGateTest {
    @Test
    fun blocksWaitForReadyInOrderThenRunAtOnce() = runTest {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val ready = CompletableDeferred<Unit>()
        val ran = mutableListOf<String>()
        val gate = UsbStartGate(scope) { ready.await() }
        gate.run { ran += "start" }
        gate.run { ran += "intent" }
        assertEquals(emptyList<String>(), ran) // nothing may open a device before the links are loaded
        ready.complete(Unit)
        assertEquals(listOf("start", "intent"), ran)
        gate.run { ran += "late" }
        assertEquals(listOf("start", "intent", "late"), ran) // synchronous once ready
    }
}

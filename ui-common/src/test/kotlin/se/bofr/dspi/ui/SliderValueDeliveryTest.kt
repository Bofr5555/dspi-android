package se.bofr.dspi.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SliderValueDeliveryTest {
    @Test
    fun firstValueAtOnceThenLatestPerInterval() = runTest {
        val got = mutableListOf<Float>()
        val d = SliderValueDelivery(backgroundScope, intervalMs = 30).apply { onValue = { got += it } }
        d.submit(1f)
        assertEquals(listOf(1f), got)
        d.submit(2f)
        d.submit(3f)
        assertEquals(listOf(1f), got)
        advanceTimeBy(31)
        runCurrent()
        assertEquals(listOf(1f, 3f), got)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(1f, 3f), got)
        d.submit(4f)
        assertEquals(listOf(1f, 3f, 4f), got)
    }

    @Test
    fun duplicatesAreDroppedAndFinishWins() = runTest {
        val got = mutableListOf<Float>()
        val d = SliderValueDelivery(backgroundScope, intervalMs = 30).apply { onValue = { got += it } }
        d.synchronize(5f)
        d.submit(5f)
        assertEquals(emptyList<Float>(), got)
        d.submit(6f)
        d.submit(7f)
        d.finish(8f)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(8f), got) // the dropped duplicate still started the cooldown, as on macOS
    }
}

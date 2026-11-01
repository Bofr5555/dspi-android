package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {
    @Test
    fun firstImmediatelyThenLatest() = runTest {
        val sent = mutableListOf<Int>()
        val t = Throttle<Int>(this, 30) { sent += it }
        t.submit(1)
        t.submit(2)
        t.submit(3)
        assertEquals(listOf(1), sent)
        advanceTimeBy(31)
        assertEquals(listOf(1, 3), sent)
        t.cancel()
    }
}

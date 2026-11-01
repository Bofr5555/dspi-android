package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Throttle.qml timing; the cases follow Windows
 * DSPiConsole.Core.Tests/ValueDeliveryTests.cs (same coalescer).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThrottleTest {
    @Test
    fun firstValueGoesAtOnceAndOnlyTheLatestFollows() = runTest {
        val sent = mutableListOf<Int>()
        val t = Throttle<Int>(backgroundScope, 30) { sent += it }
        t.submit(1)
        t.submit(2)
        t.submit(3)
        assertEquals(listOf(1), sent)
        advanceTimeBy(31)
        assertEquals(listOf(1, 3), sent)
        advanceTimeBy(1000)
        assertEquals(listOf(1, 3), sent)
    }

    @Test
    fun cancelDropsThePendingValueBeforeTheCommit() = runTest {
        val sent = mutableListOf<Int>()
        val t = Throttle<Int>(backgroundScope, 30) { sent += it }
        t.submit(1)
        t.submit(2)
        t.cancel()
        advanceTimeBy(1000)
        assertEquals(listOf(1), sent)
        t.finish(5)
        assertEquals(listOf(1, 5), sent)
        // After the drag a new one starts at once
        t.submit(6)
        assertEquals(listOf(1, 5, 6), sent)
    }

    @Test
    fun finishSendsTheLastValueAndDropsThePending() = runTest {
        val sent = mutableListOf<Int>()
        val t = Throttle<Int>(backgroundScope, 30) { sent += it }
        t.submit(1)
        t.submit(2)
        t.finish(5)
        advanceTimeBy(1000)
        assertEquals(listOf(1, 5), sent)
    }

    @Test
    fun atMostThirtyThreeASecond() = runTest {
        val sent = mutableListOf<Int>()
        val t = Throttle<Int>(backgroundScope, 30) { sent += it }
        for (i in 0 until 1000) {
            t.submit(i)
            advanceTimeBy(1)
            runCurrent()
        }
        assertTrue("sent ${sent.size}", sent.size in 30..35)
        assertEquals(0, sent[0])
    }
}

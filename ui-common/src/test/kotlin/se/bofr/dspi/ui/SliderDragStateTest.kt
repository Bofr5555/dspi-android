package se.bofr.dspi.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Review H1 / decision F4: a slider never emits a value the user did not produce. */
@OptIn(ExperimentalCoroutinesApi::class)
class SliderDragStateTest {
    @Test
    fun grabSendsNothingThenMovesGoLiveAndReleaseCommitsOnce() = runTest {
        val live = mutableListOf<Float>()
        val commits = mutableListOf<Float>()
        val d = SliderDragState(backgroundScope, initial = -10f, intervalMs = 30)
        d.editing(true) { commits += it }
        runCurrent()
        assertTrue(live.isEmpty())
        assertTrue(d.dragging)
        // The first moved value goes out at once (no cooldown left over from the grab).
        d.change(-9f) { live += it }
        assertEquals(listOf(-9f), live)
        d.change(-8f) { live += it }
        d.change(-7f) { live += it }
        advanceTimeBy(31)
        runCurrent()
        assertEquals(listOf(-9f, -7f), live)
        d.editing(false) { commits += it }
        advanceTimeBy(100)
        runCurrent()
        assertEquals(listOf(-7f), commits)
        assertEquals(listOf(-9f, -7f), live)
        assertFalse(d.dragging)
    }

    @Test
    fun aMoveBackToTheGrabValueIsNotSent() = runTest {
        val live = mutableListOf<Float>()
        val d = SliderDragState(backgroundScope, initial = 0f, intervalMs = 30)
        d.editing(true) {}
        d.change(0f) { live += it }
        assertTrue(live.isEmpty())
    }

    @Test
    fun theModelIsIgnoredWhileDragging() = runTest {
        val d = SliderDragState(backgroundScope, initial = 1f)
        d.editing(true) {}
        d.change(2f) {}
        d.sync(5f) // a late echo
        assertEquals(2f, d.value)
        d.editing(false) {}
        d.sync(5f)
        assertEquals(5f, d.value)
    }

    @Test
    fun releaseWithoutGrabCommitsNothing() = runTest {
        val commits = mutableListOf<Float>()
        val d = SliderDragState(backgroundScope, initial = 1f)
        d.editing(false) { commits += it }
        assertTrue(commits.isEmpty())
    }

    @Test
    fun relativeDragMath() {
        assertEquals(-5f, SliderMath.dragged(-10f, 50f, 700f, -60f..10f), 1e-4f)
        assertEquals(10f, SliderMath.dragged(5f, 10_000f, 700f, -60f..10f))
        assertEquals(-60f, SliderMath.dragged(5f, -10_000f, 700f, -60f..10f))
        assertEquals(0.5f, SliderMath.fraction(-25f, -60f..10f), 1e-6f)
    }
}

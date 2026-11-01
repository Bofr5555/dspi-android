package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.FilterParams
import se.bofr.dspi.fake.FakeDspiCore

/** DSPiBridge::scheduleNotifications / processNotifications: ≤ 1 emission per 30 ms. */
@OptIn(ExperimentalCoroutinesApi::class)
class NotifyBatchingTest {
    private fun peq(gain: Float) = FilterParams(DspiConstants.FILTER_PEAKING, false, 1000f, 1f, gain, 0.707f)

    @Test
    fun aBurstOfNotificationsIsOneStateEmission() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false) // keep the status poll out of the count
        attach(b, core)
        advanceTimeBy(100)
        val events = collectEvents(b)
        val rev = b.stateRevision.value
        // A turning knob: ten notifications inside 30 ms
        repeat(10) { i ->
            core.simulateUserVolume(-20f - i)
            advanceTimeBy(2)
        }
        advanceTimeBy(40)
        runCurrent()
        assertEquals(-29f, b.userVolumeDB)
        // The first one drains at once (nothing for 30 ms before it), the rest batch
        val emissions = b.stateRevision.value - rev
        assertTrue("emissions $emissions", emissions in 1..2)
        assertEquals(emissions.toInt(), events.count { it == BridgeEvent.NotificationsArrived })
        assertEquals(emissions.toInt(), core.callCount("processNotifications"))
    }

    @Test
    fun drainsAreAtLeastThirtyMillisecondsApart() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        advanceTimeBy(100)
        val times = mutableListOf<Long>()
        val base = core.callCount("processNotifications")
        repeat(100) {
            core.simulateUserVolume(-10f)
            runCurrent()
            val n = core.callCount("processNotifications") - base
            if (n > times.size) times += testScheduler.currentTime
            advanceTimeBy(1)
        }
        assertTrue("drains at $times", times.size in 3..5)
        assertTrue("drains at $times", times.zipWithNext().all { (a, c) -> c - a >= 30 })
    }

    @Test
    fun onlyTheChangedChannelsCurvesAreRecomputed() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        advanceTimeBy(100)
        val before = core.callCount("computeMagnitudeCurve")
        val mrev = b.magnitudesRevision.value
        core.simulateFilterChange(9, 3, peq(5f)) // wire 9 = output 2 = app id 3
        core.simulateFilterChange(1, 3, peq(-5f)) // wire 1 = input 2 = app id 1
        advanceTimeBy(40)
        runCurrent()
        assertEquals(before + 2, core.callCount("computeMagnitudeCurve"))
        assertTrue(b.magnitudesRevision.value > mrev)
        assertEquals(5f, b.filterGain(3, 3))
        assertEquals(-5f, b.filterGain(1, 3))
    }

    @Test
    fun aPresetLoadOnTheDeviceRefreshesEverythingAndIsClean() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        b.setBypass(true)
        runCurrent()
        assertTrue(b.presetDirty.value)
        val before = core.callCount("computeMagnitudeCurve")
        core.simulatePresetLoaded(1)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(before + 17, core.callCount("computeMagnitudeCurve"))
        assertEquals(1, b.activePresetSlot)
        assertFalse(b.presetDirty.value)
    }

    @Test
    fun signalGeneratorAndControlSurfaceSignals() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        val events = collectEvents(b)
        val rev = b.stateRevision.value
        core.simulateRemote(flags = DspiConstants.NOTIFY_SIGGEN or DspiConstants.NOTIFY_CS_AUX)
        advanceTimeBy(40)
        runCurrent()
        assertTrue(BridgeEvent.SiggenNotified in events)
        assertTrue(BridgeEvent.CsNotified(aux = true, learn = false) in events)
        assertEquals(rev, b.stateRevision.value) // no NOTIFY_STATE: no stateChanged
    }
}

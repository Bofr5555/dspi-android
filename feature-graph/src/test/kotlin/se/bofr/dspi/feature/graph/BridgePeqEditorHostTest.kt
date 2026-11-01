package se.bofr.dspi.feature.graph

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.ui.settings.AppSettings

/** The editor's host over the real bridge and the fake core: live writes throttled, one commit on release. */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgePeqEditorHostTest {
    private fun TestScope.bridge(core: FakeDspiCore): DspiBridgeImpl {
        val d = StandardTestDispatcher(testScheduler)
        return DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
    }

    // App id 2 = output 1 (wire 8 on the RP2350 fake).
    private val ch = 2

    @Test
    fun liveWritesAreThrottledAndTheCommitLandsOnce() = runTest {
        val core = FakeDspiCore()
        val b = bridge(core)
        assertTrue(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        val host = BridgePeqEditorHost(b, PeqGraphSelection(), backgroundScope)
        core.calls.clear()
        val p1 = FilterTypes.band(FILTER_PEAKING, 1000f, 1f, 3f)
        val p2 = p1.copy(gain = 4f)
        val p3 = p1.copy(gain = 5f)
        host.sendGraphBandsToDevice(ch, listOf(4 to p1))
        runCurrent()
        assertEquals("the first value goes out at once", 1, core.calls.count { it.startsWith("setFilter") })
        host.sendGraphBandsToDevice(ch, listOf(4 to p2))
        host.sendGraphBandsToDevice(ch, listOf(4 to p3))
        runCurrent()
        assertEquals("then at most one per 30 ms", 1, core.calls.count { it.startsWith("setFilter") })
        advanceTimeBy(31)
        runCurrent()
        assertEquals("the latest one", 2, core.calls.count { it.startsWith("setFilter") })
        assertTrue(core.calls.last { it.startsWith("setFilter") }.contains("gain=5.0"))

        val revBefore = b.stateRevision.value
        val final = p1.copy(gain = 6f)
        host.sendGraphBandsToDevice(ch, listOf(4 to p1.copy(gain = 5.5f)))
        host.commitGraphBands(ch, listOf(4 to final))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(6f, b.filterGain(ch, 4))
        assertTrue("the commit emits once", b.stateRevision.value > revBefore)
        assertTrue(
            "a pending live value never lands after the commit",
            core.calls.last {
                it.startsWith("setFilter")
            }.contains("gain=6.0")
        )
    }

    @Test
    fun bypassAndTheLiveRows() = runTest {
        val core = FakeDspiCore()
        val b = bridge(core)
        assertTrue(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        val selection = PeqGraphSelection()
        val host = BridgePeqEditorHost(b, selection, backgroundScope)
        host.setGraphBandBypass(ch, listOf(0, 1), true)
        runCurrent()
        assertTrue(b.filterBypass(ch, 0))
        assertTrue(b.filterBypass(ch, 1))
        val p = FilterTypes.band(FILTER_PEAKING, 500f, 2f, -3f)
        host.showLive(ch, 3, p)
        assertEquals(p, selection.live.value[3])
        host.endLive(ch)
        assertNull(selection.live.value[3])
    }

    @Test
    fun editorConfigFollowsTheBridge() = runTest {
        val core = FakeDspiCore()
        val b = bridge(core)
        assertTrue(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        val s = AppSettings()
        val none = editorConfigFor(b, null, s)
        assertNull(none.channel)
        val c = editorConfigFor(b, ch, s)
        assertEquals(ch, c.channel)
        assertEquals(b.channelBands(ch)!!.peq, c.bands)
        assertEquals("an output carries its crossover", b.channelBands(ch)!!.xover, c.statics)
        assertEquals(b.channelGainOffset(ch), c.offsetDb)
        assertEquals(s.dbTop, c.dbTop, 0.0)
        assertTrue(FILTER_PEAKING in c.availableTypes)
        assertTrue(DspiConstants.FILTER_LINKWITZ_TRANSFORM !in c.availableTypes)
        assertTrue(DspiConstants.FILTER_FLAT !in c.availableTypes)
        assertNotNull(editableChannels(b).firstOrNull())
    }
}

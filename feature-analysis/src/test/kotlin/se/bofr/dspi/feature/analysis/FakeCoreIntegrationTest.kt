package se.bofr.dspi.feature.analysis

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/**
 * The views' logic over the real bridge and FakeDspiCore: what the device
 * (fake) serves turns into what the pages show.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeCoreIntegrationTest {
    private suspend fun TestScope.connected(core: FakeDspiCore): DspiBridgeImpl {
        val d = StandardTestDispatcher(testScheduler)
        val b = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        assertTrue(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        return b
    }

    @Test
    fun signalGeneratorStartsOnlyWhenAskedAndReportsRunning() = runTest {
        val core = FakeDspiCore()
        val b = connected(core)
        b.siggen.watching = true
        runCurrent()
        val caps = b.siggen.caps.value!!
        val draft = b.siggen.draft.value!!
        assertEquals(0, core.callCount("siggenControl")) // never starts by itself
        assertEquals(-20f, draft.levelDb)
        val desc = siggenTypeDesc(caps, draft.signalType)
        assertEquals(null, siggenStartBlocker(b.connected, caps.supported, draft, desc))

        b.siggen.selectType(SIGGEN_SWEEP_LOG)
        runCurrent()
        val sweep = b.siggen.draft.value!!
        assertEquals(5000L, sweep.durationMs)
        assertEquals(listOf(20f, 20000f), sweep.p.take(2))

        assertTrue(b.siggen.start())
        runCurrent()
        val st = b.siggen.status.value
        assertTrue(st.isRunning)
        assertEquals("Fading in · Log sweep", siggenTransportTitle(st, null, true, true, sweep))

        b.siggen.stop() // leaving the page
        runCurrent()
        assertTrue(!b.siggen.status.value.isRunning)
        assertEquals("Stopped", siggenTransportDetail(b.siggen.status.value, sweep) { "" })
        b.siggen.watching = false
    }

    @Test
    fun analyserFramesReachTheRenderStateAndReleaseStops() = runTest {
        val core = FakeDspiCore()
        val b = connected(core)
        val caps = b.rta.caps.value!!
        assertTrue(caps.supported)
        val outputs = b.rta.availableChannels(DspiConstants.RTA_TAP_OUTPUT)
        val sel = RtaChannelSelection.resolve(null) { b.rta.availableChannels(it) }
        assertEquals(RtaChannelSelection.of(DspiConstants.RTA_TAP_OUTPUT, listOf(outputs.first())), sel)

        b.rta.update(77, sel.tap, sel.mask, false)
        runCurrent()
        core.emitRtaFrame()
        runCurrent()
        val snap = b.rta.snapshot.value!!
        val r = RtaRenderState()
        r.take(snap, caps, sel.tap, sel.channels.toIntArray(), false, 0.0)
        assertTrue(r.bandCount(0) > 0)
        // The core's per-band populated mask: bars are drawn for the measurable bands
        assertTrue(r.shownBands(r.bandCount(0), IntArray(DspiConstants.RTA_MAX_BANDS)) > 10)
        assertTrue(r.levels(0)[5] > DspiConstants.RTA_FLOOR_DB)
        assertTrue(rtaRefreshDescription(snap, 10).startsWith("1 channel"))

        b.rta.release(77)
        runCurrent()
        assertTrue(core.calls.last { it.startsWith("rtaSetRequest") }.contains("mask=0"))
    }

    @Test
    fun statisticsCountersAndFillsFormat() = runTest {
        val core = FakeDspiCore()
        val b = connected(core)
        b.stats.watching = true
        runCurrent()
        val c = StatCounters(b.stats.info.value)
        assertTrue(SystemInfoFormat.clock(c[DspiConstants.STAT_CLOCK_HZ]).endsWith(".0 MHz"))
        assertEquals("1.10 V", SystemInfoFormat.coreVoltage(c[DspiConstants.STAT_CORE_MV]))
        assertEquals("31.2 °C", SystemInfoFormat.temperature(c[DspiConstants.STAT_TEMPERATURE]))

        val h = BufferFillHistory()
        repeat(5) {
            advanceTimeBy(61)
            runCurrent()
            b.stats.buffers.value?.let { h.append(it) }
        }
        assertTrue(h.total >= 4)
        assertTrue(h.value(0, h.total - 1) in 40f..60f)
        b.stats.watching = false
    }

    @Test
    fun spdifInputSectionReadsTheReceiver() = runTest {
        val core = FakeDspiCore()
        val b = connected(core)
        val rx = b.fetchSpdifRxStatus()!!
        assertTrue(spdifRxStateString(rx.state).isNotEmpty())
        assertEquals(spdifRxIsLocked(rx.state), b.fetchSpdifChannelStatus() != null)
    }
}

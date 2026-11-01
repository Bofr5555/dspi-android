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
import se.bofr.dspi.fake.FakeDspiCore

@OptIn(ExperimentalCoroutinesApi::class)
class ControllersTest {
    @Test
    fun analyserRequestsTheUnionOfViewsAtTheNewestTapAndPublishesFrames() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        runCurrent()
        assertTrue(b.rta.caps.value?.supported == true)
        b.rta.update(1, DspiConstants.RTA_TAP_INPUT, 0x1, false)
        b.rta.update(2, DspiConstants.RTA_TAP_INPUT, 0x2, false)
        runCurrent()
        assertTrue(core.calls.last { it.startsWith("rtaSetRequest") }.contains("mask=3"))
        b.rta.update(3, DspiConstants.RTA_TAP_OUTPUT, 0x4, true) // newest picks the tap; bins: its channel alone
        runCurrent()
        val req = core.calls.last { it.startsWith("rtaSetRequest") }
        assertTrue(req, req.contains("tap=1") && req.contains("mask=4") && req.contains("wantsBins=true"))
        core.emitRtaFrame()
        runCurrent()
        assertEquals(1L, b.rta.snapshot.value?.version)
        b.rta.release(3)
        b.rta.release(1)
        b.rta.release(2)
        runCurrent()
        assertTrue(core.calls.last { it.startsWith("rtaSetRequest") }.contains("mask=0"))
        assertEquals(11, b.rta.appChannel(DspiConstants.RTA_TAP_INPUT, 2))
        assertEquals(4, b.rta.appChannel(DspiConstants.RTA_TAP_OUTPUT, 2))
        assertEquals(2, b.rta.tapIndex(11))
        assertEquals(DspiConstants.RTA_TAP_OUTPUT, b.rta.tapOf(5))
        assertEquals(listOf(0, 1, 2, 3), b.rta.availableChannels(DspiConstants.RTA_TAP_OUTPUT))
    }

    @Test
    fun signalGeneratorEditsApplyLiveAfterAPause() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        runCurrent()
        val g = b.siggen
        assertTrue(g.caps.value?.supported == true)
        g.selectType(4) // log sweep
        assertEquals(5000L, g.draft.value?.durationMs)
        assertEquals(listOf(20f, 20000f, 0f, 0f), g.draft.value?.p)
        g.cycleOutput(2)
        g.cycleOutput(2)
        assertEquals(0b100, g.draft.value!!.invertMask and 0b100)
        assertTrue(g.start())
        runCurrent()
        val sets = core.callCount("siggenSetConfig")
        g.setLevelDb(-30f)
        g.setLevelDb(-31f)
        advanceTimeBy(300)
        assertEquals(sets, core.callCount("siggenSetConfig"))
        advanceTimeBy(100)
        runCurrent()
        assertEquals(sets + 1, core.callCount("siggenSetConfig"))
        g.stopNow()
        runCurrent()
        assertEquals(0, g.status.value?.state)
    }

    @Test
    fun statisticsPollOnlyWhileWatched() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        advanceTimeBy(5_000)
        assertEquals(0, core.callCount("fetchBufferStats"))
        b.stats.watching = true
        advanceTimeBy(600)
        assertTrue(core.callCount("fetchBufferStats") >= 10)
        assertEquals(150_000_000L, b.stats.info.value[DspiConstants.STAT_CLOCK_HZ])
        b.stats.watching = false
        val n = core.callCount("fetchBufferStats")
        advanceTimeBy(5_000)
        assertEquals(n, core.callCount("fetchBufferStats"))
        // Reconnects after the first connection are counted
        b.detachDevice(core.serial)
        attach(b, core)
        assertEquals(1, b.stats.reconnects.value)
    }

    @Test
    fun monitorReadsLinesWhenNotificationsArrive() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        b.monitor.watching = true
        core.simulateUserVolume(-7f)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(1, b.monitor.lines.value.size)
        assertTrue(b.monitor.allText().endsWith("USER_VOLUME -7.0 dB"))
        b.monitor.paused = true
        core.simulateUserVolume(-8f)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(1, b.monitor.lines.value.size) // skipped, not queued
        b.monitor.clear()
        assertTrue(b.monitor.lines.value.isEmpty())
    }

    @Test
    fun controlSurfacesLoadOnConnectAndGateSave() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        runCurrent()
        assertTrue(b.cs.loaded.value)
        assertTrue(b.cs.supported.value)
        assertFalse(b.cs.unsaved.value)
        assertTrue(b.cs.apply("""{"op":"binding","index":0}""").contains("\"ok\":true"))
        assertTrue(b.cs.unsaved.value)
        assertTrue(runCatching { b.cs.apply("""{"op":"save"}""") }.exceptionOrNull() is DangerousOpNotConfirmed)
        // Text that is not a JSON operation never reaches the core (review N8)
        val n = core.callCount("csApply")
        assertTrue(b.cs.apply("""{"op":"save"""").contains("invalid operation"))
        assertTrue(b.cs.apply("""["save"]""").contains("invalid operation"))
        b.cs.sendOnly("""{op: save}""")
        runCurrent()
        assertEquals(n, core.callCount("csApply"))
        assertEquals(0, b.cs.save(b.dangerousOps.confirm(DangerousOp.CS_SAVE)))
        assertFalse(b.cs.unsaved.value)
        assertEquals("", b.cs.statusMessage(0))
        assertEquals("Invalid slot", b.cs.statusMessage(0x10))
    }

    @Test
    fun configurationFilesRoundTripAndRestoreLinks() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        b.setUiVisible(false)
        attach(b, core)
        advanceTimeBy(1)
        b.restoreInputLinks(0b1)
        val doc = b.configFiles.exportConfiguration("Living room")
        assertTrue(b.configFiles.inspectConfiguration(doc).contains("\"crossPlatform\":false"))
        b.restoreInputLinks(0)
        b.setBypass(true)
        runCurrent()
        val ops = b.dangerousOps
        val r = b.configFiles.importConfiguration(ops.confirm(DangerousOp.CONFIG_IMPORT), doc, volumes = false, hardware = false)
        assertTrue(r, r.contains("\"ok\":true"))
        assertEquals(0b1, b.inputLinks)
        assertFalse(b.bypass)
        val filters = b.configFiles.exportFilters()
        assertTrue(b.configFiles.inspectFilters(filters).contains("\"ok\":true"))
        assertTrue(b.configFiles.inspectFilters("nothing here").contains("No filters this device can use"))
        b.clearPeq(0)
        runCurrent()
        b.configFiles.importFilters(filters, listOf(0))
        assertEquals(DspiConstants.FILTER_LOWSHELF, b.filterType(0, 0))
    }
}

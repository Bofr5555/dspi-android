package se.bofr.dspi.feature.matrix

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/** MatrixMixerState.from + MatrixActions against DspiBridgeImpl over the V32 FakeDspiCore. */
@OptIn(ExperimentalCoroutinesApi::class)
class MatrixActionsTest {
    private class Rig(val core: FakeDspiCore, val bridge: DspiBridgeImpl, val actions: MatrixActions)

    private suspend fun TestScope.rig(overview: Boolean = false): Rig {
        val core = FakeDspiCore.seeded()
        val d = StandardTestDispatcher(testScheduler)
        val bridge = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        bridge.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
        runCurrent()
        val actions = MatrixActions(bridge, backgroundScope, isOverviewMode = { overview })
        core.calls.clear()
        return Rig(core, bridge, actions)
    }

    private fun Rig.calls(name: String) = core.calls.filter { it.startsWith("$name(") }

    @Test
    fun stateFromTheFixture() = runTest {
        val r = rig()
        val s = MatrixMixerState.from(r.bridge, activeInputChannels = 2, connected = true)
        assertEquals("RP2350", s.platformName)
        assertTrue(s.is8ch)
        assertEquals(9, s.visibleOutputs.size)
        assertEquals("OUT1", s.visibleOutputs[0].descriptor)
        assertEquals("OUT9", s.visibleOutputs[8].descriptor)
        assertEquals("SPDIF 1 L", s.visibleOutputs[0].name)
        assertEquals("PDM", s.visibleOutputs[8].name)
        assertEquals(8, s.pdmOutputIndex)
        assertEquals(85f, s.maxDelay)
        // V32 fixture: stereo active, upmixer off -> 2 rows
        assertEquals(listOf("USB 1", "USB 2"), s.matrixInputs.map { it.name })
        assertEquals("#4A8FE3", s.matrixInputs[0].color)
        assertEquals(s.routes.size, s.matrixInputs.size)
        assertEquals(r.bridge.matrixRouting(0, 0), s.route(0, 0).isConnected)
        assertEquals(r.bridge.outputEnabled(0), s.output(0).enabled)
        // 8 active channels -> 8 rows with the 3/4, 5/6 pair dividers
        val s8 = MatrixMixerState.from(r.bridge, activeInputChannels = 8, connected = true)
        assertEquals(8, s8.matrixInputs.size)
        assertEquals(8, s8.inputTrimDB.size)
    }

    @Test
    fun upmixerAddsDerivedRows() = runTest {
        val r = rig()
        r.bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_ENABLED, 1f)
        r.bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_SURROUND_MODE, 1f)
        runCurrent()
        val s = MatrixMixerState.from(r.bridge, activeInputChannels = 2, connected = true)
        assertEquals(listOf("USB 1", "USB 2", "C", "Ls", "Rs"), s.matrixInputs.map { it.name })
        assertEquals("Upmix Centre", s.matrixInputs[2].fullName)
        r.bridge.setUpmixParam(DspiConstants.UPMIX_PARAM_SURROUND_MODE, 0f)
        runCurrent()
        assertEquals(3, MatrixMixerState.from(r.bridge, 2, true).matrixInputs.size)
    }

    @Test
    fun toggleRouteKeepsGainAndPolarity() = runTest {
        val r = rig()
        r.actions.toggleRoute(1, 4, MatrixRoute(false, -3f, true))
        runCurrent()
        assertEquals(listOf("setMatrixRoute(1, 4, true, -3.0, true)"), r.calls("setMatrixRoute"))
        assertTrue(r.bridge.matrixRouting(1, 4))
        r.actions.toggleInvert(1, 4, MatrixRoute(true, -3f, true))
        runCurrent()
        assertFalse(r.bridge.matrixInvert(1, 4))
    }

    @Test
    fun matrixGainDragIsThrottledThenCommittedOnce() = runTest {
        val r = rig()
        val route = MatrixRoute(true, 0f, false)
        val rev0 = r.bridge.stateRevision.value
        r.actions.matrixGainLive(0, 0, route, -1f) // goes out at once
        r.actions.matrixGainLive(0, 0, route, -2f)
        r.actions.matrixGainLive(0, 0, route, -3f) // latest wins
        runCurrent()
        advanceTimeBy(MatrixActions.THROTTLE_MS + 1)
        runCurrent()
        assertEquals(
            listOf("setMatrixRoute(0, 0, true, -1.0, false)", "setMatrixRoute(0, 0, true, -3.0, false)"),
            r.calls("setMatrixRoute")
        )
        // sendOnly: the device follows, no stateChanged during the drag
        assertEquals(rev0, r.bridge.stateRevision.value)
        advanceTimeBy(100) // the throttle goes idle
        runCurrent()
        r.core.calls.clear()
        r.actions.matrixGainLive(0, 0, route, -4f)
        r.actions.matrixGainLive(0, 0, route, -5f) // pending, dropped by the commit
        r.actions.matrixGainCommit(0, 0, route, -6f)
        runCurrent()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(
            listOf("setMatrixRoute(0, 0, true, -4.0, false)", "setMatrixRoute(0, 0, true, -6.0, false)"),
            r.calls("setMatrixRoute")
        )
        assertEquals(-6f, r.bridge.matrixGain(0, 0))
        assertTrue(r.bridge.stateRevision.value > rev0) // the commit emits
    }

    @Test
    fun outputGainAndDelayCommitRounded() = runTest {
        val r = rig()
        r.actions.outputGainLive(2, -3.04f)
        r.actions.outputGainCommit(2, -3.26f)
        r.actions.outputDelayLive(2, 9.6f)
        r.actions.outputDelayCommit(2, 12.4f)
        runCurrent()
        assertEquals(listOf("setOutputGain(2, -3.0)", "setOutputGain(2, -3.3)"), r.calls("setOutputGain"))
        assertEquals(listOf("setOutputDelay(2, 10.0)", "setOutputDelay(2, 12.0)"), r.calls("setOutputDelay"))
        assertEquals(-3.3f, r.bridge.outputGainDB(2), 1e-6f)
        assertEquals(12f, r.bridge.outputDelayMS(2), 0f)
    }

    @Test
    fun inputTrimLiveIsSendOnlyAndCommits() = runTest {
        val r = rig()
        r.actions.inputTrimLive(4, -2f)
        r.actions.inputTrimCommit(4, -2.5f)
        runCurrent()
        assertEquals(listOf("setInputPreamp(4, -2.0)", "setInputPreamp(4, -2.5)"), r.calls("setInputPreamp"))
        assertEquals(-2.5f, r.bridge.inputPreampDB(4), 0f)
    }

    @Test
    fun enableOutsideOverviewModeLeavesGraphVisibilityAlone() = runTest {
        val r = rig(overview = false)
        val before = r.bridge.channelVisible(4)
        r.actions.setOutputEnable(2, !r.bridge.outputEnabled(2))
        runCurrent()
        assertEquals(before, r.bridge.channelVisible(4))
    }

    @Test
    fun enableInOverviewModeFollowsOnlyWhenTheDeviceTookIt() = runTest {
        val r = rig(overview = true)
        r.actions.setOutputEnable(2, true)
        runCurrent()
        assertTrue(r.bridge.outputEnabled(2))
        assertTrue(r.bridge.channelVisible(4))
        // PDM on while OUT3 runs on Core 1: the device refuses, visibility stays
        r.bridge.setChannelVisible(10, false)
        r.actions.setOutputEnable(8, true)
        runCurrent()
        assertFalse(r.bridge.outputEnabled(8))
        assertFalse(r.bridge.channelVisible(10))
    }

    @Test
    fun pdmConflictAlertAndSwitchToPdm() = runTest {
        val r = rig(overview = true)
        r.actions.setOutputEnable(2, true)
        runCurrent()
        val s = MatrixMixerState.from(r.bridge, 2, true)
        r.actions.requestOutputEnable(s, 8)
        assertEquals(PendingConflict(8, true), r.actions.pendingConflict.value)
        r.core.calls.clear()
        r.actions.resolveConflict(r.actions.pendingConflict.value!!, s.eqWorkerRange, s.pdmOutputIndex)
        assertEquals(null, r.actions.pendingConflict.value)
        runCurrent()
        // macOS switchToPDM: every EQ-worker output off, then PDM on
        assertEquals((2..7).map { "setOutputEnable($it, false)" } + "setOutputEnable(8, true)", r.calls("setOutputEnable"))
        assertTrue(r.bridge.outputEnabled(8))
        assertFalse(r.bridge.outputEnabled(2))
        assertTrue(r.bridge.outputEnabled(0))
        assertFalse(r.bridge.channelVisible(4))
        assertTrue(r.bridge.channelVisible(10))
    }

    @Test
    fun switchFromPdm() = runTest {
        val r = rig()
        r.actions.setOutputEnable(8, true)
        runCurrent()
        assertTrue(r.bridge.outputEnabled(8))
        val s = MatrixMixerState.from(r.bridge, 2, true)
        r.actions.requestOutputEnable(s, 4)
        assertEquals(PendingConflict(4, false), r.actions.pendingConflict.value)
        r.core.calls.clear()
        r.actions.resolveConflict(PendingConflict(4, false), s.eqWorkerRange, s.pdmOutputIndex)
        runCurrent()
        assertEquals(listOf("setOutputEnable(8, false)", "setOutputEnable(4, true)"), r.calls("setOutputEnable"))
        assertTrue(r.bridge.outputEnabled(4))
        assertFalse(r.bridge.outputEnabled(8))
    }

    @Test
    fun dismissAndRenameStateLiveInTheController() = runTest {
        val r = rig()
        val s = MatrixMixerState.from(r.bridge, 2, true)
        r.actions.requestOutputEnable(s, 0) // enabled -> disable at once, no alert
        assertEquals(null, r.actions.pendingConflict.value)
        r.actions.startRename(3)
        assertEquals(3, r.actions.renamingOutput.value)
        r.actions.cancelRename()
        assertEquals(null, r.actions.renamingOutput.value)
    }

    @Test
    fun directAndClearRouting() = runTest {
        val r = rig()
        r.actions.applyDirectRouting()
        runCurrent()
        assertTrue(r.bridge.matrixRouting(0, 0))
        assertTrue(r.bridge.matrixRouting(1, 1))
        assertFalse(r.bridge.matrixRouting(0, 1))
        r.actions.clearAllRoutes()
        runCurrent()
        for (i in 0 until 8) for (o in 0 until 9) assertFalse(r.bridge.matrixRouting(i, o))
    }

    @Test
    fun renameTrimsAndIgnoresEmpty() = runTest {
        val r = rig()
        r.actions.startRename(0)
        r.actions.commitRename(0, "   ")
        assertEquals(null, r.actions.renamingOutput.value)
        r.actions.commitRename(0, "  Left Tweeter ")
        runCurrent()
        assertEquals(1, r.calls("setChannelName").size)
        assertEquals("Left Tweeter", r.bridge.channelName(2))
    }

    @Test
    fun copyPasteParameters() = runTest {
        val r = rig()
        assertFalse(r.actions.canPaste())
        r.actions.copyChannelParams(0)
        assertTrue(r.actions.canPaste())
        r.actions.pasteChannelParams(1)
        runCurrent()
    }

    @Test
    fun directRoutingShowsTheOutputsItEnablesOnlyInOverview() = runTest {
        for (overview in listOf(false, true)) {
            val r = rig(overview)
            r.bridge.setOutputEnable(1, false)
            runCurrent()
            r.bridge.setChannelVisible(3, false)
            r.actions.applyDirectRouting()
            runCurrent()
            assertTrue(r.bridge.outputEnabled(1))
            assertEquals("overview=$overview", overview, r.bridge.channelVisible(3))
        }
    }
}

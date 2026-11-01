package se.bofr.dspi.feature.tools

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/**
 * The setter paths the tool views use, against the bridge over FakeDspiCore
 * (which models the core's clamping and tube-row copying): what a view commits
 * is what its parameter projection reads back.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ToolBridgeMappingTest {
    private fun TestScope.bridge(core: FakeDspiCore = FakeDspiCore()): DspiBridgeImpl {
        val d = StandardTestDispatcher(testScheduler)
        val b = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        runCurrent()
        return b
    }

    @Test
    fun crossfeedPairMaskAndCustomPreset() = runTest {
        val b = bridge()
        val pairs = ToolMasks.pairCount(b.state.value.numOutputChannels)
        assertEquals(4, pairs)
        b.setCrossfeedOutputPairMask(ToolMasks.allPairs(pairs))
        b.setCrossfeedFreq(650f)
        b.setCrossfeedPreset(CrossfeedParameters.PRESET_CUSTOM)
        runCurrent()
        val c = CrossfeedParameters.from(b.state.value)
        assertEquals(0x0F, c.outputMask)
        assertEquals(650f, c.freq)
        assertTrue(c.isCustom)
    }

    @Test
    fun tubeTypeLoadsTheCharacterAndAnEditDropsToCustom() = runTest {
        val b = bridge()
        b.setTubeParam(DspiConstants.TUBE_PARAM_TYPE, 12f)
        runCurrent()
        var t = TubeParameters.from(b.state.value)
        assertEquals(12, t.type)
        val el34 = TubeParameters.tubeTypeRow(12)!!
        assertEquals(listOf(el34.biasPct, el34.asymDB, el34.hardnessPct, el34.sagPct), listOf(t.biasPct, t.asymDB, t.hardnessPct, t.sagPct))
        b.setTubeParam(DspiConstants.TUBE_PARAM_BIAS, 25f)
        runCurrent()
        t = TubeParameters.from(b.state.value)
        assertEquals(TubeParameters.TUBE_TYPE_CUSTOM, t.type)
        assertEquals(25f, t.biasPct)
        // The core clamps like the firmware.
        b.setTubeParam(DspiConstants.TUBE_PARAM_DRIVE, 99f)
        runCurrent()
        assertEquals(TubeParameters.TUBE_DRIVE_MAX, TubeParameters.from(b.state.value).driveDB)
    }

    @Test
    fun subharmParamsByIdAndSolo() = runTest {
        val b = bridge()
        val n = b.state.value.numOutputChannels
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_ENABLED, 1f)
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_MASK, ToolMasks.subOnly(n).toFloat())
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_SELECT, SubharmParameters.SUBHARM_SELECT_SUSTAINED.toFloat())
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_CEILING, -12f)
        b.setSubharmSolo(true)
        runCurrent()
        val s = SubharmParameters.from(b.state.value)
        assertTrue(s.enabled)
        assertEquals(0x100, s.outputMask)
        assertEquals(SubharmParameters.SUBHARM_SELECT_SUSTAINED, s.selectMode)
        assertFalse(s.ceilingOff)
        assertTrue(s.solo)
        assertTrue(ToolRoute.SUBHARM.isOn(b.state.value))
    }

    @Test
    fun upmixEnginesAndLiveDragDoesNotEmit() = runTest {
        val b = bridge()
        b.setUpmixParam(DspiConstants.UPMIX_PARAM_CENTER_MODE, UpmixParameters.UPMIX_CENTER_MODE_OFF.toFloat())
        runCurrent()
        assertTrue(UpmixParameters.from(b.state.value).centreOff)
        val rev = b.stateRevision.value
        b.setUpmixParam(DspiConstants.UPMIX_PARAM_STRENGTH, 40f, sendOnly = true)
        runCurrent()
        assertEquals(rev, b.stateRevision.value)
        b.setUpmixParam(DspiConstants.UPMIX_PARAM_STRENGTH, 40f)
        runCurrent()
        assertEquals(40f, UpmixParameters.from(b.state.value).strengthPct)
    }

    @Test
    fun psybassStartingPointSetsAllFive() = runTest {
        val b = bridge()
        val p = PsybassParameters.startingPoints[2]
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_CUTOFF, p.cutoff)
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_HARMONICS, p.harmonics)
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_DRIVE, p.drive)
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_CHARACTER, p.character)
        b.setPsybassParam(DspiConstants.PSYBASS_PARAM_ORIGINAL, p.original)
        b.setPsybassOutputMask(ToolMasks.excludeSub(9))
        runCurrent()
        assertEquals(PsybassParameters(180f, 6f, 12f, 50f, -24f, 0xFF), PsybassParameters.from(b.state.value))
    }

    @Test
    fun limiterHelpers() = runTest {
        val b = bridge()
        b.setLimiterEnabled(0, true)
        b.setLimiterThreshold(0, -6f)
        b.linkAllLimiterPairs()
        runCurrent()
        var l = LimiterParameters.from(b.state.value)
        assertEquals(LimiterParameters.stereoPairGroups(9), l.outputs.take(9).map { it.linkGroup })
        assertEquals(-6f, l.outputs[0].thresholdDB)
        b.copyLimiterToAll(0)
        runCurrent()
        l = LimiterParameters.from(b.state.value)
        assertTrue(l.outputs.take(9).all { it.enabled && it.thresholdDB == -6f })
        b.disableAllLimiters()
        runCurrent()
        assertFalse(LimiterParameters.from(b.state.value).anyEnabled)
    }

    @Test
    fun levellerMasksAndLoudnessMask() = runTest {
        val b = bridge()
        val (_, det, app) = LevellerParameters.maskPresets[1]
        b.setLevellerMasks(det, app)
        b.setLoudnessOutputMask(LoudnessParameters.HEADPHONES_MASK)
        runCurrent()
        val lv = LevellerParameters.from(b.state.value)
        assertEquals(0x04, lv.detectorMask)
        assertEquals(0x04, lv.applyMask)
        assertEquals(0x0003, LoudnessParameters.from(b.state.value).outputMask)
    }

    @Test
    fun committedLimiterEditsMarkTheOutputConfigUnsavedInIndependentMode() = runTest {
        val core = FakeDspiCore(demoDspState().copy(outputConfigMode = 0))
        val b = bridge(core)
        assertFalse(b.outputConfigDirty.value)
        // A live drag value does not start an output edit (macOS sendLimiterParamToDevice).
        b.setLimiterThreshold(0, -3f, sendOnly = true)
        runCurrent()
        assertFalse(b.outputConfigDirty.value)
        // The commit goes through the view's edit { beginOutputEdit(); ... }.
        edit(b) { b.setLimiterThreshold(0, -3f) }
        runCurrent()
        assertTrue(b.outputConfigDirty.value)
        assertEquals(-3f, LimiterParameters.from(b.state.value).outputs[0].thresholdDB)
    }

    @Test
    fun limiterEditsInPresetModeStayClean() = runTest {
        val b = bridge(FakeDspiCore(demoDspState().copy(outputConfigMode = 1)))
        edit(b) { b.setLimiterEnabled(0, true) }
        runCurrent()
        assertFalse(b.outputConfigDirty.value)
    }

    @Test
    fun thePublishedStateMirrorsLinkGroupGanging() = runTest {
        val b = bridge()
        edit(b) { b.linkAllLimiterPairs() }
        runCurrent()
        val before = LimiterParameters.from(b.state.value).outputs
        edit(b) { b.setLimiterThreshold(1, -9f) }
        runCurrent()
        val after = LimiterParameters.from(b.state.value).outputs
        val oracle = LimiterGang.edit(1, before, 9) { it.copy(thresholdDB = -9f) }
        assertEquals(oracle.take(9).map { it.thresholdDB }, after.take(9).map { it.thresholdDB })
        assertEquals(-9f, after[0].thresholdDB)
    }
}

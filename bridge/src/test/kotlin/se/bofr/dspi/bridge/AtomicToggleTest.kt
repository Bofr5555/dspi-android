package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/**
 * reviews/stale-state-audit.md T1/T2: chip and crosspoint toggles issued
 * before the previous write is published (the UI still shows the old value)
 * must all take effect. A UI-side `set(mask xor bit)` from the composed
 * value loses all but the last.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AtomicToggleTest {
    @Test
    fun maskTogglesQueuedBeforeAPublishAllLand() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setLoudnessOutputMask(0)
        b.setLevellerMasks(0, 0)
        b.setCrossfeedOutputPairMask(0)
        b.setPsybassOutputMask(0)
        b.setSubharmParam(DspiConstants.SUBHARM_PARAM_MASK, 0f)
        b.setTubeParam(DspiConstants.TUBE_PARAM_MASK, 0f)
        runCurrent()
        // No runCurrent between the taps: nothing has been published yet.
        for (bit in listOf(0, 2, 3)) b.toggleMaskBit(MaskField.LOUDNESS_OUTPUTS, bit)
        b.toggleMaskBit(MaskField.LEVELLER_DETECTOR, 1)
        b.toggleMaskBit(MaskField.LEVELLER_APPLY, 4) // must not undo the detector tap
        b.toggleMaskBit(MaskField.CROSSFEED_PAIRS, 0)
        b.toggleMaskBit(MaskField.CROSSFEED_PAIRS, 1)
        b.toggleMaskBit(MaskField.PSYBASS_OUTPUTS, 5)
        b.toggleMaskBit(MaskField.PSYBASS_OUTPUTS, 6)
        b.toggleMaskBit(MaskField.SUBHARM_OUTPUTS, 1)
        b.toggleMaskBit(MaskField.SUBHARM_OUTPUTS, 2)
        b.toggleMaskBit(MaskField.TUBE_OUTPUTS, 0)
        b.toggleMaskBit(MaskField.TUBE_OUTPUTS, 3)
        b.toggleMaskBit(MaskField.LOUDNESS_OUTPUTS, 2) // and off again
        runCurrent()
        assertEquals(0b1001, b.loudnessOutputMask)
        assertEquals(0b10, b.levellerDetectorMask)
        assertEquals(0b10000, b.levellerApplyMask)
        assertEquals(0b11, b.crossfeedOutputPairMask)
        assertEquals(0b1100000, b.psybassOutputMask)
        assertEquals(0b110, b.subharmParams[DspiConstants.SUBHARM_PARAM_MASK].toInt())
        assertEquals(0b1001, b.tubeParams[DspiConstants.TUBE_PARAM_MASK].toInt())
    }

    @Test
    fun connectThenInvertBeforeAPublishKeepsTheConnection() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setMatrixRoute(1, 3, false, -6f, false)
        runCurrent()
        b.toggleMatrixRoute(1, 3, polarity = false) // connect
        b.toggleMatrixRoute(1, 3, polarity = true) // INV, same frame
        runCurrent()
        assertTrue(b.matrixRouting(1, 3))
        assertTrue(b.matrixInvert(1, 3))
        assertEquals(-6f, b.matrixGain(1, 3), 1e-6f)
        b.toggleMatrixRoute(1, 3, polarity = false)
        b.toggleMatrixRoute(1, 3, polarity = false) // double tap: no net change
        runCurrent()
        assertTrue(b.matrixRouting(1, 3))
    }

    @Test
    fun aGainWrittenRightAfterAConnectKeepsTheConnection() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setMatrixRoute(1, 3, false, 0f, false)
        runCurrent()
        b.toggleMatrixRoute(1, 3, polarity = false) // connect
        b.toggleMatrixRoute(1, 3, polarity = true) // INV
        b.setMatrixGain(1, 3, -4f, sendOnly = true) // drag, same frame
        b.setMatrixGain(1, 3, -5f) // commit
        runCurrent()
        assertTrue(b.matrixRouting(1, 3))
        assertTrue(b.matrixInvert(1, 3))
        assertEquals(-5f, b.matrixGain(1, 3), 1e-6f)
    }
}

package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.SpdifRxStatus
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

@OptIn(ExperimentalCoroutinesApi::class)
class WaveBTest {
    // ── revertOutputConfig (macOS SettingsSaveCoordinator.revert) ──

    @Test
    fun revertReappliesTheBaselineInTheMacOsOrder() = runTest {
        val core = FakeDspiCore(demoDspState().copy(outputConfigMode = 0, i2sInputChannels = 4))
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        assertFalse(b.revertOutputConfig(ops.confirm(DangerousOp.HARDWARE_APPLY))) // nothing pending
        b.beginOutputEdit()
        assertTrue(b.outputConfigDirty.value)
        val hw = ops.confirm(DangerousOp.HARDWARE_APPLY)
        b.setOutputType(hw, 1, 1)
        b.setOutputPin(hw, 0, 20)
        b.setI2sInputChannels(hw, 2)
        b.setMckPin(hw, 21)
        b.setLimiterThreshold(2, -9f)
        b.setLimiterLinkGroup(2, 3)
        runCurrent()
        assertEquals(20, b.outputPin(0))
        val mark = core.calls.size
        assertTrue(b.revertOutputConfig(ops.confirm(DangerousOp.HARDWARE_APPLY)))
        runCurrent()
        assertFalse(b.outputConfigDirty.value)
        val s = b.state.value
        assertEquals(6, s.outputPins[0])
        assertEquals(0, s.outputTypes[1])
        assertEquals(4, s.i2sInputChannels)
        assertEquals(13, s.mckPin)
        assertEquals(0f, s.limiterThresholdDb[2])
        assertEquals(0, s.limiterLinkGroup[2])
        // Order: slot types, pins, MCK, I2S channels up last, limiters after the hardware
        val seq = core.calls.drop(mark).map { it.substringBefore('(') }
        val order = listOf("setOutputType", "setOutputPin", "setMckPin", "setI2sInputChannels", "setLimiterParam")
        assertEquals(order, order.sortedBy { seq.indexOf(it) })
        assertTrue(order.all { it in seq })
        assertTrue("only what differs is written", "setI2sBckPin" !in seq && "setAdatOutPin" !in seq)
    }

    @Test
    fun revertNeedsAHardwareApplyToken() = runTest {
        val core = FakeDspiCore(demoDspState().copy(outputConfigMode = 0))
        val b = newBridge(core)
        attach(b, core)
        b.beginOutputEdit()
        val wrong = b.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
        assertTrue(runCatching { b.revertOutputConfig(wrong) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertTrue(b.outputConfigDirty.value)
    }

    // ── Limiter ganging (macOS LimiterGang) ──

    @Test
    fun aSetOnALinkedOutputReachesItsGroupInOneEmission() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setLimiterThreshold(0, -6f)
        b.setLimiterThreshold(1, -12f)
        b.setLimiterLinkGroup(0, 1) // first member keeps its own
        runCurrent()
        assertEquals(-6f, b.limiterThreshold(0))
        b.setLimiterLinkGroup(1, 1) // joining adopts the lowest member's settings
        runCurrent()
        assertEquals(-6f, b.limiterThreshold(1))
        val rev = b.stateRevision.value
        b.setLimiterThreshold(1, -3f)
        b.setLimiterEnabled(0, true)
        runCurrent()
        assertEquals(rev + 2, b.stateRevision.value) // one emission per SET
        assertEquals(listOf(-3f, -3f), listOf(b.limiterThreshold(0), b.limiterThreshold(1)))
        assertTrue(b.limiterEnabled(0) && b.limiterEnabled(1))
        assertFalse(b.limiterEnabled(2))
        b.setLimiterLinkGroup(1, 0) // leaving keeps the current values
        b.setLimiterRelease(0, 300f)
        runCurrent()
        assertEquals(-3f, b.limiterThreshold(1))
        assertEquals(300f, b.limiterRelease(0))
        assertEquals(100f, b.limiterRelease(1))
    }

    @Test
    fun aLiveDragSendsToTheDeviceOnlyAndTheCommitGangs() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setLimiterLinkGroup(0, 2)
        b.setLimiterLinkGroup(1, 2)
        runCurrent()
        val n = core.callCount("setLimiterParam")
        b.setLimiterThreshold(0, -8f, sendOnly = true)
        runCurrent()
        assertEquals(n + 1, core.callCount("setLimiterParam"))
        b.setLimiterThreshold(0, -8f)
        runCurrent()
        assertEquals(-8f, b.limiterThreshold(1))
    }

    // ── directRouting / clearRouting (macOS applyDirectRouting / clearAllRoutes, E5) ──

    @Test
    fun directRoutingResetsTheActiveRowsOnly() = runTest {
        val core = FakeDspiCore(demoDspState().copy(outputEnabled = List(9) { it == 0 }))
        val b = newBridge(core)
        attach(b, core)
        b.setMatrixRoute(0, 3, true, -6f, true)
        b.setMatrixRoute(5, 5, true, -2f, false) // row 5: outside the 2 active rows
        runCurrent()
        b.setChannelVisible(3, false)
        b.directRouting(rows = 2)
        runCurrent()
        assertTrue(b.matrixRouting(0, 0) && b.matrixRouting(1, 1))
        assertFalse(b.matrixRouting(0, 3))
        assertEquals(0f, b.matrixGain(0, 3))
        assertFalse(b.matrixInvert(0, 3))
        assertFalse(b.matrixRouting(1, 0) || b.matrixRouting(0, 1)) // demo routed USB L/R to 0..3: off-diagonal cleared
        assertTrue(b.matrixRouting(5, 5))
        assertEquals(-2f, b.matrixGain(5, 5))
        assertTrue(b.outputEnabled(0) && b.outputEnabled(1))
        assertTrue(b.channelVisible(3)) // output 2 switched on in overview: shown
        b.clearRouting(rows = 2)
        runCurrent()
        assertFalse(b.matrixRouting(0, 0) || b.matrixRouting(1, 1))
        assertTrue(b.matrixRouting(5, 5))
    }

    @Test
    fun effectiveInputsFollowTheSource() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        advanceTimeBy(61) // status: the fake reports 8 active USB channels
        assertEquals(8, b.effectiveInputChannels)
        b.setInputSource(b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY), 1)
        runCurrent()
        assertEquals(2, b.effectiveInputChannels) // S/PDIF
        b.setInputSource(b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY), 3)
        runCurrent()
        assertEquals(8, b.effectiveInputChannels) // ADAT
    }

    // ── UI additions ──

    @Test
    fun spdifReceiverReadings() = runTest {
        val cs = listOf(0x04, 0x01, 0, 0, 0x0B) + List(19) { 0 }
        val core =
            object : FakeDspiCore() {
                override fun fetchSpdifRxStatus() = SpdifRxStatus(2, 4, 1, 0, 48_000, 0, 50, 3, 10, 0)

                override fun fetchSpdifRxChannelStatus() = cs
            }
        val b = newBridge(core)
        attach(b, core)
        assertEquals(48_000L, b.fetchSpdifRxStatus()?.sampleRate)
        val st = b.fetchSpdifChannelStatus()!!
        assertTrue(st.consumer && st.pcm && st.copyPermitted)
        assertEquals("CD Player", st.category)
        assertEquals("24-bit", st.wordLength)
        assertEquals(20, b.fetchActiveSpdifRxPin()) // source 4 = S/PDIF 2 = RX index 1
    }

    @Test
    fun noChannelStatusWithoutLock() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertNull(b.fetchSpdifChannelStatus())
        assertEquals(5, b.fetchActiveSpdifRxPin())
    }

    @Test
    fun capabilitiesAndSiggenSupport() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertEquals(HardwareCaps(), b.hardwareCaps)
        attach(b, core)
        runCurrent()
        val caps = b.hardwareCaps
        assertTrue(caps.multiSpdifSupported && caps.i2sInputSupported && caps.i2sClockPinModeSupported && caps.limiterSupported)
        assertTrue(caps.adatOutSupported && caps.adatInputSupported && caps.i2sMultichannelInput)
        assertEquals(4, caps.spdifInputCount)
        assertTrue(b.siggenSupported)
        val c2040 =
            FakeDspiCore(
                se.bofr.dspi.fake.FakeDspiCore.factoryOf(
                    demoDspState()
                ).copy(platformId = 0, numChannels = 7, numInputChannels = 2, numOutputChannels = 5)
            )
        val b2 = newBridge(c2040)
        attach(b2, c2040)
        assertFalse(b2.hardwareCaps.adatOutSupported || b2.hardwareCaps.i2sMultichannelInput)
        assertEquals(DspiConstants.COMPAT_OK, b2.compat)
    }
}

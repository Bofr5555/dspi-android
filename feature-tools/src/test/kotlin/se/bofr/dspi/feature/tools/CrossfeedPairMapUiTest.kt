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
import se.bofr.dspi.bridge.CrossfeedPairs
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/** K340 fork: the crossfeed "Pairs" row, its custom editor and the pair-labelled chips, over FakeDspiCore. */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfeedPairMapUiTest {
    private class Rig(val core: FakeDspiCore, val bridge: DspiBridgeImpl) {
        val actions = CrossfeedPairActions(bridge)

        fun model() = CrossfeedPairsModel.from(
            bridge.state.value,
            supported = bridge.hardwareCaps.crossfeedPairMapSupported,
            connected = bridge.connected,
            writesAllowed = bridge.writesAllowed.value,
            outputName = { bridge.channelName(2 + it) }
        )

        fun mapWrites() = core.calls.filter { it.startsWith("setCrossfeedPairMap(") }
    }

    private suspend fun TestScope.rig(core: FakeDspiCore = FakeDspiCore()): Rig {
        val d = StandardTestDispatcher(testScheduler)
        val b = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
        runCurrent()
        core.calls.clear()
        return Rig(core, b)
    }

    @Test
    fun pickerSendsExactlyOneWholeMap() = runTest {
        val r = rig()
        assertEquals(CrossfeedPairChoice.DEFAULT, r.model().choice)
        r.actions.choose(CrossfeedPairChoice.ONE_DAC_PER_EAR)
        runCurrent()
        assertEquals(listOf("setCrossfeedPairMap(2,19,0,0)"), r.mapWrites())
        assertEquals(CrossfeedPairChoice.ONE_DAC_PER_EAR, r.model().choice)
        assertEquals(0, r.core.callCount("setCrossfeedOutputs")) // no implicit mask write
        r.core.calls.clear()
        r.actions.choose(CrossfeedPairChoice.DEFAULT)
        runCurrent()
        assertEquals(listOf("setCrossfeedPairMap(0,0,0,0)"), r.mapWrites())
        assertEquals(CrossfeedPairChoice.DEFAULT, r.model().choice)
        // "Custom…" only opens the editor
        r.core.calls.clear()
        r.actions.choose(CrossfeedPairChoice.CUSTOM)
        runCurrent()
        assertTrue(r.mapWrites().isEmpty())
    }

    @Test
    fun theK340OfferSetsBothPairsOnExplicitTap() = runTest {
        val r = rig()
        r.bridge.setCrossfeedOutputPairMask(0x00)
        r.actions.choose(CrossfeedPairChoice.ONE_DAC_PER_EAR)
        runCurrent()
        assertEquals(0x00, r.bridge.crossfeedOutputPairMask)
        r.actions.enableK340Pairs()
        runCurrent()
        assertEquals(0x03, r.bridge.crossfeedOutputPairMask)
    }

    @Test
    fun customMapAppliesOnceAndInvalidMapsCannotBeApplied() = runTest {
        val r = rig()
        val pairs = listOf(0 to 1, 2 to 3, 4 to 5, 6 to 7)
        assertTrue(r.actions.isValid(pairs))
        // Output 3 in two pairs / a pair on one output / the PDM sub: invalid, nothing sent
        val dup = listOf(2 to 0, 2 to 3, 4 to 5, 6 to 7)
        val same = listOf(1 to 1, 2 to 3, 4 to 5, 6 to 7)
        val pdm = listOf(0 to 8, 2 to 3, 4 to 5, 6 to 7)
        for (bad in listOf(dup, same, pdm)) {
            assertFalse(r.actions.isValid(bad))
            assertFalse(r.actions.applyCustom(bad))
        }
        runCurrent()
        assertTrue(r.mapWrites().isEmpty())
        // A valid custom map: one whole-map write, defaults encoded 0
        val custom = listOf(1 to 0, 3 to 2, 4 to 5, 7 to 6)
        assertTrue(r.actions.applyCustom(custom))
        runCurrent()
        assertEquals(listOf("setCrossfeedPairMap(1,35,0,103)"), r.mapWrites())
        assertEquals(CrossfeedPairChoice.CUSTOM, r.model().choice)
        assertEquals("Custom", r.model().choiceTitle)
    }

    @Test
    fun chipLabelsFollowTheMap() = runTest {
        val r = rig()
        var m = r.model()
        assertEquals(4, m.pairCount)
        assertEquals("Pair 1: Out 1 / Out 2", m.chipLabels[0])
        r.actions.choose(CrossfeedPairChoice.ONE_DAC_PER_EAR)
        runCurrent()
        m = r.model()
        assertEquals(
            listOf("Pair 1: Out 3 / Out 1", "Pair 2: Out 4 / Out 2", "Pair 3: Out 5 / Out 6", "Pair 4: Out 7 / Out 8"),
            m.chipLabels
        )
        assertEquals("${r.bridge.channelName(4)} / ${r.bridge.channelName(2)}", m.chipHelp[0])
        // The editor lists every output but the PDM sub
        assertEquals((0 until 8).toList(), m.selectableOutputs)
        assertEquals("3 — ${r.bridge.channelName(4)}", m.outputLabels[2])
    }

    @Test
    fun unsupportedFirmwareHasNoPairsRowAndFixedPairs() = runTest {
        val core = FakeDspiCore().apply { crossfeedPairMapSupported = false }
        val r = rig(core)
        val m = r.model()
        assertFalse(m.pairMapSupported)
        assertEquals(listOf("1", "2", "3", "4"), m.chipLabels) // the macOS chips
        assertEquals("${r.bridge.channelName(2)} / ${r.bridge.channelName(3)}", m.chipHelp[0])
        r.actions.choose(CrossfeedPairChoice.ONE_DAC_PER_EAR)
        assertFalse(r.actions.applyCustom(listOf(2 to 0, 3 to 1)))
        runCurrent()
        assertTrue(r.mapWrites().isEmpty())
    }

    @Test
    fun readOnlyDisablesEverything() = runTest {
        val r = rig(FakeDspiCore(demoDspState().copy(compat = DspiConstants.COMPAT_FIRMWARE_TOO_OLD)))
        assertFalse(r.bridge.writesAllowed.value)
        assertFalse(r.model().enabled)
        r.actions.choose(CrossfeedPairChoice.ONE_DAC_PER_EAR)
        assertFalse(r.actions.applyCustom(listOf(2 to 0, 3 to 1)))
        r.actions.enableK340Pairs()
        runCurrent()
        assertTrue(r.mapWrites().isEmpty())
        assertEquals(0, r.core.callCount("setCrossfeedOutputs"))
        // The model also follows writesAllowed alone (LocalWritesAllowed in the view)
        val ok = rig()
        val st = ok.bridge.state.value
        assertFalse(CrossfeedPairsModel.from(st, true, connected = true, writesAllowed = false) { "" }.enabled)
        assertFalse(CrossfeedPairsModel.from(st, true, connected = false, writesAllowed = true) { "" }.enabled)
        assertTrue(CrossfeedPairsModel.from(st, true, connected = true, writesAllowed = true) { "" }.enabled)
    }

    @Test
    fun mapEncodingKeepsDefaultsZero() {
        assertEquals(CrossfeedPairs.ONE_DAC_PER_EAR, CrossfeedPairActions.mapOf(listOf(2 to 0, 3 to 1, 4 to 5, 6 to 7)))
        assertEquals(CrossfeedPairs.DEFAULT, CrossfeedPairActions.mapOf(listOf(0 to 1, 2 to 3)))
    }
}

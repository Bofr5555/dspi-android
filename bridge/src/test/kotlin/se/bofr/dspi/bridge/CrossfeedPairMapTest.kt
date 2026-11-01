package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/** K340 firmware fork: crossfeed pair map (core patch 0002). */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossfeedPairMapTest {
    @Test
    fun helpersDecodeTheFirmwareEncoding() {
        assertEquals(listOf(0x02, 0x13, 0, 0), CrossfeedPairs.ONE_DAC_PER_EAR)
        assertEquals(2 to 0, CrossfeedPairs.pair(CrossfeedPairs.ONE_DAC_PER_EAR, 0))
        assertEquals(3 to 1, CrossfeedPairs.pair(CrossfeedPairs.ONE_DAC_PER_EAR, 1))
        assertEquals(4 to 5, CrossfeedPairs.pair(CrossfeedPairs.ONE_DAC_PER_EAR, 2))
        assertEquals(0 to 1, CrossfeedPairs.pair(CrossfeedPairs.DEFAULT, 0))
        assertEquals(CrossfeedPairs.ONE_DAC_PER_EAR, CrossfeedPairs.of(2 to 0, 3 to 1))
        assertTrue(CrossfeedPairs.samePairing(CrossfeedPairs.of(0 to 1, 2 to 3), CrossfeedPairs.DEFAULT))
        assertEquals(4, CrossfeedPairs.pairCount(9)) // RP2350
        assertEquals(2, CrossfeedPairs.pairCount(5)) // RP2040
    }

    @Test
    fun probedOnConnectAndWritten() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertFalse(b.hardwareCaps.crossfeedPairMapSupported)
        attach(b, core)
        assertTrue(core.callCount("fetchCrossfeedPairMap") >= 1) // once per device selection
        assertTrue(b.hardwareCaps.crossfeedPairMapSupported)
        assertEquals(4, b.hardwareCaps.crossfeedPairCount)
        assertEquals(CrossfeedPairs.DEFAULT, b.crossfeedPairMap)
        assertTrue(b.crossfeedPairMapValid(CrossfeedPairs.ONE_DAC_PER_EAR))
        assertFalse(b.crossfeedPairMapValid(listOf(0x02, 0, 0, 0))) // output 3 twice
        val events = collectEvents(b)
        b.setCrossfeedPairMap(CrossfeedPairs.ONE_DAC_PER_EAR)
        runCurrent()
        assertTrue(core.calls.contains("setCrossfeedPairMap(2,19,0,0)"))
        assertEquals(CrossfeedPairs.ONE_DAC_PER_EAR, b.crossfeedPairMap)
        assertEquals(CrossfeedPairs.ONE_DAC_PER_EAR, b.state.value.crossfeedPairMap)
        assertTrue(events.filterIsInstance<BridgeEvent.CommandFailed>().isEmpty())
        // Disconnect clears the capability
        b.detachDevice(core.serial)
        runCurrent()
        assertFalse(b.hardwareCaps.crossfeedPairMapSupported)
    }

    @Test
    fun anInvalidMapFailsWithoutChangingTheState() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val events = collectEvents(b)
        b.setCrossfeedPairMap(listOf(0x11, 0, 0, 0))
        runCurrent()
        val e = events.filterIsInstance<BridgeEvent.CommandFailed>().single()
        assertEquals("setCrossfeedPairMap", e.command)
        assertTrue(e.error is DspiException.InvalidArgument)
        assertEquals(CrossfeedPairs.DEFAULT, b.crossfeedPairMap)
        assertEquals(0, b.guardedCore.trips)
    }

    @Test
    fun upstreamFirmwareIsUnsupportedAndNeverSent() = runTest {
        val core = FakeDspiCore()
        core.crossfeedPairMapSupported = false
        val b = newBridge(core)
        attach(b, core)
        assertTrue(b.writesAllowed.value) // the rest of the app is unaffected
        assertFalse(b.hardwareCaps.crossfeedPairMapSupported)
        val events = collectEvents(b)
        b.setCrossfeedPairMap(CrossfeedPairs.ONE_DAC_PER_EAR)
        runCurrent()
        val e = events.filterIsInstance<BridgeEvent.CommandFailed>().single()
        assertTrue(e.error is DspiException.Unsupported)
        assertEquals(0, core.callCount("setCrossfeedPairMap"))
    }

    @Test
    fun readOnlyFirmwareIsNotProbed() = runTest {
        val core = FakeDspiCore(demoDspState().copy(compat = DspiConstants.COMPAT_FIRMWARE_TOO_OLD))
        val b = newBridge(core)
        attach(b, core)
        assertEquals(0, core.callCount("fetchCrossfeedPairMap"))
        assertFalse(b.hardwareCaps.crossfeedPairMapSupported)
    }

    @Test
    fun aWholeMapIsSentAsTheCallerSawIt() = runTest {
        // Stale-state safety: no read-modify-write, the list is copied on the call
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val map = CrossfeedPairs.ONE_DAC_PER_EAR.toMutableList()
        b.setCrossfeedPairMap(map)
        map[0] = 0x11 // the caller mutates its list before the FFI thread runs
        b.setCrossfeedPairMap(CrossfeedPairs.DEFAULT)
        runCurrent()
        val sent = core.calls.filter { it.startsWith("setCrossfeedPairMap(") }
        assertEquals(listOf("setCrossfeedPairMap(2,19,0,0)", "setCrossfeedPairMap(0,0,0,0)"), sent)
        assertEquals(CrossfeedPairs.DEFAULT, b.crossfeedPairMap)
    }

    @Test
    fun theProbeRetriesATransientErrorButNotAStall() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        runCurrent()
        core.failNext("fetchCrossfeedPairMap", 1) { DspiException.Disconnected() }
        attach(b, core)
        assertTrue(b.hardwareCaps.crossfeedPairMapSupported)
        val core2 = FakeDspiCore()
        val b2 = newBridge(core2)
        runCurrent()
        val before = core2.callCount("fetchCrossfeedPairMap")
        core2.failNext("fetchCrossfeedPairMap", 1) // Transfer = a STALL
        attach(b2, core2)
        assertFalse(b2.hardwareCaps.crossfeedPairMapSupported)
        assertEquals(before + 1, core2.callCount("fetchCrossfeedPairMap"))
    }

    @Test
    fun theK340MaskCoversBothPairs() {
        assertEquals(0x03, CrossfeedPairs.ONE_DAC_PER_EAR_PAIR_MASK)
    }
}

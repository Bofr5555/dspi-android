package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import se.bofr.dspi.fake.FakeDspiCore

@OptIn(ExperimentalCoroutinesApi::class)
class StubDspiBridgeTest {
    @Test
    fun setterReachesCoreAndEmits() = runTest {
        val core = FakeDspiCore()
        val bridge = StubDspiBridge(core, this, StandardTestDispatcher(testScheduler))
        assertEquals("1.1.6-beta5", bridge.firmwareVersion)
        bridge.setMasterVolume(-6f)
        advanceUntilIdle()
        assertEquals(-6f, bridge.masterVolumeDB)
        assertEquals(1L, bridge.stateRevision.value)
    }
}

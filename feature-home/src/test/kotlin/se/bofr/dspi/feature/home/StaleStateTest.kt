package se.bofr.dspi.feature.home

import androidx.compose.runtime.Composable
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/**
 * reviews/stale-state-audit.md H1-H3: a composable that reads plain bridge
 * getters is recomposed when they change, even when its parent calls it
 * with the same arguments (strong skipping) and the change brings no new
 * DspState (input links).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleStateTest {
    private suspend fun TestScope.connectedBridge(): DspiBridgeImpl {
        val core = FakeDspiCore.seeded()
        val d = StandardTestDispatcher(testScheduler)
        val b = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        check(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        return b
    }

    /** InputChannelHeader's shape: observeState(), then a plain getter. */
    @Composable
    private fun LinkProbe(bridge: DspiBridge, seen: MutableList<Boolean>) {
        bridge.observeState()
        seen += bridge.isInputLinked(0)
    }

    @Test
    fun linkChangeWithoutANewStateRecomposesTheReader() = runTest {
        val b = connectedBridge()
        assertTrue(b.pairAvailable(0))
        val seen = mutableListOf<Boolean>()
        CompositionHarness().use { h ->
            h.setContent { LinkProbe(b, seen) }
            val before = seen.last()
            b.restoreInputLinks(b.inputLinks xor 1) // bumps stateRevision only
            h.frame()
            assertNotEquals("the link pill follows the link", before, seen.last())
            b.restoreInputLinks(b.inputLinks xor 1)
            h.frame()
            assertEquals(before, seen.last())
        }
    }
}

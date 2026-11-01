package se.bofr.dspi.feature.home

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.feature.home.components.InputPairMismatch
import se.bofr.dspi.feature.presets.PresetMenuActions
import se.bofr.dspi.ui.alert.AlertHost

/**
 * The sidebar picker drives PRESETS' controller (F1); these check the wiring
 * HOME relies on, plus the link-mismatch rule (review H5).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {
    private suspend fun TestScope.connectedBridge(): DspiBridgeImpl {
        val core = FakeDspiCore.seeded()
        val d = StandardTestDispatcher(testScheduler)
        val b = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        check(b.attachDevice(42, core.serial, DspiConstants.VENDOR_ID))
        runCurrent()
        return b
    }

    /** Let queued FFI work and the 30 ms notify batching land (the 60 ms poll never idles). */
    private fun TestScope.settle() {
        runCurrent()
        advanceTimeBy(500)
        runCurrent()
    }

    @Test
    fun dirtySwitchShowsUnsavedChangesAndCancelKeepsTheSlot() = runTest {
        val b = connectedBridge()
        val host = AlertHost()
        val menu = PresetMenuActions(b, backgroundScope, host)
        val before = b.activePresetSlot
        b.setBypass(!b.bypass)
        settle()
        assertTrue(b.presetDirty.value)
        menu.selectPreset((before + 1) % DspiConstants.MAX_PRESETS)
        settle()
        val alert = host.alert.value!!
        assertEquals("Unsaved Changes", alert.messageText)
        assertTrue(menu.presetSwitchInFlight.value)
        host.dismiss()
        settle()
        assertNull(host.alert.value)
        assertFalse(menu.presetSwitchInFlight.value)
        assertEquals(before, b.activePresetSlot)
    }

    @Test
    fun menuSaveActsOnTheTap() = runTest {
        val b = connectedBridge()
        val host = AlertHost()
        val menu = PresetMenuActions(b, backgroundScope, host)
        b.setBypass(!b.bypass)
        settle()
        menu.saveActivePreset()
        settle()
        assertNull(host.alert.value)
        assertFalse(b.presetDirty.value)
    }

    @Test
    fun linkMismatchFollowsMacOs() = runTest {
        val b = connectedBridge()
        b.setInputPreamp(0, -6f)
        b.setInputPreamp(1, 0f)
        settle()
        assertTrue(InputPairMismatch.of(b.state.value, 0, 1).preamp)
        b.setInputPreamp(1, -6.04f)
        settle()
        // Within 0.05 dB: trims are stored rounded to 0.1 dB, the rest is noise.
        assertFalse(InputPairMismatch.of(b.state.value, 0, 1).preamp)
    }
}

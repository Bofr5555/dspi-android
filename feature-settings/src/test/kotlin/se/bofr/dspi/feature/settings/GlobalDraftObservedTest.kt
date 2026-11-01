package se.bofr.dspi.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/**
 * reviews/stale-state-audit.md S2: the Global tab, the save bar and the
 * sidebar dot read the draft during composition; every edit (not only the
 * first, which flips globalUserEdited) must invalidate them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GlobalDraftObservedTest {
    @Test
    fun everyGlobalEditRecomposesItsReaders() = runTest {
        val d = StandardTestDispatcher(testScheduler)
        val core = FakeDspiCore()
        val bridge = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        bridge.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
        runCurrent()
        val c = SettingsSaveCoordinator(bridge, backgroundScope)
        runCurrent()
        val device = c.globalDraft().presetDefaultSlot
        // The Global tab's composition: it also reads globalUserEdited, as GlobalSettingsTab does.
        val tab = ObservedRead { c.globalUserEdited.value to c.globalDraft().presetDefaultSlot }
        val dot = ObservedRead { c.globalDirty() }
        val a = (device + 1) % DspiConstants.MAX_PRESETS
        val b = (device + 2) % DspiConstants.MAX_PRESETS
        c.editGlobal { it.copy(presetDefaultSlot = a) }
        assertEquals(a, tab.value.second)
        assertTrue(dot.value)
        c.editGlobal { it.copy(presetDefaultSlot = b) } // the second edit: globalUserEdited stays true
        assertEquals("the second pick shows", b, tab.value.second)
        c.editGlobal { it.copy(presetDefaultSlot = device) } // back to the device's value
        assertFalse("nothing left to save", dot.value)
        tab.close()
        dot.close()
    }
}

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

@OptIn(ExperimentalCoroutinesApi::class)
class PresetFlowsTest {
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
    fun cleanSwitchLoadsWithoutAlert() = runTest {
        val b = connectedBridge()
        assertTrue(b.connected)
        val flows = PresetFlows(b, backgroundScope)
        val target = (b.activePresetSlot + 1) % DspiConstants.MAX_PRESETS
        // Copy to… on a clean preset goes straight through (no alert on macOS).
        flows.requestCopy(target)
        settle()
        assertNull(flows.dialog)
        assertTrue(b.isPresetOccupied(target))
        flows.switchTo(target)
        settle()
        assertNull(flows.dialog)
        assertFalse(flows.switchInFlight)
        assertEquals(target, b.activePresetSlot)
    }

    @Test
    fun dirtySwitchAsksAndCancelKeepsSlot() = runTest {
        val b = connectedBridge()
        val flows = PresetFlows(b, backgroundScope)
        val before = b.activePresetSlot
        b.setBypass(!b.bypass)
        settle()
        assertTrue(b.presetDirty.value)
        flows.switchTo((before + 1) % DspiConstants.MAX_PRESETS)
        settle()
        assertTrue(flows.dialog is PresetDialog.Unsaved)
        assertTrue(flows.switchInFlight)
        flows.dismiss()
        settle()
        assertNull(flows.dialog)
        assertFalse(flows.switchInFlight)
        assertEquals(before, b.activePresetSlot)
    }

    @Test
    fun saveFromMenuMintsItsOwnToken() = runTest {
        val b = connectedBridge()
        val flows = PresetFlows(b, backgroundScope)
        b.setBypass(!b.bypass)
        settle()
        flows.save()
        settle()
        assertNull(flows.dialog)
        assertFalse(b.presetDirty.value)
        assertTrue(b.isPresetOccupied(b.activePresetSlot))
    }

    @Test
    fun linkMismatchDetectsPreamp() = runTest {
        val b = connectedBridge()
        b.setInputPreamp(0, -6f)
        b.setInputPreamp(1, 0f)
        settle()
        val m = InputPairMismatch.of(b.state.value, 0, 1)
        assertTrue(m.preamp)
    }
}

package se.bofr.dspi.feature.presets

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiException

@OptIn(ExperimentalCoroutinesApi::class)
class ToolsMenuActionsTest {
    @Test
    fun commitParametersSavesTheActiveSlot() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        b.setBypass(true)
        runCurrent()
        t.commitParameters()
        val alert = host.alert.value!!
        assertEquals("Save Preset", alert.messageText)
        assertEquals("Save current parameters to preset slot 1?", alert.informativeText)
        host.press("Save")
        runCurrent()
        assertEquals(listOf("savePreset(0)"), core.presetCalls())
        assertEquals("Success", host.alert.value!!.messageText)
        assertEquals("Preset saved successfully", host.alert.value!!.informativeText)
        assertFalse(b.presetDirty.value)
    }

    @Test
    fun commitParametersReportsAFailure() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        core.failNext("savePreset", error = { DspiException.Refused(4) })
        t.commitParameters()
        host.press("Save")
        runCurrent()
        assertEquals("Error", host.alert.value!!.messageText)
        assertEquals("Failed to save preset (error 4)", host.alert.value!!.informativeText)
    }

    @Test
    fun notConnected() = runTest {
        val core = presetFake()
        val b = detached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        t.commitParameters()
        assertEquals("Not connected to device", host.alert.value!!.informativeText)
        host.press("OK")
        t.saveMasterVolume()
        assertEquals("No device connected.", host.alert.value!!.informativeText)
        host.press("OK")
        t.revertToSaved()
        host.press("Revert")
        assertEquals("Not connected to device", host.alert.value!!.informativeText)
        assertTrue(core.presetCalls().isEmpty())
    }

    @Test
    fun revertToSaved() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        b.setBypass(true)
        runCurrent()
        t.revertToSaved()
        assertEquals("Revert to last saved parameters?\n\nCurrent unsaved changes will be lost.", host.alert.value!!.informativeText)
        host.press("Revert")
        runCurrent()
        assertEquals(listOf("loadParams"), core.presetCalls())
        assertEquals("Parameters reverted successfully", host.alert.value!!.informativeText)
        assertFalse(b.presetDirty.value)
    }

    @Test
    fun factoryReset() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        t.factoryReset()
        assertEquals("Factory Reset", host.alert.value!!.messageText)
        assertEquals(AlertButtonRole.DESTRUCTIVE, host.alert.value!!.button("Reset").role)
        host.press("Reset")
        runCurrent()
        assertEquals(listOf("factoryReset"), core.presetCalls())
        assertEquals("Factory reset complete", host.alert.value!!.informativeText)
    }

    @Test
    fun saveMasterVolumeAndOutputConfigurationActOnTheTap() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val t = ToolsMenuActions(b, this, host)
        // macOS: no confirmation, only the result alert (review P3, decision E1)
        t.saveMasterVolume()
        assertNull(host.alert.value)
        runCurrent()
        assertEquals("Success", host.alert.value!!.messageText)
        assertTrue(host.alert.value!!.informativeText.startsWith("Master volume saved ("))
        assertTrue(host.alert.value!!.informativeText.endsWith("). It will be applied on next boot."))
        host.press("OK")
        t.saveOutputConfig()
        assertNull(host.alert.value)
        runCurrent()
        assertEquals("Output configuration saved. It will be applied on next boot.", host.alert.value!!.informativeText)
        assertEquals(listOf("saveMasterVolume", "saveOutputConfig"), core.presetCalls())
    }

    @Test
    fun saveMasterVolumeFailure() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        core.failNext("saveMasterVolume", error = { DspiException.Refused(4) })
        ToolsMenuActions(b, this, host).saveMasterVolume()
        runCurrent()
        assertEquals("Failed to save master volume — the device did not acknowledge the request.", host.alert.value!!.informativeText)
    }
}

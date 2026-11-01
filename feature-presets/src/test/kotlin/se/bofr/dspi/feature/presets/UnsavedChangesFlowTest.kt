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
import se.bofr.dspi.core.PresetStatus

/**
 * The preset switch / Copy to state machine of ContentView.swift
 * (`unsaved_changes_spec.md`) against the real bridge over FakeDspiCore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsavedChangesFlowTest {
    private fun kotlinx.coroutines.test.TestScope.makeDirty(b: se.bofr.dspi.bridge.DspiBridge) {
        b.setBypass(true)
        runCurrent()
        assertTrue("bypass makes the preset dirty", b.presetDirty.value)
    }

    @Test
    fun cleanSwitchLoadsWithoutAsking() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.selectPreset(1)
        runCurrent()
        assertNull(host.alert.value)
        assertEquals(listOf("loadPreset(1)"), core.presetCalls())
        assertEquals(1, b.activePresetSlot)
        assertFalse(a.presetSwitchInFlight.value)
    }

    @Test
    fun selectingTheActiveSlotDoesNothing() = runTest {
        val core = presetFake()
        val b = attached(core)
        val a = PresetMenuActions(b, this, AlertHost())
        a.selectPreset(0)
        runCurrent()
        assertTrue(core.presetCalls().isEmpty())
    }

    @Test
    fun dirtySwitchShowsTheUnsavedChangesAlert() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        val alert = host.alert.value!!
        assertEquals("Unsaved Changes", alert.messageText)
        assertTrue(alert.informativeText.startsWith("The current preset has unsaved changes:\n\n• "))
        assertTrue(alert.informativeText.endsWith("\n\nSave before continuing?"))
        assertEquals(listOf("Save", "Discard", "Cancel"), alert.buttons.map { it.title })
        assertTrue(a.presetSwitchInFlight.value)
        // A second switch while the alert is up is ignored (presetSwitchInFlight)
        a.selectPreset(1)
        runCurrent()
        assertTrue(core.presetCalls().isEmpty())
    }

    @Test
    fun saveSavesThePreviousSlotThenLoads() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        host.press("Save")
        runCurrent()
        assertEquals(listOf("savePreset(0)", "loadPreset(1)"), core.presetCalls())
        assertNull(host.alert.value)
        assertEquals(1, b.activePresetSlot)
        assertFalse(b.presetDirty.value)
        assertFalse(a.presetSwitchInFlight.value)
    }

    @Test
    fun saveNamesAnUnnamedSlotPresetN() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.selectPreset(1)
        runCurrent()
        makeDirty(b)
        a.selectPreset(0)
        runCurrent()
        host.press("Save")
        runCurrent()
        assertEquals(listOf("loadPreset(1)", "setPresetName(1, Preset 2)", "savePreset(1)", "loadPreset(0)"), core.presetCalls())
    }

    @Test
    fun discardLoadsWithoutSaving() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        host.press("Discard")
        runCurrent()
        assertEquals(listOf("loadPreset(1)"), core.presetCalls())
        assertEquals(1, b.activePresetSlot)
    }

    @Test
    fun cancelStaysOnTheCurrentPreset() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        host.press("Cancel")
        runCurrent()
        assertTrue(core.presetCalls().isEmpty())
        assertEquals(0, b.activePresetSlot)
        assertTrue(b.presetDirty.value)
        assertFalse(a.presetSwitchInFlight.value)
    }

    @Test
    fun dismissingTheAlertCancels() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        host.dismiss()
        runCurrent()
        assertTrue(core.presetCalls().isEmpty())
        assertFalse(a.presetSwitchInFlight.value)
    }

    @Test
    fun aFailedSaveAbortsTheSwitch() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.selectPreset(1)
        runCurrent()
        core.failNext("savePreset", error = { DspiException.Refused(4) })
        host.press("Save")
        runCurrent()
        val alert = host.alert.value!!
        assertEquals("Save Failed", alert.messageText)
        assertEquals("Failed to save preset (error 4). Preset switch aborted.", alert.informativeText)
        assertFalse(core.presetCalls().any { it.startsWith("loadPreset") })
        assertEquals(0, b.activePresetSlot)
        assertFalse(a.presetSwitchInFlight.value)
    }

    @Test
    fun aFailedLoadSaysSo() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        core.failNext("loadPreset", error = { DspiException.Refused(3) })
        a.selectPreset(1)
        runCurrent()
        assertEquals("Load Failed", host.alert.value!!.messageText)
        assertEquals("Preset data is corrupted.", host.alert.value!!.informativeText)
    }

    @Test
    fun copyAsksToReplaceAnOccupiedSlotThenAboutUnsavedChanges() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.copyActivePreset(1)
        assertEquals("Replace \"2: Preset 2\"?", host.alert.value!!.messageText)
        host.press("Replace")
        runCurrent()
        assertEquals("Unsaved Changes", host.alert.value!!.messageText)
        host.press("Discard")
        runCurrent()
        assertEquals(listOf("loadPreset(0)", "setPresetName(1, Preset 2)", "copyPreset(0, 1)"), core.presetCalls())
        assertEquals(0, b.activePresetSlot)
        assertNull(host.alert.value)
    }

    @Test
    fun copyToAnEmptySlotWithSave() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        makeDirty(b)
        a.copyActivePreset(5)
        assertEquals("Copy Preset", host.alert.value!!.messageText)
        assertEquals("Copy the current settings into \"6: Empty\"? The current preset stays active.", host.alert.value!!.informativeText)
        host.press("Copy")
        runCurrent()
        host.press("Save")
        runCurrent()
        assertEquals(listOf("savePreset(0)", "setPresetName(5, Preset 6)", "copyPreset(0, 5)"), core.presetCalls())
        assertTrue(b.isPresetOccupied(5))
    }

    @Test
    fun copyWhenCleanDoesNotAskAboutChanges() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.copyActivePreset(1)
        host.press("Replace")
        runCurrent()
        assertNull(host.alert.value)
        assertEquals(listOf("setPresetName(1, Preset 2)", "copyPreset(0, 1)"), core.presetCalls())
    }

    @Test
    fun saveAsksThenSavesTheActiveSlot() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.saveActivePreset()
        assertEquals("Save current parameters to preset slot 1?", host.alert.value!!.informativeText)
        host.press("Save")
        runCurrent()
        assertEquals(listOf("savePreset(0)"), core.presetCalls())
        assertNull(host.alert.value)
    }

    @Test
    fun renameAndSetAsDefault() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.selectPreset(1)
        runCurrent()
        assertFalse(a.isActiveDefault)
        a.renameActivePreset()
        assertEquals("", host.alert.value!!.textField!!.initialText)
        host.press("Rename", AlertResponse(text = "  Headphones  "))
        a.setActiveAsDefault()
        runCurrent()
        assertEquals("Headphones", b.presetName(1))
        assertTrue(a.isActiveDefault)
        assertTrue(core.presetCalls().contains("setPresetStartup(0, 1)"))
    }

    @Test
    fun clearTheActiveSlotReloadsIt() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.clearPreset(0)
        val alert = host.alert.value!!
        assertEquals("Clear Preset?", alert.messageText)
        assertEquals("Clear \"1: Living Room\" and restore factory defaults? This cannot be undone.", alert.informativeText)
        host.press("Clear")
        runCurrent()
        assertEquals(listOf("deletePreset(0)", "loadPreset(0)"), core.presetCalls().take(2))
        assertFalse(b.isPresetOccupied(0))
    }

    @Test
    fun clearAllDeletesEveryOccupiedSlot() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        a.clearAllPresets()
        host.press("Clear All")
        runCurrent()
        assertEquals(listOf("deletePreset(0)", "deletePreset(1)", "loadPreset(0)"), core.presetCalls())
        assertEquals(0, b.presetOccupied)
    }

    @Test
    fun cancelledConfirmationsTouchNothing() = runTest {
        val core = presetFake()
        val b = attached(core)
        val host = AlertHost()
        val a = PresetMenuActions(b, this, host)
        for (open in listOf({ a.saveActivePreset() }, { a.copyActivePreset(3) }, { a.clearPreset(0) }, { a.clearAllPresets() })) {
            open()
            host.press("Cancel")
            runCurrent()
        }
        assertTrue(core.presetCalls().isEmpty())
        assertEquals(PresetStatus.OK, PresetStatus.of(0))
    }
}

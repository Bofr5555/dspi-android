// SettingsSaveCoordinator (macOS 1:1, gating per E1b) over DspiBridgeImpl +
// FakeDspiCore: live hardware apply, Save ordering, Revert to the baseline.
package se.bofr.dspi.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.ConfirmationToken
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSaveCoordinatorTest {
    private class Rig(val core: FakeDspiCore, val bridge: DspiBridgeImpl, val coordinator: SettingsSaveCoordinator) {
        /** Device writes after [mark]. */
        fun writes(mark: Int): List<String> = core.calls.drop(mark).filter { WRITE.containsMatchIn(it) }

        companion object {
            val WRITE = Regex("^(set|save|cs)")
        }
    }

    private suspend fun TestScope.rig(state: DspState = demoDspState()): Rig {
        val d = StandardTestDispatcher(testScheduler)
        val core = FakeDspiCore(state)
        val bridge = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        bridge.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
        runCurrent()
        val c = SettingsSaveCoordinator(bridge, backgroundScope)
        runCurrent()
        return Rig(core, bridge, c)
    }

    private val independent = demoDspState().copy(outputConfigMode = GlobalSettingsDraft.OUTPUT_CONFIG_MODE_INDEPENDENT)

    @Test
    fun aPickerAppliesAtOnceAndMarksTheOutputConfigDirty() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        val mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        runCurrent()
        assertEquals(listOf("setOutputPin(0, 0)"), r.writes(mark))
        assertTrue(c.outputConfigDirty.value)
        assertTrue(c.outputDirty())
        assertEquals(6, c.outputBaseline.outputPins[0])
        assertEquals(0, c.deviceSnapshot().outputPins[0])
        assertEquals("Output 1 GPIO set to GPIO 0" to false, c.hardwareStatus.value)
    }

    @Test
    fun theInteractionMintsAHardwareTokenThatIsRevokedAfterwards() = runTest {
        val r = rig()
        val c = r.coordinator
        var token: ConfirmationToken? = null
        c.applyHardware(
            HardwareChange("probe", "p", "Probe", "a", "b", apply = { t ->
                token = t
                0
            })
        )
        runCurrent()
        assertEquals(DangerousOp.HARDWARE_APPLY, token!!.op)
        assertFalse(r.bridge.dangerousOps.isValid(token!!))
    }

    @Test
    fun withPresetModeAppliesButNothingIsPending() = runTest {
        val r = rig()
        val c = r.coordinator
        val mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        assertEquals(listOf("setMckMultiplier(1)"), r.writes(mark))
        assertFalse(c.outputConfigDirty.value)
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun aNoOpChoiceSendsNothing() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        assertNull(c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 6)))
        assertFalse(c.outputConfigDirty.value)
    }

    @Test
    fun oneActionWithSeveralCallsIsOrderedSafely() = runTest {
        val r = rig()
        val c = r.coordinator
        var mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.spdifInputCount(c.deviceSnapshot(), 4))
        runCurrent()
        assertEquals(
            listOf("setSpdifInputEnabled(1, true)", "setSpdifInputEnabled(2, true)", "setSpdifInputEnabled(3, true)"),
            r.writes(mark)
        )
        mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.spdifInputCount(c.deviceSnapshot(), 2))
        runCurrent()
        assertEquals(listOf("setSpdifInputEnabled(3, false)", "setSpdifInputEnabled(2, false)"), r.writes(mark))
        // ADAT enable listed before its pin still goes after it
        mark = r.core.calls.size
        val dev = c.deviceSnapshot()
        c.applyHardware(listOf(c.hardwareChanges.adatOutEnabled(dev, true), c.hardwareChanges.adatOutPin(dev, 3)))
        runCurrent()
        assertEquals(listOf("setAdatOutPin(3)", "setAdatOutEnabled(true)"), r.writes(mark))
    }

    @Test
    fun aRefusalStopsTheActionWithTheMacOsMessage() = runTest {
        val r = rig()
        val c = r.coordinator
        val dev = c.deviceSnapshot()
        val mark = r.core.calls.size
        // Output 2 onto Output 1's pin: the fake refuses with PIN_IN_USE
        c.applyHardware(listOf(c.hardwareChanges.outputPin(dev, 1, 6), c.hardwareChanges.outputPin(dev, 2, 0)))
        runCurrent()
        assertEquals(listOf("setOutputPin(1, 6)"), r.writes(mark))
        assertEquals("GPIO 6 is already in use by another output" to true, c.hardwareStatus.value)
    }

    @Test
    fun saveWritesGlobalsThenFlashesTheOutputConfig() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 1, 0))
        runCurrent()
        c.editGlobal { it.copy(presetMasterVolumeMode = GlobalSettingsDraft.MASTER_VOLUME_MODE_WITH_PRESET) }
        val mark = r.core.calls.size
        val result = c.save()
        runCurrent()
        assertEquals(listOf("setMasterVolumeMode(1)", "saveOutputConfig"), r.writes(mark))
        assertTrue(result!!.ok)
        assertFalse(c.outputConfigDirty.value)
        assertFalse(c.globalUserEdited.value)
    }

    @Test
    fun withPresetModeSaveDoesNotFlashTheOutputConfig() = runTest {
        val r = rig()
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        c.editGlobal { it.copy(presetDefaultSlot = 3) }
        runCurrent()
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(listOf("setPresetStartup(0, 3)"), r.writes(mark))
    }

    @Test
    fun revertReappliesTheBaselineInTheMacOsOrder() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 1, 0))
        runCurrent()
        c.applyHardware(c.hardwareChanges.outputType(c.deviceSnapshot(), 1, OutputConfigSnapshot.OUTPUT_TYPE_I2S))
        runCurrent()
        val mark = r.core.calls.size
        c.onRevert()
        runCurrent()
        assertEquals(listOf("setOutputType(1, 0)", "setOutputPin(1, 7)", "setMckMultiplier(0)"), r.writes(mark))
        assertFalse(c.outputConfigDirty.value)
        assertEquals(c.outputBaseline.outputPins, c.deviceSnapshot().outputPins)
    }

    @Test
    fun revertOfTheGlobalDraftAloneSendsNothing() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetStartupMode = 1) }
        val mark = r.core.calls.size
        c.onRevert()
        runCurrent()
        assertEquals(emptyList<String>(), r.writes(mark))
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun aGlobalFieldEditedBackIsNotDirty() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetStartupMode = 1) }
        assertTrue(c.globalDirty())
        c.editGlobal { it.copy(presetStartupMode = 0) }
        assertFalse(c.globalDirty())
        assertEquals(null, c.save())
    }

    @Test
    fun anotherDeviceDiscardsPendingStateButARePlugKeepsIt() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.noteSelectedDevice(r.core.serial)
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        c.noteSelectedDevice(r.core.serial)
        assertTrue(c.outputConfigDirty.value)
        c.noteSelectedDevice("OTHER")
        assertFalse(c.outputConfigDirty.value)
    }

    @Test
    fun controlSurfacesSaveLastWithItsOwnToken() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        r.bridge.cs.refresh()
        runCurrent()
        r.bridge.cs.apply("""{"op":"name","index":0,"name":"Vol"}""")
        runCurrent()
        assertTrue(c.csDirty())
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        val w = r.writes(mark)
        assertEquals(listOf("saveOutputConfig"), w.filter { !it.startsWith("cs") })
        val csSave = w.indexOfFirst { it.startsWith("csApply") && it.contains("\"save\"") }
        assertTrue(csSave > w.indexOf("saveOutputConfig"))
        assertFalse(c.csBusy.value)
    }

    @Test
    fun pendingSummaryListsEveryCategory() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.editGlobal { it.copy(presetMasterVolumeMode = 1) }
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        assertEquals(
            listOf("Master volume: Independent → With Preset", "Input and output configuration (live now, not yet saved to flash)"),
            c.pendingSummary()
        )
    }
}

// Draft → apply ordering and gating of the Settings save bar
// (SettingsSaveCoordinator over DspiBridgeImpl + FakeDspiCore).
package se.bofr.dspi.feature.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsSaveCoordinatorTest {
    private class Rig(val core: FakeDspiCore, val bridge: DspiBridgeImpl, val coordinator: SettingsSaveCoordinator) {
        /** Core calls after [mark], hardware/config writes only. */
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
        return Rig(core, bridge, SettingsSaveCoordinator(bridge, backgroundScope))
    }

    private val independent = demoDspState().copy(outputConfigMode = GlobalSettingsDraft.OUTPUT_CONFIG_MODE_INDEPENDENT)

    @Test
    fun stagingSendsNothingToTheDevice() = runTest {
        val r = rig()
        val c = r.coordinator
        val mark = r.core.calls.size
        c.stage(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        c.editGlobal { it.copy(presetStartupMode = 1) }
        runCurrent()
        assertEquals(emptyList<String>(), r.writes(mark))
        assertTrue(c.hasPendingChanges())
        // The draft shows the staged value, the device keeps its own
        assertEquals(0, c.hardwareDraft().outputPins[0])
        assertEquals(6, c.deviceSnapshot().outputPins[0])
    }

    @Test
    fun saveAppliesGlobalThenHardwareInDependencyOrderThenSavesOutputConfig() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        val dev = c.deviceSnapshot()
        // Staged in an order the device would refuse: pin first, then the
        // type it depends on, then a BCK move that needs every slot S/PDIF
        c.stage(c.hardwareChanges.outputPin(dev, 1, 0))
        c.stage(c.hardwareChanges.i2sBckPin(dev, 18))
        c.stage(c.hardwareChanges.outputType(dev, 1, OutputConfigSnapshot.OUTPUT_TYPE_I2S))
        c.editGlobal { it.copy(presetMasterVolumeMode = GlobalSettingsDraft.MASTER_VOLUME_MODE_WITH_PRESET) }
        val mark = r.core.calls.size
        val result = c.save()
        runCurrent()
        assertEquals(
            listOf(
                "setMasterVolumeMode(1)",
                "setOutputType(1, 1)",
                "setOutputPin(1, 0)",
                "setI2sBckPin(0, 18)",
                "saveOutputConfig"
            ),
            r.writes(mark)
        )
        assertTrue(result!!.ok)
        assertEquals(0, c.hardware.count)
        assertFalse(c.globalUserEdited.value)
    }

    @Test
    fun withPresetModeAppliesHardwareButDoesNotSaveOutputConfig() = runTest {
        val r = rig() // demo: output config saved with the preset
        val c = r.coordinator
        c.stage(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(listOf("setMckMultiplier(1)"), r.writes(mark))
    }

    @Test
    fun switchingToIndependentInTheSameSaveFlashesTheHardware() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetOutputConfigMode = GlobalSettingsDraft.OUTPUT_CONFIG_MODE_INDEPENDENT) }
        c.stage(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(listOf("setOutputConfigMode(0)", "setMckMultiplier(1)", "saveOutputConfig"), r.writes(mark))
    }

    @Test
    fun enableAfterPinAndDisableBeforePin() = runTest {
        val r = rig()
        val c = r.coordinator
        // ADAT output: enable staged before the pin, applied after it
        c.stage(c.hardwareChanges.adatOutEnabled(c.deviceSnapshot(), c.hardwareDraft(), true))
        c.stage(c.hardwareChanges.adatOutPin(c.deviceSnapshot(), c.hardwareDraft(), 3))
        // I2S input: a raise goes after the newly active pair's pin
        c.stage(c.hardwareChanges.i2sInputChannels(c.deviceSnapshot(), 4))
        c.stage(c.hardwareChanges.i2sRxPin(c.deviceSnapshot(), 1, 2))
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        val w = r.writes(mark)
        assertTrue(w.indexOf("setAdatOutPin(3)") < w.indexOf("setAdatOutEnabled(true)"))
        assertTrue(w.indexOf("setI2sRxPin(1, 2)") < w.indexOf("setI2sInputChannels(4)"))
    }

    @Test
    fun spdifInstancesEnableUpwardsAndDisableDownwards() = runTest {
        val r = rig()
        val c = r.coordinator
        c.stage(c.hardwareChanges.spdifInputCount(c.deviceSnapshot(), 4))
        var mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(
            listOf("setSpdifInputEnabled(1, true)", "setSpdifInputEnabled(2, true)", "setSpdifInputEnabled(3, true)"),
            r.writes(mark)
        )
        c.stage(c.hardwareChanges.spdifInputCount(c.deviceSnapshot(), 1))
        mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(
            listOf("setSpdifInputEnabled(3, false)", "setSpdifInputEnabled(2, false)", "setSpdifInputEnabled(1, false)"),
            r.writes(mark)
        )
    }

    @Test
    fun aRefusedChangeStaysStagedWithTheMacOsMessage() = runTest {
        val r = rig()
        val c = r.coordinator
        // Output 2's pin is Output 1's: the fake refuses with PIN_IN_USE
        c.stage(c.hardwareChanges.outputPin(c.deviceSnapshot(), 1, 6))
        c.stage(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        val result = c.save()!!
        runCurrent()
        assertEquals(listOf("Output 2 GPIO: GPIO 6 is already in use by another output"), result.failures)
        assertEquals(listOf("outputs.out1.pin"), c.hardware.pending.value.map { it.key })
        assertEquals(result, c.lastResult.value)
    }

    @Test
    fun theHardwareTokenIsRevokedAfterSave() = runTest {
        val r = rig()
        val c = r.coordinator
        var token: se.bofr.dspi.bridge.ConfirmationToken? = null
        c.stage(
            PendingChange("probe", "p", "Probe", "a", "b", apply = { t ->
                token = t
                assertEquals(DangerousOp.HARDWARE_APPLY, t.op)
                0
            })
        )
        c.save()
        runCurrent()
        assertFalse(r.bridge.dangerousOps.isValid(token!!))
    }

    @Test
    fun revertDiscardsTheDraftWithoutTouchingTheDevice() = runTest {
        val r = rig()
        val c = r.coordinator
        c.stage(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        c.editGlobal { it.copy(presetStartupMode = 1) }
        val mark = r.core.calls.size
        c.revert()
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
    fun startupWritesModeAndSlotTogether() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetDefaultSlot = 3) }
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        assertEquals(listOf("setPresetStartup(0, 3)"), r.writes(mark))
    }

    @Test
    fun anotherDeviceDiscardsPendingEditsButARePlugKeepsThem() = runTest {
        val r = rig()
        val c = r.coordinator
        c.noteSelectedDevice(r.core.serial)
        c.stage(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        c.noteSelectedDevice(r.core.serial)
        assertEquals(1, c.hardware.count)
        c.noteSelectedDevice("OTHER")
        assertEquals(0, c.hardware.count)
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
        c.stage(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        val mark = r.core.calls.size
        c.save()
        runCurrent()
        val w = r.writes(mark)
        assertEquals(listOf("setMckMultiplier(1)", "saveOutputConfig"), w.filter { !it.startsWith("cs") })
        val csSave = w.indexOfFirst { it.startsWith("csApply") && it.contains("\"save\"") }
        assertTrue(csSave > w.indexOf("saveOutputConfig"))
        assertFalse(c.csBusy.value)
    }

    @Test
    fun pendingSummaryListsEveryCategory() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetMasterVolumeMode = 1) }
        c.stage(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        assertEquals(
            listOf("Master volume: Independent → With Preset", "Output 1 GPIO: GPIO 6 → GPIO 0"),
            c.pendingSummary()
        )
    }
}

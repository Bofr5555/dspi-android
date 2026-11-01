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
import se.bofr.dspi.bridge.DangerousOpNotConfirmed
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
    fun aPickerBeginsTheBridgeEditAndAppliesAtOnce() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        val mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 0))
        runCurrent()
        assertEquals(listOf("setOutputPin(0, 0)"), r.writes(mark))
        assertTrue(r.bridge.outputConfigDirty.value)
        assertTrue(c.hasPendingChanges())
        assertEquals("Output 1 reassigned to GPIO 0" to false, c.hardwareStatus.value)
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

    /**
     * Review S6 + review-stalestate #1: a gate refusal is never a plain "Failed to set" row and
     * never escapes the launched job (no crash); it is logged and shown as an internal error.
     */
    @Test
    fun aDangerousOpNotConfirmedIsShownAsAnInternalErrorNotThrown() = runTest {
        val r = rig()
        var caught: Throwable? = null
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> caught = e }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
        val c = SettingsSaveCoordinator(r.bridge, scope)
        c.applyHardware(
            HardwareChange("bad", "p", "Bad", "a", "b", apply = { throw DangerousOpNotConfirmed(DangerousOp.HARDWARE_APPLY, "spent") })
        )
        runCurrent()
        assertNull(caught)
        assertEquals("Bad not applied: confirmation refused (internal error)" to true, c.hardwareStatus.value)
    }

    /** review-stalestate #1: a pick queued behind a long sequence outlives its token: ask again, no crash. */
    @Test
    fun aPickWhoseConfirmationTimedOutInTheQueueAsksAgain() = runTest {
        val r = rig()
        var caught: Throwable? = null
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> caught = e }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + StandardTestDispatcher(testScheduler) + handler)
        val c = SettingsSaveCoordinator(r.bridge, scope)
        val ttl = r.bridge.dangerousOps.ttlMs
        var secondRan = false
        c.applyHardware(
            HardwareChange("slow", "p", "Slow", "a", "b", apply = {
                kotlinx.coroutines.delay(ttl + 1_000)
                0
            })
        )
        c.applyHardwareLatest { _, _ ->
            listOf(
                HardwareChange("next", "p", "Next", "a", "b", apply = {
                    secondRan = true
                    0
                })
            )
        }
        runCurrent()
        testScheduler.advanceTimeBy(ttl + 2_000)
        runCurrent()
        assertNull(caught)
        assertFalse(secondRan)
        assertEquals(SettingsSaveCoordinator.CONFIRMATION_TIMED_OUT to true, c.hardwareStatus.value)
    }

    @Test
    fun withPresetModeAppliesButNothingIsPending() = runTest {
        val r = rig()
        val c = r.coordinator
        val mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        assertEquals(listOf("setMckMultiplier(1)"), r.writes(mark))
        assertFalse(r.bridge.outputConfigDirty.value)
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun hardwareUnsavedAloneNeverRaisesTheBar() = runTest {
        // A pin call outside Settings (no beginOutputEdit) sets hardwareUnsaved only
        val r = rig(independent)
        val c = r.coordinator
        r.bridge.setMckMultiplier(r.bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY), 1)
        runCurrent()
        assertTrue(r.bridge.hardwareUnsaved.value)
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun aNoOpChoiceSendsNothing() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        assertNull(c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 0, 6)))
        assertFalse(r.bridge.outputConfigDirty.value)
    }

    /** Audit S3: a second pick made before the first applied diffs against the first's result. */
    @Test
    fun aSecondPickBeforeTheFirstLandedEndsOnTheSecond() = runTest {
        val r = rig()
        val c = r.coordinator
        assertEquals(1, c.deviceSnapshot().spdifEnabledCount)
        c.applyHardwareLatest { d, _ -> c.hardwareChanges.spdifInputCount(d, 3) }
        c.applyHardwareLatest { d, _ -> c.hardwareChanges.spdifInputCount(d, 2) } // same frame: the page still shows 1
        runCurrent()
        assertEquals(2, c.deviceSnapshot().spdifEnabledCount)
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
        mark = r.core.calls.size
        val dev = c.deviceSnapshot()
        c.applyHardware(listOf(c.hardwareChanges.adatOutEnabled(dev, true), c.hardwareChanges.adatOutPin(dev, 3)))
        runCurrent()
        assertEquals(listOf("setAdatOutPin(3)", "setAdatOutEnabled(true)"), r.writes(mark))
    }

    @Test
    fun aRefusalStopsTheActionAndNamesTheOwner() = runTest {
        val r = rig()
        val c = r.coordinator
        val dev = c.deviceSnapshot()
        val pins = c.pinContext()
        val mark = r.core.calls.size
        c.applyHardware(listOf(c.hardwareChanges.outputPin(dev, 1, 6, pins), c.hardwareChanges.outputPin(dev, 2, 0, pins)))
        runCurrent()
        assertEquals(listOf("setOutputPin(1, 6)"), r.writes(mark))
        assertEquals("GPIO 6 is already assigned to Output 1" to true, c.hardwareStatus.value)
    }

    @Test
    fun aSubPinRefusedAsActiveCyclesThePdmOutput() = runTest {
        val r = rig()
        val c = r.coordinator
        val dev = c.deviceSnapshot()
        // The firmware refuses while the sub plays; macOS assignOutputPin retries around a disable
        r.core.failNext("setOutputPin", 1) { se.bofr.dspi.core.DspiException.Refused(DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE) }
        val mark = r.core.calls.size
        c.applyHardware(c.hardwareChanges.outputPin(dev, dev.pdmIndex, 3))
        runCurrent()
        val w = r.writes(mark).filter { it.startsWith("setOutput") }
        assertEquals("setOutputPin(${dev.pdmIndex}, 3)", w.first())
        assertTrue(w[1].startsWith("setOutputEnable") && w[1].endsWith("false)"))
        assertEquals("setOutputPin(${dev.pdmIndex}, 3)", w[2])
        assertTrue(w[3].startsWith("setOutputEnable") && w[3].endsWith("true)"))
        assertEquals("Subwoofer reassigned to GPIO 3" to false, c.hardwareStatus.value)
    }

    @Test
    fun saveWritesGlobalsThenFlashesTheOutputConfig() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.outputPin(c.deviceSnapshot(), 1, 0))
        runCurrent()
        c.editGlobal { it.copy(presetMasterVolumeMode = GlobalSettingsDraft.MASTER_VOLUME_MODE_WITH_PRESET) }
        val mark = r.core.calls.size
        c.onSave()
        runCurrent()
        assertEquals(listOf("setMasterVolumeMode(1)", "saveOutputConfig"), r.writes(mark))
        assertTrue(c.lastResult.value!!.ok)
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun withPresetModeSaveDoesNotFlashTheOutputConfig() = runTest {
        val r = rig()
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        c.editGlobal { it.copy(presetDefaultSlot = 3) }
        runCurrent()
        val mark = r.core.calls.size
        c.onSave()
        runCurrent()
        assertEquals(listOf("setPresetStartup(0, 3)"), r.writes(mark))
    }

    @Test
    fun saveTokensAreMintedOnTheTapAndRevoked() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        val t = r.bridge.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
        c.save(t, null)
        runCurrent()
        assertFalse(r.bridge.dangerousOps.isValid(t))
        // A token for something not pending is revoked unused
        val cs = r.bridge.dangerousOps.confirm(DangerousOp.CS_SAVE)
        c.save(null, cs)
        assertFalse(r.bridge.dangerousOps.isValid(cs))
    }

    @Test
    fun revertRestoresTheBaselineAndDismissesTheBar() = runTest {
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
        assertFalse(r.bridge.outputConfigDirty.value)
        // Review S1: the bar goes away although the restore ran pin setters
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun revertOfTheGlobalDraftAloneSendsNothing() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetStartupMode = GlobalSettingsDraft.STARTUP_LAST_ACTIVE) }
        val mark = r.core.calls.size
        c.onRevert()
        runCurrent()
        assertEquals(emptyList<String>(), r.writes(mark))
        assertFalse(c.hasPendingChanges())
    }

    @Test
    fun theGlobalDraftIsEditableOffline() = runTest {
        val r = rig()
        val c = r.coordinator
        r.bridge.detachDevice(r.core.serial)
        runCurrent()
        c.editGlobal { it.copy(presetStartupMode = GlobalSettingsDraft.STARTUP_LAST_ACTIVE) }
        assertTrue(c.globalDirty())
        c.onRevert()
        runCurrent()
        assertFalse(c.globalDirty())
    }

    @Test
    fun aGlobalFieldEditedBackIsNotDirty() = runTest {
        val r = rig()
        val c = r.coordinator
        c.editGlobal { it.copy(presetStartupMode = GlobalSettingsDraft.STARTUP_LAST_ACTIVE) }
        assertTrue(c.globalDirty())
        c.editGlobal { it.copy(presetStartupMode = GlobalSettingsDraft.STARTUP_SPECIFIED) }
        assertFalse(c.globalDirty())
        assertNull(c.save(null, null))
    }

    @Test
    fun anotherDeviceDropsTheGlobalDraftButARePlugKeepsIt() = runTest {
        val r = rig()
        val c = r.coordinator
        c.noteSelectedDevice(r.core.serial)
        c.editGlobal { it.copy(presetStartupMode = GlobalSettingsDraft.STARTUP_LAST_ACTIVE) }
        c.noteSelectedDevice(r.core.serial)
        assertTrue(c.globalUserEdited.value)
        c.noteSelectedDevice("OTHER")
        assertFalse(c.globalUserEdited.value)
    }

    @Test
    fun controlSurfacesSaveLastWithItsOwnToken() = runTest {
        val r = rig(independent)
        val c = r.coordinator
        r.bridge.cs.refresh()
        runCurrent()
        c.csOp("""{"op":"name","index":0,"name":"Vol"}""")
        runCurrent()
        assertTrue(c.csDirty())
        assertFalse(c.csBusy.value)
        c.applyHardware(c.hardwareChanges.mckMultiplier(c.deviceSnapshot(), 1))
        runCurrent()
        val mark = r.core.calls.size
        c.onSave()
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

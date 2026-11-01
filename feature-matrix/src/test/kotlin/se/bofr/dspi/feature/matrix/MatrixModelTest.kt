package se.bofr.dspi.feature.matrix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants

/** macOS DSPViewModel matrix helpers and MatrixMixerView reducers. */
class MatrixModelTest {
    @Test
    fun supports8chInputOnlyOnRp2350WithEightInputsAndTheClientsWireFormat() {
        val v = DspiConstants.WIRE_FORMAT_VERSION
        assertTrue(supports8chInput("RP2350", 8, v))
        assertFalse(supports8chInput("RP2350", 8, v - 1))
        assertFalse(supports8chInput("RP2350", 2, v))
        assertFalse(supports8chInput("RP2040", 2, v))
    }

    @Test
    fun effectiveInputChannelsFollowsTheInputSource() {
        // USB: the device-reported active count, at least the stereo pair
        assertEquals(2, effectiveInputChannels("RP2350", 8, INPUT_SOURCE_USB, 2, 0))
        assertEquals(6, effectiveInputChannels("RP2350", 8, INPUT_SOURCE_USB, 2, 6))
        assertEquals(8, effectiveInputChannels("RP2350", 8, INPUT_SOURCE_USB, 2, 8))
        // S/PDIF 1-4: always stereo
        for (src in listOf(INPUT_SOURCE_SPDIF, INPUT_SOURCE_SPDIF2, INPUT_SOURCE_SPDIF3, INPUT_SOURCE_SPDIF4)) {
            assertEquals(2, effectiveInputChannels("RP2350", 8, src, 8, 8))
        }
        // I2S: the configured channel count; ADAT: 8
        assertEquals(6, effectiveInputChannels("RP2350", 8, INPUT_SOURCE_I2S, 6, 2))
        assertEquals(8, effectiveInputChannels("RP2350", 8, INPUT_SOURCE_ADAT, 2, 2))
        // RP2040 (chOut1 = 2) never exceeds stereo
        assertEquals(2, effectiveInputChannels("RP2040", 2, INPUT_SOURCE_USB, 2, 8))
    }

    @Test
    fun upmixRowsOnlyOnStereoInput() {
        assertTrue(upmixDerivesRows(upmixSupported = true, upmixEnabled = true, effectiveInputChannels = 2))
        assertFalse(upmixDerivesRows(upmixSupported = true, upmixEnabled = true, effectiveInputChannels = 8))
        assertFalse(upmixDerivesRows(upmixSupported = true, upmixEnabled = false, effectiveInputChannels = 2))
        assertFalse(upmixDerivesRows(upmixSupported = false, upmixEnabled = true, effectiveInputChannels = 2))
    }

    @Test
    fun matrixSourceRowCountAddsUpmixRows() {
        assertEquals(2, matrixSourceRowCount(false, false, 2))
        assertEquals(8, matrixSourceRowCount(false, true, 8))
        assertEquals(3, matrixSourceRowCount(true, surroundOff = true, numMatrixInputs = 2))
        assertEquals(5, matrixSourceRowCount(true, surroundOff = false, numMatrixInputs = 2))
    }

    @Test
    fun rowNamesAreContextualOnTheUpmixer() {
        assertEquals("USB 1", matrixRowShortName(0, true, "USB 1"))
        assertEquals("C", matrixRowShortName(2, true, "USB 3"))
        assertEquals("Ls", matrixRowShortName(3, true, "USB 4"))
        assertEquals("Rs", matrixRowShortName(4, true, "USB 5"))
        assertEquals("USB 3", matrixRowShortName(2, false, "USB 3"))
        assertEquals("Upmix Centre", matrixRowFullName(2, true, "x"))
        assertEquals("Upmix Left Surround", matrixRowFullName(3, true, "x"))
        assertEquals("Upmix Right Surround", matrixRowFullName(4, true, "x"))
        assertEquals("x", matrixRowFullName(4, false, "x"))
    }

    @Test
    fun core1ConflictBetweenPdmAndEqWorkers() {
        val range = eqWorkerRange("RP2350")
        assertEquals(2..7, range)
        assertEquals(2..3, eqWorkerRange("RP2040"))
        val pdm = pdmOutputIndex(9)
        assertEquals(8, pdm)
        val stereoOnly = List(9) { it < 2 }
        assertFalse(outputEnableWouldConflict(pdm, stereoOnly, pdm, range))
        assertFalse(outputEnableWouldConflict(4, stereoOnly, pdm, range))
        val withOut3 = List(9) { it < 3 }
        assertTrue(outputEnableWouldConflict(pdm, withOut3, pdm, range))
        val withPdm = List(9) { it < 2 || it == pdm }
        assertTrue(outputEnableWouldConflict(5, withPdm, pdm, range))
        // outputs 1-2 never share Core 1
        assertFalse(outputEnableWouldConflict(1, withPdm, pdm, range))
    }

    @Test
    fun rowDividers() {
        // stereo: a single L/R separator
        assertTrue(rowNeedsDivider(0, is8ch = false, numMatrixInputs = 2))
        assertFalse(rowNeedsDivider(1, is8ch = false, numMatrixInputs = 2))
        // 8 channels: after each pair except the last
        assertEquals(listOf(1, 3, 5), (0 until 8).filter { rowNeedsDivider(it, true, 8) })
    }

    @Test
    fun maxDelayPerPlatform() {
        assertEquals(42f, maxDelayFor("RP2040"))
        assertEquals(85f, maxDelayFor("RP2350"))
    }

    private fun state(enabled: List<Boolean>, platform: String = "RP2350"): MatrixMixerState {
        val pdm = pdmOutputIndex(enabled.size)
        val range = eqWorkerRange(platform)
        return MatrixMixerState(
            platformName = platform,
            connected = true,
            is8ch = false,
            numMatrixInputs = 2,
            visibleOutputs = emptyList(),
            matrixInputs = emptyList(),
            routes = emptyList(),
            outputs = enabled.indices.map {
                MatrixOutputControls(enabled[it], 0f, 0f, false, outputEnableWouldConflict(it, enabled, pdm, range))
            },
            inputTrimDB = emptyList(),
            pdmOutputIndex = pdm,
            eqWorkerRange = range,
            maxDelay = maxDelayFor(platform)
        )
    }

    @Test
    fun requestOutputEnableDisablesAtOnceAndAsksBeforeAConflict() {
        val s = state(List(9) { it < 3 })
        assertEquals(EnableAction.Set(false), s.requestOutputEnable(0))
        assertEquals(EnableAction.Set(true), s.requestOutputEnable(4))
        assertEquals(EnableAction.Confirm(PendingConflict(8, isPDM = true)), s.requestOutputEnable(8))
        val pdmOn = state(List(9) { it < 2 || it == 8 })
        assertEquals(EnableAction.Confirm(PendingConflict(5, isPDM = false)), pdmOn.requestOutputEnable(5))
    }

    @Test
    fun conflictAlertUsesTheMacOsWording() {
        val s = state(List(9) { it < 3 })
        assertEquals(
            ConflictAlert("Warning", "Outputs 3-8 will be disabled. Are you sure?", "Enable PDM"),
            s.conflictAlert(PendingConflict(8, true))
        )
        assertEquals(
            ConflictAlert("Warning", "The PDM output will be disabled. Are you sure?", "Disable PDM"),
            s.conflictAlert(PendingConflict(3, false))
        )
        val rp2040 = state(List(5) { it < 3 }, "RP2040")
        assertEquals("Outputs 3-4 will be disabled. Are you sure?", rp2040.conflictAlert(PendingConflict(4, true)).message)
    }

    @Test
    fun enableHelpTexts() {
        assertEquals("Disable output (saves CPU)", enableHelp(MatrixOutputControls(true, 0f, 0f, false, false)))
        assertEquals("Conflict: shared Core 1 resource", enableHelp(MatrixOutputControls(false, 0f, 0f, false, true)))
        assertEquals("Enable output", enableHelp(MatrixOutputControls(false, 0f, 0f, false, false)))
    }
}

// Pin conflict detection: the port of DSPViewModel.swift pinAssignment /
// pinInUseBy / activePinAssignments and the draft conflict check.
package se.bofr.dspi.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.PinOwner
import se.bofr.dspi.fake.demoDspState

class PinAssignmentsTest {
    // RP2350: outputs 6,7,8,9 + PDM 10, BCK 14 (+LRCLK 15), MCK 13, S/PDIF RX 5
    private val base = OutputConfigSnapshot()
    private fun ctx(c: OutputConfigSnapshot = base, dac: DacHwMuteConfig = DacHwMuteConfig(), cs: List<Pair<Int, Int>> = emptyList()) =
        PinContext(c, dac, csPins = cs)

    @Test
    fun outputsSubwooferAndClocksAreClaimedWithMacLabels() {
        val p = ctx()
        assertEquals("Output 1", p.pinInUseBy(6))
        assertEquals("Output 4", p.pinInUseBy(9))
        assertEquals("Subwoofer", p.pinInUseBy(10))
        assertEquals("I2S BCK", p.pinInUseBy(14))
        assertEquals("I2S LRCLK", p.pinInUseBy(15))
        assertEquals("I2S MCK", p.pinInUseBy(13))
        assertEquals("S/PDIF RX", p.pinInUseBy(5))
        assertNull(p.pinInUseBy(0))
    }

    @Test
    fun aConsumerNeverSeesItsOwnPin() {
        val p = ctx()
        assertNull(p.pinInUseBy(6, PinConsumer.Output(0)))
        assertEquals("Output 1", p.pinInUseBy(6, PinConsumer.Output(1)))
        assertNull(p.pinInUseBy(15, PinConsumer.I2sBck))
        assertNull(p.pinInUseBy(10, PinConsumer.Output(4)))
    }

    @Test
    fun slavePairIsReservedOnlyInSplitMode() {
        assertNull(ctx().pinInUseBy(16))
        val split = ctx(base.copy(i2sClockPinMode = OutputConfigSnapshot.I2S_CLOCK_PIN_MODE_SPLIT))
        assertEquals("I2S Slave BCK", split.pinInUseBy(16))
        assertEquals("I2S Slave LRCLK", split.pinInUseBy(17))
    }

    @Test
    fun optionalSpdifInputsHoldPinsOnlyWhileEnabled() {
        assertNull(ctx().pinInUseBy(20))
        val two = ctx(base.copy(spdifInputsEnabled = 0b11))
        assertEquals("S/PDIF 2 RX", two.pinInUseBy(20))
        assertEquals("S/PDIF 1 RX", two.pinInUseBy(5))
    }

    @Test
    fun onlyActiveI2sPairsReserveTheirPins() {
        val p = ctx()
        assertEquals("I2S RX 1", p.pinInUseBy(1))
        assertNull(p.pinInUseBy(2))
        assertEquals("I2S RX 2", ctx(base.copy(i2sInputChannels = 4)).pinInUseBy(2))
    }

    @Test
    fun rp2040HasOneUnnumberedI2sPair() {
        val c = base.copy(
            platformName = "RP2040",
            numPinOutputs = 3,
            outputPins = listOf(6, 7, 10, 0, 0),
            i2sInputChannels = 8,
            caps = OutputConfigSnapshot.FULL_CAPS.copy(i2sMultichannelInput = false, adatOutSupported = false, adatInputSupported = false)
        )
        assertEquals("I2S RX", ctx(c).pinInUseBy(1))
        assertNull(ctx(c).pinInUseBy(2))
        assertEquals("Subwoofer", ctx(c).pinInUseBy(10))
        assertNull(ctx(c).pinInUseBy(8))
    }

    @Test
    fun dacMuteHoldsItsPinOnlyWhenEnabled() {
        assertNull(ctx(dac = DacHwMuteConfig(enabled = false, pin = 22)).pinInUseBy(22))
        assertEquals("DAC Mute", ctx(dac = DacHwMuteConfig(enabled = true, pin = 22)).pinInUseBy(22))
        assertNull(ctx(dac = DacHwMuteConfig(enabled = true, pin = 22)).pinInUseBy(22, PinConsumer.DacMute))
    }

    @Test
    fun adatInputMayShareTheOutputPinButNotTheOtherWayRound() {
        val c = base.copy(adatEnabled = true, adatPin = 12, adatInputEnabled = true, adatInputPin = 12)
        // Input assignment: the output pin is not taken (loopback self-test)
        assertNull(ctx(c.copy(adatInputEnabled = false)).pinInUseBy(12, PinConsumer.AdatIn))
        // Output assignment still sees the input's pin
        assertEquals("ADAT Input", ctx(c).pinInUseBy(12, PinConsumer.AdatOut))
        // And the loopback is not a conflict
        assertTrue(ctx(c).pinConflicts().none { it.pin == 12 })
    }

    @Test
    fun controlSurfaceAndInterfaceClaimsComeFromTheirSources() {
        val p = PinContext(base, uart = CtrlClaim(true, 0, 1), i2c = CtrlClaim(false, 2, 3), csPins = listOf(3 to 18))
        assertEquals("UART Control", p.pinInUseBy(0))
        assertNull(p.pinInUseBy(2))
        assertEquals("Control Surface 4", p.pinInUseBy(18))
        assertNull(p.pinInUseBy(18, PinConsumer.ControlSurface(3)))
    }

    @Test
    fun pinContextReadsBridgePinOwnersForControlSurfaces() {
        val owners = listOf(PinOwner(18, "Control Surface 2", "control"), PinOwner(6, "Output 1/2", "output"))
        val p = PinContext.from(base, demoDspState(), owners)
        assertEquals(listOf(1 to 18), p.csPins)
    }

    @Test
    fun activeAssignmentsAreInPinOrderAndOnlyValidPins() {
        val list = ctx().activePinAssignments()
        assertEquals(list.map { it.pin }.sorted(), list.map { it.pin })
        assertTrue(list.all { it.pin in VALID_PINS })
        assertEquals(listOf(1, 5, 6, 7, 8, 9, 10, 13, 14, 15), list.map { it.pin })
    }

    @Test
    fun aDraftPuttingTwoFeaturesOnOnePinIsAConflict() {
        // Enabling S/PDIF 2 whose parked pin is Output 1's
        val c = base.copy(spdifInputsEnabled = 0b11, spdifRxPins = listOf(5, 6, 21, 22))
        val conflicts = ctx(c).pinConflicts()
        assertEquals(listOf(6), conflicts.map { it.pin })
        assertEquals(listOf("Output 1", "S/PDIF 2 RX"), conflicts.single().claimants.map { it.label })
        assertEquals(SettingsCategory.SPDIF_INPUT, conflicts.single().claimants.last().page)
    }

    @Test
    fun aMovedBckOverlappingAnOutputIsAConflictOnBothPins() {
        val c = base.copy(i2sBckPin = 6)
        assertEquals(listOf(6, 7), ctx(c).pinConflicts().map { it.pin })
    }

    @Test
    fun theDefaultConfigurationHasNoConflicts() {
        assertTrue(ctx().pinConflicts().isEmpty())
    }

    @Test
    fun describeFirstHeldNamesTheOwner() {
        assertEquals("Can't enable. GPIO 6 is already used by Output 1.", ctx().describeFirstHeld(listOf(0, 6), null, "Can't enable."))
        assertNull(ctx().describeFirstHeld(listOf(0, 2), null))
    }

    @Test
    fun bckCandidatesNeedTheLrclkNeighbourFree() {
        val cands = bckCandidates(ctx(), PinConsumer.I2sBck, 14)
        assertTrue(14 in cands)
        assertTrue(5 !in cands) // S/PDIF RX
        assertTrue(4 !in cands) // LRCLK would land on S/PDIF RX 5
        assertTrue(22 !in cands) // LRCLK would land on GPIO 23, which is not wirable (Windows/Linux rule)
    }
}

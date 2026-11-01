package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.I2cConfig
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PinUse
import se.bofr.dspi.core.UartConfig
import se.bofr.dspi.fake.FakeDspiCore
import se.bofr.dspi.fake.demoDspState

/** BRIDGE-3: status variants (S7), owner kinds (S15), caps, device generation (S16), live input rate (S8). */
@OptIn(ExperimentalCoroutinesApi::class)
class Bridge3Test {
    // ── S7: setInputRateWithStatus / setI2sClockModeWithStatus ──

    @Test
    fun rateAndClockModeVariantsReturnTheTransferStatusInOrder() = runTest {
        val core = FakeDspiCore(demoDspState().copy(outputConfigMode = 0))
        val b = newBridge(core)
        attach(b, core)
        val t = b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        b.beginOutputEdit()
        val mark = core.calls.size
        assertEquals(PinConfigStatus.SUCCESS, b.setInputRateWithStatus(t, 2))
        // Applied and published by the time the call returns (no runCurrent)
        assertEquals(2, b.state.value.i2sInputRate)
        assertEquals(PinConfigStatus.SUCCESS, b.setI2sClockModeWithStatus(t, 1))
        assertEquals(1, b.state.value.i2sClockMode)
        assertEquals(PinConfigStatus.SUCCESS, b.setMckPin(t, 21))
        val seq = core.calls.drop(mark).map { it.substringBefore('(') }.filter { it.startsWith("set") }
        assertEquals(listOf("setInputRate", "setI2sClockMode", "setMckPin"), seq)
        assertTrue(b.hardwareUnsaved.value)
        assertTrue(b.outputConfigDirty.value)
    }

    @Test
    fun aFailedTransferIsNoReplyAndTheGateHolds() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        core.failNext("setInputRate", 1)
        assertEquals(PinConfigStatus.NO_REPLY, b.setInputRateWithStatus(ops.confirm(DangerousOp.HARDWARE_APPLY), 0))
        core.failNext("setI2sClockMode", 1) { DspiException.Disconnected() }
        assertEquals(PinConfigStatus.NO_REPLY, b.setI2sClockModeWithStatus(ops.confirm(DangerousOp.HARDWARE_APPLY), 1))
        val wrong = ops.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
        val before = core.callCount("setInputRate")
        assertTrue(runCatching { b.setInputRateWithStatus(wrong, 1) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertTrue(runCatching { b.setI2sClockModeWithStatus(wrong, 1) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertEquals("refused before any traffic", before, core.callCount("setInputRate"))
    }

    @Test
    fun theFireAndForgetFormsStillWork() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val t = b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        b.setInputRate(t, 0)
        b.setI2sClockMode(t, 1)
        runCurrent()
        assertEquals(0, b.state.value.i2sInputRate)
        assertEquals(1, b.state.value.i2sClockMode)
    }

    // ── S15: structured pin owner kinds ──

    @Test
    fun everyOwnerCarriesItsMacOsConsumerKind() = runTest {
        val st =
            demoDspState().copy(
                adatOutEnabled = true,
                adatOutPin = 12,
                i2sClockPinMode = 1,
                mckEnabled = true,
                mckPin = 13,
                spdifInputsEnabled = 0x05,
                i2sInputChannels = 4,
                adatInputEnabled = true,
                adatInputPin = 27,
                dacMuteEnabled = true,
                dacMutePin = 11,
                uart = UartConfig(enabled = true, txPin = 16, rxPin = 17, notify = false, baud = 115_200),
                i2c = I2cConfig(enabled = true, sdaPin = 18, sclPin = 19, address = 0x42)
            )
        val core = FakeDspiCore(st)
        val b = newBridge(core)
        attach(b, core)
        b.csPinUses = listOf(PinUse(pin = 28, slot = 2))
        val owners = b.pinOwners()
        fun of(kind: PinOwnerKind) = owners.filter { it.kind == kind }
        assertEquals(listOf(0, 1, 2, 3), of(PinOwnerKind.OUTPUT_SLOT).map { it.index })
        assertEquals(listOf(10 to 4), of(PinOwnerKind.SUBWOOFER).map { it.pin to it.index })
        assertEquals(listOf(12), of(PinOwnerKind.ADAT_OUT).map { it.pin })
        assertEquals(listOf(14), of(PinOwnerKind.I2S_BCK).map { it.pin })
        assertEquals(listOf(15), of(PinOwnerKind.I2S_LRCLK).map { it.pin })
        assertEquals(listOf(26), of(PinOwnerKind.I2S_SLAVE_BCK).map { it.pin })
        assertEquals(listOf(27), of(PinOwnerKind.I2S_SLAVE_LRCLK).map { it.pin })
        assertEquals(listOf(13), of(PinOwnerKind.MCK).map { it.pin })
        assertEquals(listOf(5 to 0, 21 to 2), of(PinOwnerKind.SPDIF_RX).map { it.pin to it.index })
        assertEquals(listOf("S/PDIF RX 1", "S/PDIF RX 3"), of(PinOwnerKind.SPDIF_RX).map { it.owner })
        assertEquals(listOf(1 to 0, 2 to 1), of(PinOwnerKind.I2S_RX).map { it.pin to it.index })
        assertEquals(listOf(27), of(PinOwnerKind.ADAT_IN).map { it.pin })
        assertEquals(listOf(11), of(PinOwnerKind.DAC_MUTE).map { it.pin })
        assertEquals(listOf(16 to 0, 17 to 1), of(PinOwnerKind.UART).map { it.pin to it.index })
        assertEquals(listOf(18 to 0, 19 to 1), of(PinOwnerKind.I2C).map { it.pin to it.index })
        val cs = of(PinOwnerKind.CONTROL_SURFACE).single()
        assertEquals(PinOwner(28, "Control Surface 3", "control", PinOwnerKind.CONTROL_SURFACE, 2), cs)
        assertTrue("the bridge never emits OTHER", of(PinOwnerKind.OTHER).isEmpty())
        // Labels and roles are Qt's, unchanged; the kind adds the macOS role
        assertEquals("other", of(PinOwnerKind.DAC_MUTE).single().role)
        assertEquals("utility", PinOwnerKind.DAC_MUTE.macRole)
        assertTrue(owners.all { it.kind.macRole == if (it.role == "other") "utility" else it.role })
    }

    @Test
    fun aHandBuiltOwnerKeepsTheOldShape() {
        val o = PinOwner(6, "Output 1/2", "output")
        assertEquals(PinOwnerKind.OTHER, o.kind)
        assertEquals(-1, o.index)
    }

    // ── Capabilities ──

    @Test
    fun inputSourceAndLgSoundSyncFollowTheUsableImage() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertFalse(b.hardwareCaps.inputSourceSupported)
        assertFalse(b.hardwareCaps.lgSoundSyncSupported)
        attach(b, core)
        assertTrue(b.hardwareCaps.inputSourceSupported)
        assertTrue(b.hardwareCaps.lgSoundSyncSupported)
        b.detachDevice(core.serial)
        runCurrent()
        assertEquals(HardwareCaps(), b.hardwareCaps)
    }

    // ── S16: device generation ──

    @Test
    fun theGenerationMovesOnEveryOpenAndClose() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        assertEquals(0L, b.deviceGeneration.value)
        attach(b, core)
        val g1 = b.deviceGeneration.value
        assertTrue(g1 > 0)
        // State changes do not move it
        b.setMasterVolume(-6f)
        runCurrent()
        assertEquals(g1, b.deviceGeneration.value)
        b.detachDevice(core.serial)
        runCurrent()
        val g2 = b.deviceGeneration.value
        assertTrue(g2 > g1)
        // A re-plug of the SAME unit is a new generation (selectedSerial is unchanged)
        attach(b, core)
        val g3 = b.deviceGeneration.value
        assertTrue(g3 > g2)
        assertEquals(core.serial, b.selectedSerial)
        b.reconnect()
        runCurrent()
        assertTrue(b.deviceGeneration.value >= g3 + 2) // close + open
        assertTrue(b.connected)
    }

    @Test
    fun aSequenceCanAbortOnTheGeneration() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val gen = b.deviceGeneration.value
        fun stillCurrent() = b.deviceGeneration.value == gen
        assertTrue(stillCurrent())
        b.disconnectDevice()
        runCurrent()
        assertFalse(stillCurrent())
        assertNotEquals(gen, b.deviceGeneration.value)
    }

    // ── S8: fetchInputRate is the live pipeline rate ──

    @Test
    fun fetchInputRateIsTheLivePipelineRateNotTheConfiguredOne() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val t = b.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        core.externalRateHz = 44_100
        assertEquals(0, b.state.value.inputSource) // USB: the host's rate
        assertEquals(44_100, b.fetchInputRate())
        b.setInputRateWithStatus(t, 2) // stored, not applied: not the rate authority
        assertEquals(44_100, b.fetchInputRate())
        b.setInputSource(t, 2) // I2S master: the device is the rate authority
        runCurrent()
        assertEquals(96_000, b.fetchInputRate())
        b.setI2sClockModeWithStatus(t, 1) // I2S slave: the external master's rate
        assertEquals(44_100, b.fetchInputRate())
        advanceTimeBy(1)
        b.detachDevice(core.serial)
        runCurrent()
        assertEquals(0, b.fetchInputRate())
    }
}

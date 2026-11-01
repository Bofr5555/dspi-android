package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.fake.FakeDspiCore

@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleAndSafetyTest {
    // ── Status poll lifecycle ──

    @Test
    fun statusPollsEvery60msWhileAUiIsVisible() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val n0 = core.callCount("fetchStatus")
        advanceTimeBy(601)
        assertEquals(10, core.callCount("fetchStatus") - n0)
    }

    @Test
    fun noPollingWithoutAVisibleUiAndBackOnWhenVisible() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setUiVisible(false)
        val n0 = core.callCount("fetchStatus")
        advanceTimeBy(5_000)
        assertEquals(n0, core.callCount("fetchStatus"))
        b.setUiVisible(true)
        advanceTimeBy(121)
        assertEquals(n0 + 2, core.callCount("fetchStatus"))
    }

    @Test
    fun noPollingWhileDisconnected() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.detachDevice(core.serial)
        runCurrent()
        val n0 = core.callCount("fetchStatus")
        advanceTimeBy(1_000)
        assertEquals(n0, core.callCount("fetchStatus"))
    }

    // ── DangerousOps ──

    @Test
    fun flashWritesWithoutATokenDoNothing() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val events = collectEvents(b)
        assertEquals(PresetStatus.NO_REPLY, b.savePreset(3))
        assertEquals(PresetStatus.NO_REPLY, b.deletePreset(1))
        assertEquals(PresetStatus.NO_REPLY, b.copyPreset(0, 3))
        assertEquals(PresetStatus.NO_REPLY, b.saveMasterVolume())
        assertEquals(PresetStatus.NO_REPLY, b.saveOutputConfig())
        assertEquals(PresetStatus.NO_REPLY, b.loadPreset(1))
        assertEquals(FlashStatus.ERR_WRITE, b.saveParams())
        assertEquals(FlashStatus.ERR_WRITE, b.loadParams())
        assertEquals(FlashStatus.ERR_WRITE, b.factoryReset())
        assertEquals(PinConfigStatus.NO_REPLY, b.setOutputPin(0, 20))
        assertEquals(PinConfigStatus.NO_REPLY, b.setMckPin(20))
        assertEquals(255, b.testDacMute())
        b.enterBootloader()
        b.clearClips()
        b.stats.resetWatermarks()
        b.siggen.identify(0)
        assertFalse(b.siggen.start())
        assertTrue(b.cs.apply("""{"op":"save"}""").contains("\"ok\":false"))
        assertTrue(b.configFiles.importConfiguration("{}", volumes = true, hardware = true).contains("\"ok\":false"))
        runCurrent()
        assertTrue(b.connected)
        val gated =
            listOf(
                "savePreset", "deletePreset", "copyPreset", "saveMasterVolume", "saveOutputConfig", "loadPreset",
                "saveParams", "loadParams", "factoryReset", "setOutputPin", "setMckPin", "testDacMute",
                "enterBootloader", "resetBufferStats", "siggenControl", "csApply", "configImport"
            )
        for (m in gated) assertEquals(m, 0, core.callCount(m))
        assertEquals(0, core.callCount("clearClips")) // no clip: not even the poll's read-then-clear ran
        val refused = events.filterIsInstance<BridgeEvent.CommandFailed>().map { (it.error as DangerousOpNotConfirmed).op }.toSet()
        assertEquals(18, refused.size)
    }

    @Test
    fun aTokenIsSingleUseAndForItsOperationOnly() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.SAVE_PRESET)
        assertEquals(PresetStatus.NO_REPLY, ops.deletePreset(t, 1)) // wrong operation: refused and dropped
        assertEquals(PresetStatus.NO_REPLY, ops.savePreset(t, 3)) // dropped by the misuse
        val t2 = ops.confirm(DangerousOp.SAVE_PRESET)
        assertEquals(PresetStatus.OK, ops.savePreset(t2, 3))
        assertEquals(PresetStatus.NO_REPLY, ops.savePreset(t2, 3)) // used up
        assertEquals(1, core.callCount("savePreset"))
        assertEquals(0, core.callCount("deletePreset"))
    }

    @Test
    fun tokensExpire() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.FACTORY_RESET)
        assertTrue(ops.isArmed(DangerousOp.FACTORY_RESET))
        advanceTimeBy(DangerousOps.DEFAULT_TTL_MS + 1)
        assertEquals(FlashStatus.ERR_WRITE, ops.factoryReset(t))
        assertEquals(0, core.callCount("factoryReset"))
    }

    @Test
    fun theContractMembersRunAfterAConfirmation() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.dangerousOps.confirm(DangerousOp.COPY_PRESET)
        assertEquals(PresetStatus.OK, b.copyPreset(0, 5))
        assertTrue(b.isPresetOccupied(5))
        b.dangerousOps.confirm(DangerousOp.SIGGEN_START)
        assertTrue(b.siggen.start())
        runCurrent()
        assertEquals(1, b.siggen.status.value?.state)
        b.siggen.stop() // never gated
        runCurrent()
        assertEquals(0, b.siggen.status.value?.state)
    }

    @Test
    fun everyListedOperationCarriesItsOpcodes() {
        val flash = DangerousOp.entries.filter { it.writesFlash }.flatMap { it.opcodes }.toSet()
        assertTrue(flash.containsAll(listOf(0x51, 0x52, 0x53, 0x90, 0x92, 0x9D, 0xD6, 0xA3, 0xA1, 0xF0)))
        val all = DangerousOp.entries.flatMap { it.opcodes }.toSet()
        // DESIGN §4.5 rule 1 (incl. the undocumented ones the core can issue)
        assertTrue(all.containsAll(listOf(0x7C, 0x83, 0x91, 0xB1, 0xC0, 0xC2, 0xC4, 0xC6, 0xC8, 0xE4, 0xEC, 0xF1)))
        assertTrue(all.containsAll(listOf(0x68, 0x6A, 0x6C, 0x8F, 0x9E, 0xA6, 0xCA, 0xCC, 0xE9, 0xF3, 0xFE, 0x25)))
    }

    // ── Retry policy ──

    @Test
    fun idempotentGetsAreRetriedAfterAFlashWrite() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setUiVisible(false)
        val ops = b.dangerousOps
        assertEquals(PresetStatus.OK, ops.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 2))
        core.failNext("fetchInputRate", 3)
        assertEquals(48_000, b.fetchInputRate()) // 3 failures, retried 150 ms apart
        assertEquals(4, core.callCount("fetchInputRate"))
        core.failNext("fetchInputRate", 5)
        assertEquals(0, b.fetchInputRate()) // more than 4 retries: given up
        assertEquals(4 + 5, core.callCount("fetchInputRate"))
    }

    @Test
    fun noRetryOutsideTheBlackoutWindow() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        core.failNext("fetchInputRate", 1)
        assertEquals(0, b.fetchInputRate())
        assertEquals(1, core.callCount("fetchInputRate"))
        val ops = b.dangerousOps
        ops.saveParams(ops.confirm(DangerousOp.SAVE_PARAMS))
        advanceTimeBy(DspiBridgeImpl.FLASH_BLACKOUT_MS + 1)
        core.failNext("fetchInputRate", 1)
        assertEquals(0, b.fetchInputRate())
        assertEquals(2, core.callCount("fetchInputRate"))
    }

    @Test
    fun setsAreNeverRetried() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        ops.saveParams(ops.confirm(DangerousOp.SAVE_PARAMS))
        core.failNext("setUserVolume", 2) { DspiException.Transfer("stall") }
        b.setUserVolume(-3f)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(1, core.callCount("setUserVolume"))
        core.failNext("savePreset", 1)
        assertEquals(PresetStatus.NO_REPLY, ops.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 1))
        assertEquals(1, core.callCount("savePreset"))
    }

    @Test
    fun theStatusPollRidesOutABlackout() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        advanceTimeBy(61)
        val ops = b.dangerousOps
        ops.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 1)
        core.failNext("fetchStatus", 2)
        advanceTimeBy(61 + 300)
        assertTrue(b.connected)
        assertTrue(b.status.value.connected)
    }
}

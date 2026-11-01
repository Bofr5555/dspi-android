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

    // ── DangerousOps (D1/D2/D5) ──

    @Test
    fun gatedMembersThrowWithoutAValidTokenAndSendNothing() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val other = ops.confirm(DangerousOp.SAVE_PARAMS) // valid, but for another op
        val calls: List<Pair<String, suspend () -> Unit>> =
            listOf(
                "savePreset" to { b.savePreset(other, 3) },
                "deletePreset" to { b.deletePreset(other, 1) },
                "copyPreset" to { b.copyPreset(other, 0, 3) },
                "saveMasterVolume" to { b.saveMasterVolume(other) },
                "saveOutputConfig" to { b.saveOutputConfig(other) },
                "factoryReset" to { b.factoryReset(other) },
                "enterBootloader" to { b.enterBootloader(other) },
                "setOutputPin" to { b.setOutputPin(other, 0, 20) },
                "setMckPin" to { b.setMckPin(other, 20) },
                "setInputSource" to { b.setInputSource(other, 2) },
                "setInputRate" to { b.setInputRate(other, 2) },
                "setI2sClockMode" to { b.setI2sClockMode(other, 1) },
                "csSave" to { b.cs.save(other) },
                "configImport" to { b.configFiles.importConfiguration(other, "{}", volumes = true, hardware = true) }
            )
        for ((name, call) in calls) {
            val e = runCatching { call() }.exceptionOrNull()
            assertTrue("$name: $e", e is DangerousOpNotConfirmed)
        }
        runCurrent()
        assertTrue(b.connected)
        for (m in listOf("savePreset", "deletePreset", "copyPreset", "saveMasterVolume", "saveOutputConfig", "factoryReset")) {
            assertEquals(m, 0, core.callCount(m))
        }
        for (m in listOf(
            "enterBootloader",
            "setOutputPin",
            "setMckPin",
            "setInputSource",
            "setInputRate",
            "setI2sClockMode",
            "configImport"
        )) {
            assertEquals(m, 0, core.callCount(m))
        }
        assertEquals(0, core.calls.count { it.startsWith("csApply") && it.contains("save") })
        assertTrue(ops.isValid(other)) // a refusal does not spend the caller's token
    }

    @Test
    fun ungatedOperationsRunWithoutAToken() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        assertEquals(PresetStatus.OK, b.loadPreset(1))
        assertEquals(FlashStatus.OK, b.loadParams())
        b.clearClips()
        b.stats.resetWatermarks()
        b.siggen.identify(0)
        runCurrent()
        assertTrue(b.siggen.start())
        assertEquals(0, b.cs.revert())
        assertTrue(b.cs.apply("""{"op":"macroFire","index":0}""").contains("\"ok\":true"))
        runCurrent()
        for (m in listOf(
            "loadPreset",
            "loadParams",
            "clearClips",
            "resetBufferStats",
            "siggenControl"
        )) {
            assertTrue(m, core.callCount(m) > 0)
        }
    }

    @Test
    fun aTokenIsSingleUseAndForItsOperationOnly() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.SAVE_PRESET)
        assertTrue(runCatching { b.deletePreset(t, 1) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertEquals(PresetStatus.OK, b.savePreset(t, 3))
        assertFalse(ops.isValid(t))
        assertTrue(runCatching { b.savePreset(t, 3) }.exceptionOrNull() is DangerousOpNotConfirmed) // spent
        val r = ops.confirm(DangerousOp.SAVE_PRESET)
        ops.revoke(r)
        assertTrue(runCatching { b.savePreset(r, 3) }.exceptionOrNull() is DangerousOpNotConfirmed) // revoked
        // A token of another bridge (same op, same id) is not valid here
        val foreign = newBridge(FakeDspiCore()).dangerousOps.confirm(DangerousOp.SAVE_PRESET)
        assertTrue(runCatching { b.savePreset(foreign, 3) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertEquals(1, core.callCount("savePreset"))
        assertEquals(0, core.callCount("deletePreset"))
    }

    @Test
    fun aCallWhileDisconnectedLeavesTheTokenValid() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.detachDevice(core.serial)
        runCurrent()
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.SAVE_PRESET)
        assertEquals(PresetStatus.NO_REPLY, b.savePreset(t, 2))
        assertTrue(ops.isValid(t)) // review N2: not spent without a device
        val wrong = ops.confirm(DangerousOp.DELETE_PRESET)
        assertTrue(runCatching { b.savePreset(wrong, 2) }.exceptionOrNull() is DangerousOpNotConfirmed) // still loud
        attach(b, core)
        assertEquals(PresetStatus.OK, b.savePreset(t, 2))
        assertFalse(ops.isValid(t))
    }

    @Test
    fun expiredTokensArePrunedOnConfirm() = runTest {
        val b = newBridge(FakeDspiCore())
        val ops = b.dangerousOps as DangerousOpsImpl
        repeat(5) { ops.confirm(DangerousOp.SAVE_PRESET) }
        advanceTimeBy(ops.ttlMs + 1)
        ops.confirm(DangerousOp.SAVE_PRESET)
        assertEquals(1, ops.liveCount) // review N3
    }

    @Test
    fun tokensExpire() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.FACTORY_RESET)
        assertTrue(ops.isValid(t))
        advanceTimeBy(ops.ttlMs + 1)
        assertFalse(ops.isValid(t))
        assertTrue(runCatching { b.factoryReset(t) }.exceptionOrNull() is DangerousOpNotConfirmed)
        assertEquals(0, core.callCount("factoryReset"))
    }

    @Test
    fun oneHardwareApplyTokenCoversAWholeSettingsSave() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        val t = ops.confirm(DangerousOp.HARDWARE_APPLY)
        assertEquals(PinConfigStatus.SUCCESS, b.setOutputPin(t, 0, 20))
        advanceTimeBy(ops.ttlMs - 1) // each use renews it
        assertEquals(PinConfigStatus.SUCCESS, b.setMckPin(t, 21))
        b.setInputSource(t, 2)
        runCurrent()
        assertEquals(2, b.inputSource)
        ops.revoke(t) // the Save sequence is done
        assertTrue(runCatching { b.setMckPin(t, 22) }.exceptionOrNull() is DangerousOpNotConfirmed)
    }

    // ── Retry policy (DESIGN §4.5 rule 4, commands.md §1.3) ──

    @Test
    fun idempotentGetsAreRetriedAfterAFlashWrite() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setUiVisible(false)
        assertEquals(PresetStatus.OK, b.savePreset(b.dangerousOps.confirm(DangerousOp.SAVE_PRESET), 2))
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
        b.setUiVisible(false)
        advanceTimeBy(DspiBridgeImpl.FLASH_BLACKOUT_MS + 1) // past the connect-time load
        core.failNext("fetchInputRate", 1)
        assertEquals(0, b.fetchInputRate())
        assertEquals(1, core.callCount("fetchInputRate"))
        b.saveParams(b.dangerousOps.confirm(DangerousOp.SAVE_PARAMS))
        advanceTimeBy(DspiBridgeImpl.FLASH_BLACKOUT_MS + 1)
        core.failNext("fetchInputRate", 1)
        assertEquals(0, b.fetchInputRate())
        assertEquals(2, core.callCount("fetchInputRate"))
    }

    /** Every busy-window op of commands.md §1.3 and every D3 directory/config write arms the window; plain SETs don't. */
    @Test
    fun theBlackoutIsArmedByTheDeferredAndFlashOpsOnly() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        b.setUiVisible(false)
        val ops = b.dangerousOps
        suspend fun armed(): Boolean {
            core.failNext("fetchInputRate", 1)
            val ok = b.fetchInputRate() == 48_000
            advanceTimeBy(DspiBridgeImpl.FLASH_BLACKOUT_MS + 1)
            return ok
        }
        advanceTimeBy(DspiBridgeImpl.FLASH_BLACKOUT_MS + 1)
        val arming: List<Pair<String, suspend () -> Unit>> =
            listOf(
                "loadPreset" to { b.loadPreset(1) },
                "loadParams" to { b.loadParams() },
                "savePreset" to { b.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 2) },
                "deletePreset" to { b.deletePreset(ops.confirm(DangerousOp.DELETE_PRESET), 2) },
                "copyPreset" to { b.copyPreset(ops.confirm(DangerousOp.COPY_PRESET), 0, 3) },
                "saveParams" to { b.saveParams(ops.confirm(DangerousOp.SAVE_PARAMS)) },
                "saveOutputConfig" to { b.saveOutputConfig(ops.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)) },
                "setInputRate" to { b.setInputRate(ops.confirm(DangerousOp.HARDWARE_APPLY), 1).also { runCurrent() } },
                "setI2sClockMode" to { b.setI2sClockMode(ops.confirm(DangerousOp.HARDWARE_APPLY), 1).also { runCurrent() } },
                "setI2c" to { b.setI2c(false, 18, 19, 0x42).also { runCurrent() } },
                "factoryReset" to { b.factoryReset(ops.confirm(DangerousOp.FACTORY_RESET)) },
                "saveMasterVolume" to { b.saveMasterVolume(ops.confirm(DangerousOp.SAVE_MASTER_VOLUME)) },
                "setOutputType" to { b.setOutputType(ops.confirm(DangerousOp.HARDWARE_APPLY), 0, 1) },
                "setOutputPin" to { b.setOutputPin(ops.confirm(DangerousOp.HARDWARE_APPLY), 0, 20) },
                "setInputSource" to { b.setInputSource(ops.confirm(DangerousOp.HARDWARE_APPLY), 1).also { runCurrent() } },
                "cs.save" to { b.cs.save(ops.confirm(DangerousOp.CS_SAVE)) },
                "configImport" to { b.configFiles.importConfiguration(ops.confirm(DangerousOp.CONFIG_IMPORT), "{}", false, false) },
                "setPresetName" to { b.setPresetName(1, "x").also { runCurrent() } },
                "setPresetStartup" to { b.setPresetStartup(0, 1).also { runCurrent() } },
                "setOutputConfigMode" to { b.setOutputConfigMode(1).also { runCurrent() } },
                "setMasterVolumeMode" to { b.setMasterVolumeMode(1).also { runCurrent() } },
                "setDacMute" to { b.setDacMute(false, true, 11, 5, 0).also { runCurrent() } },
                "setUart" to { b.setUart(false, 16, 17, false, 115200).also { runCurrent() } }
            )
        for ((name, op) in arming) {
            op()
            assertTrue("$name arms the blackout", armed())
        }
        b.setBypass(true)
        b.setMasterVolume(-3f)
        runCurrent()
        assertFalse("plain SETs do not arm it", armed())
    }

    @Test
    fun setsAreNeverRetried() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        val ops = b.dangerousOps
        b.saveParams(ops.confirm(DangerousOp.SAVE_PARAMS))
        core.failNext("setUserVolume", 2) { DspiException.Transfer("stall") }
        b.setUserVolume(-3f)
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(1, core.callCount("setUserVolume"))
        core.failNext("savePreset", 1)
        assertEquals(PresetStatus.NO_REPLY, b.savePreset(ops.confirm(DangerousOp.SAVE_PRESET), 1))
        assertEquals(1, core.callCount("savePreset"))
    }

    @Test
    fun theStatusPollRidesOutABlackout() = runTest {
        val core = FakeDspiCore()
        val b = newBridge(core)
        attach(b, core)
        advanceTimeBy(61)
        b.savePreset(b.dangerousOps.confirm(DangerousOp.SAVE_PRESET), 1)
        core.failNext("fetchStatus", 2)
        advanceTimeBy(61 + 300)
        assertTrue(b.connected)
        assertTrue(b.status.value.connected)
    }
}

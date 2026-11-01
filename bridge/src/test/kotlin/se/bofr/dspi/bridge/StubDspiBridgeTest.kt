package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.fake.FakeDspiCore

@OptIn(ExperimentalCoroutinesApi::class)
class StubDspiBridgeTest {
    @Test
    fun setterReachesCoreAndEmits() = runTest {
        val core = FakeDspiCore()
        val bridge = StubDspiBridge(core, this, StandardTestDispatcher(testScheduler))
        assertEquals("1.1.6-beta5", bridge.firmwareVersion)
        bridge.setMasterVolume(-6f)
        advanceUntilIdle()
        assertEquals(-6f, bridge.masterVolumeDB)
        assertEquals(1L, bridge.stateRevision.value)
    }

    @Test
    fun gatedCallsRefuseLoudlyWithoutAValidToken() = runTest {
        val bridge = StubDspiBridge(FakeDspiCore(), this, StandardTestDispatcher(testScheduler))
        val ops = bridge.dangerousOps
        val wrong = ops.confirm(DangerousOp.DELETE_PRESET)
        assertThrows(DangerousOpNotConfirmed::class.java) { bridge.enterBootloader(wrong) }
        val boot = ops.confirm(DangerousOp.ENTER_BOOTLOADER)
        assertTrue(ops.isValid(boot))
        ops.revoke(boot)
        assertFalse(ops.isValid(boot))
        assertThrows(DangerousOpNotConfirmed::class.java) { bridge.enterBootloader(boot) }
    }

    @Test
    fun tokensAreSingleUseExceptHardwareApply() {
        var now = 0L
        val ops = StubDangerousOps(clock = { now }, ttlMs = 100)
        val save = ops.confirm(DangerousOp.SAVE_PRESET)
        ops.use(save, DangerousOp.SAVE_PRESET)
        assertThrows(DangerousOpNotConfirmed::class.java) { ops.use(save, DangerousOp.SAVE_PRESET) }
        val hw = ops.confirm(DangerousOp.HARDWARE_APPLY)
        repeat(3) {
            now += 60
            ops.use(hw, DangerousOp.HARDWARE_APPLY)
        }
        now += 101
        assertThrows(DangerousOpNotConfirmed::class.java) { ops.use(hw, DangerousOp.HARDWARE_APPLY) }
    }
}

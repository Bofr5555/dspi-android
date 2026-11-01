package se.bofr.dspi.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.FakeDspiCore

/** Decision G1 shim (until DspiBridge.writesAllowed lands): connected && compat == COMPAT_OK. */
@OptIn(ExperimentalCoroutinesApi::class)
class WritesAllowedShimTest {
    @Test
    fun writesFollowTheConnectionOfACompatibleDevice() = runTest {
        val core = FakeDspiCore.seeded()
        val d = StandardTestDispatcher(testScheduler)
        val bridge = DspiBridgeImpl(core, backgroundScope, ffi = d, main = d, clock = { testScheduler.currentTime })
        assertFalse("no device", bridge.writesAllowedNow())
        bridge.attachDevice(42, core.serial, DspiConstants.VENDOR_ID)
        runCurrent()
        assertTrue(bridge.writesAllowedNow())
        bridge.detachDevice(core.serial)
        runCurrent()
        assertFalse(bridge.writesAllowedNow())
    }
}

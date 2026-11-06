package se.bofr.dspi.bridge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.fake.FakeDspiCore

/** DSPi Link: a remote device attached by handle, the viewer role, detach and reconnect. */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteAttachTest {
    private fun linked(): FakeDspiCore = FakeDspiCore().apply {
        closeFd() // not attached over USB
        linkConnect("ws://10.0.2.2:11915/dspi/v1", "Pixel", "test", "")
    }

    @Test
    fun attachRemoteReadsTheDeviceLikeUsb() = runTest {
        val core = linked()
        val b = newBridge(core)
        assertTrue(b.attachRemote(0, core.serial))
        runCurrent()
        assertTrue(b.connected)
        assertEquals(core.serial, b.selectedSerial)
        assertTrue(b.writesAllowed.value)
        assertTrue(core.callCount("linkSelectDevice") == 1 && core.callCount("fetchAll") >= 1)
        b.detachDevice(core.serial)
        runCurrent()
        assertFalse(b.connected)
        assertEquals(1, core.callCount("closeFd") - 1) // one from linked(), one from detach
    }

    @Test
    fun aViewerIsReadOnly() = runTest {
        val core = linked()
        val b = newBridge(core)
        b.attachRemote(0, core.serial)
        runCurrent()
        val events = collectEvents(b)
        b.setRemoteReadOnly(true)
        assertFalse(b.writesAllowed.value)
        val mark = core.callCount("setUserMute")
        b.setUserMute(true)
        runCurrent()
        assertEquals(mark, core.callCount("setUserMute"))
        val refusal = events.filterIsInstance<BridgeEvent.CommandFailed>().last()
        assertEquals(WritesNotAllowedException.VIEWER, refusal.error.message)
        b.setRemoteReadOnly(false)
        assertTrue(b.writesAllowed.value)
    }

    @Test
    fun attachFailsCleanlyWithoutASession() = runTest {
        val core = FakeDspiCore().apply { closeFd() }
        val b = newBridge(core)
        assertFalse(b.attachRemote(0, core.serial))
        assertFalse(b.connected)
    }

    @Test
    fun reconnectReattachesTheRemoteDevice() = runTest {
        val core = linked()
        val b = newBridge(core)
        b.attachRemote(0, core.serial)
        runCurrent()
        b.reconnect()
        runCurrent()
        assertEquals(2, core.callCount("linkSelectDevice"))
        assertTrue(b.connected)
    }

    @Test
    fun noBootloaderEntryOverTheNetwork() = runTest {
        val core = linked()
        val b = newBridge(core)
        b.attachRemote(0, core.serial)
        runCurrent()
        val events = collectEvents(b)
        b.enterBootloader(b.dangerousOps.confirm(DangerousOp.ENTER_BOOTLOADER))
        runCurrent()
        assertEquals(0, core.callCount("enterBootloader"))
        val e = events.filterIsInstance<BridgeEvent.CommandFailed>().single()
        assertEquals(WritesNotAllowedException.REMOTE_BOOTLOADER, e.error.message)
    }
}

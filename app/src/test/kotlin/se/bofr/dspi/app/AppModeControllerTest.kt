package se.bofr.dspi.app

import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.link.LinkHub
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.usb.DspiUsbDevice
import se.bofr.dspi.usb.UsbIds
import se.bofr.dspi.usb.UsbSessionState

@OptIn(ExperimentalCoroutinesApi::class)
class AppModeControllerTest {
    /** A DspiBridge that only records setUiVisible (identity is all the controller needs). */
    private class RecordingBridge {
        val visible = mutableListOf<Boolean>()
        val bridge: DspiBridge = Proxy.newProxyInstance(DspiBridge::class.java.classLoader, arrayOf(DspiBridge::class.java)) {
                proxy,
                m,
                args
            ->
            when (m.name) {
                "setUiVisible" -> visible += args!![0] as Boolean
                "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
                "equals" -> return@newProxyInstance proxy === args!![0]
                "toString" -> return@newProxyInstance "RecordingBridge"
            }
            null
        } as DspiBridge
    }

    private class FakeDemo : DemoSession {
        val rec = RecordingBridge()
        var closed = 0
        override val bridge get() = rec.bridge

        override fun close() {
            closed++
        }
    }

    private class FakeRemote : RemoteSession {
        val rec = RecordingBridge()
        val flow = MutableStateFlow<LinkSessionState>(LinkSessionState.Idle)
        val connects = mutableListOf<LinkHub>()
        var closed = 0
        var retries = 0
        override val bridge get() = rec.bridge
        override val state get() = flow

        override fun connect(hub: LinkHub) {
            connects += hub
            flow.value = LinkSessionState.Connecting(hub)
        }

        override fun submitPin(pin: String) {}

        override fun cancelPin() {
            flow.value = LinkSessionState.Idle
        }

        override fun chooseDevice(device: LinkDevice) {}

        override fun retryNow() {
            retries++
        }

        override fun disconnect() {
            flow.value = LinkSessionState.Idle
        }

        override fun close() {
            closed++
        }
    }

    private val remotes = mutableListOf<FakeRemote>()
    private val hub = LinkHub("6f1a", "Studio Mac", "console", "none", 1, emptyList(), "192.168.1.20", 11915)

    private val usbState = MutableStateFlow<UsbSessionState>(UsbSessionState.NoDevice)
    private val usbDevices = MutableStateFlow<List<DspiUsbDevice>>(emptyList())
    private val device = RecordingBridge()
    private val demos = mutableListOf<FakeDemo>()
    private val forwarded = mutableListOf<Boolean>()

    private fun TestScope.controller() = AppModeController(
        deviceBridge = device.bridge,
        usbState = usbState,
        usbDevices = usbDevices,
        scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
        demoFactory = { FakeDemo().also { demos += it } },
        forwardUiVisible = { forwarded += it },
        remoteFactory = { FakeRemote().also { remotes += it } }
    )

    private val unit = DspiUsbDevice("/dev/bus/usb/001/002", UsbIds.DSPI_VID, "E6614103", hasPermission = true)

    @Test
    fun startsOnTheDeviceBridgeWithDemoOfferedWhenNothingIsAttached() = runTest {
        val c = controller()
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertSame(device.bridge, c.bridge.value)
        assertTrue(c.demoAvailable.value)
    }

    @Test
    fun enterDemoSwitchesToASeparateBridgeAndExitTearsItDown() = runTest {
        val c = controller()
        assertTrue(c.enterDemo())
        assertEquals(AppMode.DEMO, c.mode.value)
        assertSame(demos.single().bridge, c.bridge.value)
        assertFalse(c.demoAvailable.value)
        assertTrue(c.enterDemo()) // idempotent: no second stack
        assertEquals(1, demos.size)

        c.exitDemo()
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertSame(device.bridge, c.bridge.value)
        assertEquals(1, demos.single().closed)
        assertTrue(c.demoAvailable.value)
    }

    @Test
    fun demoIsRefusedWhileAnyDspiIsAttached() = runTest {
        val c = controller()
        for (s in listOf(
            UsbSessionState.PermissionRequired(""),
            UsbSessionState.PermissionPending("", dialogShown = true),
            UsbSessionState.PermissionDenied(""),
            UsbSessionState.Failed("x", "busy"),
            UsbSessionState.Open("x", UsbIds.DSPI_VID)
        )) {
            usbState.value = s
            assertFalse("$s", c.demoAvailable.value)
            assertFalse("$s", c.enterDemo())
        }
        usbState.value = UsbSessionState.NoDevice
        usbDevices.value = listOf(unit) // listed but no state yet
        assertFalse(c.demoAvailable.value)
        assertFalse(c.enterDemo())
        assertTrue(demos.isEmpty())
        assertSame(device.bridge, c.bridge.value)
    }

    @Test
    fun aDspiAppearingEndsDemoAtOnce() = runTest {
        val c = controller()
        c.enterDemo()
        usbState.value = UsbSessionState.PermissionRequired("")
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertSame(device.bridge, c.bridge.value)
        assertEquals(1, demos.single().closed)
        assertFalse(c.demoAvailable.value)
    }

    @Test
    fun uiVisibilityReachesTheSessionAndTheDemoBridge() = runTest {
        val c = controller()
        c.setUiVisible(false)
        c.enterDemo()
        assertEquals(listOf(false), demos.single().rec.visible) // a new demo bridge inherits the current visibility
        c.setUiVisible(true)
        assertEquals(listOf(false, true), forwarded)
        assertEquals(listOf(false, true), demos.single().rec.visible)
        assertTrue(device.visible.isEmpty()) // the session forwards to the device bridge itself
    }

    @Test
    fun remoteIsItsOwnStackAndLeavesDemo() = runTest {
        val c = controller()
        c.enterDemo()
        c.enterRemote(hub)
        assertEquals(AppMode.REMOTE, c.mode.value)
        assertEquals(1, demos.single().closed)
        assertSame(remotes.single().bridge, c.bridge.value)
        assertEquals(listOf(hub), remotes.single().connects)
        assertFalse(c.demoAvailable.value)
        assertFalse(c.enterDemo())
        c.exitRemote()
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertSame(device.bridge, c.bridge.value)
        assertEquals(1, remotes.single().closed)
    }

    @Test
    fun leavingTheHubEndsRemote() = runTest {
        val c = controller()
        c.enterRemote(hub)
        assertTrue(c.remoteState.value is LinkSessionState.Connecting)
        remotes.single().cancelPin() // the engine goes Idle
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertEquals(1, remotes.single().closed)
    }

    @Test
    fun aUsbDspiWhileRemoteAsksInsteadOfSwitching() = runTest {
        val c = controller()
        c.enterRemote(hub)
        assertFalse(c.usbPrompt.value)
        usbState.value = UsbSessionState.Open("x", UsbIds.DSPI_VID)
        assertTrue("D9: prompt, no silent switch", c.usbPrompt.value)
        assertEquals(AppMode.REMOTE, c.mode.value)
        c.answerUsbPrompt(useUsb = false)
        assertFalse(c.usbPrompt.value)
        assertEquals(AppMode.REMOTE, c.mode.value)
        // unplug + plug again asks again; "Use USB" leaves REMOTE
        usbState.value = UsbSessionState.NoDevice
        usbState.value = UsbSessionState.Open("x", UsbIds.DSPI_VID)
        assertTrue(c.usbPrompt.value)
        c.answerUsbPrompt(useUsb = true)
        assertEquals(AppMode.DEVICE, c.mode.value)
        assertSame(device.bridge, c.bridge.value)
    }

    @Test
    fun enteringRemoteWithAUsbDspiPresentDoesNotPrompt() = runTest {
        usbState.value = UsbSessionState.Open("x", UsbIds.DSPI_VID)
        val c = controller()
        c.enterRemote(hub)
        assertFalse(c.usbPrompt.value)
        assertEquals(AppMode.REMOTE, c.mode.value)
    }

    @Test
    fun theRemoteBridgeFollowsUiVisibilityAndRetriesOnReturn() = runTest {
        val c = controller()
        c.setUiVisible(false)
        c.enterRemote(hub)
        assertEquals(listOf(false), remotes.single().rec.visible)
        c.setUiVisible(true)
        assertEquals(listOf(false, true), remotes.single().rec.visible)
        assertEquals(1, remotes.single().retries)
    }
}

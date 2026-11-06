package se.bofr.dspi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.link.LinkHub
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.usb.UsbIds
import se.bofr.dspi.usb.UsbSessionState

class ConnectionStripTest {
    @Test
    fun demoModeIsAlwaysLabelled() {
        for (s in listOf(UsbSessionState.NoDevice, UsbSessionState.Open("x", UsbIds.DSPI_VID))) {
            val strip = connectionStrip(AppMode.DEMO, s, demoAvailable = false)
            assertEquals(ConnectionStrip.Demo, strip)
            assertTrue(strip.text.startsWith("Demo (no device)"))
        }
    }

    @Test
    fun deviceModeFollowsTheUsbSession() {
        fun of(s: UsbSessionState, demo: Boolean = false) = connectionStrip(AppMode.DEVICE, s, demo)
        assertEquals(ConnectionStrip.NoDevice(true), of(UsbSessionState.NoDevice, demo = true))
        assertEquals(ConnectionStrip.NoDevice(false), of(UsbSessionState.NoDevice))
        assertTrue(of(UsbSessionState.PermissionRequired("")) is ConnectionStrip.NeedsPermission)
        assertTrue(of(UsbSessionState.PermissionDenied("")) is ConnectionStrip.NeedsPermission)
        assertTrue(of(UsbSessionState.PermissionPending("", dialogShown = false)) is ConnectionStrip.Info)
        assertTrue(of(UsbSessionState.Failed("x", "busy")).text.contains("busy"))
        assertEquals(ConnectionStrip.Hidden, of(UsbSessionState.Open("x", UsbIds.DSPI_LEGACY_VID)))
    }

    private val hub = LinkHub("6f1a", "Studio Mac", "console", "pin", 1, emptyList(), "192.168.1.20", 11915)
    private val dev = LinkDevice(0, "0123456789ABCDEF", "Living room", "1.1.6", 0, 1, "online", "usb", 0)

    @Test
    fun noDeviceOffersTheNetworkWhenLinkIsOn() {
        assertEquals(
            ConnectionStrip.NoDevice(true, true),
            connectionStrip(AppMode.DEVICE, UsbSessionState.NoDevice, true, networkAvailable = true)
        )
    }

    @Test
    fun remoteModeIsAlwaysLabelledWithItsHub() {
        fun of(s: LinkSessionState, lock: Boolean = false) = connectionStrip(
            AppMode.REMOTE,
            UsbSessionState.Open("x", UsbIds.DSPI_VID),
            false,
            s,
            lockNotice = lock
        ) as ConnectionStrip.Remote
        assertEquals("Living room via Studio Mac", of(LinkSessionState.Connected(hub, dev, "control")).text)
        assertEquals("Living room via Studio Mac · view only", of(LinkSessionState.Connected(hub, dev, "viewer")).text)
        assertEquals(DspiException.LOCKED_MESSAGE, of(LinkSessionState.Connected(hub, dev, "control"), lock = true).text)
        assertTrue(of(LinkSessionState.Connecting(hub)).text.startsWith("Connecting to Studio Mac"))
        assertTrue(of(LinkSessionState.NeedsPin(hub, "window")).text.contains("needs pairing"))
        val r = of(LinkSessionState.Reconnecting(hub, "hub shutting down", 0, 1))
        assertTrue(r.text.contains("hub shutting down"))
        assertEquals(listOf(RemoteAction.RETRY, RemoteAction.DISCONNECT), r.actions)
        assertEquals(listOf(RemoteAction.CONNECT, RemoteAction.DISCONNECT), of(LinkSessionState.Failed(hub, "revoked")).actions)
        assertTrue(of(LinkSessionState.WaitingForDevice(hub, "0123456789ABCDEF")).text.contains("89ABCDEF"))
    }
}

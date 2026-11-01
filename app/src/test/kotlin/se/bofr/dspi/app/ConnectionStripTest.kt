package se.bofr.dspi.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
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
}

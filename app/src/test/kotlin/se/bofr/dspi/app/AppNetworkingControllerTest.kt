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
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.link.HubBrowser
import se.bofr.dspi.link.InMemoryLinkSettingsStore
import se.bofr.dspi.link.LinkHub
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.link.LinkTokenStore
import se.bofr.dspi.link.PairedHub
import se.bofr.dspi.usb.DspiUsbDevice
import se.bofr.dspi.usb.UsbSessionState

@OptIn(ExperimentalCoroutinesApi::class)
class AppNetworkingControllerTest {
    private val bridge = Proxy.newProxyInstance(DspiBridge::class.java.classLoader, arrayOf(DspiBridge::class.java)) { p, m, a ->
        when (m.name) {
            "hashCode" -> System.identityHashCode(p)
            "equals" -> p === a!![0]
            else -> null
        }
    } as DspiBridge

    private class Browser : HubBrowser {
        override val hubs = MutableStateFlow<List<LinkHub>>(emptyList())
        override val error = MutableStateFlow<String?>(null)
        override val browsing = MutableStateFlow(false)
        var started = 0
        var stopped = 0

        override fun start() {
            started++
            browsing.value = true
        }

        override fun stop() {
            stopped++
            browsing.value = false
        }
    }

    private class Tokens : LinkTokenStore {
        val m = HashMap<String, String>()

        override fun token(hubId: String) = m[hubId]

        override fun setToken(hubId: String, name: String, token: String) {
            m[hubId] = token
        }

        override fun remove(hubId: String) {
            m.remove(hubId)
        }

        override fun paired() = m.keys.map { PairedHub(it, "Hub $it") }
    }

    private class Remote(override val bridge: DspiBridge) : RemoteSession {
        override val state = MutableStateFlow<LinkSessionState>(LinkSessionState.Idle)
        val connected = mutableListOf<LinkHub>()

        override fun connect(hub: LinkHub) {
            connected += hub
            state.value = LinkSessionState.Connected(hub, LinkDevice(0, "S", "Bench", "", -1, -1, "online", "usb", 0), "control")
        }

        override fun submitPin(pin: String) {}

        override fun cancelPin() {}

        override fun chooseDevice(device: LinkDevice) {}

        override fun retryNow() {}

        override fun disconnect() {}

        override fun close() {}
    }

    private fun TestScope.setup(): Triple<AppNetworkingController, Browser, MutableList<Remote>> {
        val scope = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
        val remotes = mutableListOf<Remote>()
        val modes = AppModeController(
            bridge,
            MutableStateFlow<UsbSessionState>(UsbSessionState.NoDevice),
            MutableStateFlow<List<DspiUsbDevice>>(emptyList()),
            scope,
            demoFactory = { error("no demo") },
            forwardUiVisible = {},
            remoteFactory = { Remote(bridge).also { remotes += it } }
        )
        val browser = Browser()
        val c = AppNetworkingController(
            InMemoryLinkSettingsStore(),
            browser,
            Tokens(),
            modes,
            scope,
            "Pixel 9",
            parseAddress = { t ->
                t.trim().takeIf { it.isNotEmpty() && ' ' !in it }?.let {
                    it.substringBefore(':') to
                        (it.substringAfter(':', "11915").toIntOrNull() ?: 0)
                }
            }
        )
        return Triple(c, browser, remotes)
    }

    @Test
    fun offByDefaultAndBrowsingOnlyWhileVisibleAndOn() = runTest {
        val (c, b, _) = setup()
        assertFalse(c.state.value.enabled)
        assertEquals("Pixel 9", c.state.value.defaultClientName)
        val stops = b.stopped
        c.setVisible(true)
        assertEquals(0, b.started)
        c.setEnabled(true)
        assertEquals(1, b.started)
        c.setFindAutomatically(false)
        assertEquals(stops + 1, b.stopped)
        c.setFindAutomatically(true)
        assertEquals(2, b.started)
        c.setVisible(false)
        assertEquals(stops + 2, b.stopped)
    }

    @Test
    fun manualHubsAndConnecting() = runTest {
        val (c, b, remotes) = setup()
        c.connect("x") // off: nothing happens
        assertTrue(remotes.isEmpty())
        c.setEnabled(true)
        assertFalse(c.isValidAddress("a b"))
        assertTrue(c.addManual("192.168.1.20"))
        b.hubs.value = listOf(LinkHub("6f1a", "Studio Mac", "console", "pin", 2, emptyList(), "192.168.1.30", 11915))
        val rows = c.state.value.hubs
        assertEquals(listOf("Studio Mac", "192.168.1.20"), rows.map { it.name })
        assertEquals("192.168.1.30, 2 devices", rows[0].detail)
        assertFalse(rows[0].open)
        assertTrue(rows[1].manual)
        c.connect("6f1a")
        assertEquals("Studio Mac", remotes.single().connected.single().name)
        assertTrue(c.state.value.hubs[0].active)
        assertEquals("Bench via Studio Mac", c.state.value.status)
        c.removeManual(LinkHub.manualId("192.168.1.20", 11915))
        assertEquals(1, c.state.value.hubs.size)
        c.setEnabled(false) // turning Link off leaves REMOTE
        assertFalse(c.state.value.connected)
    }
}

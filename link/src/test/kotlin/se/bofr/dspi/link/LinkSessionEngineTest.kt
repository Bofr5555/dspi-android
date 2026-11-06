package se.bofr.dspi.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.bridge.InMemoryLinkStore
import se.bofr.dspi.fake.FakeDspiCore

/**
 * The engine over the REMOTE stack's real bridge (DspiBridgeImpl) and a
 * FakeDspiCore playing the hub: connect, pairing, tokens, reconnect,
 * revocation, viewer role, leaving.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkSessionEngineTest {
    private class Tokens : LinkTokenStore {
        val m = HashMap<String, Pair<String, String>>()

        override fun token(hubId: String) = m[hubId]?.second

        override fun setToken(hubId: String, name: String, token: String) {
            m[hubId] = name to token
        }

        override fun remove(hubId: String) {
            m.remove(hubId)
        }

        override fun paired() = m.map { PairedHub(it.key, it.value.first) }
    }

    private class Service : LinkServiceControl {
        var running = false

        override fun start(): Boolean {
            running = true
            return true
        }

        override fun stop() {
            running = false
        }
    }

    private class Stack(scope: TestScope, val core: FakeDspiCore = FakeDspiCore().apply { closeFd() }) {
        val d = StandardTestDispatcher(scope.testScheduler)
        val bridge =
            DspiBridgeImpl(core, scope.backgroundScope, ffi = d, main = d, linkStore = InMemoryLinkStore(), clock = {
                scope.testScheduler.currentTime
            })
        val tokens = Tokens()
        val service = Service()
        val engine = LinkSessionEngine(
            BridgeLinkCoreOps(core, bridge, d),
            tokens,
            { "Pixel 9" },
            "test",
            scope.backgroundScope,
            service,
            clock = { scope.testScheduler.currentTime }
        )
    }

    private val hub = LinkHub("6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b", "Fake Hub", "console", "none", 1, emptyList(), "10.0.2.2", 11915)

    @Test
    fun anOpenHubConnectsAndAttachesItsDevice() = runTest {
        val s = Stack(this)
        s.engine.connect(hub)
        runCurrent()
        val st = s.engine.state.value as LinkSessionState.Connected
        assertEquals(s.core.serial, st.device.serial)
        assertTrue(s.bridge.connected)
        assertTrue(s.bridge.writesAllowed.value)
        assertTrue(s.service.running)
        assertEquals(1, s.core.callCount("linkSelectDevice"))
        assertTrue(s.core.calls.any { it.startsWith("linkConnect(ws://10.0.2.2:11915/dspi/v1, Pixel 9") })

        s.engine.disconnect()
        runCurrent()
        assertEquals(LinkSessionState.Idle, s.engine.state.value)
        assertFalse(s.bridge.connected)
        assertFalse(s.service.running)
        assertEquals(1, s.core.callCount("linkClose"))
    }

    @Test
    fun aPinHubAsksThenRemembersTheToken() = runTest {
        val s = Stack(this)
        s.core.linkHello = s.core.linkHello.copy(auth = "pin")
        s.engine.connect(hub.copy(auth = "pin"))
        runCurrent()
        val ask = s.engine.state.value as LinkSessionState.NeedsPin
        assertEquals("window", ask.pinSource)
        assertTrue(PairingText.message(ask.hub, ask.pinSource).startsWith("On Fake Hub, open Settings > Networking"))
        s.engine.submitPin("000000")
        runCurrent()
        assertEquals("Pairing failed: the hub did not accept that code.", (s.engine.state.value as LinkSessionState.NeedsPin).error)
        s.engine.submitPin("123456")
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
        assertEquals(s.core.linkToken, s.tokens.token(hub.id))

        // Next time the token is offered and no prompt appears
        s.engine.disconnect()
        runCurrent()
        s.engine.connect(hub.copy(auth = "pin"))
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
    }

    @Test
    fun aDroppedSessionReconnectsWithBackoffToTheSameDevice() = runTest {
        val s = Stack(this)
        s.engine.connect(hub)
        runCurrent()
        s.core.linkDrop(4003, "hub shutting down")
        runCurrent()
        val r = s.engine.state.value as LinkSessionState.Reconnecting
        assertEquals("hub shutting down", r.reason)
        assertFalse(s.bridge.connected)
        assertFalse(s.service.running)
        advanceTimeBy(900)
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Reconnecting)
        advanceTimeBy(200)
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
        assertEquals(2, s.core.callCount("linkSelectDevice"))
        assertTrue(s.bridge.connected)
    }

    @Test
    fun aRevokedTokenIsDroppedAndTheEngineStops() = runTest {
        val s = Stack(this)
        s.tokens.setToken(hub.id, "Fake Hub", "fake-token")
        s.core.linkHello = s.core.linkHello.copy(auth = "pin")
        s.engine.connect(hub)
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
        s.core.linkDrop(4002, "access revoked")
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Failed)
        assertNull(s.tokens.token(hub.id))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals("no reconnect after 4002", 1, s.core.callCount("linkConnect"))
    }

    @Test
    fun aViewerSessionIsReadOnly() = runTest {
        val s = Stack(this)
        s.core.linkRole = "viewer"
        s.engine.connect(hub)
        runCurrent()
        assertEquals("viewer", (s.engine.state.value as LinkSessionState.Connected).role)
        assertTrue(s.bridge.connected)
        assertFalse(s.bridge.writesAllowed.value)
    }

    @Test
    fun aPinTypedAfterTheHubsDeadlineGoesOverANewConnection() = runTest {
        val s = Stack(this)
        s.core.linkHello = s.core.linkHello.copy(auth = "pin")
        s.engine.connect(hub)
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.NeedsPin)
        s.core.linkDrop(4001, "authentication timed out")
        runCurrent()
        assertTrue("the prompt stays", s.engine.state.value is LinkSessionState.NeedsPin)
        s.engine.submitPin("123456")
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
        assertEquals(2, s.core.callCount("linkConnect"))
    }

    @Test
    fun cancellingThePromptLeaves() = runTest {
        val s = Stack(this)
        s.core.linkHello = s.core.linkHello.copy(auth = "pin")
        s.engine.connect(hub)
        runCurrent()
        s.engine.cancelPin()
        runCurrent()
        assertEquals(LinkSessionState.Idle, s.engine.state.value)
    }

    @Test
    fun anUnreachableHubKeepsRetrying() = runTest {
        val s = Stack(this)
        s.core.failNext("linkConnect", times = 2) { se.bofr.dspi.core.DspiException.Link("io", "Connection refused") }
        s.engine.connect(hub)
        runCurrent()
        assertEquals("Connection refused", (s.engine.state.value as LinkSessionState.Reconnecting).reason)
        advanceTimeBy(1_100)
        runCurrent()
        val r2 = s.engine.state.value as LinkSessionState.Reconnecting
        assertEquals(2, r2.attempt)
        s.engine.retryNow()
        runCurrent()
        assertTrue(s.engine.state.value is LinkSessionState.Connected)
    }
}

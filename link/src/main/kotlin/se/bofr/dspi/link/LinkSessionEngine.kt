// Port of DSPi-Console release/v1.1.6 `Link/NetworkTransport.swift` (the
// app-side policy around one hub's LinkClient): bind by serial, pair when the
// hub asks (keeping a PIN typed after the hub's 30 s auth deadline for the
// next connection), store the token by hub id, reconnect with backoff 1, 2,
// 4 ... 30 s (spec 5), and keep the chosen device across reconnects.
// Android differences: the pairing prompt is a non-modal state the UI shows
// ([LinkSessionState.NeedsPin]); a device list with several boards asks the
// user ([LinkSessionState.ChooseDevice]) instead of macOS's device menu; no
// reconnect after close codes 4000 (protocol error) and 4002 (revoked: the
// token is dropped and the user pairs again).
package se.bofr.dspi.link

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.bofr.dspi.core.DspiException
import se.bofr.dspi.core.LinkDevice
import se.bofr.dspi.core.LinkListener

/** Where the REMOTE connection stands. */
sealed interface LinkSessionState {
    val hub: LinkHub?

    data object Idle : LinkSessionState {
        override val hub: LinkHub? = null
    }

    data class Connecting(override val hub: LinkHub) : LinkSessionState

    /** The hub wants a PIN: show the pairing prompt. [pinSource] `window` or `fixed` (spec 6.2). */
    data class NeedsPin(override val hub: LinkHub, val pinSource: String, val error: String = "", val busy: Boolean = false) :
        LinkSessionState

    /** The hub shares several boards and none was chosen yet. */
    data class ChooseDevice(override val hub: LinkHub, val devices: List<LinkDevice>) : LinkSessionState

    /** Connected to the hub, but [serial] (or any board, when empty) is not online there. */
    data class WaitingForDevice(override val hub: LinkHub, val serial: String) : LinkSessionState

    /** Controlling [device] through [hub]; [role] `viewer`, `control` or `admin`. */
    data class Connected(override val hub: LinkHub, val device: LinkDevice, val role: String) : LinkSessionState

    /** Lost (or could not reach) the hub; the next attempt is at [retryAtMs]. */
    data class Reconnecting(override val hub: LinkHub, val reason: String, val retryAtMs: Long, val attempt: Int) : LinkSessionState

    /** Gave up (access revoked, protocol error); the user connects again by hand. */
    data class Failed(override val hub: LinkHub, val reason: String) : LinkSessionState
}

/** The foreground service while a remote device is open (`connectedDevice`). */
interface LinkServiceControl {
    fun start(): Boolean

    fun stop()

    companion object {
        val NONE = object : LinkServiceControl {
            override fun start() = true

            override fun stop() {}
        }
    }
}

/**
 * One REMOTE connection's policy. Main thread only (all calls and state).
 * [clientName] is read at every connect (Settings may change it).
 */
class LinkSessionEngine(
    private val ops: LinkCoreOps,
    private val tokens: LinkTokenStore,
    private val clientName: () -> String,
    private val appVersion: String,
    private val scope: CoroutineScope,
    private val service: LinkServiceControl = LinkServiceControl.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<LinkSessionState>(LinkSessionState.Idle)
    val state: StateFlow<LinkSessionState> = _state.asStateFlow()

    /** The hub the user wants (null = none: Idle). */
    private var hub: LinkHub? = null

    /** The board to bind to, by serial (kept across reconnects, macOS `bind(serial:)`). */
    private var wantedSerial: String? = null
    private var attached: LinkDevice? = null
    private var role = "admin"
    private var attempt = 0

    /** A PIN typed after the session closed under the prompt: sent on the next hello. */
    private var pendingPin: String? = null

    /** The session under the PIN prompt is gone (auth deadline): reconnect before pairing. */
    private var promptSessionDead = false

    /** Bumped for every session and teardown: callbacks of an older one do nothing. */
    private var generation = 0
    private var job: Job? = null

    val attachedSerial: String? get() = attached?.serial

    /** Connect to [target] (a user tap), optionally to board [serial]. */
    fun connect(target: LinkHub, serial: String? = null) {
        val sameHub = hub?.id == target.id
        hub = target
        wantedSerial = serial ?: wantedSerial.takeIf { sameHub }
        attempt = 0
        pendingPin = null
        scope.launch {
            detachCurrent()
            start()
        }
    }

    /** The user typed a PIN in the prompt. */
    fun submitPin(pin: String) {
        val s = _state.value as? LinkSessionState.NeedsPin ?: return
        _state.value = s.copy(busy = true, error = "")
        if (promptSessionDead) {
            pendingPin = pin
            start()
            return
        }
        val gen = generation
        job = scope.launch { pair(s.hub, s.pinSource, pin, gen) }
    }

    /** The user cancelled the prompt: give the connection up (macOS). */
    fun cancelPin() {
        if (_state.value is LinkSessionState.NeedsPin) disconnect()
    }

    fun chooseDevice(device: LinkDevice) {
        val s = _state.value as? LinkSessionState.ChooseDevice ?: return
        wantedSerial = device.serial
        val gen = generation
        job = scope.launch { attach(s.hub, device, gen) }
    }

    /** Try now instead of waiting out the backoff (app back in front, network back). */
    fun retryNow() {
        if (_state.value is LinkSessionState.Reconnecting) start()
    }

    /** Leave REMOTE: close the session and forget the hub. Idempotent. */
    fun disconnect() {
        generation++
        job?.cancel()
        job = null
        val had = hub != null
        hub = null
        wantedSerial = null
        pendingPin = null
        promptSessionDead = false
        _state.value = LinkSessionState.Idle
        if (!had && attached == null) return
        scope.launch {
            detachCurrent()
            ops.setListener(null)
            runCatching { ops.close() }
        }
    }

    /** [disconnect], then wait for the device to be detached and the session closed (stack teardown). */
    suspend fun close() {
        generation++
        job?.cancel()
        job = null
        hub = null
        wantedSerial = null
        _state.value = LinkSessionState.Idle
        detachCurrent()
        ops.setListener(null)
        runCatching { ops.close() }
    }

    /** Forget the token stored for [hubId] (Settings › Networking). */
    fun forget(hubId: String) = tokens.remove(hubId)

    // ── Session ───────────────────────────────────────────────────────

    private fun start() {
        val h = hub ?: return
        val gen = ++generation
        promptSessionDead = false
        job?.cancel()
        job = scope.launch { runSession(h, gen) }
    }

    private suspend fun runSession(target: LinkHub, gen: Int) {
        _state.value = LinkSessionState.Connecting(target)
        var h = target
        if (h.isManual && h.id.startsWith("manual:")) {
            // A typed address: its info document names the hub (for the token) and its path
            runCatching { ops.fetchInfo(h.host, h.port) }.getOrNull()?.let { i ->
                h =
                    h.copy(
                        id = i.hubId.ifEmpty {
                            h.id
                        },
                        name = i.hubName.ifEmpty { h.name },
                        kind = i.hubKind,
                        auth = i.auth,
                        path = i.wsPath
                    )
            }
            if (gen != generation) return
        }
        ops.setListener(listenerFor(gen))
        val token = tokens.token(h.id).orEmpty()
        val hello = try {
            ops.connect(ops.wsUrl(h.host, h.port, h.path), clientName(), appVersion, token)
        } catch (e: DspiException) {
            if (gen == generation) retryLater(h, describe(e))
            return
        }
        if (gen != generation) return
        h = h.copy(
            id = hello.hubId.ifEmpty { h.id },
            name = hello.hubName.ifEmpty { h.name },
            kind = hello.hubKind.ifEmpty { h.kind },
            auth = hello.auth.ifEmpty { h.auth }
        )
        hub = h
        log("link: connected to ${h.name} (${h.kind}, auth ${hello.auth}, 1.${hello.protoMinor})")
        if (hello.needsPairing) {
            val stored = tokens.token(h.id)
            if (token.isEmpty() && stored != null) {
                // Only the hello named this hub: dial again with its token
                start()
                return
            }
            if (token.isNotEmpty()) tokens.remove(h.id) // refused: pair again
            val pin = pendingPin
            pendingPin = null
            if (pin != null) {
                _state.value = LinkSessionState.NeedsPin(h, hello.pinSource, busy = true)
                pair(h, hello.pinSource, pin, gen)
            } else {
                _state.value = LinkSessionState.NeedsPin(h, hello.pinSource)
            }
            return
        }
        afterAuth(h, gen)
    }

    private suspend fun pair(h: LinkHub, pinSource: String, pin: String, gen: Int) {
        val token = try {
            ops.pair(pin)
        } catch (e: DspiException) {
            if (gen != generation) return
            if (e is DspiException.Disconnected || (e is DspiException.Link && e.code == "closed")) {
                // The hub's auth deadline closed the socket: send the PIN on the next connection
                pendingPin = pin
                start()
            } else {
                _state.value = LinkSessionState.NeedsPin(h, pinSource, error = pairingError(e))
            }
            return
        }
        if (gen != generation) return
        if (token.isNotEmpty()) tokens.setToken(h.id, h.name, token)
        afterAuth(h, gen)
    }

    private suspend fun afterAuth(h: LinkHub, gen: Int) {
        attempt = 0
        val st = runCatching { ops.status() }.getOrNull()
        val devices = runCatching { ops.devices() }.getOrDefault(emptyList())
        if (gen != generation) return
        role = st?.role?.ifEmpty { "admin" } ?: "admin"
        ops.setReadOnly(role == "viewer")
        choose(h, devices, gen)
    }

    private suspend fun choose(h: LinkHub, devices: List<LinkDevice>, gen: Int) {
        val online = devices.filter { it.state == "online" || it.state.isEmpty() }
        val want = wantedSerial
        val pick = when {
            want != null -> online.firstOrNull { it.serial == want }
            online.size == 1 -> online[0]
            else -> null
        }
        when {
            pick != null -> attach(h, pick, gen)
            want != null -> _state.value = LinkSessionState.WaitingForDevice(h, want)
            online.isEmpty() -> _state.value = LinkSessionState.WaitingForDevice(h, "")
            else -> _state.value = LinkSessionState.ChooseDevice(h, online)
        }
    }

    private suspend fun attach(h: LinkHub, d: LinkDevice, gen: Int) {
        _state.value = LinkSessionState.Connecting(h)
        wantedSerial = d.serial
        val ok = ops.attach(d.handle, d.serial)
        if (gen != generation) {
            if (ok) ops.detach(d.serial)
            return
        }
        if (!ok) {
            retryLater(h, "Could not open the DSPi on ${h.name}.")
            return
        }
        attached = d
        service.start()
        _state.value = LinkSessionState.Connected(h, d, role)
    }

    private suspend fun detachCurrent() {
        val d = attached ?: return
        attached = null
        runCatching { ops.detach(d.serial) }
        ops.setReadOnly(false)
        service.stop()
    }

    private fun retryLater(h: LinkHub, reason: String) {
        val delayMs = minOf(30_000L, 1_000L shl attempt.coerceAtMost(5))
        attempt++
        val gen = ++generation
        _state.value = LinkSessionState.Reconnecting(h, reason, clock() + delayMs, attempt)
        log("link: $reason; retry in ${delayMs / 1000} s")
        job = scope.launch {
            delay(delayMs)
            if (gen == generation) start()
        }
    }

    // ── Session events (LinkListener, on the Link reader thread) ───────

    private fun listenerFor(gen: Int) = object : LinkListener {
        override fun onLinkDevicesChanged() {
            scope.launch { devicesChanged(gen) }
        }

        override fun onLinkClosed(code: Int, reason: String) {
            scope.launch { closed(gen, code, reason) }
        }
    }

    private suspend fun devicesChanged(gen: Int) {
        if (gen != generation) return
        val h = hub ?: return
        val devices = runCatching { ops.devices() }.getOrDefault(emptyList())
        if (gen != generation) return
        when (val s = _state.value) {
            is LinkSessionState.Connected -> {
                val now = devices.firstOrNull { it.serial == s.device.serial }
                if (now == null || (now.state != "online" && now.state.isNotEmpty())) {
                    // Unplugged from the hub, or updating: wait for it to come back
                    detachCurrent()
                    _state.value = LinkSessionState.WaitingForDevice(h, s.device.serial)
                } else if (now != s.device) {
                    if (now.handle != s.device.handle) {
                        // Re-attached under a new handle (spec 2): bind again
                        detachCurrent()
                        attach(h, now, gen)
                    } else {
                        _state.value = s.copy(device = now)
                    }
                }
            }
            is LinkSessionState.WaitingForDevice, is LinkSessionState.ChooseDevice -> choose(h, devices, gen)
            else -> {}
        }
    }

    private suspend fun closed(gen: Int, code: Int, reason: String) {
        if (gen != generation) return
        val h = hub ?: return
        if (_state.value is LinkSessionState.NeedsPin) {
            // Keep the prompt; the PIN goes over a new connection (macOS pendingPIN)
            promptSessionDead = true
            return
        }
        generation++
        job?.cancel()
        detachCurrent()
        when (code) {
            4000 -> _state.value = LinkSessionState.Failed(h, "The hub reported a protocol error.")
            4002 -> {
                tokens.remove(h.id)
                _state.value = LinkSessionState.Failed(h, "${h.name} revoked this phone's access. Pair again to reconnect.")
            }
            else -> retryLater(h, reason.ifEmpty { "connection lost" })
        }
    }

    private fun describe(e: DspiException): String = when (e) {
        is DspiException.Link -> e.msg
        else -> e.message ?: e.toString()
    }

    /** macOS NetworkTransport: "Pairing failed: …". */
    private fun pairingError(e: DspiException): String = when {
        e is DspiException.Link && e.code == "denied" -> "Pairing failed: the hub did not accept that code."
        e is DspiException.Link && e.code == "rate_limited" -> "Pairing failed: too many attempts. Wait a minute and try again."
        else -> "Pairing failed: ${describe(e)}"
    }
}

/**
 * The PIN prompt's text (macOS `PairingPrompt`, spec 6.2): a Console hub
 * shows the code under Settings > Networking; another hub's window PIN is
 * "shown on the hub"; a fixed PIN is the hub's own.
 */
object PairingText {
    fun title(hub: LinkHub) = "Pair with ${hub.name}"

    fun message(hub: LinkHub, pinSource: String): String = when {
        pinSource == "fixed" -> "Enter the hub's PIN."
        hub.kind == "console" ->
            "On ${hub.name}, open Settings > Networking and choose Allow a New Client, then enter the code it shows."
        else -> "Enter the PIN shown on the hub."
    }
}

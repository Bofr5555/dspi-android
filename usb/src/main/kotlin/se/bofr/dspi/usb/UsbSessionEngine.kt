// Lifecycle concepts ported from DSPi-Console (macOS)
// `DSPi Console/USBDevice.swift` @ v1.1.6-beta5 (openWithRetry delays,
// onMatched "rematch = new instance, reopen" and "never steal a healthy
// connection", onTerminated "keep the selection for re-plug, switch to a
// survivor") and DSPi-Console-Linux `qt/src/DSPiBridge.cpp` `rescan()` /
// `reconnect()` (prefer the previously selected serial). Android specifics
// (permission, fd hand-off, detach order) per PLAN-NATIVE-ANDROID.md §5.2.
package se.bofr.dspi.usb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The platform-independent USB session state machine. All events go through
 * one channel and are handled one at a time on [scope], so attach/detach/
 * permission races resolve in arrival order.
 *
 * Invariants:
 * - at most one open [UsbLink]; its fd goes to [CoreLink.attach] only after
 *   the vendor interface was claimed (`force = false`);
 * - teardown order: [CoreLink.detach] → release interface 2 → [UsbLink.close]
 *   → service stop (PLAN §5.2), run to the end even under cancellation;
 * - the service is wanted exactly while a device is [UsbSessionState.Open]
 *   (the start is re-issued whenever a UI becomes visible, which recovers a
 *   refused one);
 * - an exception from one event is logged and the loop goes on; if the loop
 *   ends (scope cancelled) an open device is still torn down;
 * - a denial is remembered per device until an explicit [requestPermission]
 *   or the device detaches; permission dialogs only appear while a UI is
 *   visible (otherwise they are queued: `PermissionPending(dialogShown = false)`).
 */
class UsbSessionEngine(
    private val host: UsbHost,
    private val core: CoreLink,
    private val service: ConnectionServiceControl,
    private val scope: CoroutineScope,
    private val openRetryDelaysMs: List<Long> = OPEN_RETRY_DELAYS_MS,
    private val attachIntentGraceMs: Long = ATTACH_INTENT_GRACE_MS,
    private val log: (String) -> Unit = {}
) {
    private val _state = MutableStateFlow<UsbSessionState>(UsbSessionState.NoDevice)
    val state: StateFlow<UsbSessionState> = _state.asStateFlow()

    private val _devices = MutableStateFlow<List<DspiUsbDevice>>(emptyList())

    /** Every DSPi on the bus (multi-device picker, "inaccessible" count). */
    val devices: StateFlow<List<DspiUsbDevice>> = _devices.asStateFlow()

    private sealed interface Event {
        data class Scan(val askPermission: Boolean) : Event

        data class Attached(val device: UsbDeviceRef, val fromActivityIntent: Boolean) : Event

        data class Detached(val device: UsbDeviceRef) : Event

        data class PermissionResult(val device: UsbDeviceRef, val granted: Boolean) : Event

        data class GraceExpired(val device: UsbDeviceRef) : Event

        data object RequestPermission : Event

        data class Select(val serial: String) : Event

        data class UiVisible(val visible: Boolean) : Event

        data class Close(val done: CompletableDeferred<Unit>) : Event
    }

    private class Opened(val device: UsbDeviceRef, val link: UsbLink, val serial: String)

    private val events = Channel<Event>(Channel.UNLIMITED)
    private var loop: Job? = null
    private var opened: Opened? = null
    private var uiVisible = false

    /** Serial of the last unit we had open: preferred on re-attach (core keeps selected_serial). */
    var lastSerial: String? = null
        private set

    /** Device names with a dialog on screen (answer pending). */
    private val asked = mutableSetOf<String>()

    /** Device names whose request waits for a visible UI. */
    private val queued = mutableSetOf<String>()

    /** Device names the user denied; cleared by an explicit request or detach. */
    private val denied = mutableSetOf<String>()

    /** false after [close] until the user/system asks for a device again. */
    private var autoConnect = true

    val isStarted: Boolean get() = loop?.isActive == true

    /** Idempotent. */
    fun start() {
        if (isStarted) return
        val job = scope.launch {
            try {
                for (e in events) {
                    try {
                        handle(e)
                    } catch (c: CancellationException) {
                        // Only our own cancellation ends the loop; a foreign one
                        // (e.g. from the bridge's scope) is just a failed event.
                        if (!currentCoroutineContext().isActive) throw c
                        log("event $e cancelled: $c")
                    } catch (t: Throwable) {
                        // One bad event (a UsbManager RuntimeException, a throwing
                        // close()) must not kill the loop and strand an open fd.
                        log("event $e failed: $t")
                    } finally {
                        if (e is Event.Close) e.done.complete(Unit)
                    }
                }
            } finally {
                // The loop is ending (scope cancelled): never leave the fd or the
                // service behind.
                withContext(NonCancellable) {
                    opened?.let { teardown(it) }
                    _state.value = UsbSessionState.NoDevice
                }
            }
        }
        // Never leave a close() waiting on a loop that died.
        job.invokeOnCompletion {
            while (true) {
                val e = events.tryReceive().getOrNull() ?: break
                if (e is Event.Close) e.done.complete(Unit)
            }
        }
        loop = job
    }

    // ── Inputs ──────────────────────────────────────────────────────────

    /** Cold start / UI request: enumerate and connect (asks for permission if [askPermission]). */
    fun scan(askPermission: Boolean) = post(Event.Scan(askPermission))

    fun onAttached(device: UsbDeviceRef, fromActivityIntent: Boolean) = post(Event.Attached(device, fromActivityIntent))

    fun onDetached(device: UsbDeviceRef) = post(Event.Detached(device))

    fun onPermissionResult(device: UsbDeviceRef, granted: Boolean) = post(Event.PermissionResult(device, granted))

    /** Explicit user request: clears a remembered denial. */
    fun requestPermission() = post(Event.RequestPermission)

    /** Switch to another attached unit (multi-device picker). Unknown serial: no-op. */
    fun select(serial: String) = post(Event.Select(serial))

    fun setUiVisible(visible: Boolean) = post(Event.UiVisible(visible))

    /**
     * Full teardown. Returns at once if the loop is not running. The teardown
     * itself runs in the loop, so cancelling the caller does not interrupt it.
     */
    suspend fun close() {
        if (!isStarted) return
        val done = CompletableDeferred<Unit>()
        post(Event.Close(done))
        done.await()
    }

    private fun post(e: Event) {
        events.trySend(e)
    }

    // ── Event handling ──────────────────────────────────────────────────

    private suspend fun handle(e: Event) {
        when (e) {
            is Event.Scan -> {
                autoConnect = true
                refreshDevices()
                if (opened == null) connectBest(askPermission = e.askPermission)
            }

            is Event.Attached -> onAttachedEvent(e.device, e.fromActivityIntent)

            is Event.Detached -> onDetachedEvent(e.device)

            is Event.PermissionResult -> {
                val name = e.device.deviceName
                asked.remove(name)
                queued.remove(name)
                if (!e.granted) denied.add(name) else denied.remove(name)
                refreshDevices()
                if (e.device.isDspi && opened == null && autoConnect) {
                    if (e.granted && present(e.device)) connect(e.device) else connectBest(askPermission = false)
                }
            }

            is Event.GraceExpired -> {
                val d = e.device
                if (opened == null && autoConnect && present(d) && !host.hasPermission(d)) {
                    connectBest(askPermission = uiVisible)
                }
            }

            Event.RequestPermission -> {
                autoConnect = true
                refreshDevices()
                if (opened == null) {
                    // Explicit: forget a denial, and a dialog whose answer was lost.
                    pickCandidate()?.let {
                        denied.remove(it.deviceName)
                        asked.remove(it.deviceName)
                    }
                    connectBest(askPermission = true)
                }
            }

            is Event.Select -> {
                autoConnect = true
                refreshDevices()
                if (opened?.serial == e.serial) return
                val target = host.devices().firstOrNull { it.isDspi && host.hasPermission(it) && host.serial(it) == e.serial }
                if (target == null) {
                    log("select: ${e.serial} not attached or no permission")
                    return
                }
                opened?.let { teardown(it) }
                connect(target)
            }

            is Event.UiVisible -> {
                uiVisible = e.visible
                if (!e.visible) return
                val current = opened
                if (current != null) {
                    // Re-issued every time: a start refused in the background (API
                    // 31+) or inside Service.startForeground (API 34) is invisible
                    // here; on a running service this only refreshes the notification.
                    service.start()
                } else if (autoConnect) {
                    refreshDevices()
                    connectBest(askPermission = true)
                }
            }

            is Event.Close -> {
                autoConnect = false
                opened?.let { teardown(it) }
                asked.clear()
                queued.clear()
                _state.value = UsbSessionState.NoDevice
            }
        }
    }

    private suspend fun onAttachedEvent(device: UsbDeviceRef, fromActivityIntent: Boolean) {
        if (!device.isDspi) return
        refreshDevices()
        // A launch intent replayed from Recents names a device that is long gone.
        if (!present(device)) return
        if (fromActivityIntent) autoConnect = true
        val current = opened
        if (current != null) {
            if (current.device.deviceName == device.deviceName) return // duplicate intent/broadcast
            // macOS onMatched: a same-serial match is a NEW instance of our
            // unit (the detach of the old one is still in flight): reopen.
            // Any other unit never steals a healthy connection.
            if (host.hasPermission(device) && host.serial(device) == current.serial) {
                teardown(current)
                connect(device)
            }
            return
        }
        if (!autoConnect) return
        when {
            host.hasPermission(device) -> connect(device)

            // The activity intent normally carries implicit permission; if the
            // user declined "open with", ask the normal way (denial still honoured).
            fromActivityIntent -> connectBest(askPermission = true, prefer = device)

            else -> {
                // The ATTACHED broadcast usually races the activity intent that
                // grants implicit permission (DESIGN.md §2.1): wait for it before
                // showing a second dialog.
                _state.value = UsbSessionState.PermissionRequired("")
                scope.launch {
                    delay(attachIntentGraceMs)
                    post(Event.GraceExpired(device))
                }
            }
        }
    }

    private suspend fun onDetachedEvent(device: UsbDeviceRef) {
        val name = device.deviceName
        asked.remove(name)
        queued.remove(name)
        denied.remove(name)
        val current = opened
        refreshDevices(gone = device)
        if (current != null && current.device.deviceName == name) {
            teardown(current)
            _state.value = UsbSessionState.NoDevice
        }
        // macOS: keep lastSerial for re-plug; switch to a surviving unit.
        // Also refreshes a stale Failed/Pending state of an idle session.
        if (opened == null && autoConnect) {
            connectBest(askPermission = false, gone = device)
        } else if (opened == null) {
            _state.value = UsbSessionState.NoDevice
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun present(d: UsbDeviceRef) = host.devices().any { it.deviceName == d.deviceName }

    private fun refreshDevices(gone: UsbDeviceRef? = null) {
        _devices.value = host.devices()
            .filter { it.isDspi && it.deviceName != gone?.deviceName }
            .map { d ->
                val perm = host.hasPermission(d)
                val serial = if (opened?.device?.deviceName == d.deviceName) {
                    opened?.serial
                } else if (perm) {
                    host.serial(d)
                } else {
                    null
                }
                DspiUsbDevice(d.deviceName, d.vid, serial, perm)
            }
    }

    /**
     * Linux rescan(): previous serial first, else any unit we may open, else
     * one not denied, else the first one.
     */
    private fun pickCandidate(gone: UsbDeviceRef? = null, prefer: UsbDeviceRef? = null): UsbDeviceRef? {
        val dspis = host.devices().filter { it.isDspi && it.deviceName != gone?.deviceName }
        if (dspis.isEmpty()) return null
        val permitted = dspis.filter { host.hasPermission(it) }
        return permitted.firstOrNull { lastSerial != null && host.serial(it) == lastSerial }
            ?: permitted.firstOrNull()
            ?: dspis.firstOrNull { it.deviceName == prefer?.deviceName }
            ?: dspis.firstOrNull { it.deviceName !in denied }
            ?: dspis.first()
    }

    /** Connect to the best candidate, or settle the permission state for it. */
    private suspend fun connectBest(askPermission: Boolean, gone: UsbDeviceRef? = null, prefer: UsbDeviceRef? = null) {
        val c = pickCandidate(gone, prefer)
        if (c == null) {
            _state.value = UsbSessionState.NoDevice
            return
        }
        val name = c.deviceName
        val serial = host.serial(c).orEmpty()
        when {
            host.hasPermission(c) -> connect(c)

            name in denied -> _state.value = UsbSessionState.PermissionDenied(serial)

            name in asked -> _state.value = UsbSessionState.PermissionPending(serial, dialogShown = true)

            (askPermission || name in queued) && uiVisible -> {
                queued.remove(name)
                asked.add(name)
                _state.value = UsbSessionState.PermissionPending(serial, dialogShown = true)
                host.requestPermission(c)
            }

            // No UI on screen (cold start from the Application, or an explicit
            // request from a notification): queue it until one is visible.
            askPermission -> {
                queued.add(name)
                _state.value = UsbSessionState.PermissionPending(serial, dialogShown = false)
            }

            name in queued -> _state.value = UsbSessionState.PermissionPending(serial, dialogShown = false)

            else -> _state.value = UsbSessionState.PermissionRequired(serial)
        }
    }

    /** macOS openWithRetry: open + claim, retrying with backoff while the device is still there. */
    private suspend fun connect(d: UsbDeviceRef) {
        asked.remove(d.deviceName)
        queued.remove(d.deviceName)
        denied.remove(d.deviceName)
        var attempt = 0
        var link: UsbLink? = null
        while (true) {
            link = host.open(d)
            if (link != null) {
                val claimed = try {
                    link.claimVendorInterface()
                } catch (t: RuntimeException) {
                    log("claim failed: $t")
                    false
                }
                if (claimed) break
                runCatching { link.close() }
                link = null
            }
            if (attempt >= openRetryDelaysMs.size || !present(d)) break
            delay(openRetryDelaysMs[attempt++])
            if (!present(d)) break
        }
        val serialGuess = host.serial(d).orEmpty()
        if (link == null) {
            _state.value = UsbSessionState.Failed(
                serialGuess,
                if (present(d)) "Device busy (another app has it open)" else "Device Removed"
            )
            return
        }
        val serial = link.serial ?: serialGuess
        val result = try {
            core.attach(link.fd, serial, d.vid)
        } catch (c: CancellationException) {
            // The core may already have adopted the fd: undo everything, then propagate.
            withContext(NonCancellable) {
                runCatching { core.detach(serial) }
                runCatching { link.releaseVendorInterface() }
                runCatching { link.close() }
            }
            throw c
        } catch (t: Throwable) {
            AttachResult(false, t.message ?: t.javaClass.simpleName)
        }
        if (!result.ok) {
            runCatching { link.releaseVendorInterface() }
            runCatching { link.close() }
            _state.value = UsbSessionState.Failed(serial, result.error)
            return
        }
        opened = Opened(d, link, serial)
        lastSerial = serial
        refreshDevices()
        _state.value = UsbSessionState.Open(serial, d.vid)
        service.start()
    }

    /**
     * PLAN §5.2 order: core disconnect, then release + close the connection,
     * then stop the service. Never interrupted; every step runs even if an
     * earlier one throws.
     */
    private suspend fun teardown(o: Opened) = withContext(NonCancellable) {
        try {
            core.detach(o.serial)
        } catch (t: Throwable) {
            log("detach failed: $t")
        }
        runCatching { o.link.releaseVendorInterface() }.onFailure { log("release failed: $it") }
        runCatching { o.link.close() }.onFailure { log("close failed: $it") }
        opened = null
        runCatching { service.stop() }.onFailure { log("service stop failed: $it") }
    }

    companion object {
        /** USBDevice.swift openRetryDelays (0.1, 0.2, 0.4, 0.8, 1.0 s). */
        val OPEN_RETRY_DELAYS_MS = listOf(100L, 200L, 400L, 800L, 1000L)

        const val ATTACH_INTENT_GRACE_MS = 1500L
    }
}

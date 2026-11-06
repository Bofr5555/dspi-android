package se.bofr.dspi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.link.LinkHub
import se.bofr.dspi.link.LinkSessionState
import se.bofr.dspi.usb.DspiUsbDevice
import se.bofr.dspi.usb.UsbSessionState

/** Which bridge the UI is looking at. */
enum class AppMode {
    /** The real stack: FfiDspiCore behind DspiBridgeImpl, fed by AndroidUsbSession. */
    DEVICE,

    /** "Demo (no device)": FakeDspiCore.demo() behind its own DspiBridgeImpl; no USB at all. */
    DEMO,

    /** DSPi Link client: a DSPi on a hub (Mac Console, ESP32 bridge), its own core + bridge ([RemoteSession]). */
    REMOTE
}

/** A running demo stack; [close] tears it down completely (scope, FFI thread, fake core). */
interface DemoSession {
    val bridge: DspiBridge

    fun close()
}

/**
 * True while any DSPi is on the bus, opened or not (permission pending,
 * denied, failed...). Demo mode is offered only when this is false.
 */
fun dspiPresent(state: UsbSessionState, devices: List<DspiUsbDevice>): Boolean = state !is UsbSessionState.NoDevice || devices.isNotEmpty()

/**
 * Selects between the device bridge and an explicit, separate demo bridge
 * (PLAN §3.3 `AppState`). Rules:
 * - demo can be entered only while no DSPi is attached ([dspiPresent] false);
 * - the demo stack is its own core + bridge; the USB session only ever talks
 *   to [deviceBridge], so a real unit and the fake are never mixed;
 * - when a DSPi appears while in demo, demo is left at once and torn down,
 *   and the UI switches back to the device bridge;
 * - REMOTE (DSPi Link) is its own stack too; entering it leaves demo. When a
 *   DSPi appears on USB while REMOTE, the user is asked ([usbPrompt], owner
 *   decision D9) instead of switching silently. REMOTE ends when the user
 *   leaves the hub (the engine goes Idle) or chooses USB.
 *
 * Main thread only (as the bridge and the USB session).
 */
class AppModeController(
    private val deviceBridge: DspiBridge,
    usbState: StateFlow<UsbSessionState>,
    usbDevices: StateFlow<List<DspiUsbDevice>>,
    scope: CoroutineScope,
    private val demoFactory: () -> DemoSession,
    private val forwardUiVisible: (Boolean) -> Unit,
    private val remoteFactory: () -> RemoteSession = { error("DSPi Link is not available") }
) {
    private val scope = scope
    private val _mode = MutableStateFlow(AppMode.DEVICE)
    val mode: StateFlow<AppMode> = _mode.asStateFlow()

    private val _bridge = MutableStateFlow(deviceBridge)

    /** The bridge every screen is given; changes on demo enter/exit. */
    val bridge: StateFlow<DspiBridge> = _bridge.asStateFlow()

    private var demo: DemoSession? = null
    private var uiVisible = true

    private val present: StateFlow<Boolean> =
        combine(usbState, usbDevices, ::dspiPresent)
            .stateIn(scope, SharingStarted.Eagerly, dspiPresent(usbState.value, usbDevices.value))

    /** Whether the "Demo (no device)" action is offered now. */
    val demoAvailable: StateFlow<Boolean> =
        combine(present, _mode) { p, m -> !p && m == AppMode.DEVICE }
            .stateIn(scope, SharingStarted.Eagerly, !present.value)

    private var remote: RemoteSession? = null
    private var remoteWatch: Job? = null
    private val _remoteState = MutableStateFlow<LinkSessionState>(LinkSessionState.Idle)

    /** The REMOTE session's state (Idle outside REMOTE). */
    val remoteState: StateFlow<LinkSessionState> = _remoteState.asStateFlow()

    /** The REMOTE stack while it exists (dialogs act on it). */
    val remoteSession: RemoteSession? get() = remote

    private val _usbPrompt = MutableStateFlow(false)

    /** D9: a DSPi appeared on USB while REMOTE: ask whether to switch to it. */
    val usbPrompt: StateFlow<Boolean> = _usbPrompt.asStateFlow()

    init {
        scope.launch {
            var before = present.value
            present.collect {
                if (it) exitDemo()
                if (it && !before && _mode.value == AppMode.REMOTE) _usbPrompt.value = true
                if (!it) _usbPrompt.value = false
                before = it
            }
        }
    }

    /** Switch to the DSPi on [hub] (DSPi Link). Leaves demo; reuses the REMOTE stack when it exists. */
    fun enterRemote(hub: LinkHub) {
        exitDemo()
        val r = remote ?: remoteFactory().also { r ->
            remote = r
            r.bridge.setUiVisible(uiVisible)
            remoteWatch = scope.launch {
                var active = false
                r.state.collect { st ->
                    _remoteState.value = st
                    if (st != LinkSessionState.Idle) {
                        active = true
                    } else if (active) {
                        exitRemote()
                    }
                }
            }
        }
        r.connect(hub)
        _bridge.value = r.bridge
        _mode.value = AppMode.REMOTE
        _usbPrompt.value = false
    }

    /** Leave REMOTE and tear its stack down; the UI goes back to the device bridge. No-op otherwise. */
    fun exitRemote() {
        val r = remote ?: return
        remote = null
        remoteWatch?.cancel()
        remoteWatch = null
        _remoteState.value = LinkSessionState.Idle
        _usbPrompt.value = false
        if (_mode.value == AppMode.REMOTE) {
            _bridge.value = deviceBridge
            _mode.value = AppMode.DEVICE
        }
        r.close()
    }

    /** The USB prompt's answer: true = use the USB DSPi (leave REMOTE). */
    fun answerUsbPrompt(useUsb: Boolean) {
        _usbPrompt.value = false
        if (useUsb) exitRemote()
    }

    /** Start demo mode. Refused (false) while a DSPi is attached. Idempotent. */
    fun enterDemo(): Boolean {
        if (demo != null) return true
        if (present.value || remote != null) return false
        val d = demoFactory()
        demo = d
        d.bridge.setUiVisible(uiVisible)
        _bridge.value = d.bridge
        _mode.value = AppMode.DEMO
        return true
    }

    /** Leave demo mode and tear the demo stack down. No-op in device mode. */
    fun exitDemo() {
        val d = demo ?: return
        demo = null
        _bridge.value = deviceBridge
        _mode.value = AppMode.DEVICE
        d.close()
    }

    /** From UiVisibilityTracker: the USB session (and device bridge) plus the demo/remote bridge if any. */
    fun setUiVisible(visible: Boolean) {
        uiVisible = visible
        forwardUiVisible(visible)
        demo?.bridge?.setUiVisible(visible)
        remote?.bridge?.setUiVisible(visible)
        if (visible) remote?.retryNow()
    }
}

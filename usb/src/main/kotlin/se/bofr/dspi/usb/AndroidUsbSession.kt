package se.bofr.dspi.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.bridge.DspiBridge

/**
 * [UsbSession] on `UsbManager` (PLAN §5.2). The logic lives in
 * [UsbSessionEngine]; this class only adapts broadcasts and intents.
 *
 * App wiring (coordinator, `:app`):
 * ```
 * // Application.onCreate
 * val session = AndroidUsbSession(this, bridge, appScope)   // appScope on Dispatchers.Main
 * session.start(this)
 * UiVisibilityTracker(appScope) { session.setUiVisible(it) }.install(this)
 * // Activity.onCreate / onNewIntent
 * session.onAttachedIntent(this, intent)
 * ```
 * [setUiVisible] forwards to `bridge.setUiVisible`. [DspiConnectionService]
 * reads its notification controls from the [ConnectionServiceLink] this
 * constructor registers.
 */
class AndroidUsbSession(
    context: Context,
    private val bridge: DspiBridge,
    scope: CoroutineScope,
    host: UsbHost = AndroidUsbHost(context, UsbBroadcasts.ACTION_USB_PERMISSION),
    core: CoreLink = BridgeCoreLink(bridge),
    service: ConnectionServiceControl = AndroidServiceControl(context)
) : UsbSession {
    private val appContext = context.applicationContext

    private val engine = UsbSessionEngine(
        host = host,
        core = core,
        service = service,
        scope = scope,
        log = { Log.i(TAG, it) }
    )

    init {
        ConnectionServiceLink.controls = BridgeNotificationControls(bridge, scope)
    }

    override val state: StateFlow<UsbSessionState> get() = engine.state

    override val devices: StateFlow<List<DspiUsbDevice>> get() = engine.devices

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = UsbBroadcasts.device(intent) ?: return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> engine.onAttached(device, fromActivityIntent = false)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> engine.onDetached(device)
                UsbBroadcasts.ACTION_USB_PERMISSION -> engine.onPermissionResult(device, UsbBroadcasts.granted(intent))
            }
        }
    }

    /** Idempotent: only the first call (or the first after [close]/[stop]) scans. */
    override fun start(context: Context) {
        val first = !engine.isStarted || !registered
        engine.start()
        register()
        // Cold start: getDeviceList() + requestPermission() (queued until a UI is visible).
        if (first) engine.scan(askPermission = true)
    }

    override fun onAttachedIntent(context: Context, intent: Intent) {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        // Relaunched from Recents: the intent is a replay, the device may be long gone.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val device = UsbBroadcasts.device(intent) ?: return
        engine.start()
        engine.onAttached(device, fromActivityIntent = true) // the engine also checks it is still attached
    }

    override fun requestPermission(context: Context) = engine.requestPermission()

    override fun select(serial: String) = engine.select(serial)

    override fun setUiVisible(visible: Boolean) {
        bridge.setUiVisible(visible)
        engine.setUiVisible(visible)
    }

    override suspend fun close() {
        try {
            engine.close()
        } finally {
            unregister()
        }
    }

    override fun stop(context: Context) = unregister()

    private fun register() {
        if (registered) return
        UsbBroadcasts.register(appContext, UsbBroadcasts.ACTION_USB_PERMISSION, receiver)
        registered = true
    }

    private fun unregister() {
        if (!registered) return
        UsbBroadcasts.unregister(appContext, receiver)
        registered = false
    }

    private companion object {
        const val TAG = "DspiUsbSession"
    }
}

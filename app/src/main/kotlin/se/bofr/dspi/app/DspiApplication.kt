package se.bofr.dspi.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.ffi.FfiDspiCore
import se.bofr.dspi.usb.AndroidUsbSession
import se.bofr.dspi.usb.UiVisibilityTracker
import se.bofr.dspi.usb.UsbSession

/**
 * Process-wide wiring (decision D9, review W4). One real stack per process:
 * ```
 * FfiDspiCore (UniFFI, libdspi_ffi.so)
 *   -> DspiBridgeImpl(core, appScope, linkStore = DataStoreLinkStore, appVersion)
 *   -> AndroidUsbSession(this, bridge, appScope).start(this)
 * UiVisibilityTracker(appScope) { session.setUiVisible(it) (+ demo bridge) }
 * ```
 * The USB session is started here, not per Activity (UsbSession.start KDoc),
 * so the device stays open across Activity recreation while the foreground
 * service runs. Demo mode builds a separate fake stack on demand
 * ([AppModeController]).
 */
class DspiApplication : Application() {
    /** Main-thread scope for the bridge, the session and the mode controller; lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var bridge: DspiBridge
        private set
    lateinit var session: UsbSession
        private set
    lateinit var modes: AppModeController
        private set

    override fun onCreate() {
        super.onCreate()
        val version = appVersion()
        val linkStore = DataStoreLinkStore(dspiBridgeStore, appScope)
        bridge = DspiBridgeImpl(FfiDspiCore(), appScope, linkStore = linkStore, appVersion = version)
        val usb = AndroidUsbSession(this, bridge, appScope)
        session = usb
        modes = AppModeController(
            deviceBridge = bridge,
            usbState = usb.state,
            usbDevices = usb.devices,
            scope = appScope,
            demoFactory = { startDemoSession(appScope, version, log = { Log.i(TAG, it) }) },
            forwardUiVisible = usb::setUiVisible
        )
        usb.start(this)
        UiVisibilityTracker(appScope) { modes.setUiVisible(it) }.install(this)
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private companion object {
        const val TAG = "DspiApplication"
    }
}

package se.bofr.dspi.app

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.DspiBridgeImpl
import se.bofr.dspi.ffi.FfiDspiCore
import se.bofr.dspi.ui.settings.AppSettingsStore
import se.bofr.dspi.ui.settings.DataStoreAppSettingsStore
import se.bofr.dspi.ui.state.AppState
import se.bofr.dspi.usb.AndroidBootloaderLocator
import se.bofr.dspi.usb.AndroidUsbSession
import se.bofr.dspi.usb.BootloaderLocator
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
 * The session starts (and attach intents are handed over) only after the
 * links file has been read ([UsbStartGate]), so the bridge's main-thread
 * `LinkStore.load` never waits for the disk.
 * The USB session is started here, not per Activity (UsbSession.start KDoc),
 * so the device stays open across Activity recreation while the foreground
 * service runs. Demo mode builds a separate fake stack on demand
 * ([AppModeController]).
 *
 * Also process-wide, provided to every screen by [MainActivity]: the one
 * preference store ([appSettings], decision F3; its analyser options are
 * pushed into the current bridge by [bindRtaOptions]), the shared selection
 * ([appState]).
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

    /** Run [block] (anything that may open a DSPi) once the persistent links are loaded (review A3). */
    lateinit var usbGate: UsbStartGate
        private set

    /** The app's one preference store (macOS AppSettings, DataStore). */
    lateinit var appSettings: AppSettingsStore
        private set

    /** The main window's selection (macOS activeEqChannel / isOverviewMode). */
    val appState = AppState()

    /**
     * The one bootloader locator (macOS `BootloaderLocator`), shared by every
     * FirmwareUpdateView visit; the view starts it on open and stops it when
     * the visit ends. Created on first use.
     */
    val bootloaderLocator: BootloaderLocator by lazy { AndroidBootloaderLocator(this, appScope) }

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
        usbGate = UsbStartGate(appScope) { linkStore.awaitLoaded() }
        usbGate.run { usb.start(this) }
        UiVisibilityTracker(appScope) { modes.setUiVisible(it) }.install(this)
        appSettings = DataStoreAppSettingsStore.of(this)
        bindRtaOptions(appScope, appSettings.settings, modes.bridge)
    }

    private fun appVersion(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private companion object {
        const val TAG = "DspiApplication"
    }
}

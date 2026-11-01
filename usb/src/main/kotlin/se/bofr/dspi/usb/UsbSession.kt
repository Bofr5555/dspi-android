package se.bofr.dspi.usb

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.bridge.DspiBridge

/** A DSPi seen on the bus, connected or not. */
data class DspiUsbDevice(
    /** `UsbDevice.deviceName` (stable while attached; readable without permission). */
    val deviceName: String,
    val vid: Int,
    /** null while the app has no permission for it (API 29+ hides the serial). */
    val serial: String?,
    val hasPermission: Boolean
) {
    /** Pre-May-2026 firmware (VID 0x2E8A): connects, the bridge reports `compat` and the UI shows the banner. */
    val legacyVid: Boolean get() = vid == UsbIds.DSPI_LEGACY_VID
}

/**
 * Where the USB side is. `serial` is "" when it is not readable yet (no
 * permission, API 29+).
 */
sealed interface UsbSessionState {
    data object NoDevice : UsbSessionState

    /**
     * A DSPi is present, the app may not open it, and no permission dialog is
     * up (found while no UI was visible, or the grace window after an attach
     * expired). The UI offers an action that calls [UsbSession.requestPermission].
     */
    data class PermissionRequired(val serial: String) : UsbSessionState

    /**
     * Permission was requested for this device. [dialogShown] false: queued,
     * to be shown when a UI is visible ([UsbSession.setUiVisible]).
     */
    data class PermissionPending(val serial: String, val dialogShown: Boolean) : UsbSessionState

    /**
     * The user denied permission. Remembered: no automatic re-prompt (scan,
     * rotation, UI return) until an explicit [UsbSession.requestPermission] or
     * the device is detached and re-attached.
     */
    data class PermissionDenied(val serial: String) : UsbSessionState

    /** fd handed to the core via [DspiBridge.attachDevice]. */
    data class Open(val serial: String, val vid: Int) : UsbSessionState

    data class Failed(val serial: String, val reason: String) : UsbSessionState
}

/**
 * Owns the `UsbDeviceConnection` for the DSPi (PLAN §5.2):
 * - cold start: `UsbManager.getDeviceList()` + `requestPermission()`;
 *   `USB_DEVICE_ATTACHED` (device_filter) gives implicit permission;
 * - opens the device, claims the vendor interface 2 (`force = false`), keeps
 *   the connection object alive and passes `fileDescriptor` to
 *   [DspiBridge.attachDevice] (rusb does NOT own the fd). The core claims
 *   interface 2 again on the same fd: intentional and idempotent (usbfs
 *   returns 0 for an interface this fd already holds), so neither side
 *   "fixes" it;
 * - `USB_DEVICE_DETACHED` / [close]: [DspiBridge.detachDevice] FIRST, then
 *   `UsbDeviceConnection.close()`;
 * - starts/stops [DspiConnectionService] exactly while a device is open.
 * Never claims interfaces 0/1 (audio), never calls `setConfiguration`/`setInterface`.
 *
 * Main thread only (state flows emit on main).
 */
interface UsbSession {
    val state: StateFlow<UsbSessionState>

    /** Every DSPi attached now (multi-device picker), with or without permission. */
    val devices: StateFlow<List<DspiUsbDevice>>

    /**
     * Register the USB receivers and pick up an already-attached device.
     * Idempotent. Call once from the Application (not per Activity).
     */
    fun start(context: Context)

    /** Handle the activity's `USB_DEVICE_ATTACHED` launch intent (`onCreate`/`onNewIntent`); stale intents are ignored. */
    fun onAttachedIntent(context: Context, intent: android.content.Intent)

    /** Explicit user request: ask permission for the attached DSPi (also clears a remembered denial). */
    fun requestPermission(context: Context)

    /** Switch to another attached unit by serial (closes the current one first). Unknown serial: no-op. */
    fun select(serial: String)

    /**
     * A UI is on screen (debounced across configuration changes). Visible:
     * queued permission prompts are shown and the service is (re)started if a
     * device is open. Forwards to [DspiBridge.setUiVisible] (status poll).
     */
    fun setUiVisible(visible: Boolean)

    /**
     * Full teardown: [DspiBridge.detachDevice], then `connection.close()`,
     * stop the service, unregister receivers (as [stop]). Cancellation-safe
     * (the teardown runs to the end even if the caller is cancelled), returns
     * promptly when nothing is open or [start] was never called, idempotent.
     * The session may be [start]ed again afterwards.
     */
    suspend fun close()

    /** Unregister the receivers only; an open device stays open. Idempotent. */
    fun stop(context: Context)
}

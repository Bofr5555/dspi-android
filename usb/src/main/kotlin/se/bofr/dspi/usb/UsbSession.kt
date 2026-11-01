package se.bofr.dspi.usb

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.bridge.DspiBridge

/** Where the USB side is. */
sealed interface UsbSessionState {
    data object NoDevice : UsbSessionState

    data class PermissionPending(val serial: String) : UsbSessionState

    data class PermissionDenied(val serial: String) : UsbSessionState

    /** fd handed to the core via [DspiBridge.attachDevice]. */
    data class Open(val serial: String, val vid: Int) : UsbSessionState

    data class Failed(val serial: String, val reason: String) : UsbSessionState
}

/**
 * Owns the `UsbDeviceConnection` for the DSPi (PLAN §5.2):
 * - cold start: `UsbManager.getDeviceList()` + `requestPermission()`;
 *   `USB_DEVICE_ATTACHED` (device_filter) gives implicit permission;
 * - opens the device, keeps the connection object alive and passes
 *   `fileDescriptor` to [DspiBridge.attachDevice] (rusb does NOT own the fd);
 * - `USB_DEVICE_DETACHED` / [close]: [DspiBridge.detachDevice] FIRST, then
 *   `UsbDeviceConnection.close()`;
 * - starts/stops [DspiConnectionService] while a device is open.
 * Never claims interfaces 0/1 (audio) and never calls `setConfiguration`.
 *
 * WAVE 0: interface only. Package USB implements `AndroidUsbSession`.
 */
interface UsbSession {
    val state: StateFlow<UsbSessionState>

    /** Register receivers and pick up an already-attached device. */
    fun start(context: Context)

    /** Handle the activity's `USB_DEVICE_ATTACHED` launch intent. */
    fun onAttachedIntent(context: Context, intent: android.content.Intent)

    /** Ask the user for permission for the attached DSPi (cold start). */
    fun requestPermission(context: Context)

    /** Detach from the core, then close the connection; stop the service. */
    suspend fun close()

    fun stop(context: Context)
}

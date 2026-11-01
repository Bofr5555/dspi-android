// Port of DSPi-Console (macOS) `DSPi Console/BootloaderLocator.swift` @
// v1.1.6-beta5 (SystemBootloaderLocator: USB match by VID 0x2E8A / PID per
// chip, one scan per change plus a 1 s poll while a flashing UI is open,
// report every board, the caller refuses to act on more than one). Android
// has no volume half: the write path is PICOBOOT over the bootloader's
// vendor interface (package FIRMWARE), so a board is identified by its
// usbfs device name instead of a mount point.
package se.bofr.dspi.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Platform-independent scan logic: [UsbHost] → [BootloaderDevice] list,
 * published only on change.
 */
class BootloaderScanner(private val host: UsbHost, private val scope: CoroutineScope, private val pollMs: Long = POLL_MS) {
    private val _devices = MutableStateFlow<List<BootloaderDevice>>(emptyList())
    val devices: StateFlow<List<BootloaderDevice>> = _devices.asStateFlow()
    private var poll: Job? = null

    fun start() {
        rescan()
        if (poll?.isActive == true) return
        poll = scope.launch {
            while (isActive) {
                delay(pollMs)
                rescan()
            }
        }
    }

    fun stop() {
        poll?.cancel()
        poll = null
    }

    fun rescan(gone: UsbDeviceRef? = null) {
        _devices.value = host.devices()
            .filter { it.isBootloader && it.deviceName != gone?.deviceName }
            .sortedBy { it.deviceName }
            .map { BootloaderDevice(it.deviceName, it.pid) }
    }

    fun ref(device: BootloaderDevice) = UsbDeviceRef(device.deviceName, UsbIds.RPI_VID, device.pid)

    companion object {
        /** BootloaderLocator.swift: one-second poll while a flashing UI is on screen. */
        const val POLL_MS = 1000L
    }
}

/**
 * [BootloaderLocator] on `UsbManager`. Locating and permission only: no
 * claim, no transfer. The device filter lists DSPi ids only (D8), so a board
 * in BOOTSEL never auto-launches the app; FIRMWARE calls [requestPermission]
 * for the board of an install it started and opens [usbDevice] itself.
 */
class AndroidBootloaderLocator(
    context: Context,
    scope: CoroutineScope,
    private val host: AndroidUsbHost = AndroidUsbHost(context, UsbBroadcasts.ACTION_BOOTLOADER_PERMISSION)
) : BootloaderLocator {
    private val scanner = BootloaderScanner(host, scope)
    override val devices: StateFlow<List<BootloaderDevice>> get() = scanner.devices

    private val _permissionResults = MutableSharedFlow<BootloaderPermissionResult>(extraBufferCapacity = 8)
    override val permissionResults: SharedFlow<BootloaderPermissionResult> = _permissionResults.asSharedFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = UsbBroadcasts.device(intent) ?: return
            if (!device.isBootloader) return
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scanner.rescan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> scanner.rescan(gone = device)
                UsbBroadcasts.ACTION_BOOTLOADER_PERMISSION ->
                    _permissionResults.tryEmit(BootloaderPermissionResult(device.deviceName, UsbBroadcasts.granted(intent)))
            }
        }
    }

    override fun start(context: Context) {
        if (!registered) {
            UsbBroadcasts.register(context.applicationContext, UsbBroadcasts.ACTION_BOOTLOADER_PERMISSION, receiver)
            registered = true
        }
        scanner.start()
    }

    override fun stop(context: Context) {
        scanner.stop()
        if (registered) {
            UsbBroadcasts.unregister(context.applicationContext, receiver)
            registered = false
        }
    }

    override fun hasPermission(device: BootloaderDevice): Boolean = host.hasPermission(scanner.ref(device))

    override fun requestPermission(device: BootloaderDevice) = host.requestPermission(scanner.ref(device))

    /** FIRMWARE claims the PICOBOOT vendor interface (1) of this device itself. */
    override fun usbDevice(device: BootloaderDevice): UsbDevice? = host.usbDevice(scanner.ref(device))
}

package se.bofr.dspi.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** A board in BOOTSEL mode seen by UsbManager. */
data class BootloaderDevice(val deviceName: String, val pid: Int) {
    val isRp2350: Boolean get() = pid == UsbIds.RP2350_BOOTSEL_PID
}

/** The user's answer to [BootloaderLocator.requestPermission]. */
data class BootloaderPermissionResult(val deviceName: String, val granted: Boolean)

/**
 * Android replacement of macOS `BootloaderLocator.swift` / the core's
 * `bootloader_counts` (libusb cannot enumerate on Android): watches
 * `UsbManager` for VID 0x2E8A PID 0x0003 (RP2040) / 0x000F (RP2350).
 * Locating and permission only — no claim, no transfer; writing firmware
 * (PICOBOOT/SAF) is package FIRMWARE.
 *
 * The app does not auto-launch for boards in BOOTSEL (D8: the device filter
 * lists DSPi ids only), so FIRMWARE calls [requestPermission] for the board
 * of an install it started, then opens [usbDevice] itself.
 *
 * Main thread only.
 */
interface BootloaderLocator {
    val devices: StateFlow<List<BootloaderDevice>>

    /** Every answer, including a repeated identical one (hence a SharedFlow, not a StateFlow). */
    val permissionResults: SharedFlow<BootloaderPermissionResult>

    fun start(context: Context)

    fun stop(context: Context)

    fun hasPermission(device: BootloaderDevice): Boolean

    /** Shows the system dialog; the answer arrives on [permissionResults]. */
    fun requestPermission(device: BootloaderDevice)

    /** The platform device for PICOBOOT, null if it is gone. */
    fun usbDevice(device: BootloaderDevice): UsbDevice?

    /** Counts like `dspi_bootloader_counts` (rp2040, rp2350). */
    fun counts(): Pair<Int, Int> = devices.value.count { !it.isRp2350 } to devices.value.count { it.isRp2350 }
}

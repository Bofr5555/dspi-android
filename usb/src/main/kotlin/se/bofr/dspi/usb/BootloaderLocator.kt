package se.bofr.dspi.usb

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/** A board in BOOTSEL mode seen by UsbManager. */
data class BootloaderDevice(val deviceName: String, val pid: Int) {
    val isRp2350: Boolean get() = pid == UsbIds.RP2350_BOOTSEL_PID
}

/**
 * Android replacement of macOS `BootloaderLocator.swift` / the core's
 * `bootloader_counts` (libusb cannot enumerate on Android): watches
 * `UsbManager` for VID 0x2E8A PID 0x0003 (RP2040) / 0x000F (RP2350).
 * Locating only — writing firmware (PICOBOOT/SAF) is package FIRMWARE.
 *
 * WAVE 0: interface only. Package USB implements it.
 */
interface BootloaderLocator {
    val devices: StateFlow<List<BootloaderDevice>>

    fun start(context: Context)

    fun stop(context: Context)

    /** Counts like `dspi_bootloader_counts` (rp2040, rp2350). */
    fun counts(): Pair<Int, Int> = devices.value.count { !it.isRp2350 } to devices.value.count { it.isRp2350 }
}

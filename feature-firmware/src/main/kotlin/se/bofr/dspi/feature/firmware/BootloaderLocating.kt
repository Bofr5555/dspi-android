// Port of DSPi-Console (macOS) `DSPi Console/BootloaderLocator.swift` @
// v1.1.6-beta5 (b5fdeac): `BootloaderBoard`, `BootloaderBoard.Chip`,
// `BootloaderLocating`, `FakeBootloaderLocator`. The system locator is
// :usb's `BootloaderLocator` (USB half only, D8); [UsbBootloaderLocating]
// adapts it. The macOS volume half (`volumeURL`) moves into the writer:
// PICOBOOT needs no volume, and the drive write path holds the drive the user
// picked through the Storage Access Framework (UPSTREAM-DEVIATIONS "FIRMWARE").
package se.bofr.dspi.feature.firmware

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import se.bofr.dspi.usb.BootloaderDevice
import se.bofr.dspi.usb.BootloaderLocator
import se.bofr.dspi.usb.UsbIds

/** One board in BOOTSEL mode. */
data class BootloaderBoard(
    val chip: Chip,
    /** usbfs device name (`/dev/bus/usb/001/007`); identifies the board for PICOBOOT. */
    val deviceName: String
) {
    enum class Chip(val bootloaderProductID: Int, val volumeName: String, val assetToken: String, val displayName: String) {
        RP2040(UsbIds.RP2040_BOOTSEL_PID, "RPI-RP2", "RP2040", "RP2040 (Pico)"),
        RP2350(UsbIds.RP2350_BOOTSEL_PID, "RP2350", "RP2350", "RP2350 (Pico 2)");

        companion object {
            fun ofProductID(pid: Int): Chip? = entries.firstOrNull { it.bootloaderProductID == pid }
        }
    }
}

/** Finds boards in BOOTSEL mode; a seam so tests drive the installer with no hardware. Main thread only. */
interface BootloaderLocating {
    fun currentBoards(): List<BootloaderBoard>
    var onChange: ((List<BootloaderBoard>) -> Unit)?
    fun start()
    fun stop()
}

/**
 * [BootloaderLocating] over :usb's [BootloaderLocator] (UsbManager; 1 s poll
 * plus attach/detach broadcasts while started). Reports every board; the
 * installer refuses to act on more than one.
 */
class UsbBootloaderLocating(
    val locator: BootloaderLocator,
    private val scope: CoroutineScope,
    private val startLocator: () -> Unit,
    private val stopLocator: () -> Unit
) : BootloaderLocating {
    override var onChange: ((List<BootloaderBoard>) -> Unit)? = null
    private var watch: Job? = null

    override fun currentBoards(): List<BootloaderBoard> = locator.devices.value.mapNotNull { it.toBoard() }

    override fun start() {
        startLocator()
        if (watch?.isActive == true) return
        watch = scope.launch {
            locator.devices.collect { devices -> onChange?.invoke(devices.mapNotNull { it.toBoard() }) }
        }
    }

    override fun stop() {
        watch?.cancel()
        watch = null
        stopLocator()
    }

    /** The :usb device behind [board], if it is still attached. */
    fun device(board: BootloaderBoard): BootloaderDevice? = locator.devices.value.firstOrNull { it.deviceName == board.deviceName }

    private fun BootloaderDevice.toBoard(): BootloaderBoard? = BootloaderBoard.Chip.ofProductID(pid)?.let {
        BootloaderBoard(it, deviceName)
    }
}

/** Test and preview double: set [boards], then call [emit]. */
class FakeBootloaderLocator(var boards: List<BootloaderBoard> = emptyList()) : BootloaderLocating {
    override var onChange: ((List<BootloaderBoard>) -> Unit)? = null
    var started = false
        private set

    override fun currentBoards(): List<BootloaderBoard> = boards

    override fun start() {
        started = true
        onChange?.invoke(boards)
    }

    override fun stop() {
        started = false
    }

    fun emit() {
        onChange?.invoke(boards)
    }
}

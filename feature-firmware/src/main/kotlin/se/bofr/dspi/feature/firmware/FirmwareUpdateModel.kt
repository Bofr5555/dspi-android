// The @State of macOS `FirmwareUpdateView` (confirmed, rebootRequested,
// lastSeenChip) plus its `start()` / `resetRun()`, lifted out of the
// composable so it survives leaving the screen mid-install and is JVM-tested.
// Android additions (owner requirements, UPSTREAM-DEVIATIONS "FIRMWARE"): the
// image source (bundled official release or a local .uf2 — first class) and
// the write path (PICOBOOT or the board's drive through SAF).
package se.bofr.dspi.feature.firmware

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** What will be written. */
sealed class FirmwareSource {
    /** The official release bundled with the app. */
    data object Bundled : FirmwareSource()

    /** A .uf2 the user chose on the phone. */
    class Local(val image: FirmwareImage) : FirmwareSource()
}

class FirmwareUpdateModel(
    val installer: FirmwareInstaller,
    private val bundled: (BootloaderBoard.Chip) -> FirmwareImage,
    private val picobootWriter: FirmwareWriter,
    private val driveWriter: (Uf2Volume?) -> FirmwareWriter = { DriveFirmwareWriter(it) }
) {
    /** The user committed to the update (macOS `confirmed`). */
    var confirmed by mutableStateOf(false)
        private set

    var rebootRequested by mutableStateOf(false)
        private set

    /** Chip of the board most recently seen, so the writing card can name it. */
    var lastSeenChip by mutableStateOf<BootloaderBoard.Chip?>(null)

    var source by mutableStateOf<FirmwareSource>(FirmwareSource.Bundled)
        private set

    /** Why the last chosen file was refused, null if it wasn't. */
    var localFileError by mutableStateOf<String?>(null)
        private set

    var useDrive by mutableStateOf(false)
        private set

    var driveVolume by mutableStateOf<Uf2Volume?>(null)
        private set

    /** Settings can't change once committed. */
    val canChangeSetup: Boolean get() = !confirmed

    val bundledVersion: String get() = FirmwareVersion.expected?.description ?: "unknown"

    /** What the header and cards call the firmware being written. */
    val imageLabel: String
        get() = when (val s = source) {
            FirmwareSource.Bundled -> bundledVersion
            is FirmwareSource.Local -> s.image.name
        }

    fun chooseBundled() {
        if (!canChangeSetup) return
        if (apply(FirmwareSource.Bundled, useDrive, driveVolume)) localFileError = null
    }

    fun chooseLocal(image: FirmwareImage) {
        if (!canChangeSetup) return
        if (apply(FirmwareSource.Local(image), useDrive, driveVolume)) localFileError = null
    }

    fun localFileRefused(reason: String) {
        localFileError = reason
    }

    fun usePicoboot() {
        if (!canChangeSetup) return
        apply(source, false, driveVolume)
    }

    /** Write through the board's drive; [volume] is the drive just picked (allowed after committing: it is how a committed install proceeds). */
    fun useDrive(volume: Uf2Volume? = null) {
        apply(source, true, volume ?: driveVolume)
    }

    /** Hands the setup to the installer; nothing changes while it is installing. */
    private fun apply(source: FirmwareSource, drive: Boolean, volume: Uf2Volume?): Boolean {
        val provider: (BootloaderBoard.Chip) -> FirmwareImage = when (source) {
            FirmwareSource.Bundled -> bundled
            is FirmwareSource.Local -> { _ -> source.image }
        }
        if (!installer.configure(writer = if (drive) driveWriter(volume) else picobootWriter, imageProvider = provider)) return false
        this.source = source
        useDrive = drive
        driveVolume = volume
        return true
    }

    /**
     * macOS `start()`: commits to the update. The installer writes as soon as a
     * board is ready; [enterBootloader] restarts a connected device into
     * BOOTSEL (called once, with the user's ENTER_BOOTLOADER confirmation).
     */
    fun start(deviceConnected: Boolean, enterBootloader: () -> Unit) {
        confirmed = true
        installer.installWhenReady()
        if (!deviceConnected || rebootRequested) return
        rebootRequested = true
        enterBootloader()
    }

    /** macOS `resetRun()`: Try Again and Update Another Board. */
    fun resetRun() {
        confirmed = false
        rebootRequested = false
        installer.reset()
    }

    /** macOS `.onChange(of: installer.state)`. */
    fun observe(state: FirmwareInstallState) {
        when (state) {
            is FirmwareInstallState.Ready -> lastSeenChip = state.board.chip
            is FirmwareInstallState.WaitingForVolume -> lastSeenChip = state.chip
            is FirmwareInstallState.WaitingForPermission -> lastSeenChip = state.chip
            else -> Unit
        }
    }
}

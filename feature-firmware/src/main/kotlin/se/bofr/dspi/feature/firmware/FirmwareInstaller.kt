// Port of DSPi-Console (macOS) `DSPi Console/FirmwareInstaller.swift` @
// v1.1.6-beta5 (b5fdeac): `FirmwareImage`, `FirmwareInstallError`,
// `FirmwareVerifying`, `ViewModelFirmwareVerifier` (→ [BridgeFirmwareVerifier]),
// `FirmwareInstallState`, `FirmwareInstaller` — state machine 1:1; timings
// also match Linux qt/src/FirmwareUpdater.cpp @ 4683a2c.
// Android deviations (UPSTREAM-DEVIATIONS "FIRMWARE"): the write goes through
// a [FirmwareWriter] (PICOBOOT by default, the board's drive via SAF as the
// fallback); PICOBOOT needs a USB permission, so `install` may pass through
// [FirmwareInstallState.WaitingForPermission]; the image may be a local file
// (version unknown → only the device's return is verified); UF2 contents are
// validated against the board before anything is written.
package se.bofr.dspi.feature.firmware

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import se.bofr.dspi.bridge.DspiBridge

// MARK: - Images

/** A UF2 image: bundled with the app (official release) or a file the user chose. */
class FirmwareImage(
    val chip: BootloaderBoard.Chip?,
    /** File name, used as the name on the board's drive. */
    val name: String,
    /** What the device must report afterwards; null for a local file (unknown). */
    val version: FirmwareVersion?,
    val bytes: ByteArray,
    val isBundled: Boolean
) {
    companion object {
        /** Pulls "1.1.7" out of "DSPi-RP2350-v1.1.7.uf2". */
        fun versionFromAssetName(name: String): FirmwareVersion? {
            val marker = name.lastIndexOf("-v")
            if (marker < 0) return null
            return FirmwareVersion.parse(name.substring(marker + 2).replace(".uf2", ""))
        }

        /**
         * `FirmwareImage.bundled(for:)`: the image whose name carries
         * `-<assetToken>-`, refused when its version is not [FirmwareVersion.expected].
         */
        fun bundled(chip: BootloaderBoard.Chip, names: List<String>, read: (String) -> ByteArray): FirmwareImage {
            val name = names.firstOrNull { it.endsWith(".uf2") && it.contains("-${chip.assetToken}-") }
                ?: throw FirmwareInstallError.ImageMissing(chip.displayName)
            val version = versionFromAssetName(name) ?: throw FirmwareInstallError.ImageMissing(chip.displayName)
            val expected = FirmwareVersion.expected
            if (expected != null && version != expected) {
                throw FirmwareInstallError.BundledImageStale(version.description, expected.description)
            }
            return FirmwareImage(chip, name, version, read(name), isBundled = true)
        }
    }
}

// MARK: - Errors

sealed class FirmwareInstallError : Exception() {
    data object NoBoardFound : FirmwareInstallError()
    data class MultipleBoards(val count: Int) : FirmwareInstallError()
    data class VolumeNotMounted(val name: String) : FirmwareInstallError()
    data class ImageMissing(val chip: String) : FirmwareInstallError()
    data class BundledImageStale(val bundled: String, val expected: String) : FirmwareInstallError()
    data class WriteFailed(val detail: String) : FirmwareInstallError()
    data object DeviceDidNotReturn : FirmwareInstallError()
    data class VersionMismatch(val expected: String, val got: String) : FirmwareInstallError()

    // Android additions.
    /** The user (or the system) refused USB access to the board. */
    data object PermissionDenied : FirmwareInstallError()

    /** The image targets the other chip. */
    data class WrongBoardFamily(val image: String, val board: String) : FirmwareInstallError()

    /** Not a UF2 the boot ROM would take. */
    data class InvalidImage(val detail: String) : FirmwareInstallError()

    /** The drive the user picked is not the board's. */
    data class WrongVolume(val expected: String) : FirmwareInstallError()

    /** One sentence fit to show a user. */
    override val message: String
        get() = when (this) {
            NoBoardFound -> "No board in bootloader mode was found. Hold the BOOTSEL button while plugging the board in."
            is MultipleBoards -> "$count boards are in bootloader mode. Disconnect all but the one you want to update."
            is VolumeNotMounted ->
                "The board is connected but its $name drive has not appeared. If Android asked for permission to access it, allow it and try again."
            is ImageMissing -> "This build of DSPi does not include firmware for the $chip."
            is BundledImageStale ->
                "The bundled firmware is version $bundled but this Console expects $expected. The app was built incorrectly; do not install it."
            is WriteFailed -> "Writing the firmware failed: $detail"
            DeviceDidNotReturn ->
                "The firmware was written but the device did not reappear. Unplug it, plug it back in, and check whether it works."
            is VersionMismatch -> "The device came back running firmware $got instead of $expected."
            PermissionDenied -> "Android did not allow DSPi to use the board. Try again and allow USB access when asked."
            is WrongBoardFamily -> "This firmware is for the $image, but the board is an $board. Nothing was written."
            is InvalidImage -> "This is not a firmware file the board can take: $detail. Nothing was written."
            is WrongVolume -> "That is not the board's drive. Choose the drive named $expected."
        }

    /** FirmwareInstallUI.swift `isMundane`: detection-side stumbles read as ordinary. */
    val isMundane: Boolean
        get() = when (this) {
            NoBoardFound, is VolumeNotMounted, is MultipleBoards, PermissionDenied, is WrongVolume -> true
            else -> false
        }
}

// MARK: - Verification

/** Waits for a DSPi to enumerate after a flash and reports the version it claims. */
fun interface FirmwareVerifying {
    suspend fun awaitDeviceVersion(timeoutMs: Long): FirmwareVersion?
}

/** `ViewModelFirmwareVerifier`: polls the bridge every 250 ms (main thread). */
class BridgeFirmwareVerifier(private val deviceVersion: () -> FirmwareVersion?) : FirmwareVerifying {
    constructor(bridge: DspiBridge) : this({ if (bridge.connected) FirmwareVersion.of(bridge.state.value) else null })

    override suspend fun awaitDeviceVersion(timeoutMs: Long): FirmwareVersion? = withTimeoutOrNull(timeoutMs) {
        var version: FirmwareVersion? = deviceVersion()
        while (version == null) {
            delay(POLL_MS)
            version = deviceVersion()
        }
        version
    }

    companion object {
        const val POLL_MS = 250L
    }
}

// MARK: - Writers

/** How [FirmwareWriter.obtainAccess] ended. */
enum class BoardAccess { GRANTED, DENIED, GONE }

/** Puts an image onto a board. Called on the installer's IO dispatcher for [write], main thread otherwise. */
interface FirmwareWriter {
    /** macOS `volumeURL != nil`: the writer knows where to write (drive picked); always true for PICOBOOT. */
    fun isReady(board: BootloaderBoard): Boolean

    /** Whether [obtainAccess] would return at once (no system dialog). */
    fun hasAccess(board: BootloaderBoard): Boolean

    /** Asks for access (USB permission dialog), suspending until answered or the board leaves. */
    suspend fun obtainAccess(board: BootloaderBoard): BoardAccess

    /**
     * Writes [image] ([uf2] is its parsed form) and returns once the board
     * has been told to (or is about to) reboot. Throws [FirmwareInstallError].
     */
    suspend fun write(board: BootloaderBoard, image: FirmwareImage, uf2: Uf2Image, progress: (Double) -> Unit)
}

// MARK: - Installer

sealed class FirmwareInstallState {
    data object Idle : FirmwareInstallState()

    /** Nothing in BOOTSEL yet. */
    data object WaitingForBoard : FirmwareInstallState()

    /** Board on the bus but no drive chosen yet (drive write path only). */
    data class WaitingForVolume(val chip: BootloaderBoard.Chip) : FirmwareInstallState()

    /** Exactly one board, ready to be written when the user says so. */
    data class Ready(val board: BootloaderBoard) : FirmwareInstallState()

    /** Android: the system USB permission dialog for the board is up. */
    data class WaitingForPermission(val chip: BootloaderBoard.Chip) : FirmwareInstallState()

    /** Writing, with the fraction of the image sent so far. */
    data class Writing(val fraction: Double) : FirmwareInstallState()

    /** Bytes are out and the board is rebooting; waiting for it to enumerate. */
    data object WaitingForDevice : FirmwareInstallState()
    data class Verified(val version: FirmwareVersion) : FirmwareInstallState()
    data class Failed(val error: FirmwareInstallError) : FirmwareInstallState()
}

/**
 * Detects a board in BOOTSEL, writes an image to it, and confirms the device
 * came back running what was written. Never flashes on its own, never chooses
 * between two boards, never touches presets; callers own the confirmation UI.
 * Main thread only (the write itself runs on [io]).
 */
class FirmwareInstaller(
    private val locator: BootloaderLocating,
    private val verifier: FirmwareVerifying,
    writer: FirmwareWriter,
    imageProvider: (BootloaderBoard.Chip) -> FirmwareImage,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO
) {
    private val _state = MutableStateFlow<FirmwareInstallState>(FirmwareInstallState.Idle)
    val state: StateFlow<FirmwareInstallState> = _state.asStateFlow()

    /** Set once `install` begins, cleared only by [reset]: detection stops touching the state. */
    private var installing = false

    /** The user has committed; the next ready board is written without asking again. */
    private var armed = false

    private var job: Job? = null

    /** Where writes go; changeable only while nothing is installing. */
    var writer: FirmwareWriter = writer
        private set

    /** Which image to write for a chip (bundled release or a local file). */
    var imageProvider: (BootloaderBoard.Chip) -> FirmwareImage = imageProvider
        private set

    /** Whether an update has been committed to but not yet started. */
    val isArmed: Boolean get() = armed

    /** Writing or waiting for the device: leaving must not cancel anything. */
    val isBusy: Boolean
        get() = when (_state.value) {
            is FirmwareInstallState.WaitingForPermission, is FirmwareInstallState.Writing, FirmwareInstallState.WaitingForDevice -> true
            else -> false
        }

    // MARK: Detection

    fun beginWatching() {
        locator.onChange = { boards -> applyBoards(boards) }
        locator.start()
        applyBoards(locator.currentBoards())
    }

    fun stopWatching() {
        locator.onChange = null
        locator.stop()
    }

    /** Re-reads what is attached (a drive was picked, the write path changed). */
    fun refresh() = applyBoards(locator.currentBoards())

    /** Switches the write path or image before an install; refused while one runs. */
    fun configure(
        writer: FirmwareWriter = this.writer,
        imageProvider: (BootloaderBoard.Chip) -> FirmwareImage = this.imageProvider
    ): Boolean {
        if (installing) return false
        this.writer = writer
        this.imageProvider = imageProvider
        refresh()
        return true
    }

    /** Returns a finished installer to detection. Refused mid-install. */
    fun reset() {
        when (_state.value) {
            is FirmwareInstallState.Writing, FirmwareInstallState.WaitingForDevice, is FirmwareInstallState.WaitingForPermission -> return
            else -> Unit
        }
        installing = false
        armed = false
        applyBoards(locator.currentBoards())
    }

    private fun applyBoards(boards: List<BootloaderBoard>) {
        if (installing) return
        val next = when (boards.size) {
            0 -> FirmwareInstallState.WaitingForBoard
            1 -> if (writer.isReady(
                    boards[0]
                )
            ) {
                FirmwareInstallState.Ready(boards[0])
            } else {
                FirmwareInstallState.WaitingForVolume(boards[0].chip)
            }
            else -> FirmwareInstallState.Failed(FirmwareInstallError.MultipleBoards(boards.size))
        }
        publish(next)
        if (armed && next is FirmwareInstallState.Ready) install(next.board)
    }

    // MARK: Install

    /** Records the user's decision and writes as soon as there is a board. */
    fun installWhenReady() {
        armed = true
        (_state.value as? FirmwareInstallState.Ready)?.let { install(it.board) }
    }

    /** Abandons a committed update before anything is written. */
    fun abandon(error: FirmwareInstallError) {
        if (installing) return
        armed = false
        installing = true
        publish(FirmwareInstallState.Failed(error))
    }

    /** Writes the image for [board] and verifies the result. Only from `Ready`, after confirmation. */
    fun install(board: BootloaderBoard) {
        if (installing) return
        armed = false
        installing = true

        if (!writer.isReady(board)) return fail(FirmwareInstallError.VolumeNotMounted(board.chip.volumeName))

        val image = try {
            imageProvider(board.chip)
        } catch (e: FirmwareInstallError) {
            return fail(e)
        } catch (e: Exception) {
            return fail(FirmwareInstallError.WriteFailed(e.message ?: e.toString()))
        }
        val uf2 = try {
            Uf2Image.parse(image.bytes)
        } catch (e: Uf2FormatException) {
            return fail(FirmwareInstallError.InvalidImage(e.message ?: "unreadable"))
        }
        uf2.targetError(board.chip)?.let { return fail(it) }

        val writer = this.writer
        job = scope.launch {
            if (!writer.hasAccess(board)) {
                publish(FirmwareInstallState.WaitingForPermission(board.chip))
                when (writer.obtainAccess(board)) {
                    BoardAccess.GRANTED -> Unit
                    BoardAccess.DENIED -> return@launch fail(FirmwareInstallError.PermissionDenied)
                    BoardAccess.GONE -> return@launch fail(FirmwareInstallError.NoBoardFound)
                }
            }

            publish(FirmwareInstallState.Writing(0.0))
            try {
                withContext(io) { writer.write(board, image, uf2) { f -> _state.value = FirmwareInstallState.Writing(f) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: FirmwareInstallError) {
                return@launch fail(e)
            } catch (e: Exception) {
                return@launch fail(FirmwareInstallError.WriteFailed(e.message ?: e.toString()))
            }

            publish(FirmwareInstallState.WaitingForDevice)

            // The copy returning proves nothing: success is the device coming
            // back and telling us it is running what we wrote.
            val reported = verifier.awaitDeviceVersion(VERIFY_TIMEOUT_MS) ?: return@launch fail(FirmwareInstallError.DeviceDidNotReturn)
            val expected = image.version
            if (expected != null && reported != expected) {
                return@launch fail(FirmwareInstallError.VersionMismatch(expected.description, reported.description))
            }
            publish(FirmwareInstallState.Verified(reported))
        }
    }

    /** Test hook (`setStateForTesting`). */
    internal fun setStateForTesting(next: FirmwareInstallState, installing: Boolean = false) {
        _state.value = next
        this.installing = installing
    }

    private fun fail(error: FirmwareInstallError) = publish(FirmwareInstallState.Failed(error))

    private fun publish(next: FirmwareInstallState) {
        _state.value = next
    }

    companion object {
        /**
         * Fraction past which a drive write error is the boot ROM rebooting as
         * it takes the last blocks (drive path only; PICOBOOT reboots on command).
         */
        const val REBOOT_THRESHOLD = 0.95

        /** Reboot, re-enumeration, reconnect and platform fetch. */
        const val VERIFY_TIMEOUT_MS = 30_000L

        /** Drive write chunk size. */
        const val WRITE_CHUNK_SIZE = 16 * 1024

        /** Two independent signals, either sufficient. */
        fun isRebootSignal(fractionWritten: Double, volumeStillMounted: Boolean): Boolean =
            fractionWritten >= REBOOT_THRESHOLD || !volumeStillMounted
    }
}

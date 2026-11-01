// The two write paths behind [FirmwareWriter].
// - [PicobootFirmwareWriter]: default. USB permission through :usb's
//   BootloaderLocator, then [PicobootFlasher] over the board's vendor interface.
// - [DriveFirmwareWriter]: fallback. Port of the copy loop of macOS
//   `FirmwareInstaller.writeImage` and Linux `FirmwareUpdater::install`
//   (16 KB chunks, each synced like Linux's fdatasync, a late error or a
//   vanished drive read as the boot ROM rebooting) onto a drive the user picked
//   through the Storage Access Framework ([Uf2Volume]).
package se.bofr.dspi.feature.firmware

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import se.bofr.dspi.usb.BootloaderLocator

// MARK: - PICOBOOT

/** Opens the PICOBOOT interface of an attached board (null when it can't). Called on an IO thread. */
fun interface PicobootTransportFactory {
    fun open(board: BootloaderBoard): PicobootTransport?
}

/** Permission half: the :usb locator, or a fake in tests. */
interface BoardPermissions {
    fun hasPermission(board: BootloaderBoard): Boolean
    suspend fun requestPermission(board: BootloaderBoard): BoardAccess
    fun isAttached(board: BootloaderBoard): Boolean
}

/** [BoardPermissions] over :usb's [BootloaderLocator] (collect before requesting: the results flow does not replay). */
class LocatorBoardPermissions(private val locator: BootloaderLocator) : BoardPermissions {
    private fun device(board: BootloaderBoard) = locator.devices.value.firstOrNull { it.deviceName == board.deviceName }

    override fun hasPermission(board: BootloaderBoard): Boolean = device(board)?.let { locator.hasPermission(it) } ?: false

    override fun isAttached(board: BootloaderBoard): Boolean = device(board) != null

    override suspend fun requestPermission(board: BootloaderBoard): BoardAccess = coroutineScope {
        val device = device(board) ?: return@coroutineScope BoardAccess.GONE
        // Subscribed synchronously (UNDISPATCHED runs up to the first
        // suspension, which is inside the SharedFlow's collect) before asking.
        val answer = async(start = CoroutineStart.UNDISPATCHED) {
            val result = locator.permissionResults.first { it.deviceName == board.deviceName }
            if (result.granted) BoardAccess.GRANTED else BoardAccess.DENIED
        }
        val gone = async(start = CoroutineStart.UNDISPATCHED) {
            locator.devices.first { list -> list.none { it.deviceName == board.deviceName } }
            BoardAccess.GONE
        }
        locator.requestPermission(device)
        val access = select {
            answer.onAwait { it }
            gone.onAwait { it }
        }
        answer.cancel()
        gone.cancel()
        access
    }
}

class PicobootFirmwareWriter(private val permissions: BoardPermissions, private val transports: PicobootTransportFactory) : FirmwareWriter {
    override fun isReady(board: BootloaderBoard) = true

    override fun hasAccess(board: BootloaderBoard) = permissions.hasPermission(board)

    override suspend fun obtainAccess(board: BootloaderBoard): BoardAccess =
        if (permissions.hasPermission(board)) BoardAccess.GRANTED else permissions.requestPermission(board)

    override suspend fun write(board: BootloaderBoard, image: FirmwareImage, uf2: Uf2Image, progress: (Double) -> Unit) {
        val transport = transports.open(board)
            ?: throw FirmwareInstallError.WriteFailed(
                if (permissions.isAttached(board)) "could not open the board's USB interface." else "the board was disconnected."
            )
        transport.use {
            val flasher = PicobootFlasher(PicobootConnection(it))
            try {
                flasher.flash(uf2, board.chip, progress)
            } catch (e: IOException) {
                // Not the reboot: PICOBOOT reboots only when told to. The board
                // stays in BOOTSEL and can be written again.
                flasher.abandon()
                val detail = if (!permissions.isAttached(board)) "the board was disconnected." else "${e.message}."
                throw FirmwareInstallError.WriteFailed(detail)
            }
        }
    }
}

// MARK: - Drive (SAF)

/** The board's drive as the user picked it. */
interface Uf2Volume {
    /** Display name (`RP2350`, `RPI-RP2`). */
    val name: String

    /** `fm.fileExists(atPath: volumeURL.path)`. */
    fun isMounted(): Boolean

    /** Creates (or truncates) [fileName] on the drive. */
    fun create(fileName: String): Uf2Sink
}

interface Uf2Sink : Closeable {
    fun write(bytes: ByteArray, offset: Int, length: Int)

    /** Pushes the chunk to the board (fsync), so the progress is real. */
    fun sync()
}

class DriveFirmwareWriter(private val volume: Uf2Volume?) : FirmwareWriter {
    override fun isReady(board: BootloaderBoard) = volume != null

    override fun hasAccess(board: BootloaderBoard) = true

    override suspend fun obtainAccess(board: BootloaderBoard) = BoardAccess.GRANTED

    override suspend fun write(board: BootloaderBoard, image: FirmwareImage, uf2: Uf2Image, progress: (Double) -> Unit) {
        val volume = volume ?: throw FirmwareInstallError.VolumeNotMounted(board.chip.volumeName)
        if (volume.name != board.chip.volumeName) throw FirmwareInstallError.WrongVolume(board.chip.volumeName)
        writeImage(image.bytes, image.name, volume, progress)
    }

    companion object {
        /**
         * Streams [data] onto [volume] in chunks. Returns normally when the
         * board reboots mid-write (past the threshold, or the drive is gone).
         */
        fun writeImage(data: ByteArray, fileName: String, volume: Uf2Volume, progress: (Double) -> Unit) {
            val sink = try {
                volume.create(fileName)
            } catch (e: Exception) {
                throw FirmwareInstallError.WriteFailed("could not open ${volume.name} for writing")
            }
            val total = data.size
            var written = 0
            try {
                while (written < total) {
                    val n = minOf(FirmwareInstaller.WRITE_CHUNK_SIZE, total - written)
                    try {
                        sink.write(data, written, n)
                        sink.sync()
                    } catch (e: IOException) {
                        val fraction = if (total > 0) written.toDouble() / total else 0.0
                        if (FirmwareInstaller.isRebootSignal(fraction, runCatching { volume.isMounted() }.getOrDefault(false))) return
                        throw FirmwareInstallError.WriteFailed(e.message ?: e.toString())
                    }
                    written += n
                    progress(if (total > 0) written.toDouble() / total else 0.0)
                }
            } finally {
                // May fail once the board has gone; every byte is out by then.
                runCatching { sink.close() }
            }
        }
    }
}

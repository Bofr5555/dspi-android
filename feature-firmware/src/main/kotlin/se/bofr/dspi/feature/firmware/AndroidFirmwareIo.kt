// Android glue for the firmware installer: the PICOBOOT transport over
// UsbManager, the SAF drive ([Uf2Volume]), bundled and local images.
// Kept thin; the logic it feeds is JVM-tested against fakes. Nothing here runs
// in unit tests and nothing here may be exercised against real hardware
// outside the coordinator's HIL steps.
package se.bofr.dspi.feature.firmware

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.FileOutputStream
import java.io.IOException
import se.bofr.dspi.usb.BootloaderLocator

/**
 * One claimed PICOBOOT interface: found by class 0xFF / subclass 0 / protocol 0
 * and its bulk IN/OUT endpoints, never by number (datasheet §5.6.2-5.6.3).
 * Claimed without force: the mass-storage interface stays with the kernel.
 */
class AndroidPicobootTransport private constructor(
    private val connection: UsbDeviceConnection,
    private val intf: UsbInterface,
    private val inEp: UsbEndpoint,
    private val outEp: UsbEndpoint
) : PicobootTransport {
    override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int) = connection.bulkTransfer(outEp, data, length, timeoutMs)

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int) = connection.bulkTransfer(inEp, buffer, length, timeoutMs)

    override fun controlOut(request: Int, timeoutMs: Int) =
        connection.controlTransfer(Picoboot.REQUEST_TYPE_OUT, request, 0, intf.id, null, 0, timeoutMs)

    override fun controlIn(request: Int, buffer: ByteArray, timeoutMs: Int) =
        connection.controlTransfer(Picoboot.REQUEST_TYPE_IN, request, 0, intf.id, buffer, buffer.size, timeoutMs)

    override fun close() {
        runCatching { connection.releaseInterface(intf) }
        connection.close()
    }

    companion object {
        /** A [PicobootTransportFactory] for boards seen by [locator]. */
        fun factory(context: Context, locator: BootloaderLocator) = PicobootTransportFactory { board ->
            val manager = context.applicationContext.getSystemService(Context.USB_SERVICE) as UsbManager
            val device = locator.devices.value.firstOrNull { it.deviceName == board.deviceName }?.let { locator.usbDevice(it) }
                ?: return@PicobootTransportFactory null
            val intf = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC && it.interfaceSubclass == 0 && it.interfaceProtocol == 0
            } ?: return@PicobootTransportFactory null
            val endpoints = (0 until intf.endpointCount).map { intf.getEndpoint(it) }.filter {
                it.type ==
                    UsbConstants.USB_ENDPOINT_XFER_BULK
            }
            val inEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_IN } ?: return@PicobootTransportFactory null
            val outEp = endpoints.firstOrNull { it.direction == UsbConstants.USB_DIR_OUT } ?: return@PicobootTransportFactory null
            val connection = manager.openDevice(device) ?: return@PicobootTransportFactory null
            if (!connection.claimInterface(intf, false)) {
                connection.close()
                return@PicobootTransportFactory null
            }
            AndroidPicobootTransport(connection, intf, inEp, outEp)
        }
    }
}

/** The board's drive picked with ACTION_OPEN_DOCUMENT_TREE (Linux path: the mounted `RP2350` / `RPI-RP2`). */
class SafUf2Volume(context: Context, private val tree: Uri) : Uf2Volume {
    private val resolver = context.applicationContext.contentResolver
    private val root: Uri = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))

    override val name: String = runCatching {
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull().orEmpty()

    override fun isMounted(): Boolean = runCatching {
        resolver.query(root, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    }.getOrDefault(false)

    override fun create(fileName: String): Uf2Sink {
        val doc = DocumentsContract.createDocument(resolver, root, "application/octet-stream", fileName)
            ?: throw IOException("could not create $fileName")
        val pfd = resolver.openFileDescriptor(doc, "wt") ?: throw IOException("could not open $fileName")
        return FdSink(pfd)
    }

    private class FdSink(private val pfd: ParcelFileDescriptor) : Uf2Sink {
        private val out = FileOutputStream(pfd.fileDescriptor)

        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)

        override fun sync() = out.fd.sync()

        override fun close() {
            try {
                out.close()
            } finally {
                pfd.close()
            }
        }
    }
}

/** Images shipped in `assets/firmware/` (official release, see docs/SUPPLY-CHAIN.md). */
object BundledFirmware {
    const val ASSET_DIR = "firmware"

    fun names(context: Context): List<String> = context.assets.list(ASSET_DIR)?.toList().orEmpty()

    /** `FirmwareImage.bundled(for:)`; throws [FirmwareInstallError]. */
    fun image(context: Context, chip: BootloaderBoard.Chip): FirmwareImage =
        FirmwareImage.bundled(chip, names(context)) { name -> context.assets.open("$ASSET_DIR/$name").use { it.readBytes() } }
}

/** A user-chosen `.uf2`, read whole (bounded by [MAX_LOCAL_FILE_SIZE]) and checked before it is offered. */
object LocalFirmware {
    /**
     * Largest local file read into memory. DSPi UF2s are ~0.6 MB; 8 MB is a
     * 4 MB flash image with headroom, and keeps a mis-picked file from costing
     * the heap the parser's theoretical 64 MB limit would.
     */
    const val MAX_LOCAL_FILE_SIZE = 8 * 1024 * 1024

    fun read(context: Context, uri: Uri): FirmwareImage {
        val resolver = context.applicationContext.contentResolver
        var name = "firmware.uf2"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    if (!it.isNull(0)) name = it.getString(0)
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
        }
        // Refuse a mis-picked large file before reading any of it.
        if (size > MAX_LOCAL_FILE_SIZE) throw Uf2FormatException("the file is too large to be firmware")
        val bytes = resolver.openInputStream(uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                if (buf.size() > MAX_LOCAL_FILE_SIZE) throw Uf2FormatException("the file is too large to be firmware")
            }
            buf.toByteArray()
        } ?: throw IOException("could not read $name")
        return localImage(name, bytes)
    }

    /** Parses [bytes] (throws [Uf2FormatException]) and names the chip it is for. */
    fun localImage(name: String, bytes: ByteArray): FirmwareImage {
        val uf2 = Uf2Image.parse(bytes)
        val chip = uf2.familyId?.let { Uf2.chipOfFamily(it) } ?: if (uf2.familyId == null) BootloaderBoard.Chip.RP2040 else null
        return FirmwareImage(chip, name.ifBlank { "firmware.uf2" }, version = null, bytes = bytes, isBundled = false)
    }
}

package se.bofr.dspi.feature.firmware

import android.content.Context
import android.hardware.usb.UsbDevice
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import se.bofr.dspi.usb.BootloaderDevice
import se.bofr.dspi.usb.BootloaderLocator
import se.bofr.dspi.usb.BootloaderPermissionResult

/** Builds UF2 files block by block (UF2 spec layout). */
object TestUf2 {
    fun block(
        addr: Long,
        blockNo: Int,
        numBlocks: Int,
        family: Long? = Uf2.FAMILY_RP2350_ARM_S,
        payload: ByteArray = ByteArray(256) { (addr / 256 + it).toByte() },
        flags: Int = if (family != null) Uf2.FLAG_FAMILY_ID_PRESENT else 0,
        payloadSize: Int = payload.size
    ): ByteArray {
        val b = ByteBuffer.allocate(512).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(Uf2.MAGIC_START0.toInt())
        b.putInt(Uf2.MAGIC_START1.toInt())
        b.putInt(flags)
        b.putInt(addr.toInt())
        b.putInt(payloadSize)
        b.putInt(blockNo)
        b.putInt(numBlocks)
        b.putInt((family ?: 0L).toInt())
        b.put(payload)
        b.putInt(508, Uf2.MAGIC_END.toInt())
        return b.array()
    }

    /** RP2350-E10 block as picotool `gen_abs_block` makes it. */
    fun absBlock(addr: Long = 0x10FF_FF00L): ByteArray {
        val payload = ByteArray(260) { if (it < 256) 0xEF.toByte() else 0 }
        ByteBuffer.wrap(payload, 256, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(Uf2.EXTENSION_RP2_IGNORE_BLOCK.toInt())
        return block(
            addr,
            0,
            2,
            Uf2.FAMILY_ABSOLUTE,
            payload,
            Uf2.FLAG_FAMILY_ID_PRESENT or Uf2.FLAG_EXTENSION_TAGS_PRESENT,
            payloadSize = 256
        )
    }

    /** A contiguous image of [pages] pages from 0x10000000. */
    fun image(pages: Int, family: Long? = Uf2.FAMILY_RP2350_ARM_S, start: Long = Uf2.FLASH_START): ByteArray =
        (0 until pages).map { block(start + it * 256L, it, pages, family) }.reduce { a, b -> a + b }
}

/** Bundled assets, read from the module's source tree (unit tests run in the module dir). */
object Assets {
    val dir = File("src/main/assets/firmware")
    val names: List<String> get() = dir.list()?.toList().orEmpty()

    fun read(name: String): ByteArray = File(dir, name).readBytes()
}

fun firmwareImage(
    bytes: ByteArray,
    version: FirmwareVersion? = FirmwareVersion(1, 1, 7),
    chip: BootloaderBoard.Chip = BootloaderBoard.Chip.RP2350
) = FirmwareImage(chip, "DSPi-${chip.assetToken}-v${version?.tagSuffix ?: "x"}.uf2", version, bytes, isBundled = version != null)

/** Records every write; can fail after [failAfterWrites] chunks or be gated on access. */
class FakeWriter(
    var ready: Boolean = true,
    var access: Boolean = true,
    var accessAnswer: BoardAccess = BoardAccess.GRANTED,
    var failure: Exception? = null,
    var progressSteps: Int = 20
) : FirmwareWriter {
    val written = mutableListOf<Pair<BootloaderBoard, FirmwareImage>>()
    var accessRequests = 0

    override fun isReady(board: BootloaderBoard) = ready

    override fun hasAccess(board: BootloaderBoard) = access

    override suspend fun obtainAccess(board: BootloaderBoard): BoardAccess {
        accessRequests++
        return accessAnswer
    }

    override suspend fun write(board: BootloaderBoard, image: FirmwareImage, uf2: Uf2Image, progress: (Double) -> Unit) {
        for (i in 1..progressSteps) progress(i.toDouble() / progressSteps)
        failure?.let { throw it }
        written += board to image
    }
}

/** A stub verifier answering at once. */
class StubVerifier(private val version: FirmwareVersion?) : FirmwareVerifying {
    override suspend fun awaitDeviceVersion(timeoutMs: Long) = version
}

/** :usb BootloaderLocator double (no Context use). */
class FakeUsbLocator(devices: List<BootloaderDevice> = emptyList()) : BootloaderLocator {
    val deviceFlow = MutableStateFlow(devices)
    override val devices: StateFlow<List<BootloaderDevice>> get() = deviceFlow
    val results = MutableSharedFlow<BootloaderPermissionResult>(extraBufferCapacity = 8)
    override val permissionResults: SharedFlow<BootloaderPermissionResult> get() = results
    val granted = mutableSetOf<String>()
    val requested = mutableListOf<String>()

    /** What the "system dialog" answers, emitted synchronously from requestPermission; null = no answer. */
    var answer: Boolean? = null

    /** Subscribers when requestPermission was called (proves collect-before-request). */
    var subscribersAtRequest = -1

    override fun start(context: Context) = Unit

    override fun stop(context: Context) = Unit

    override fun hasPermission(device: BootloaderDevice) = device.deviceName in granted

    override fun requestPermission(device: BootloaderDevice) {
        requested += device.deviceName
        subscribersAtRequest = results.subscriptionCount.value
        answer?.let { results.tryEmit(BootloaderPermissionResult(device.deviceName, it)) }
    }

    override fun usbDevice(device: BootloaderDevice): UsbDevice? = null
}

/**
 * PICOBOOT device double: decodes commands per datasheet Table 458, keeps a
 * sparse flash, records the command log, and can drop off the bus.
 */
class FakePicobootDevice(private val partitionReply: IntArray = intArrayOf(3, 0xFF, 0 or ((4095) shl 13), 0)) : PicobootTransport {
    val log = mutableListOf<String>()
    val flash = HashMap<Long, Byte>()
    val erased = mutableListOf<Long>()
    var exclusive = 0
    var resets = 0
    var closed = false

    /** Fail (return -1) the n-th WRITE data phase; simulates an unplug. */
    var unplugAtWrite = -1

    /** The board is gone: every transfer fails. */
    var gone = false

    /** Status the board reports after a failure. */
    var lastStatus = 0
    private var writes = 0
    private var pending: ByteArray? = null
    private var pendingAck = false
    private var lastToken = 0

    override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int {
        if (gone) return -1
        val p = pending
        if (p != null) {
            // data phase of WRITE
            val addr = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN).getInt(16).toLong() and 0xFFFFFFFFL
            writes++
            if (writes == unplugAtWrite) {
                gone = true
                return -1
            }
            for (i in 0 until length) flash[addr + i] = data[i]
            pending = null
            pendingAck = true
            return length
        }
        if (pendingAck) {
            // ack of an IN command
            pendingAck = false
            return length
        }
        require(length == 32) { "commands are 32 bytes" }
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == Picoboot.MAGIC)
        lastToken = b.getInt(4)
        val id = data[8].toInt() and 0xFF
        val size = data[9].toInt() and 0xFF
        val transfer = b.getInt(12)
        val a0 = b.getInt(16).toLong() and 0xFFFFFFFFL
        val a1 = b.getInt(20)
        log += when (id) {
            Picoboot.PC_EXCLUSIVE_ACCESS -> "EXCLUSIVE ${data[16]}".also { exclusive = data[16].toInt() }
            Picoboot.PC_EXIT_XIP -> "EXIT_XIP"
            Picoboot.PC_FLASH_ERASE -> {
                require(size == 8)
                for (i in 0 until a1) flash.remove(a0 + i)
                erased += a0
                "ERASE %08x %d".format(a0, a1)
            }
            Picoboot.PC_WRITE -> {
                require(size == 8 && transfer == a1)
                pending = data.copyOf()
                "WRITE %08x %d".format(a0, a1)
            }
            Picoboot.PC_GET_INFO -> {
                require(size == 16 && data[16].toInt() == Picoboot.GET_INFO_UF2_TARGET_PARTITION)
                "GET_INFO ${data[16]} %08x".format(a1)
            }
            Picoboot.PC_REBOOT -> "REBOOT %d %08x %d".format(a0, a1, b.getInt(24))
            Picoboot.PC_REBOOT2 -> "REBOOT2 %d %d %08x %d".format(a0, a1, b.getInt(24), b.getInt(28)).also { gone = true }
            else -> "CMD %02x".format(id)
        }
        if (id == Picoboot.PC_GET_INFO) pendingAck = false
        return 32
    }

    override fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int {
        if (gone) return -1
        val last = log.lastOrNull().orEmpty()
        if (last.startsWith("GET_INFO") && !pendingAck) {
            val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until length / 4) b.putInt(i * 4, partitionReply.getOrElse(i) { 0 })
            pendingAck = true
            return length
        }
        pendingAck = false
        return 0 // the ZLP ack
    }

    override fun controlOut(request: Int, timeoutMs: Int): Int {
        if (gone) return -1
        if (request == Picoboot.IF_RESET) {
            resets++
            exclusive = 0
            log += "RESET"
        }
        return 0
    }

    override fun controlIn(request: Int, buffer: ByteArray, timeoutMs: Int): Int {
        if (gone) return -1
        val b = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, lastToken)
        b.putInt(4, lastStatus)
        return 16
    }

    override fun close() {
        closed = true
    }

    fun read(addr: Long, n: Int) = ByteArray(n) { flash[addr + it] ?: 0xFF.toByte() }
}

/** A directory standing in for the board's drive (macOS tests' scratch volume). */
class DirVolume(val dir: File, override val name: String = "RP2350") : Uf2Volume {
    var failAtChunk = -1
    var vanishAtFailure = false
    private var chunks = 0

    override fun isMounted() = dir.exists()

    override fun create(fileName: String): Uf2Sink {
        if (!dir.exists()) throw IOException("no volume")
        val out = File(dir, fileName).outputStream()
        return object : Uf2Sink {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                chunks++
                if (chunks == failAtChunk) {
                    if (vanishAtFailure) dir.deleteRecursively()
                    throw IOException("Input/output error")
                }
                out.write(bytes, offset, length)
            }

            override fun sync() = out.fd.sync()

            override fun close() = out.close()
        }
    }
}

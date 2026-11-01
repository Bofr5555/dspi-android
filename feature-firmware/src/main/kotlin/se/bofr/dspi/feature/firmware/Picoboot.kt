// PICOBOOT: the RP2040/RP2350 boot ROM's vendor USB interface.
// Protocol from the RP2350 datasheet §5.6 "USB PICOBOOT interface" (Tables
// 458-471) and the RP2040 datasheet §2.8.5 (REBOOT 0x02); command sequencing
// (reset, exclusive access, exit XIP, erase sector + write pages, reboot) and
// the UF2_TARGET_PARTITION lookup follow picotool's `load` and `reboot`
// (raspberrypi/picotool main.cpp, picoboot_connection.c; BSD-3, read, not
// copied). No upstream DSPi Console writes over PICOBOOT; they copy the UF2 to
// the boot ROM's drive, which Android cannot reliably do (UPSTREAM-DEVIATIONS).
package se.bofr.dspi.feature.firmware

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Command encoding (datasheet Table 458) — pure, JVM-tested. */
object Picoboot {
    const val MAGIC = 0x431FD10B
    const val COMMAND_SIZE = 32

    const val PC_EXCLUSIVE_ACCESS = 0x01
    const val PC_REBOOT = 0x02
    const val PC_FLASH_ERASE = 0x03
    const val PC_READ = 0x84
    const val PC_WRITE = 0x05
    const val PC_EXIT_XIP = 0x06
    const val PC_ENTER_XIP = 0x07
    const val PC_REBOOT2 = 0x0A
    const val PC_GET_INFO = 0x8B

    /** Control requests on the default pipe, recipient = interface (§5.6.5). */
    const val IF_RESET = 0x41
    const val IF_CMD_STATUS = 0x42

    /** bmRequestType: vendor | interface, host-to-device / device-to-host. */
    const val REQUEST_TYPE_OUT = 0x41
    const val REQUEST_TYPE_IN = 0xC1

    const val NOT_EXCLUSIVE = 0
    const val EXCLUSIVE = 1
    const val EXCLUSIVE_AND_EJECT = 2

    const val GET_INFO_UF2_TARGET_PARTITION = 0x03

    /** REBOOT2 flags (`reboot()` API, §5.4.8.24). */
    const val REBOOT2_TYPE_NORMAL = 0x0000
    const val REBOOT2_TYPE_FLASH_UPDATE = 0x0004

    /** RP2040 REBOOT: initial SP picotool passes (end of SRAM); ignored when PC = 0. */
    const val RP2040_SRAM_END = 0x2004_2000

    /** picotool's reboot delay. */
    const val REBOOT_DELAY_MS = 500

    /** picotool PARTITION_LOCATION_{FIRST,LAST}_SECTOR_LSB, 13-bit sector numbers. */
    private const val LOCATION_FIRST_SECTOR_LSB = 0
    private const val LOCATION_LAST_SECTOR_LSB = 13
    private const val LOCATION_SECTOR_MASK = 0x1FFF

    /** dTransferLength for UF2_TARGET_PARTITION: count word + three words (multiple of 4, < 256). */
    const val TARGET_PARTITION_REPLY_SIZE = 16

    /** The 32-byte command packet. [args] is padded with zeros to 16 bytes. */
    fun command(token: Int, cmdId: Int, cmdSize: Int, transferLength: Int, args: ByteArray = ByteArray(0)): ByteArray {
        require(args.size <= 16 && cmdSize <= 16)
        val b = ByteBuffer.allocate(COMMAND_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(MAGIC)
        b.putInt(token)
        b.put(cmdId.toByte())
        b.put(cmdSize.toByte())
        b.putShort(0)
        b.putInt(transferLength)
        b.put(args)
        return b.array()
    }

    private fun words(vararg w: Int): ByteArray {
        val b = ByteBuffer.allocate(w.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        w.forEach { b.putInt(it) }
        return b.array()
    }

    fun exclusiveAccess(token: Int, mode: Int) = command(token, PC_EXCLUSIVE_ACCESS, 1, 0, byteArrayOf(mode.toByte()))

    /** [addr] and [size] must be 4 kB-aligned (Table 460). */
    fun flashErase(token: Int, addr: Long, size: Int): ByteArray {
        require(addr % Uf2.SECTOR_SIZE == 0L && size > 0 && size % Uf2.SECTOR_SIZE == 0) { "erase must be sector aligned" }
        return command(token, PC_FLASH_ERASE, 8, 0, words(addr.toInt(), size))
    }

    /** Flash writes must be page (256 B) aligned (Table 462); dTransferLength = dSize. */
    fun write(token: Int, addr: Long, size: Int): ByteArray {
        require(addr % Uf2.PAGE_SIZE == 0L && size > 0) { "flash write must be page aligned" }
        return command(token, PC_WRITE, 8, size, words(addr.toInt(), size))
    }

    fun read(token: Int, addr: Long, size: Int) = command(token, PC_READ, 8, size, words(addr.toInt(), size))

    fun exitXip(token: Int) = command(token, PC_EXIT_XIP, 0, 0)

    /** RP2040 only (Not supported on RP2350): dPC (0 = normal boot), dSP, dDelayMS. */
    fun reboot(token: Int, pc: Int, sp: Int, delayMs: Int) = command(token, PC_REBOOT, 12, 0, words(pc, sp, delayMs))

    /** RP2350 (Table 465): flags, delay_ms, p0, p1. */
    fun reboot2(token: Int, flags: Int, delayMs: Int, p0: Int, p1: Int) = command(token, PC_REBOOT2, 16, 0, words(flags, delayMs, p0, p1))

    /** GET_INFO (Table 466): bType, bParam, wParam, dParams[3]; dParam0 = family ID. */
    fun getInfoUf2TargetPartition(token: Int, familyId: Long): ByteArray {
        val args = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        args.put(GET_INFO_UF2_TARGET_PARTITION.toByte())
        args.put(0)
        args.putShort(0)
        args.putInt(familyId.toInt())
        return command(token, PC_GET_INFO, 16, TARGET_PARTITION_REPLY_SIZE, args.array())
    }

    /** Where a UF2 of some family would land: [start]..[end) relative to flash start. */
    data class TargetPartition(val partition: Int, val start: Long, val end: Long)

    /**
     * Decodes the UF2_TARGET_PARTITION reply (count, partition, location and
     * permissions, flags). Null when there is nowhere to download the family.
     */
    fun decodeTargetPartition(reply: ByteArray): TargetPartition? {
        val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        if (reply.size < 12) throw IOException("short GET_INFO reply")
        val count = b.getInt(0)
        if (count < 2) throw IOException("GET_INFO returned $count words")
        val partition = b.getInt(4)
        if (partition < 0) return null
        val location = b.getInt(8)
        val first = ((location ushr LOCATION_FIRST_SECTOR_LSB) and LOCATION_SECTOR_MASK).toLong() * Uf2.SECTOR_SIZE
        val last = (((location ushr LOCATION_LAST_SECTOR_LSB) and LOCATION_SECTOR_MASK).toLong() + 1) * Uf2.SECTOR_SIZE
        return TargetPartition(partition, first, last)
    }

    /** GET_COMMAND_STATUS reply (Table 471, 16 bytes). */
    data class CommandStatus(val token: Int, val statusCode: Int, val cmdId: Int, val inProgress: Boolean) {
        val statusName: String get() = statusName(statusCode)

        companion object {
            fun decode(reply: ByteArray): CommandStatus? {
                if (reply.size < 10) return null
                val b = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
                return CommandStatus(b.getInt(0), b.getInt(4), reply[8].toInt() and 0xFF, reply[9].toInt() != 0)
            }
        }
    }

    fun statusName(code: Int): String = when (code) {
        0 -> "OK"
        1 -> "UNKNOWN_CMD"
        2 -> "INVALID_CMD_LENGTH"
        3 -> "INVALID_TRANSFER_LENGTH"
        4 -> "INVALID_ADDRESS"
        5 -> "BAD_ALIGNMENT"
        6 -> "INTERLEAVED_WRITE"
        7 -> "REBOOTING"
        8 -> "UNKNOWN_ERROR"
        9 -> "INVALID_STATE"
        10 -> "NOT_PERMITTED"
        11 -> "INVALID_ARG"
        12 -> "BUFFER_TOO_SMALL"
        13 -> "PRECONDITION_NOT_MET"
        14 -> "MODIFIED_DATA"
        15 -> "INVALID_DATA"
        16 -> "NOT_FOUND"
        17 -> "UNSUPPORTED_MODIFICATION"
        else -> "status $code"
    }
}

/**
 * The bulk/control pipes of one claimed PICOBOOT interface. Return values
 * follow `UsbDeviceConnection`: bytes transferred, negative on failure.
 */
interface PicobootTransport : Closeable {
    fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int): Int
    fun bulkIn(buffer: ByteArray, length: Int, timeoutMs: Int): Int

    /** Vendor request to the interface, host-to-device, no data. */
    fun controlOut(request: Int, timeoutMs: Int): Int

    /** Vendor request to the interface, device-to-host, into [buffer]. */
    fun controlIn(request: Int, buffer: ByteArray, timeoutMs: Int): Int
}

/** A PICOBOOT command failed; [status] is GET_COMMAND_STATUS when it could be read. */
class PicobootException(message: String, val status: Picoboot.CommandStatus? = null) : IOException(message)

/** picotool `picoboot_cmd`: command, optional data phase, then the ack in the opposite direction. */
class PicobootConnection(private val transport: PicobootTransport) {
    private var token = 1

    /** INTERFACE_RESET: clears halts, aborts transfers, drops exclusivity (§5.6.5.1). */
    fun reset() {
        if (transport.controlOut(Picoboot.IF_RESET, CONTROL_TIMEOUT_MS) < 0) throw PicobootException("the board did not accept a reset")
    }

    fun commandStatus(): Picoboot.CommandStatus? {
        val buf = ByteArray(16)
        val n = transport.controlIn(Picoboot.IF_CMD_STATUS, buf, CONTROL_TIMEOUT_MS)
        return if (n == 16) Picoboot.CommandStatus.decode(buf) else null
    }

    fun nextToken(): Int = token++

    /** Sends [command] (built with [nextToken]); [data] is the OUT payload or the IN buffer. */
    fun execute(command: ByteArray, data: ByteArray? = null, timeoutMs: Int = DATA_TIMEOUT_MS) {
        val cmdId = command[8].toInt() and 0xFF
        val transferLength = ByteBuffer.wrap(command).order(ByteOrder.LITTLE_ENDIAN).getInt(12)
        val name = commandName(cmdId)
        if (transport.bulkOut(command, command.size, COMMAND_TIMEOUT_MS) != command.size) fail("$name was not accepted")
        val isIn = cmdId and 0x80 != 0
        if (transferLength != 0) {
            val buf = requireNotNull(data) { "$name needs a buffer" }
            require(buf.size >= transferLength)
            val n = if (isIn) transport.bulkIn(buf, transferLength, timeoutMs) else transport.bulkOut(buf, transferLength, timeoutMs)
            if (n != transferLength) fail("$name transferred ${maxOf(n, 0)} of $transferLength bytes")
        }
        // The ack goes the other way. The datasheet calls it an empty packet;
        // picotool (field-proven) sends a 1-byte OUT packet after IN commands
        // and reads with a 1-byte buffer after OUT commands. Do the same.
        val spoon = ByteArray(1)
        val ackTimeout = if (transferLength == 0) timeoutMs else COMMAND_TIMEOUT_MS
        val ack = if (isIn) transport.bulkOut(spoon, 1, ackTimeout) else transport.bulkIn(spoon, 1, ackTimeout)
        if (ack < 0) fail("$name did not complete")
    }

    private fun fail(what: String): Nothing {
        val status = runCatching { commandStatus() }.getOrNull()
        throw PicobootException(if (status != null && status.statusCode != 0) "$what (${status.statusName})" else what, status)
    }

    companion object {
        const val COMMAND_TIMEOUT_MS = 3000
        const val DATA_TIMEOUT_MS = 10000
        const val CONTROL_TIMEOUT_MS = 1000

        fun commandName(id: Int): String = when (id) {
            Picoboot.PC_EXCLUSIVE_ACCESS -> "EXCLUSIVE_ACCESS"
            Picoboot.PC_REBOOT -> "REBOOT"
            Picoboot.PC_FLASH_ERASE -> "FLASH_ERASE"
            Picoboot.PC_READ -> "READ"
            Picoboot.PC_WRITE -> "WRITE"
            Picoboot.PC_EXIT_XIP -> "EXIT_XIP"
            Picoboot.PC_ENTER_XIP -> "ENTER_XIP"
            Picoboot.PC_REBOOT2 -> "REBOOT2"
            Picoboot.PC_GET_INFO -> "GET_INFO"
            else -> "command 0x" + id.toString(16)
        }
    }
}

/**
 * Writes a [Uf2Image] the way picotool `load` + `reboot` does: exclusive
 * access, exit XIP, (RP2350) find the target partition for the family, then
 * per 4 kB sector one FLASH_ERASE and WRITEs of the 256-byte pages the image
 * has there (absent pages stay erased, as with a drag-and-drop), then reboot
 * into the new image.
 */
class PicobootFlasher(private val connection: PicobootConnection) {

    /** [progress] gets the fraction of sectors written, 0..1. Returns once the reboot was requested. */
    fun flash(image: Uf2Image, chip: BootloaderBoard.Chip, progress: (Double) -> Unit) {
        connection.reset()
        connection.execute(Picoboot.exclusiveAccess(connection.nextToken(), Picoboot.EXCLUSIVE))
        connection.execute(Picoboot.exitXip(connection.nextToken()))

        val offset = targetOffset(image, chip)
        val sectors = image.sectors
        sectors.forEachIndexed { index, sector ->
            connection.execute(Picoboot.flashErase(connection.nextToken(), sector + offset, Uf2.SECTOR_SIZE))
            for ((addr, data) in runs(image.pagesIn(sector))) {
                connection.execute(Picoboot.write(connection.nextToken(), addr + offset, data.size), data)
            }
            progress((index + 1).toDouble() / sectors.size)
        }

        // The board drops off the bus as it reboots, so a failure to ack this
        // last command is the ordinary ending.
        runCatching {
            when (chip) {
                BootloaderBoard.Chip.RP2040 ->
                    connection.execute(Picoboot.reboot(connection.nextToken(), 0, Picoboot.RP2040_SRAM_END, Picoboot.REBOOT_DELAY_MS))
                BootloaderBoard.Chip.RP2350 ->
                    connection.execute(
                        Picoboot.reboot2(
                            connection.nextToken(),
                            Picoboot.REBOOT2_TYPE_FLASH_UPDATE,
                            Picoboot.REBOOT_DELAY_MS,
                            (Uf2.FLASH_START + offset).toInt(),
                            0
                        )
                    )
            }
        }
    }

    /** Leave the interface as found (drops exclusivity, remounts the drive). Best effort. */
    fun abandon() {
        runCatching { connection.reset() }
    }

    /**
     * RP2350: where the boot ROM would put a UF2 of this family
     * (picotool `get_target_partition`); RP2040 has no partitions.
     */
    private fun targetOffset(image: Uf2Image, chip: BootloaderBoard.Chip): Long {
        if (chip != BootloaderBoard.Chip.RP2350) return 0
        val family = image.familyId ?: Uf2.FAMILY_ABSOLUTE
        val reply = ByteArray(Picoboot.TARGET_PARTITION_REPLY_SIZE)
        connection.execute(Picoboot.getInfoUf2TargetPartition(connection.nextToken(), family), reply)
        val target = Picoboot.decodeTargetPartition(reply)
            ?: throw PicobootException("the board's partition table has no place for ${Uf2.familyName(family)} firmware")
        val size = image.endAddr - Uf2.FLASH_START
        if (size > target.end - target.start) {
            throw PicobootException(
                "the firmware does not fit the board's partition (${size / 1024} KB > ${(target.end - target.start) / 1024} KB)"
            )
        }
        return target.start
    }

    companion object {
        /** Contiguous page runs of one sector, as (address, bytes). */
        fun runs(pages: Map<Long, ByteArray>): List<Pair<Long, ByteArray>> {
            val out = mutableListOf<Pair<Long, ByteArray>>()
            var start = -1L
            var buf = java.io.ByteArrayOutputStream()
            var next = -1L
            for ((addr, data) in pages) {
                if (addr != next) {
                    if (start >= 0) out += start to buf.toByteArray()
                    start = addr
                    buf = java.io.ByteArrayOutputStream()
                }
                buf.write(data)
                next = addr + data.size
            }
            if (start >= 0) out += start to buf.toByteArray()
            return out
        }
    }
}

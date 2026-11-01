// UF2 parsing and validation for the PICOBOOT write path. No upstream Console
// parses UF2 (macOS and Linux copy the file to the boot ROM's drive and let the
// ROM decide), so the rules come from the RP2350 datasheet §5.5.2 "UF2 format
// details" / §5.5.3 Table 455 (family IDs) and the UF2 spec
// (github.com/microsoft/uf2). The RP2350-E10 absolute block is recognised the
// way picotool's `check_abs_block` (elf2uf2.cpp, BSD-3) does and skipped.
package se.bofr.dspi.feature.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SortedMap
import java.util.TreeMap

/** The file is not a UF2 the boot ROM would take. [message] is fit for "This is not a firmware file …: <message>". */
class Uf2FormatException(message: String) : Exception(message)

/** One parsed 512-byte UF2 block. */
data class Uf2Block(
    val flags: Int,
    val targetAddr: Long,
    val payloadSize: Int,
    val blockNo: Int,
    val numBlocks: Int,
    /** familyID when [FLAG_FAMILY_ID_PRESENT] is set, otherwise the file-size field. */
    val fileSizeOrFamily: Long,
    val data: ByteArray
) {
    val familyId: Long? get() = if (flags and Uf2.FLAG_FAMILY_ID_PRESENT != 0) fileSizeOrFamily else null

    override fun equals(other: Any?): Boolean = other is Uf2Block &&
        flags == other.flags &&
        targetAddr == other.targetAddr &&
        payloadSize == other.payloadSize &&
        blockNo == other.blockNo &&
        numBlocks == other.numBlocks &&
        fileSizeOrFamily == other.fileSizeOrFamily &&
        data.contentEquals(other.data)

    override fun hashCode(): Int = (targetAddr * 31 + blockNo).hashCode()
}

/**
 * A validated flash image: 256-byte pages by absolute target address
 * (0x10000000-based, before any partition offset).
 */
class Uf2Image private constructor(
    /** Null for a legacy RP2040 UF2 without family IDs. */
    val familyId: Long?,
    val numBlocks: Int,
    val pages: SortedMap<Long, ByteArray>,
    /** An RP2350-E10 absolute block was present and skipped. */
    val skippedE10Block: Boolean
) {
    val firstAddr: Long get() = pages.firstKey()
    val endAddr: Long get() = pages.lastKey() + Uf2.PAGE_SIZE

    /** 4 kB sectors touched, in address order. */
    val sectors: List<Long> get() = pages.keys.map { it and (Uf2.SECTOR_SIZE - 1).toLong().inv() }.distinct()

    /** Pages of one 4 kB sector, in order. */
    fun pagesIn(sector: Long): SortedMap<Long, ByteArray> = pages.subMap(sector, sector + Uf2.SECTOR_SIZE)

    /**
     * Why this image can't go onto [chip], or null when it can: the family must
     * be one the chip's boot ROM boots (Table 455) and every page must fit its
     * flash window.
     */
    fun targetError(chip: BootloaderBoard.Chip): FirmwareInstallError? {
        val family = familyId
        when (chip) {
            BootloaderBoard.Chip.RP2040 -> if (family != null && family != Uf2.FAMILY_RP2040) {
                return Uf2.chipOfFamily(family)?.let { FirmwareInstallError.WrongBoardFamily(it.displayName, chip.displayName) }
                    ?: FirmwareInstallError.InvalidImage("its family ID ${Uf2.familyName(family)} is not one the RP2040 boots")
            }
            BootloaderBoard.Chip.RP2350 -> when (family) {
                Uf2.FAMILY_RP2350_ARM_S, Uf2.FAMILY_RP2350_RISCV, Uf2.FAMILY_ABSOLUTE -> Unit
                null, Uf2.FAMILY_RP2040 -> return FirmwareInstallError.WrongBoardFamily(
                    BootloaderBoard.Chip.RP2040.displayName,
                    chip.displayName
                )
                else -> return FirmwareInstallError.InvalidImage("its family ID ${Uf2.familyName(family)} is not one the RP2350 boots")
            }
        }
        val flashEnd = Uf2.FLASH_START + Uf2.flashWindow(chip)
        if (endAddr > flashEnd) {
            return FirmwareInstallError.InvalidImage(
                "it reaches past the end of the ${chip.displayName}'s flash (0x${endAddr.toString(16)})"
            )
        }
        return null
    }

    companion object {
        /** Parses and validates [bytes]; throws [Uf2FormatException] with a user-readable reason. */
        fun parse(bytes: ByteArray): Uf2Image {
            if (bytes.isEmpty()) throw Uf2FormatException("the file is empty")
            if (bytes.size > Uf2.MAX_FILE_SIZE) throw Uf2FormatException("the file is too large to be firmware")
            if (bytes.size % Uf2.BLOCK_SIZE != 0) throw Uf2FormatException("the file is not a whole number of UF2 blocks")
            val count = bytes.size / Uf2.BLOCK_SIZE
            var family: Long? = null
            var familyKnown = false
            var numBlocks = -1
            var skippedE10 = false
            val seen = HashSet<Int>()
            val pages = TreeMap<Long, ByteArray>()
            for (i in 0 until count) {
                val block = Uf2.block(bytes, i) ?: throw Uf2FormatException("block ${i + 1} is not a UF2 block")
                if (block.flags and Uf2.FLAG_NOT_MAIN_FLASH != 0) continue
                if (Uf2.isAbsBlock(block)) {
                    skippedE10 = true
                    continue
                }
                if (block.payloadSize != Uf2.PAGE_SIZE) {
                    throw Uf2FormatException("block ${i + 1} carries ${block.payloadSize} bytes; the boot ROM takes ${Uf2.PAGE_SIZE}")
                }
                if (!familyKnown) {
                    family = block.familyId
                    familyKnown = true
                    numBlocks = block.numBlocks
                } else {
                    if (block.familyId != family) throw Uf2FormatException("it mixes firmware for different chips")
                    if (block.numBlocks != numBlocks) throw Uf2FormatException("its blocks disagree about the image size")
                }
                if (block.blockNo < 0 ||
                    block.blockNo >= numBlocks
                ) {
                    throw Uf2FormatException("block ${i + 1} is numbered outside the image")
                }
                val addr = block.targetAddr
                if (addr < Uf2.FLASH_START || addr + Uf2.PAGE_SIZE > Uf2.FLASH_START + Uf2.FLASH_WINDOW_MAX) {
                    throw Uf2FormatException(
                        if (addr in Uf2.SRAM_START until Uf2.SRAM_START + 0x0100_0000L) {
                            "it is a RAM image, not firmware for flash"
                        } else {
                            "block ${i + 1} targets 0x${addr.toString(16)}, outside flash"
                        }
                    )
                }
                if (addr % Uf2.PAGE_SIZE != 0L) throw Uf2FormatException("block ${i + 1} is not aligned to a 256-byte page")
                val payload = block.data.copyOfRange(0, Uf2.PAGE_SIZE)
                val previous = pages[addr]
                if (previous != null && !previous.contentEquals(payload)) {
                    throw Uf2FormatException("two blocks write different data to 0x${addr.toString(16)}")
                }
                pages[addr] = payload
                seen += block.blockNo
            }
            if (!familyKnown) throw Uf2FormatException("it contains no firmware blocks")
            if (seen.size != numBlocks) throw Uf2FormatException("it is incomplete (${seen.size} of $numBlocks blocks)")
            return Uf2Image(family, numBlocks, pages, skippedE10)
        }
    }
}

/** UF2 constants (UF2 spec; RP2350 datasheet Table 455). */
object Uf2 {
    const val BLOCK_SIZE = 512
    const val PAGE_SIZE = 256
    const val SECTOR_SIZE = 4096
    const val MAGIC_START0 = 0x0A324655L
    const val MAGIC_START1 = 0x9E5D5157L
    const val MAGIC_END = 0x0AB16F30L

    const val FLAG_NOT_MAIN_FLASH = 0x0000_0001
    const val FLAG_FILE_CONTAINER = 0x0000_1000
    const val FLAG_FAMILY_ID_PRESENT = 0x0000_2000
    const val FLAG_MD5_PRESENT = 0x0000_4000
    const val FLAG_EXTENSION_TAGS_PRESENT = 0x0000_8000

    /** picotool UF2_EXTENSION_RP2_IGNORE_BLOCK: size 4 + tag 0x9957e3. */
    const val EXTENSION_RP2_IGNORE_BLOCK = 0x9957E304L

    const val FAMILY_ABSOLUTE = 0xE48BFF57L
    const val FAMILY_RP2040 = 0xE48BFF56L
    const val FAMILY_DATA = 0xE48BFF58L
    const val FAMILY_RP2350_ARM_S = 0xE48BFF59L
    const val FAMILY_RP2350_RISCV = 0xE48BFF5AL
    const val FAMILY_RP2350_ARM_NS = 0xE48BFF5BL

    const val FLASH_START = 0x1000_0000L
    const val SRAM_START = 0x2000_0000L

    /** Largest flash window either chip addresses (RP2350: 0x10000000-0x12000000). */
    const val FLASH_WINDOW_MAX = 0x0200_0000L

    /** Refuse anything bigger than a UF2 of the whole RP2350 flash window. */
    const val MAX_FILE_SIZE = (FLASH_WINDOW_MAX / PAGE_SIZE * BLOCK_SIZE).toInt()

    /** RP2040: 16 MB window; RP2350: 32 MB (datasheet §5.5.2). */
    fun flashWindow(chip: BootloaderBoard.Chip): Long = when (chip) {
        BootloaderBoard.Chip.RP2040 -> 0x0100_0000L
        BootloaderBoard.Chip.RP2350 -> FLASH_WINDOW_MAX
    }

    fun familyName(family: Long): String = when (family) {
        FAMILY_ABSOLUTE -> "absolute"
        FAMILY_RP2040 -> "rp2040"
        FAMILY_DATA -> "data"
        FAMILY_RP2350_ARM_S -> "rp2350-arm-s"
        FAMILY_RP2350_RISCV -> "rp2350-riscv"
        FAMILY_RP2350_ARM_NS -> "rp2350-arm-ns"
        else -> "0x" + family.toString(16)
    }

    /** The chip a family belongs to, null for absolute/data/unknown. */
    fun chipOfFamily(family: Long): BootloaderBoard.Chip? = when (family) {
        FAMILY_RP2040 -> BootloaderBoard.Chip.RP2040
        FAMILY_RP2350_ARM_S, FAMILY_RP2350_RISCV, FAMILY_RP2350_ARM_NS -> BootloaderBoard.Chip.RP2350
        else -> null
    }

    /** Block [index] of [bytes], null when its magics are wrong. */
    fun block(bytes: ByteArray, index: Int): Uf2Block? {
        val b = ByteBuffer.wrap(bytes, index * BLOCK_SIZE, BLOCK_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val base = index * BLOCK_SIZE
        fun u32(off: Int) = b.getInt(base + off).toLong() and 0xFFFF_FFFFL
        if (u32(0) != MAGIC_START0 || u32(4) != MAGIC_START1 || u32(508) != MAGIC_END) return null
        val payload = u32(16)
        return Uf2Block(
            flags = u32(8).toInt(),
            targetAddr = u32(12),
            payloadSize = if (payload > 476) Int.MAX_VALUE else payload.toInt(),
            blockNo = u32(20).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            numBlocks = u32(24).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            fileSizeOrFamily = u32(28),
            data = bytes.copyOfRange(base + 32, base + 32 + 476)
        )
    }

    /**
     * picotool `check_abs_block`: the RP2350-E10 workaround block (absolute
     * family, 0xEF payload, block 0 of 2, optional RP2_IGNORE_BLOCK extension).
     * Meant for the drive path only; written over PICOBOOT it would erase a
     * sector at the end of flash.
     */
    fun isAbsBlock(block: Uf2Block): Boolean {
        val extensionOk = if (block.flags and FLAG_EXTENSION_TAGS_PRESENT != 0) {
            val ext = ByteBuffer.wrap(block.data, PAGE_SIZE, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            ext == EXTENSION_RP2_IGNORE_BLOCK
        } else {
            true
        }
        return block.payloadSize == PAGE_SIZE &&
            (block.flags and FLAG_EXTENSION_TAGS_PRESENT.inv()) == FLAG_FAMILY_ID_PRESENT &&
            block.numBlocks == 2 &&
            block.blockNo == 0 &&
            block.fileSizeOrFamily == FAMILY_ABSOLUTE &&
            (0 until PAGE_SIZE).all { block.data[it] == 0xEF.toByte() } &&
            extensionOk
    }
}

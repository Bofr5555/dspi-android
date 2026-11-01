package se.bofr.dspi.feature.firmware

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Command encoding against RP2350 datasheet §5.6.4 (Tables 458-466) and the flasher against a fake device. */
class PicobootTest {
    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun commandHeaderLayout() {
        // dMagic 0x431fd10b LE, dToken, bCmdId, bCmdSize, reserved 0, dTransferLength, 16 args.
        val c = Picoboot.command(token = 0x01020304, cmdId = 0x05, cmdSize = 8, transferLength = 256)
        assertEquals(32, c.size)
        assertArrayEquals(hex("0bd11f43 04030201 05 08 0000 00010000"), c.copyOf(16))
        assertArrayEquals(ByteArray(16), c.copyOfRange(16, 32))
    }

    @Test fun exclusiveAccess() {
        val c = Picoboot.exclusiveAccess(7, Picoboot.EXCLUSIVE)
        assertEquals(0x01, c[8].toInt())
        assertEquals(1, c[9].toInt())
        assertEquals(0, ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN).getInt(12))
        assertEquals(1, c[16].toInt())
        assertEquals(2, Picoboot.exclusiveAccess(8, Picoboot.EXCLUSIVE_AND_EJECT)[16].toInt())
    }

    @Test fun flashEraseNeedsSectors() {
        val c = Picoboot.flashErase(1, 0x1000_1000L, 4096)
        assertArrayEquals(hex("03 08 0000 00000000 00100010 00100000"), c.copyOfRange(8, 24))
        assertThrows(IllegalArgumentException::class.java) { Picoboot.flashErase(1, 0x1000_0100L, 4096) }
        assertThrows(IllegalArgumentException::class.java) { Picoboot.flashErase(1, 0x1000_0000L, 256) }
    }

    @Test fun writeIsPageAlignedWithTransferEqualToSize() {
        val c = Picoboot.write(1, 0x1000_0200L, 512)
        assertArrayEquals(hex("05 08 0000 00020000 00020010 00020000"), c.copyOfRange(8, 24))
        assertThrows(IllegalArgumentException::class.java) { Picoboot.write(1, 0x1000_0010L, 256) }
    }

    @Test fun readExitXipRebootsAndGetInfo() {
        assertArrayEquals(hex("84 08 0000 00010000 00000010 00010000"), Picoboot.read(1, 0x1000_0000L, 256).copyOfRange(8, 24))
        assertArrayEquals(hex("06 00 0000 00000000"), Picoboot.exitXip(1).copyOfRange(8, 16))
        // RP2040 REBOOT: dPC, dSP, dDelayMS (bCmdSize 0x0c)
        assertArrayEquals(
            hex("02 0c 0000 00000000 00000000 00200420 f4010000"),
            Picoboot.reboot(1, 0, Picoboot.RP2040_SRAM_END, 500).copyOfRange(8, 28)
        )
        // RP2350 REBOOT2 (Table 465): flags, delay_ms, p0, p1 (bCmdSize 0x10)
        assertArrayEquals(
            hex("0a 10 0000 00000000 04000000 f4010000 00000010 00000000"),
            Picoboot.reboot2(1, Picoboot.REBOOT2_TYPE_FLASH_UPDATE, 500, 0x1000_0000, 0).copyOfRange(8, 32)
        )
        // GET_INFO (Table 466): bType 3 = UF2_TARGET_PARTITION, dParam0 = family; IN (0x8b), transfer multiple of 4 and < 256.
        val g = Picoboot.getInfoUf2TargetPartition(1, Uf2.FAMILY_RP2350_ARM_S)
        assertArrayEquals(hex("8b 10 0000 10000000 03 00 0000 59ff8be4"), g.copyOfRange(8, 24))
    }

    @Test fun decodesTargetPartition() {
        fun reply(vararg w: Int) = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).apply { w.forEach { putInt(it) } }.array()
        // Absolute space, whole 16 MB (first sector 0, last 4095).
        assertEquals(Picoboot.TargetPartition(0xFF, 0, 0x100_0000), Picoboot.decodeTargetPartition(reply(3, 0xFF, 4095 shl 13, 0)))
        // Partition 1 at sectors 0x80..0xFF.
        assertEquals(
            Picoboot.TargetPartition(1, 0x8_0000, 0x10_0000),
            Picoboot.decodeTargetPartition(reply(3, 1, 0x80 or (0xFF shl 13), 0))
        )
        assertNull(Picoboot.decodeTargetPartition(reply(3, -1, 0, 0)))
    }

    @Test fun commandStatusDecodes() {
        val r = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(9).putInt(5).put(0x05).put(1).array()
        val s = Picoboot.CommandStatus.decode(r)!!
        assertEquals(Picoboot.CommandStatus(9, 5, 0x05, true), s)
        assertEquals("BAD_ALIGNMENT", s.statusName)
    }

    @Test fun runsSplitAtGaps() {
        val pages = sortedMapOf(0x1000_0000L to ByteArray(256), 0x1000_0100L to ByteArray(256), 0x1000_0300L to ByteArray(256))
        val runs = PicobootFlasher.runs(pages)
        assertEquals(listOf(0x1000_0000L to 512, 0x1000_0300L to 256), runs.map { it.first to it.second.size })
    }

    // MARK: Flasher against a fake board

    @Test fun rp2350FlashSequenceAndContents() {
        val device = FakePicobootDevice()
        val uf2 = Uf2Image.parse(TestUf2.absBlock() + TestUf2.image(20))
        val fractions = mutableListOf<Double>()
        PicobootFlasher(PicobootConnection(device)).flash(uf2, BootloaderBoard.Chip.RP2350) { fractions += it }

        assertEquals("RESET", device.log[0])
        assertEquals("EXCLUSIVE 1", device.log[1])
        assertEquals("EXIT_XIP", device.log[2])
        assertEquals("GET_INFO 3 e48bff59", device.log[3])
        assertEquals(
            listOf("ERASE 10000000 4096", "WRITE 10000000 4096", "ERASE 10001000 4096", "WRITE 10001000 1024"),
            device.log.subList(4, 8)
        )
        assertEquals("REBOOT2 4 500 10000000 0", device.log.last())
        assertEquals(listOf(0.5, 1.0), fractions)
        for ((addr, data) in uf2.pages) assertArrayEquals(data, device.read(addr, 256))
        // The E10 block was not written: nothing near the end of flash.
        assertTrue(device.flash.keys.none { it >= 0x10FF_0000L })
    }

    @Test fun rp2350PartitionOffsetsTheImage() {
        val device = FakePicobootDevice(intArrayOf(3, 0, 0x100 or (0x1FF shl 13), 0))
        PicobootFlasher(PicobootConnection(device)).flash(Uf2Image.parse(TestUf2.image(2)), BootloaderBoard.Chip.RP2350) {}
        assertTrue(device.log.contains("ERASE 10100000 4096"))
        assertTrue(device.log.contains("WRITE 10100000 512"))
        assertEquals("REBOOT2 4 500 10100000 0", device.log.last())
    }

    @Test fun rp2350RefusesWhenNoPartitionTakesTheFamily() {
        val device = FakePicobootDevice(intArrayOf(3, -1, 0, 0))
        val e = assertThrows(PicobootException::class.java) {
            PicobootFlasher(PicobootConnection(device)).flash(Uf2Image.parse(TestUf2.image(2)), BootloaderBoard.Chip.RP2350) {}
        }
        assertTrue(e.message!!.contains("no place"))
        assertTrue(device.erased.isEmpty())
    }

    @Test fun rp2350RefusesAnImageBiggerThanItsPartition() {
        val device = FakePicobootDevice(intArrayOf(3, 0, 0 or (0 shl 13), 0)) // one sector
        assertThrows(PicobootException::class.java) {
            PicobootFlasher(PicobootConnection(device)).flash(Uf2Image.parse(TestUf2.image(17)), BootloaderBoard.Chip.RP2350) {}
        }
        assertTrue(device.erased.isEmpty())
    }

    @Test fun rp2040UsesRebootWithoutPartitions() {
        val device = FakePicobootDevice()
        PicobootFlasher(
            PicobootConnection(device)
        ).flash(Uf2Image.parse(TestUf2.image(1, Uf2.FAMILY_RP2040)), BootloaderBoard.Chip.RP2040) {
        }
        assertTrue(device.log.none { it.startsWith("GET_INFO") })
        assertEquals("REBOOT 0 20042000 500", device.log.last())
    }

    @Test fun unplugMidWriteIsAFailureWithTheStep() {
        val device = FakePicobootDevice().apply { unplugAtWrite = 2 }
        val e = assertThrows(PicobootException::class.java) {
            PicobootFlasher(PicobootConnection(device)).flash(Uf2Image.parse(TestUf2.image(40)), BootloaderBoard.Chip.RP2350) {}
        }
        assertTrue(e.message!!.contains("WRITE"))
        assertTrue(device.log.none { it.startsWith("REBOOT") })
    }

    @Test fun failureReportsTheBoardsStatus() {
        val fake = FakePicobootDevice().apply { lastStatus = 10 }
        val device = object : PicobootTransport by fake {
            override fun bulkOut(data: ByteArray, length: Int, timeoutMs: Int) = -1
        }
        val e = assertThrows(PicobootException::class.java) { PicobootConnection(device).execute(Picoboot.exitXip(1)) }
        assertEquals("EXIT_XIP was not accepted (NOT_PERMITTED)", e.message)
        assertEquals(10, e.status!!.statusCode)
    }

    // MARK: The writer around it

    private val board = BootloaderBoard(BootloaderBoard.Chip.RP2350, "/dev/bus/usb/001/009")

    private class Perms(var attached: Boolean = true) : BoardPermissions {
        override fun hasPermission(board: BootloaderBoard) = true
        override suspend fun requestPermission(board: BootloaderBoard) = BoardAccess.GRANTED
        override fun isAttached(board: BootloaderBoard) = attached
    }

    @Test fun writerClosesTheTransportAndReportsUnplug() = kotlinx.coroutines.test.runTest {
        val device = FakePicobootDevice().apply { unplugAtWrite = 1 }
        val perms = Perms()
        val writer = PicobootFirmwareWriter(perms) { device }
        val bytes = TestUf2.image(4)
        perms.attached = false // the board has left by the time the error is examined
        val e = runCatching { writer.write(board, firmwareImage(bytes), Uf2Image.parse(bytes)) {} }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WriteFailed("the board was disconnected."), e)
        assertTrue(device.closed)
    }

    @Test fun writerWithoutAnInterfaceFails() = kotlinx.coroutines.test.runTest {
        val writer = PicobootFirmwareWriter(Perms()) { null }
        val bytes = TestUf2.image(1)
        val e = runCatching { writer.write(board, firmwareImage(bytes), Uf2Image.parse(bytes)) {} }.exceptionOrNull()
        assertEquals(FirmwareInstallError.WriteFailed("could not open the board's USB interface."), e)
    }

    @Test fun writerSucceedsAndDropsExclusivityOnlyOnFailure() = kotlinx.coroutines.test.runTest {
        val device = FakePicobootDevice()
        val bytes = TestUf2.image(4)
        PicobootFirmwareWriter(Perms()) { device }.write(board, firmwareImage(bytes), Uf2Image.parse(bytes)) {}
        assertEquals(1, device.resets)
        assertTrue(device.closed)

        val failing = FakePicobootDevice(intArrayOf(3, -1, 0, 0))
        runCatching { PicobootFirmwareWriter(Perms()) { failing }.write(board, firmwareImage(bytes), Uf2Image.parse(bytes)) {} }
        assertEquals(2, failing.resets) // initial reset + abandon()
        assertEquals(0, failing.exclusive)
    }
}

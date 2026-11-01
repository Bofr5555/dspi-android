package se.bofr.dspi.feature.firmware

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Uf2ImageTest {
    private fun refuses(bytes: ByteArray, reason: String) {
        val e = assertThrows(Uf2FormatException::class.java) { Uf2Image.parse(bytes) }
        assertTrue("'${e.message}' should mention '$reason'", e.message!!.contains(reason))
    }

    @Test fun parsesAContiguousImage() {
        val uf2 = Uf2Image.parse(TestUf2.image(40))
        assertEquals(Uf2.FAMILY_RP2350_ARM_S, uf2.familyId)
        assertEquals(40, uf2.pages.size)
        assertEquals(0x1000_0000L, uf2.firstAddr)
        assertEquals(0x1000_0000L + 40 * 256, uf2.endAddr)
        assertEquals(listOf(0x1000_0000L, 0x1000_1000L, 0x1000_2000L), uf2.sectors)
        assertEquals(16, uf2.pagesIn(0x1000_0000L).size)
        assertEquals(8, uf2.pagesIn(0x1000_2000L).size)
        assertFalse(uf2.skippedE10Block)
    }

    @Test fun blockOrderDoesNotMatter() {
        val blocks = (0 until 4).map { TestUf2.block(0x1000_0000L + it * 256L, it, 4) }
        val uf2 = Uf2Image.parse(blocks.reversed().reduce { a, b -> a + b })
        assertEquals(listOf(0x1000_0000L, 0x1000_0100L, 0x1000_0200L, 0x1000_0300L), uf2.pages.keys.toList())
    }

    @Test fun skipsTheRp2350E10AbsoluteBlock() {
        val uf2 = Uf2Image.parse(TestUf2.absBlock() + TestUf2.image(3))
        assertTrue(uf2.skippedE10Block)
        assertEquals(3, uf2.pages.size)
        assertFalse(uf2.pages.containsKey(0x10FF_FF00L))
    }

    @Test fun skipsNotMainFlashBlocks() {
        val extra = TestUf2.block(
            0x1000_0000L,
            0,
            1,
            Uf2.FAMILY_RP2350_ARM_S,
            flags = Uf2.FLAG_FAMILY_ID_PRESENT or Uf2.FLAG_NOT_MAIN_FLASH
        )
        assertEquals(2, Uf2Image.parse(extra + TestUf2.image(2)).pages.size)
    }

    @Test fun refusesEmptyRaggedAndNonUf2Files() {
        refuses(ByteArray(0), "empty")
        refuses(ByteArray(700), "whole number")
        refuses(ByteArray(512), "not a UF2 block")
        val bad = TestUf2.image(1).also { it[508] = 0 }
        refuses(bad, "not a UF2 block")
    }

    @Test fun refusesWrongPayloadSize() {
        refuses(TestUf2.block(0x1000_0000L, 0, 1, payloadSize = 476, payload = ByteArray(256)), "carries 476 bytes")
    }

    @Test fun refusesMixedFamilies() {
        refuses(TestUf2.block(0x1000_0000L, 0, 2) + TestUf2.block(0x1000_0100L, 1, 2, Uf2.FAMILY_RP2040), "different chips")
    }

    @Test fun refusesIncompleteAndMisnumberedImages() {
        refuses(TestUf2.block(0x1000_0000L, 0, 3) + TestUf2.block(0x1000_0100L, 1, 3), "incomplete (2 of 3")
        refuses(TestUf2.block(0x1000_0000L, 5, 3), "numbered outside")
        refuses(TestUf2.block(0x1000_0000L, 0, 2) + TestUf2.block(0x1000_0100L, 1, 3), "disagree")
    }

    @Test fun refusesRamImagesAndAddressesOutsideFlash() {
        refuses(TestUf2.image(1, start = 0x2000_0000L), "RAM image")
        refuses(TestUf2.image(1, start = 0x0000_0000L), "outside flash")
        refuses(TestUf2.image(1, start = 0x1200_0000L), "outside flash")
    }

    @Test fun refusesUnalignedBlocksAndConflictingDuplicates() {
        refuses(TestUf2.image(1, start = 0x1000_0010L), "aligned")
        val a = TestUf2.block(0x1000_0000L, 0, 2, payload = ByteArray(256) { 1 })
        val b = TestUf2.block(0x1000_0000L, 1, 2, payload = ByteArray(256) { 2 })
        refuses(a + b, "different data")
    }

    @Test fun refusesAFileWithOnlyIgnoredBlocks() = refuses(TestUf2.absBlock(), "no firmware blocks")

    // MARK: Target checks (family IDs, Table 455; flash windows, §5.5.2)

    @Test fun rp2350AcceptsArmSRiscVAndAbsolute() {
        for (family in listOf(Uf2.FAMILY_RP2350_ARM_S, Uf2.FAMILY_RP2350_RISCV, Uf2.FAMILY_ABSOLUTE)) {
            assertNull(Uf2Image.parse(TestUf2.image(2, family)).targetError(BootloaderBoard.Chip.RP2350))
        }
    }

    @Test fun wrongBoardFamilyBothWays() {
        assertEquals(
            FirmwareInstallError.WrongBoardFamily("RP2040 (Pico)", "RP2350 (Pico 2)"),
            Uf2Image.parse(TestUf2.image(2, Uf2.FAMILY_RP2040)).targetError(BootloaderBoard.Chip.RP2350)
        )
        assertEquals(
            FirmwareInstallError.WrongBoardFamily("RP2350 (Pico 2)", "RP2040 (Pico)"),
            Uf2Image.parse(TestUf2.image(2, Uf2.FAMILY_RP2350_ARM_S)).targetError(BootloaderBoard.Chip.RP2040)
        )
        // Legacy RP2040 UF2s carry no family ID.
        assertNull(Uf2Image.parse(TestUf2.image(2, family = null)).targetError(BootloaderBoard.Chip.RP2040))
        assertEquals(
            FirmwareInstallError.WrongBoardFamily("RP2040 (Pico)", "RP2350 (Pico 2)"),
            Uf2Image.parse(TestUf2.image(2, family = null)).targetError(BootloaderBoard.Chip.RP2350)
        )
    }

    @Test fun nonBootableFamiliesAreInvalid() {
        val e = Uf2Image.parse(TestUf2.image(1, Uf2.FAMILY_RP2350_ARM_NS)).targetError(BootloaderBoard.Chip.RP2350)
        assertTrue(e is FirmwareInstallError.InvalidImage && e.detail.contains("rp2350-arm-ns"))
        assertTrue(
            Uf2Image.parse(TestUf2.image(1, Uf2.FAMILY_DATA)).targetError(BootloaderBoard.Chip.RP2040) is FirmwareInstallError.InvalidImage
        )
    }

    @Test fun rp2040FlashWindowIs16MB() {
        val high = TestUf2.image(1, Uf2.FAMILY_RP2040, start = 0x1100_0000L)
        assertTrue(Uf2Image.parse(high).targetError(BootloaderBoard.Chip.RP2040) is FirmwareInstallError.InvalidImage)
        assertNull(Uf2Image.parse(TestUf2.image(1, Uf2.FAMILY_RP2350_ARM_S, start = 0x1100_0000L)).targetError(BootloaderBoard.Chip.RP2350))
    }

    @Test fun localImageNamesItsChip() {
        assertEquals(BootloaderBoard.Chip.RP2350, LocalFirmware.localImage("x.uf2", TestUf2.image(1)).chip)
        assertEquals(BootloaderBoard.Chip.RP2040, LocalFirmware.localImage("x.uf2", TestUf2.image(1, Uf2.FAMILY_RP2040)).chip)
        assertEquals(BootloaderBoard.Chip.RP2040, LocalFirmware.localImage("x.uf2", TestUf2.image(1, null)).chip)
        assertNull(LocalFirmware.localImage("x.uf2", TestUf2.image(1, Uf2.FAMILY_ABSOLUTE)).chip)
        assertNull(LocalFirmware.localImage("y.uf2", TestUf2.image(1)).version)
    }

    // MARK: The bundled release (docs/SUPPLY-CHAIN.md)

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test fun bundledAssetsAreTheVerifiedOfficialRelease() {
        assertEquals(
            "485d7164879bcae3b3dcd98b8de1db458c4933f025fb02630b98cb7f59210362",
            sha256(Assets.read("DSPi-RP2040-v1.1.6-beta5.uf2"))
        )
        assertEquals(
            "0835f0af8ddfe6e70aa0dfb9a5a71760e1a122927b7a96c8a6f8e3bc7ef2d240",
            sha256(Assets.read("DSPi-RP2350-v1.1.6-beta5.uf2"))
        )
    }

    /** macOS testBundleCarriesMatchingImagesForBothChips, plus the UF2 checks. */
    @Test fun bundleCarriesMatchingValidImagesForBothChips() {
        for (chip in BootloaderBoard.Chip.entries) {
            val image = FirmwareImage.bundled(chip, Assets.names) { Assets.read(it) }
            assertEquals(FirmwareVersion.expected, image.version)
            assertTrue(image.isBundled)
            val uf2 = Uf2Image.parse(image.bytes)
            assertNull(uf2.targetError(chip))
            assertEquals(Uf2.FLASH_START, uf2.firstAddr)
        }
        val rp2350 = Uf2Image.parse(Assets.read("DSPi-RP2350-v1.1.6-beta5.uf2"))
        assertTrue("the RP2350 release carries an E10 block", rp2350.skippedE10Block)
        assertEquals(Uf2.FAMILY_RP2350_ARM_S, rp2350.familyId)
        assertEquals(1216, rp2350.pages.size)
        val rp2040 = Uf2Image.parse(Assets.read("DSPi-RP2040-v1.1.6-beta5.uf2"))
        assertEquals(Uf2.FAMILY_RP2040, rp2040.familyId)
        assertEquals(1079, rp2040.pages.size)
    }

    @Test fun staleOrMissingBundledImagesAreRefused() {
        assertThrows(FirmwareInstallError.ImageMissing::class.java) {
            FirmwareImage.bundled(BootloaderBoard.Chip.RP2040, listOf("DSPi-RP2350-v1.1.6-beta5.uf2")) { ByteArray(0) }
        }
        val e = assertThrows(FirmwareInstallError.BundledImageStale::class.java) {
            FirmwareImage.bundled(BootloaderBoard.Chip.RP2350, listOf("DSPi-RP2350-v1.1.5.uf2")) { ByteArray(0) }
        }
        assertEquals(FirmwareInstallError.BundledImageStale("1.1.5", "1.1.6 beta 5"), e)
    }
}

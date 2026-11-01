// Port of the "Version parsing and ordering" and "Asset names" sections of
// DSPi-Console (macOS) `DSPi ConsoleTests/FirmwareInstallerTests.swift` and
// `FirmwarePlatformReplyTests.swift` @ v1.1.6-beta5.
package se.bofr.dspi.feature.firmware

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.fake.demoDspState

class FirmwareVersionTest {
    @Test fun parsesPlainPointRelease() = assertEquals(FirmwareVersion(1, 1, 7), FirmwareVersion.parse("1.1.7"))

    @Test fun parsesBetaSuffixAndIgnoresOthers() {
        assertEquals(FirmwareVersion(1, 1, 6, 2), FirmwareVersion.parse("1.1.6-beta2"))
        assertEquals(FirmwareVersion(1, 1, 6), FirmwareVersion.parse("1.1.6-rc1"))
    }

    @Test fun missingPatchIsZero() = assertEquals(FirmwareVersion(1, 2, 0), FirmwareVersion.parse("1.2"))

    @Test fun rejectsNonNumeric() = assertNull(FirmwareVersion.parse("unreleased"))

    @Test fun ordersPatchNumerically() {
        assertTrue(FirmwareVersion(1, 1, 9) < FirmwareVersion(1, 1, 10))
        assertTrue(FirmwareVersion(1, 1, 15) < FirmwareVersion(1, 2, 0))
    }

    @Test fun finalOutranksItsBetasAndEarlyBetaSortsFirst() {
        assertTrue(FirmwareVersion(1, 1, 7, 3) < FirmwareVersion(1, 1, 7))
        assertTrue(FirmwareVersion(1, 1, 6, FirmwareVersion.EARLY_BETA) < FirmwareVersion(1, 1, 6, 1))
    }

    @Test fun descriptionsAndTags() {
        assertEquals("1.1.6 beta 5", FirmwareVersion(1, 1, 6, 5).description)
        assertEquals("1.1.6-beta5", FirmwareVersion(1, 1, 6, 5).tagSuffix)
        assertEquals("1.1.6 early beta", FirmwareVersion(1, 1, 6, -1).description)
        assertNull(FirmwareVersion(1, 1, 6, -1).tagSuffix)
    }

    @Test fun expectedIsTheTrackedConsoleRelease() = assertEquals(FirmwareVersion(1, 1, 6, 5), FirmwareVersion.expected)

    @Test fun readsVersionFromAssetName() {
        assertEquals(FirmwareVersion(1, 1, 7), FirmwareImage.versionFromAssetName("DSPi-RP2350-v1.1.7.uf2"))
        assertEquals(FirmwareVersion(1, 1, 16), FirmwareImage.versionFromAssetName("DSPi-RP2040-v1.1.16.uf2"))
        assertEquals(FirmwareVersion(1, 1, 6, 5), FirmwareImage.versionFromAssetName("DSPi-RP2350-v1.1.6-beta5.uf2"))
    }

    @Test fun rejectsAssetNameWithoutVersion() = assertNull(FirmwareImage.versionFromAssetName("DSPi.uf2"))

    // FirmwarePlatformReplyTests.swift
    @Test fun fullReplyCarriesWideFieldsAndOrdinal() {
        val (platform, v) = FirmwareVersion.fromPlatformReply(byteArrayOf(1, 1, 0x16, 0, 1, 16, 5))!!
        assertEquals(1, platform)
        assertEquals(FirmwareVersion(1, 1, 16, 5), v)
    }

    @Test fun shortReplyBelowOrdinalIsFinalFromNibbles() {
        assertEquals(FirmwareVersion(1, 1, 5), FirmwareVersion.fromPlatformReply(byteArrayOf(0, 1, 0x15, 0))!!.second)
    }

    @Test fun shortReplyAtOrdinalIsEarlyBeta() {
        assertEquals(FirmwareVersion(1, 1, 6, -1), FirmwareVersion.fromPlatformReply(byteArrayOf(0, 1, 0x16, 0, 1, 6))!!.second)
    }

    @Test fun tooShortReplyIsNil() = assertNull(FirmwareVersion.fromPlatformReply(byteArrayOf(0, 1, 2)))

    @Test fun ofDspStateMapsEarlyBetaAndUnknown() {
        val s = demoDspState()
        assertNull(FirmwareVersion.of(s.copy(fwMajor = 0, fwMinor = 0, fwPatch = 0)))
        assertEquals(
            FirmwareVersion(1, 1, 6, -1),
            FirmwareVersion.of(s.copy(fwMajor = 1, fwMinor = 1, fwPatch = 6, fwBeta = DspiConstants.FW_BETA_EARLY))
        )
        assertEquals(FirmwareVersion(1, 1, 6, 5), FirmwareVersion.of(s.copy(fwMajor = 1, fwMinor = 1, fwPatch = 6, fwBeta = 5)))
    }

    @Test fun match() {
        val expected = FirmwareVersion(1, 1, 6, 5)
        assertNull(FirmwareMatch.of(false, expected, expected))
        assertNull(FirmwareMatch.of(true, null, expected))
        assertEquals(FirmwareMatch.MATCH, FirmwareMatch.of(true, FirmwareVersion(1, 1, 6, 5), expected))
        assertEquals(FirmwareMatch.DEVICE_OLDER, FirmwareMatch.of(true, FirmwareVersion(1, 1, 6, 4), expected))
        assertEquals(FirmwareMatch.DEVICE_NEWER, FirmwareMatch.of(true, FirmwareVersion(1, 1, 6), expected))
        assertFalse(FirmwareMatch.of(true, FirmwareVersion(1, 1, 7), expected) == FirmwareMatch.DEVICE_OLDER)
    }
}

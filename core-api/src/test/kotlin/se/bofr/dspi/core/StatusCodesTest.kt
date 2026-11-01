package se.bofr.dspi.core

import org.junit.Assert.assertEquals
import org.junit.Test

class StatusCodesTest {
    @Test
    fun refusedMapsToItsCode() {
        val code = statusOf(255) { throw DspiException.Refused(DspiConstants.PIN_CONFIG_PIN_IN_USE) }
        assertEquals(PinConfigStatus.PIN_IN_USE, PinConfigStatus.of(code))
    }

    @Test
    fun transferMapsToNoReply() {
        val code = statusOf(255) { throw DspiException.Transfer("timeout") }
        assertEquals(PinConfigStatus.NO_REPLY, PinConfigStatus.of(code))
    }

    @Test
    fun wireFormatIsV32() {
        assertEquals(32, DspiConstants.WIRE_FORMAT_VERSION)
        assertEquals(6136, DspiConstants.BULK_PARAMS_SIZE)
    }

    @Test
    fun lockedIsMacOsAppSideStatusWithItsWording() {
        assertEquals(0xFE, PresetStatus.LOCKED.code)
        assertEquals(PresetStatus.LOCKED, PresetStatus.of(0xFE))
        // Firmware PRESET_* stay 0x00..0x04
        assertEquals(PresetStatus.FLASH_WRITE, PresetStatus.of(DspiConstants.PRESET_ERR_FLASH_WRITE))
        assertEquals(PresetStatus.NO_REPLY, PresetStatus.of(0x05))
        // macOS presetFailureText(_:)
        assertEquals("Another console is applying changes", PresetStatus.LOCKED.failureText)
        assertEquals("error 4", PresetStatus.FLASH_WRITE.failureText)
        assertEquals("error 255", PresetStatus.NO_REPLY.failureText)
    }
}

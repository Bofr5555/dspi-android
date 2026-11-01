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
}

package se.bofr.dspi.usb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbIdsTest {
    @Test
    fun recognisesDspiAndBootsel() {
        assertTrue(UsbIds.isDspi(0x2E8B, 0xFEAA))
        assertTrue(UsbIds.isDspi(0x2E8A, 0xFEAA))
        assertFalse(UsbIds.isDspi(0x2E8A, 0x000F))
        assertTrue(UsbIds.isBootloader(0x2E8A, 0x000F))
        assertTrue(UsbIds.isBootloader(0x2E8A, 0x0003))
    }
}

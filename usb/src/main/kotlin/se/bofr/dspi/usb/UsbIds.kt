package se.bofr.dspi.usb

import se.bofr.dspi.core.DspiConstants

/** USB identities (protocol.rs VENDOR_ID/LEGACY_VENDOR_ID/PRODUCT_ID; RP BOOTSEL). */
object UsbIds {
    const val DSPI_VID = DspiConstants.VENDOR_ID
    const val DSPI_LEGACY_VID = DspiConstants.LEGACY_VENDOR_ID
    const val DSPI_PID = DspiConstants.PRODUCT_ID

    /** The only interface the app claims; 0/1 are UAC audio and stay with the OS. */
    const val VENDOR_INTERFACE = DspiConstants.VENDOR_INTERFACE

    const val RPI_VID = 0x2E8A
    const val RP2040_BOOTSEL_PID = 0x0003
    const val RP2350_BOOTSEL_PID = 0x000F

    fun isDspi(vid: Int, pid: Int) = pid == DSPI_PID && (vid == DSPI_VID || vid == DSPI_LEGACY_VID)

    fun isBootloader(vid: Int, pid: Int) = vid == RPI_VID && (pid == RP2040_BOOTSEL_PID || pid == RP2350_BOOTSEL_PID)
}

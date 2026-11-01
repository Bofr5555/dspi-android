package se.bofr.dspi.usb

/**
 * The slice of `android.hardware.usb` the session and the locator need, kept
 * behind interfaces so the lifecycle logic runs on the JVM with fakes
 * (`AndroidUsbHost` is the real implementation).
 */

/**
 * One enumerated USB device. [deviceName] is the usbfs path
 * (`/dev/bus/usb/001/004`): unique per enumeration, so a replugged unit gets a
 * new name. The serial is NOT here: on API 29+ reading it needs permission.
 */
data class UsbDeviceRef(val deviceName: String, val vid: Int, val pid: Int) {
    val isDspi: Boolean get() = UsbIds.isDspi(vid, pid)
    val isBootloader: Boolean get() = UsbIds.isBootloader(vid, pid)

    /** Pre-May-2026 firmware on Raspberry Pi's VID; the bridge reports compat and the UI shows a banner. */
    val isLegacyDspi: Boolean get() = isDspi && vid == UsbIds.DSPI_LEGACY_VID
}

/** Interface descriptor summary, used to pick the vendor interface. */
data class UsbInterfaceDesc(val id: Int, val alternate: Int, val interfaceClass: Int)

interface UsbHost {
    /** `UsbManager.getDeviceList()`. */
    fun devices(): List<UsbDeviceRef>

    fun hasPermission(device: UsbDeviceRef): Boolean

    /** `UsbManager.requestPermission` with this app's permission PendingIntent. */
    fun requestPermission(device: UsbDeviceRef)

    /** `UsbDevice.getSerialNumber()`; null without permission (SecurityException on API 29+). */
    fun serial(device: UsbDeviceRef): String?

    /** `UsbManager.openDevice`; null if it fails (no permission, gone, busy). */
    fun open(device: UsbDeviceRef): UsbLink?
}

/** An open `UsbDeviceConnection`. The session keeps it alive while the core uses [fd]. */
interface UsbLink {
    /** `UsbDeviceConnection.getFileDescriptor()`; owned by the connection, NOT by rusb. */
    val fd: Int

    /** `UsbDeviceConnection.getSerial()`, null if unreadable. */
    val serial: String?

    /**
     * Claim [UsbIds.VENDOR_INTERFACE] with `force = false`. Never 0/1, never `setConfiguration`.
     * The core claims interface 2 again on the same fd (FFI patch): intentional and
     * idempotent (usbfs returns 0 for an interface this fd already holds).
     */
    fun claimVendorInterface(): Boolean

    /** Usually a silent no-op: dropping rusb's handle on detach already released interface 2. */
    fun releaseVendorInterface()

    /** `UsbDeviceConnection.close()`: the fd is invalid afterwards. */
    fun close()
}

/** The vendor control interface (2, class 0xFF, alt 0) among a device's interfaces, or null. */
fun findVendorInterface(interfaces: List<UsbInterfaceDesc>): UsbInterfaceDesc? = interfaces.firstOrNull {
    it.id == UsbIds.VENDOR_INTERFACE && it.alternate == 0 && it.interfaceClass == USB_CLASS_VENDOR_SPEC
}

/** `UsbConstants.USB_CLASS_VENDOR_SPEC`. */
const val USB_CLASS_VENDOR_SPEC = 0xFF

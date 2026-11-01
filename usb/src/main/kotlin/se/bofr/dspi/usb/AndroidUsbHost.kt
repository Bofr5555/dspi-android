package se.bofr.dspi.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

/** [UsbHost] over the real `UsbManager`. */
class AndroidUsbHost(
    context: Context,
    /** Broadcast action the permission PendingIntent fires; distinct per client (session / locator). */
    private val permissionAction: String = UsbBroadcasts.ACTION_USB_PERMISSION
) : UsbHost {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    override fun devices(): List<UsbDeviceRef> = deviceMap().values.map { it.toRef() }

    // Every UsbManager call is a binder call that may throw (service died,
    // OEM SecurityException): none of them may escape into the session loop.

    override fun hasPermission(device: UsbDeviceRef): Boolean = guarded("hasPermission", false) {
        find(device)?.let { manager.hasPermission(it) } ?: false
    }

    override fun requestPermission(device: UsbDeviceRef) = guarded("requestPermission", Unit) {
        val d = find(device) ?: return@guarded
        manager.requestPermission(d, UsbBroadcasts.permissionPendingIntent(appContext, permissionAction))
    }

    override fun serial(device: UsbDeviceRef): String? {
        val d = find(device) ?: return null
        return guarded("serial", null) { if (manager.hasPermission(d)) d.serialNumber else null }
    }

    override fun open(device: UsbDeviceRef): UsbLink? {
        val d = find(device) ?: return null
        val intf = vendorInterfaceOf(d) ?: return null
        val conn = guarded("openDevice", null) { manager.openDevice(d) } ?: return null
        return AndroidUsbLink(conn, intf)
    }

    /** The platform object, for clients that must talk to the device themselves (FIRMWARE's PICOBOOT). */
    fun usbDevice(device: UsbDeviceRef): UsbDevice? = find(device)

    private inline fun <T> guarded(what: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (e: RuntimeException) {
        Log.w(TAG, "$what: $e")
        fallback
    }

    private fun deviceMap(): Map<String, UsbDevice> = try {
        manager.deviceList ?: emptyMap()
    } catch (e: RuntimeException) {
        Log.w(TAG, "getDeviceList: $e")
        emptyMap()
    }

    private fun find(device: UsbDeviceRef): UsbDevice? = deviceMap()[device.deviceName]
        ?.takeIf { it.vendorId == device.vid && it.productId == device.pid }

    companion object {
        private const val TAG = "DspiUsb"

        fun vendorInterfaceOf(d: UsbDevice): UsbInterface? {
            val all = (0 until d.interfaceCount).map { d.getInterface(it) }
            val pick = findVendorInterface(all.map { UsbInterfaceDesc(it.id, it.alternateSetting, it.interfaceClass) })
                ?: return null
            return all.first { it.id == pick.id && it.alternateSetting == pick.alternate }
        }
    }
}

/** Claims ONLY the vendor interface, never with force (DESIGN.md §2.1: itf 0/1 stay with snd-usb-audio). */
private class AndroidUsbLink(private val conn: UsbDeviceConnection, private val intf: UsbInterface) : UsbLink {
    private var claimed = false

    override val fd: Int get() = conn.fileDescriptor
    override val serial: String? get() = conn.serial

    override fun claimVendorInterface(): Boolean {
        check(intf.id == UsbIds.VENDOR_INTERFACE)
        claimed = try {
            conn.claimInterface(intf, false)
        } catch (e: RuntimeException) {
            Log.w("DspiUsb", "claimInterface: $e")
            false
        }
        return claimed
    }

    override fun releaseVendorInterface() {
        if (claimed) {
            claimed = false
            try {
                conn.releaseInterface(intf)
            } catch (e: RuntimeException) {
                Log.w("DspiUsb", "releaseInterface: $e")
            }
        }
    }

    override fun close() {
        try {
            conn.close()
        } catch (e: RuntimeException) {
            Log.w("DspiUsb", "close: $e")
        }
    }
}

fun UsbDevice.toRef() = UsbDeviceRef(deviceName, vendorId, productId)

/** Receivers, intents and PendingIntents, with the API 31/33/34 flag rules in one place. */
object UsbBroadcasts {
    const val ACTION_USB_PERMISSION = "se.bofr.dspi.usb.USB_PERMISSION"
    const val ACTION_BOOTLOADER_PERMISSION = "se.bofr.dspi.usb.BOOTLOADER_PERMISSION"

    /**
     * The permission callback. UsbManager fills in EXTRA_DEVICE and
     * EXTRA_PERMISSION_GRANTED, so the PendingIntent must be MUTABLE (API 31+
     * requires the flag to be explicit; IMMUTABLE drops the extras). API 34+
     * forbids a mutable PendingIntent with an implicit Intent, so the Intent
     * names our package.
     */
    fun permissionPendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(action).setPackage(context.packageName)
        val mutable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutable)
    }

    /**
     * Registers for attach/detach (protected system broadcasts) and our own
     * permission action. NOT_EXPORTED (required to be explicit on API 33+):
     * system broadcasts still arrive; the permission result is sent through
     * our own PendingIntent.
     */
    fun register(context: Context, permissionAction: String, receiver: BroadcastReceiver) {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(permissionAction)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun unregister(context: Context, receiver: BroadcastReceiver) {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
    }

    fun device(intent: Intent): UsbDeviceRef? =
        IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)?.toRef()

    fun granted(intent: Intent): Boolean = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
}

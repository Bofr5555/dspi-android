package se.bofr.dspi.usb

import kotlinx.coroutines.CompletableDeferred

/** Shared, ordered record of everything the fakes were asked to do. */
class CallLog {
    val calls = mutableListOf<String>()

    fun add(s: String) {
        calls += s
    }

    fun clear() = calls.clear()
}

class FakeDevice(val ref: UsbDeviceRef, val serial: String, var permission: Boolean = false)

class FakeUsbHost(private val log: CallLog) : UsbHost {
    val attached = mutableListOf<FakeDevice>()

    /** Number of upcoming open() calls that fail. */
    var failOpens = 0

    /** Number of upcoming claims that fail. */
    var failClaims = 0
    var nextFd = 40

    /** Make hasPermission() throw (binder failure) this many times. */
    var hasPermissionThrows = 0

    /** Links created from now on throw from close(). */
    var linkCloseThrows = false
    val links = mutableListOf<FakeLink>()

    fun plug(
        name: String,
        serial: String,
        permission: Boolean = false,
        vid: Int = UsbIds.DSPI_VID,
        pid: Int = UsbIds.DSPI_PID
    ): UsbDeviceRef {
        val d = FakeDevice(UsbDeviceRef(name, vid, pid), serial, permission)
        attached += d
        return d.ref
    }

    fun unplug(ref: UsbDeviceRef) {
        attached.removeAll { it.ref.deviceName == ref.deviceName }
    }

    fun grant(ref: UsbDeviceRef) {
        dev(ref)!!.permission = true
    }

    private fun dev(ref: UsbDeviceRef) = attached.firstOrNull { it.ref.deviceName == ref.deviceName }

    override fun devices() = attached.map { it.ref }

    override fun hasPermission(device: UsbDeviceRef): Boolean {
        if (hasPermissionThrows > 0) {
            hasPermissionThrows--
            throw IllegalStateException("binder died")
        }
        return dev(device)?.permission ?: false
    }

    override fun requestPermission(device: UsbDeviceRef) {
        log.add("requestPermission(${device.deviceName})")
    }

    override fun serial(device: UsbDeviceRef): String? = dev(device)?.takeIf { it.permission }?.serial

    override fun open(device: UsbDeviceRef): UsbLink? {
        val d = dev(device)
        log.add("open(${device.deviceName})")
        if (d == null || !d.permission) return null
        if (failOpens > 0) {
            failOpens--
            return null
        }
        val link = FakeLink(nextFd++, d.serial, log, claimOk = failClaims-- <= 0, closeThrows = linkCloseThrows)
        links += link
        return link
    }
}

class FakeLink(
    override val fd: Int,
    override val serial: String?,
    private val log: CallLog,
    private val claimOk: Boolean,
    private val closeThrows: Boolean = false
) : UsbLink {
    var claimed = false
    var closed = false

    override fun claimVendorInterface(): Boolean {
        log.add("claim(itf=${UsbIds.VENDOR_INTERFACE},fd=$fd)")
        claimed = claimOk
        return claimOk
    }

    override fun releaseVendorInterface() {
        log.add("release(fd=$fd)")
        claimed = false
    }

    override fun close() {
        log.add("close(fd=$fd)")
        closed = true
        if (closeThrows) throw IllegalStateException("close failed")
    }
}

class FakeCore(private val log: CallLog) : CoreLink {
    var result = AttachResult(true)

    /** When set, attach/detach suspend until it completes (models the bridge's FFI dispatcher). */
    var attachGate: CompletableDeferred<Unit>? = null
    var detachGate: CompletableDeferred<Unit>? = null

    override suspend fun attach(fd: Int, serial: String, vid: Int): AttachResult {
        log.add("core.attach(fd=$fd,$serial,vid=0x${vid.toString(16)})")
        attachGate?.await()
        return result
    }

    var detachThrows = false

    override suspend fun detach(serial: String) {
        log.add("core.detach($serial)")
        detachGate?.await()
        if (detachThrows) throw IllegalStateException("core detach failed")
    }
}

class FakeService(private val log: CallLog) : ConnectionServiceControl {
    var running = false

    /** Number of upcoming starts Android refuses (background FGS start, API 31+). */
    var refuseStarts = 0

    override fun start(): Boolean {
        if (refuseStarts > 0) {
            refuseStarts--
            log.add("service.start refused")
            return false
        }
        log.add("service.start")
        running = true
        return true
    }

    override fun stop() {
        log.add("service.stop")
        running = false
    }
}

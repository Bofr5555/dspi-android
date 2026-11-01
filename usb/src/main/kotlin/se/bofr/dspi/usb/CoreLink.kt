package se.bofr.dspi.usb

import se.bofr.dspi.bridge.DspiBridge

/** Outcome of handing an fd to the core. */
data class AttachResult(val ok: Boolean, val error: String = "")

/**
 * What the session needs from the core side: the `open_fd` / `close_fd`
 * contract, reached through [DspiBridge.attachDevice] / [DspiBridge.detachDevice]
 * (which run on the bridge's single FFI dispatcher).
 */
interface CoreLink {
    suspend fun attach(fd: Int, serial: String, vid: Int): AttachResult

    /** Core disconnect: stops the notify listener and drops the rusb handle. */
    suspend fun detach(serial: String)
}

class BridgeCoreLink(private val bridge: DspiBridge) : CoreLink {
    override suspend fun attach(fd: Int, serial: String, vid: Int): AttachResult {
        val ok = bridge.attachDevice(fd, serial, vid)
        return AttachResult(ok, if (ok) "" else bridge.connectionError.ifEmpty { "Could not open device" })
    }

    override suspend fun detach(serial: String) = bridge.detachDevice(serial)
}

/** Starts/stops [DspiConnectionService] (foreground while a DSPi is open, PLAN §5.3). */
interface ConnectionServiceControl {
    /** false if Android refused (background start, API 31+); the session retries when a UI is visible. */
    fun start(): Boolean

    fun stop()
}

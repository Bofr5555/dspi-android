package se.bofr.dspi.core

/*
 * Callback interfaces (dspi-ffi callbacks.rs). They run on core worker threads:
 * implementations must only schedule work (post to the bridge's dispatcher) and
 * must not call back into DspiCoreApi synchronously.
 */

/** Notification packets are queued: call [DspiCoreApi.processNotifications] (≤ 1 per 30 ms). */
fun interface NotifyListener {
    fun onNotifications()
}

/** Analyser caps or snapshot changed: call rtaGetCaps / rtaGetSnapshot. */
fun interface RtaListener {
    fun onFrame()
}

/**
 * Device arrival/departure. On Android driven by USB broadcasts in `:usb`
 * (UsbSession), not by [DspiCoreApi.pollHotplug].
 */
interface DeviceEvents {
    fun onArrived(serial: String)

    fun onDeparted(serial: String)
}

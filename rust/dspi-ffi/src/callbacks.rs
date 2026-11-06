//! Callback interfaces implemented in Kotlin.
//!
//! All three are invoked on core worker threads (notify listener, RTA worker)
//! or, for `DeviceEvents`, on whichever thread calls `poll_hotplug`. They must
//! only *schedule* work; the Kotlin bridge posts to its FFI dispatcher and
//! never calls back into `DspiCore` from inside the callback
//! (same rule as `dspi_set_notify_callback` in the C API).

/// Wakes the bridge when notification packets are queued (`dspi_set_notify_callback`).
/// The bridge then calls `process_notifications()` (batched to ≤ 1 per 30 ms).
#[uniffi::export(callback_interface)]
pub trait NotifyListener: Send + Sync {
    fn on_notifications(&self);
}

/// Wakes the analyser controller when caps or the snapshot changed
/// (`dspi_rta_set_callback`). It then calls `rta_get_caps` / `rta_get_snapshot`.
#[uniffi::export(callback_interface)]
pub trait RtaListener: Send + Sync {
    fn on_frame(&self);
}

/// Device arrival/departure (`dspi_set_hotplug_callback`, event 0/1).
/// On Android these are driven by `USB_DEVICE_ATTACHED/DETACHED` broadcasts in
/// `:usb`, not by `poll_hotplug` (libusb cannot enumerate there); the interface
/// is kept so desktop/HIL hosts use the same contract.
#[uniffi::export(callback_interface)]
pub trait DeviceEvents: Send + Sync {
    fn on_arrived(&self, serial: String);
    fn on_departed(&self, serial: String);
}

/// DSPi Link session events (`link_connect`). Invoked on the Link client's
/// reader thread: schedule work only, never call back into `DspiCore` from
/// inside the callback.
#[uniffi::export(callback_interface)]
pub trait LinkListener: Send + Sync {
    /// The hub's device list changed (`link_devices()`).
    fn on_link_devices_changed(&self);
    /// The session ended. `code` = the hub's WebSocket close code (4002
    /// revoked, 4003 shutting down, ...; -1 = none, the connection was lost).
    fn on_link_closed(&self, code: i32, reason: String);
}

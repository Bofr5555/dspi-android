//! DSPi Link for the core: [`LinkTransport`] adapts one device on a
//! `dspi_link::LinkClient` session to the core's `usb::Transport`
//! (third_party/patches/0004-transport-trait.patch), and [`LinkSession`]
//! keeps the session the `link_*` methods of `DspiCore` drive.
//!
//! Mapping of Link CMD status (spec 8.1) onto the core's `rusb::Error`:
//! ERROR / BLOCKED -> `Pipe` (a STALL: capability probes and the analyser's
//! per-channel fallback for RTA bands-all, which a bridge answers ERROR);
//! TIMEOUT -> `Timeout` (may have run; the bridge never retries a SET);
//! NO_DEVICE -> `NoDevice` (the core reports Disconnected); DENIED ->
//! `Access`; LOCKED -> `Busy` (both become their own `DspiError`); OVERSIZE /
//! TOO_LARGE -> `Overflow`; UNSUPPORTED -> `NotSupported`; BUSY,
//! BULK_LOCKED, RATE_LIMITED, CRC/FRAME/BAD_FRAME -> `Io`.

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{Receiver, RecvTimeoutError};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dspi_core::protocol::REQ_GET_ALL_PARAMS;
use dspi_core::usb::{Notification, Transport, TransportCaps};
use dspi_link::{status, Dir, LinkClient, NotifyItem};

pub(crate) fn map_status(s: u8) -> rusb::Error {
    match s {
        status::ERROR | status::BLOCKED => rusb::Error::Pipe,
        status::TIMEOUT => rusb::Error::Timeout,
        status::NO_DEVICE => rusb::Error::NoDevice,
        status::DENIED => rusb::Error::Access,
        status::LOCKED => rusb::Error::Busy,
        status::OVERSIZE | status::TOO_LARGE => rusb::Error::Overflow,
        status::UNSUPPORTED => rusb::Error::NotSupported,
        _ => rusb::Error::Io,
    }
}

/// One remote device (a hub handle) as a core transport.
pub(crate) struct LinkTransport {
    client: LinkClient,
    handle: u8,
    notes: Mutex<Receiver<NotifyItem>>,
    /// The first bulk read of a connection comes from the hub's snapshot when
    /// it offers one (one round trip instead of a device read; macOS
    /// NetworkTransport). Later reads go to the device.
    snapshot_unused: AtomicBool,
}

impl LinkTransport {
    pub(crate) fn new(client: LinkClient, handle: u8) -> Self {
        let notes = Mutex::new(client.notifications(handle));
        let snapshot = client.hello().has_cap("snapshot");
        Self { client, handle, notes, snapshot_unused: AtomicBool::new(snapshot) }
    }
}

impl Transport for LinkTransport {
    fn write_control(
        &self,
        request: u8,
        value: u16,
        index: u16,
        data: &[u8],
        _usb_timeout: Duration,
    ) -> rusb::Result<usize> {
        // The Link client applies the Link timeouts (10 s, 15 s bulk)
        let r = self.client.command(self.handle, Dir::Set, request, value, index, data.len() as u16, data);
        match r.status {
            status::OK => Ok(data.len()),
            s => Err(map_status(s)),
        }
    }

    fn read_control(
        &self,
        request: u8,
        value: u16,
        index: u16,
        buf: &mut [u8],
        _usb_timeout: Duration,
    ) -> rusb::Result<usize> {
        if request == REQ_GET_ALL_PARAMS && self.snapshot_unused.swap(false, Ordering::SeqCst) {
            if let Some(blob) = self.client.snapshot(self.handle).ok().and_then(|s| s.bulk()) {
                if blob.len() >= buf.len() {
                    buf.copy_from_slice(&blob[..buf.len()]);
                    return Ok(buf.len());
                }
            }
        }
        let r = self.client.command(self.handle, Dir::Get, request, value, index, buf.len() as u16, &[]);
        match r.status {
            status::OK => {
                let n = r.payload.len().min(buf.len());
                buf[..n].copy_from_slice(&r.payload[..n]);
                Ok(n)
            }
            s => Err(map_status(s)),
        }
    }

    fn read_notification(&self, buf: &mut [u8], timeout: Duration) -> rusb::Result<Notification> {
        let rx = self.notes.lock().unwrap_or_else(|p| p.into_inner());
        match rx.recv_timeout(timeout) {
            Ok(item) => {
                let n = item.packet.len().min(buf.len());
                buf[..n].copy_from_slice(&item.packet[..n]);
                Ok(Notification { len: n, origin: Some(item.origin) })
            }
            Err(RecvTimeoutError::Timeout) => Err(rusb::Error::Timeout),
            // The session ended: the core's listener stops
            Err(RecvTimeoutError::Disconnected) => Err(rusb::Error::NoDevice),
        }
    }

    fn session_id(&self) -> Option<u16> {
        self.client.session_id()
    }

    fn caps(&self) -> TransportCaps {
        TransportCaps { chunked_bulk: false, max_payload: self.client.hello().limits.max_payload as usize }
    }

    /// Spec 7.7: skipped on a hub without the `lock` capability (it
    /// serialises commands itself); any other failure refuses the flow.
    fn lock(&self, reason: &str, timeout: Duration) -> bool {
        if !self.client.hello().has_cap("lock") {
            return true;
        }
        self.client.lock_acquire(self.handle, reason, timeout.as_millis().min(60_000) as u32).is_ok()
    }

    fn unlock(&self) {
        if self.client.hello().has_cap("lock") {
            self.client.lock_release(self.handle);
        }
    }
}

impl Drop for LinkTransport {
    fn drop(&mut self) {
        self.client.stop_notifications(self.handle);
    }
}

/// The `DspiCore`'s current Link session.
pub(crate) struct LinkSession {
    pub(crate) client: LinkClient,
}

/// Forwards session events to the Kotlin `LinkListener`.
pub(crate) struct EventsForwarder(pub(crate) Arc<dyn Fn(LinkEvent) + Send + Sync>);

pub(crate) enum LinkEvent {
    Devices,
    Closed(Option<u16>, String),
}

impl dspi_link::LinkEvents for EventsForwarder {
    fn devices_changed(&self) {
        (self.0)(LinkEvent::Devices)
    }
    fn closed(&self, code: Option<u16>, reason: &str) {
        (self.0)(LinkEvent::Closed(code, reason.to_owned()))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn link_statuses_map_onto_the_cores_errors() {
        assert_eq!(map_status(status::ERROR), rusb::Error::Pipe, "RTA bands-all fallback");
        assert_eq!(map_status(status::BLOCKED), rusb::Error::Pipe);
        assert_eq!(map_status(status::TIMEOUT), rusb::Error::Timeout);
        assert_eq!(map_status(status::NO_DEVICE), rusb::Error::NoDevice);
        assert_eq!(map_status(status::DENIED), rusb::Error::Access);
        assert_eq!(map_status(status::LOCKED), rusb::Error::Busy);
        assert_eq!(map_status(status::OVERSIZE), rusb::Error::Overflow);
        assert_eq!(map_status(status::RATE_LIMITED), rusb::Error::Io);
    }
}

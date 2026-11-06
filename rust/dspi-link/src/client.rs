//! One session with one hub (spec section 5): hello, authentication, the
//! device inventory, tunnelled commands, snapshot, polls, locks, and the
//! notification stream. Blocking API, any thread; one reader thread and one
//! keepalive thread per session.
//!
//! Port of DSPi-Console `release/v1.1.6` `Link/LinkClient.swift` (session
//! lifecycle, timeouts, `none`-mode session probe, close-code reasons) with
//! the reconnect policy left to the embedder, as there. Differences:
//! - blocking calls with timeouts instead of completion handlers;
//! - `auth: none` whose courtesy `auth.pair` fails carries on without a
//!   session id (spec 6.1) instead of failing the session (macOS);
//! - a gap in the Link NOTIFY sequence, or a RESYNC, is turned into a
//!   synthetic `BULK_INVALIDATED` packet for the bound device, so the core's
//!   own re-read path runs (macOS does the same for RESYNC only).

use std::collections::HashMap;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, Sender, SyncSender};
use std::sync::{Arc, Condvar, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::Value;

use crate::frames::{status, CmdRequest, CmdResponse, Dir, Frame, PollFrame};
use crate::messages::{out, AuthOk, ClientInfo, DeviceInfo, HelloHub, Inbound, PollGrant, PollSpec, Snapshot};
use crate::socket::{WsConnector, WsMessage, WsReceiver, WsRecv, WsSender};
use crate::LinkError;

/// Sub-protocol the hub requires and echoes (spec 4).
pub const SUB_PROTOCOL: &str = "dspi-link-1";
/// PIN sent by the `none`-mode probe; the hub ignores its value (spec 6.1).
const OPEN_ACCESS_PIN: &str = "000000";
/// macOS LinkClient: 10 s for a request, 15 s for bulk (the hub itself waits
/// 5 s for the device, and the blob is thousands of bytes behind that).
pub const REQUEST_TIMEOUT: Duration = Duration::from_secs(10);
pub const BULK_TIMEOUT: Duration = Duration::from_secs(15);
const BULK_OPCODES: [u8; 4] = [0xA0, 0xA1, 0xA2, 0xA3];
const PING_INTERVAL: Duration = Duration::from_secs(15);
/// The hub pings every 10 s (spec 4); this much silence means the link is gone.
const SILENCE_LIMIT: Duration = Duration::from_secs(35);

/// What the embedder passes to [`LinkClient::connect`].
#[derive(Debug, Clone)]
pub struct ConnectOptions {
    /// Shown in the hub's client list (Android: `Build.MODEL` by default).
    pub client_name: String,
    pub app: String,
    pub app_version: String,
    /// The token stored for this hub's `hid`, if any.
    pub token: Option<String>,
}

/// Where authentication stands after [`LinkClient::connect`].
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AuthState {
    Ready,
    /// The hub is in `pin` mode and has no (valid) token from us: ask the
    /// user for the PIN and call [`LinkClient::pair`]. `pin_source` is
    /// `window` (shown on the hub) or `fixed` (the hub's own PIN).
    NeedsPairing {
        pin_source: String,
    },
}

/// One NOTIFY for the bound device: the verbatim v2 packet and its attribution.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotifyItem {
    pub packet: Vec<u8>,
    /// Session id the hub attributes the change to (0 = unknown).
    pub origin: u16,
}

/// Session events for the embedder (reconnect policy, device picker).
pub trait LinkEvents: Send + Sync {
    /// `devices()` changed (list, add, remove, change).
    fn devices_changed(&self) {}
    /// The session ended. `code` is the hub's close code, if it sent one.
    fn closed(&self, _code: Option<u16>, _reason: &str) {}
}

/// Human reason for a hub close code (macOS `LinkClient.reason(forCloseCode:)`).
pub fn close_reason(code: Option<u16>) -> String {
    match code {
        Some(1000) => "hub closed the connection".into(),
        Some(1001) => "connection lost".into(),
        Some(4000) => "protocol error".into(),
        Some(4001) => "authentication timed out".into(),
        Some(4002) => "access revoked".into(),
        Some(4003) => "hub shutting down".into(),
        Some(c) => format!("connection closed ({c})"),
        None => "connection lost".into(),
    }
}

/// Whether to reconnect after a close with `code` (spec 5: not after a
/// protocol error; not after revocation, which needs pairing again).
pub fn should_reconnect(code: Option<u16>) -> bool {
    !matches!(code, Some(4000) | Some(4002))
}

#[derive(Default)]
struct Session {
    session: Option<u16>,
    role: String,
    denied: Vec<(u8, u8)>,
    ready: bool,
    devices: Vec<DeviceInfo>,
}

struct Inner {
    sender: Box<dyn WsSender>,
    hello: HelloHub,
    client_name: String,
    session: Mutex<Session>,
    pending_json: Mutex<HashMap<u64, SyncSender<Result<Value, LinkError>>>>,
    pending_cmd: Mutex<HashMap<u16, SyncSender<CmdResponse>>>,
    next_id: AtomicU64,
    next_tag: Mutex<u16>,
    inflight: Mutex<u32>,
    inflight_free: Condvar,
    notify_sinks: Mutex<HashMap<u8, Sender<NotifyItem>>>,
    poll_sinks: Mutex<HashMap<u8, Sender<PollFrame>>>,
    /// Last Link NOTIFY sequence (spec 8.3), per session.
    last_link_seq: Mutex<Option<u16>>,
    /// Last firmware `seq` (packet byte 3) per handle, for synthetic packets.
    last_fw_seq: Mutex<HashMap<u8, u8>>,
    closed: Mutex<Option<(Option<u16>, String)>>,
    events: Mutex<Option<Arc<dyn LinkEvents>>>,
    last_inbound: Mutex<Instant>,
    stopping: AtomicBool,
}

/// A live session with one hub. Cheap to clone (shared state).
#[derive(Clone)]
pub struct LinkClient {
    inner: Arc<Inner>,
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

impl LinkClient {
    /// Open `url` (`ws://host:port/dspi/v1`), exchange hellos and
    /// authenticate as far as possible without a PIN. When the result is
    /// [`AuthState::Ready`] the device list has been read.
    pub fn connect(
        connector: &dyn WsConnector,
        url: &str,
        opts: &ConnectOptions,
        events: Option<Arc<dyn LinkEvents>>,
    ) -> Result<(LinkClient, AuthState), LinkError> {
        let (sender, mut receiver) = connector.connect(url, SUB_PROTOCOL)?;
        let client =
            ClientInfo { name: opts.client_name.clone(), app: opts.app.clone(), version: opts.app_version.clone() };
        sender.send(WsMessage::Text(out::hello(&client))).map_err(|e| LinkError::Io(e.to_string()))?;
        let hello = match Self::await_hello(receiver.as_mut()) {
            Ok(h) => h,
            Err(e) => {
                sender.close(1000);
                return Err(e);
            }
        };
        if hello.proto.major != crate::messages::PROTO_MAJOR {
            sender.close(1000);
            return Err(LinkError::Protocol(format!("hub speaks protocol {}", hello.proto.major)));
        }
        let inner = Arc::new(Inner {
            sender,
            hello,
            client_name: opts.client_name.clone(),
            session: Mutex::new(Session::default()),
            pending_json: Mutex::new(HashMap::new()),
            pending_cmd: Mutex::new(HashMap::new()),
            next_id: AtomicU64::new(1),
            next_tag: Mutex::new(1),
            inflight: Mutex::new(0),
            inflight_free: Condvar::new(),
            notify_sinks: Mutex::new(HashMap::new()),
            poll_sinks: Mutex::new(HashMap::new()),
            last_link_seq: Mutex::new(None),
            last_fw_seq: Mutex::new(HashMap::new()),
            closed: Mutex::new(None),
            events: Mutex::new(events),
            last_inbound: Mutex::new(Instant::now()),
            stopping: AtomicBool::new(false),
        });
        let c = LinkClient { inner };
        c.spawn_threads(receiver);

        let state = if c.inner.hello.pin_required() {
            match &opts.token {
                Some(token) => match c.request(|id| out::auth_token(id, token), REQUEST_TIMEOUT) {
                    Ok(v) => {
                        c.became_ready(&v);
                        AuthState::Ready
                    }
                    // A refused or expired token is indistinguishable from none: pair again
                    Err(LinkError::Rejected { .. }) => {
                        AuthState::NeedsPairing { pin_source: c.inner.hello.pin_source().into() }
                    }
                    Err(e) => {
                        c.close();
                        return Err(e);
                    }
                },
                None => AuthState::NeedsPairing { pin_source: c.inner.hello.pin_source().into() },
            }
        } else {
            // Open access: the session is open; ask for our id anyway (spec 6.1)
            match c.request(|id| out::auth_pair(id, OPEN_ACCESS_PIN, &opts.client_name, None), REQUEST_TIMEOUT) {
                Ok(v) => c.became_ready(&v),
                Err(LinkError::Rejected { .. }) => {
                    log::warn!("dspi-link: open hub refused the session probe; own echoes cannot be recognised");
                    c.mark_ready(None, "admin".into(), Vec::new());
                }
                Err(e) => {
                    c.close();
                    return Err(e);
                }
            }
            AuthState::Ready
        };
        if state == AuthState::Ready {
            c.refresh_devices()?;
        }
        Ok((c, state))
    }

    fn await_hello(receiver: &mut dyn WsReceiver) -> Result<HelloHub, LinkError> {
        let deadline = Instant::now() + REQUEST_TIMEOUT;
        while Instant::now() < deadline {
            match receiver.recv() {
                WsRecv::Message(WsMessage::Text(t)) => {
                    if let Ok(Inbound::Hello(h)) = Inbound::parse(&t) {
                        return Ok(h);
                    }
                }
                WsRecv::Message(_) | WsRecv::Idle => {}
                WsRecv::Closed { code, reason } => {
                    return Err(LinkError::Closed {
                        code,
                        reason: if code.is_some() { close_reason(code) } else { reason },
                    })
                }
            }
        }
        Err(LinkError::Timeout)
    }

    /// Pair with the PIN the user typed (spec 6.2). Returns the token to store
    /// against [`HelloHub::hub`] `id` (empty when the hub issued none).
    pub fn pair(&self, pin: &str, role: &str) -> Result<String, LinkError> {
        let name = self.inner.client_name.clone();
        let v = self.request(|id| out::auth_pair(id, pin, &name, Some(role)), REQUEST_TIMEOUT)?;
        self.became_ready(&v);
        self.refresh_devices()?;
        let ok: AuthOk = serde_json::from_value(v).unwrap_or_default();
        Ok(ok.token.unwrap_or_default())
    }

    fn became_ready(&self, v: &Value) {
        let ok: AuthOk = serde_json::from_value(v.clone()).unwrap_or_default();
        let denied = ok
            .policy
            .map(|p| p.denied.iter().filter(|d| d[0] <= 0xFF && d[1] <= 1).map(|d| (d[0] as u8, d[1] as u8)).collect())
            .unwrap_or_default();
        let session = u16::try_from(ok.session).ok().filter(|&s| s != 0);
        self.mark_ready(session, ok.role, denied);
    }

    fn mark_ready(&self, session: Option<u16>, role: String, denied: Vec<(u8, u8)>) {
        let mut s = lock(&self.inner.session);
        s.session = session;
        s.role = if role.is_empty() { "admin".into() } else { role };
        s.denied = denied;
        s.ready = true;
    }

    /// Re-read `device.list`.
    pub fn refresh_devices(&self) -> Result<Vec<DeviceInfo>, LinkError> {
        let v = self.request(out::device_list, REQUEST_TIMEOUT)?;
        let list: Vec<DeviceInfo> = v
            .get("devices")
            .cloned()
            .map(serde_json::from_value)
            .transpose()
            .map_err(|e| LinkError::Protocol(e.to_string()))?
            .unwrap_or_default();
        lock(&self.inner.session).devices = list.clone();
        self.fire_devices_changed();
        Ok(list)
    }

    // ── Accessors ──────────────────────────────────────────────────────

    pub fn hello(&self) -> &HelloHub {
        &self.inner.hello
    }

    pub fn session_id(&self) -> Option<u16> {
        lock(&self.inner.session).session
    }

    pub fn role(&self) -> String {
        lock(&self.inner.session).role.clone()
    }

    /// `policy.denied` as `(bRequest, dir)` (dir 0 = SET, 1 = GET).
    pub fn denied(&self) -> Vec<(u8, u8)> {
        lock(&self.inner.session).denied.clone()
    }

    pub fn is_ready(&self) -> bool {
        lock(&self.inner.session).ready && !self.is_closed()
    }

    pub fn devices(&self) -> Vec<DeviceInfo> {
        lock(&self.inner.session).devices.clone()
    }

    pub fn is_closed(&self) -> bool {
        lock(&self.inner.closed).is_some()
    }

    /// The close code and reason once the session ended.
    pub fn closed_reason(&self) -> Option<(Option<u16>, String)> {
        lock(&self.inner.closed).clone()
    }

    pub fn set_events(&self, events: Option<Arc<dyn LinkEvents>>) {
        *lock(&self.inner.events) = events;
    }

    // ── Data plane ─────────────────────────────────────────────────────

    /// Tunnel one vendor request (spec 8.2) and wait for its answer. A
    /// timeout answers status TIMEOUT (the command may have run); a closed
    /// session answers NO_DEVICE, as the macOS client does.
    #[allow(clippy::too_many_arguments)]
    pub fn command(
        &self,
        handle: u8,
        dir: Dir,
        b_request: u8,
        w_value: u16,
        w_index: u16,
        w_length: u16,
        payload: &[u8],
    ) -> CmdResponse {
        let timeout = if BULK_OPCODES.contains(&b_request) { BULK_TIMEOUT } else { REQUEST_TIMEOUT };
        let deadline = Instant::now() + timeout;
        if !self.is_ready() {
            return CmdResponse { tag: 0, status: status::NO_DEVICE, payload: Vec::new() };
        }
        // max_inflight (spec 8.2): wait for a free slot
        let limit = self.inner.hello.limits.max_inflight.max(1);
        {
            let mut n = lock(&self.inner.inflight);
            while *n >= limit {
                let left = deadline.saturating_duration_since(Instant::now());
                if left.is_zero() || self.is_closed() {
                    return CmdResponse { tag: 0, status: status::TIMEOUT, payload: Vec::new() };
                }
                n = self
                    .inner
                    .inflight_free
                    .wait_timeout(n, left.min(Duration::from_millis(200)))
                    .unwrap_or_else(|p| p.into_inner())
                    .0;
            }
            *n += 1;
        }
        let r = self.command_slot(handle, dir, b_request, w_value, w_index, w_length, payload, deadline);
        *lock(&self.inner.inflight) -= 1;
        self.inner.inflight_free.notify_one();
        r
    }

    #[allow(clippy::too_many_arguments)]
    fn command_slot(
        &self,
        handle: u8,
        dir: Dir,
        b_request: u8,
        w_value: u16,
        w_index: u16,
        w_length: u16,
        payload: &[u8],
        deadline: Instant,
    ) -> CmdResponse {
        let tag = {
            let mut t = lock(&self.inner.next_tag);
            let tag = *t;
            // 0 is skipped so a zero tag always means "unset" (macOS)
            *t = if *t == u16::MAX { 1 } else { *t + 1 };
            tag
        };
        let (tx, rx) = mpsc::sync_channel(1);
        lock(&self.inner.pending_cmd).insert(tag, tx);
        let frame = Frame::CmdRequest(CmdRequest {
            tag,
            handle,
            dir,
            b_request,
            w_value,
            w_index,
            w_length,
            payload: payload.to_vec(),
        });
        if self.inner.sender.send(WsMessage::Binary(frame.encode())).is_err() {
            lock(&self.inner.pending_cmd).remove(&tag);
            self.socket_failed(None, "send failed");
            return CmdResponse { tag, status: status::NO_DEVICE, payload: Vec::new() };
        }
        match rx.recv_timeout(deadline.saturating_duration_since(Instant::now())) {
            Ok(r) => r,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                lock(&self.inner.pending_cmd).remove(&tag);
                CmdResponse { tag, status: status::TIMEOUT, payload: Vec::new() }
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                CmdResponse { tag, status: status::NO_DEVICE, payload: Vec::new() }
            }
        }
    }

    /// The notification stream of device `handle` (replaces an earlier one).
    /// The receiver disconnects when the session ends.
    pub fn notifications(&self, handle: u8) -> Receiver<NotifyItem> {
        let (tx, rx) = mpsc::channel();
        if !self.is_closed() {
            lock(&self.inner.notify_sinks).insert(handle, tx);
        }
        rx
    }

    pub fn stop_notifications(&self, handle: u8) {
        lock(&self.inner.notify_sinks).remove(&handle);
    }

    /// POLL frames of device `handle`.
    pub fn polls(&self, handle: u8) -> Receiver<PollFrame> {
        let (tx, rx) = mpsc::channel();
        lock(&self.inner.poll_sinks).insert(handle, tx);
        rx
    }

    // ── Control plane ──────────────────────────────────────────────────

    /// `device.snapshot` (spec 7.5), when the hub has the `snapshot` capability.
    pub fn snapshot(&self, handle: u8) -> Result<Snapshot, LinkError> {
        let v = self.request(|id| out::device_snapshot(id, handle), BULK_TIMEOUT)?;
        serde_json::from_value(v).map_err(|e| LinkError::Protocol(e.to_string()))
    }

    pub fn poll_subscribe(&self, handle: u8, polls: &[PollSpec]) -> Result<Vec<PollGrant>, LinkError> {
        let v = self.request(|id| out::poll_subscribe(id, handle, polls), REQUEST_TIMEOUT)?;
        v.get("granted")
            .cloned()
            .map(serde_json::from_value)
            .transpose()
            .map_err(|e| LinkError::Protocol(e.to_string()))
            .map(Option::unwrap_or_default)
    }

    pub fn poll_unsubscribe(&self, handle: u8, slots: &[u8]) {
        self.fire(|id| out::poll_unsubscribe(id, handle, slots));
    }

    /// `lock.acquire` (spec 7.7). Ok when held; `Rejected { code: "locked" }`
    /// when another session holds it.
    pub fn lock_acquire(&self, handle: u8, reason: &str, timeout_ms: u32) -> Result<(), LinkError> {
        self.request(|id| out::lock_acquire(id, handle, reason, timeout_ms.min(60_000)), REQUEST_TIMEOUT).map(|_| ())
    }

    /// Fire and forget; a lost release expires on the hub (macOS).
    pub fn lock_release(&self, handle: u8) {
        self.fire(|id| out::lock_release(id, handle));
    }

    /// Close the session normally. Idempotent.
    pub fn close(&self) {
        self.inner.stopping.store(true, Ordering::SeqCst);
        self.inner.sender.close(1000);
        self.socket_failed(Some(1000), "closed by the client");
    }

    fn fire(&self, make: impl FnOnce(u64) -> String) {
        let id = self.inner.next_id.fetch_add(1, Ordering::SeqCst);
        let _ = self.inner.sender.send(WsMessage::Text(make(id)));
    }

    fn request(&self, make: impl FnOnce(u64) -> String, timeout: Duration) -> Result<Value, LinkError> {
        if let Some((code, reason)) = self.closed_reason() {
            return Err(LinkError::Closed { code, reason });
        }
        let id = self.inner.next_id.fetch_add(1, Ordering::SeqCst);
        let (tx, rx) = mpsc::sync_channel(1);
        lock(&self.inner.pending_json).insert(id, tx);
        if let Err(e) = self.inner.sender.send(WsMessage::Text(make(id))) {
            lock(&self.inner.pending_json).remove(&id);
            return Err(LinkError::Io(e.to_string()));
        }
        match rx.recv_timeout(timeout) {
            Ok(r) => r,
            Err(mpsc::RecvTimeoutError::Timeout) => {
                lock(&self.inner.pending_json).remove(&id);
                Err(LinkError::Timeout)
            }
            Err(mpsc::RecvTimeoutError::Disconnected) => {
                let (code, reason) = self.closed_reason().unwrap_or((None, "connection lost".into()));
                Err(LinkError::Closed { code, reason })
            }
        }
    }

    // ── Threads ────────────────────────────────────────────────────────

    fn spawn_threads(&self, mut receiver: Box<dyn WsReceiver>) {
        let me = self.clone();
        let _ = thread::Builder::new().name("dspi-link-rx".into()).spawn(move || loop {
            match receiver.recv() {
                WsRecv::Message(m) => {
                    *lock(&me.inner.last_inbound) = Instant::now();
                    match m {
                        WsMessage::Text(t) => me.handle_text(&t),
                        WsMessage::Binary(b) => me.handle_binary(&b),
                    }
                }
                WsRecv::Idle => {
                    if me.is_closed() {
                        break;
                    }
                    if lock(&me.inner.last_inbound).elapsed() > SILENCE_LIMIT {
                        me.inner.sender.close(1001);
                        me.socket_failed(None, "connection lost");
                        break;
                    }
                }
                WsRecv::Closed { code, reason } => {
                    let reason = if code.is_some() { close_reason(code) } else { reason };
                    me.socket_failed(code, &reason);
                    break;
                }
            }
        });
        // Keepalive: a ping that cannot be written is the earliest sign the socket is gone
        let weak = Arc::downgrade(&self.inner);
        let _ = thread::Builder::new().name("dspi-link-ping".into()).spawn(move || loop {
            let mut slept = Duration::ZERO;
            while slept < PING_INTERVAL {
                thread::sleep(Duration::from_millis(250));
                slept += Duration::from_millis(250);
                match weak.upgrade() {
                    Some(i) if lock(&i.closed).is_none() => {}
                    _ => return,
                }
            }
            let Some(inner) = weak.upgrade() else { return };
            if inner.sender.ping().is_err() {
                LinkClient { inner }.socket_failed(None, "connection lost");
                return;
            }
        });
    }

    fn handle_text(&self, text: &str) {
        let Ok(msg) = Inbound::parse(text) else {
            log::debug!("dspi-link: unparsable message dropped");
            return;
        };
        match msg {
            Inbound::Ok { id, body } => {
                if let Some(tx) = lock(&self.inner.pending_json).remove(&id) {
                    let _ = tx.send(Ok(body));
                }
            }
            Inbound::Err(e) => {
                if let Some(tx) = e.id.and_then(|id| lock(&self.inner.pending_json).remove(&id)) {
                    let _ = tx.send(Err(LinkError::Rejected { code: e.code, msg: e.msg.unwrap_or_default() }));
                }
            }
            Inbound::DeviceAdded(d) | Inbound::DeviceChanged(d) => {
                {
                    let mut s = lock(&self.inner.session);
                    match s.devices.iter_mut().find(|x| x.handle == d.handle) {
                        Some(x) => *x = d,
                        None => s.devices.push(d),
                    }
                }
                self.fire_devices_changed();
            }
            Inbound::DeviceRemoved { handle, .. } => {
                lock(&self.inner.session).devices.retain(|d| d.handle != handle);
                self.fire_devices_changed();
            }
            Inbound::Hello(_) | Inbound::PollError { .. } | Inbound::Other { .. } => {}
        }
    }

    fn handle_binary(&self, bytes: &[u8]) {
        let Ok(frame) = Frame::decode(bytes) else {
            log::debug!("dspi-link: malformed frame dropped");
            return;
        };
        match frame {
            Frame::CmdResponse(r) => {
                if let Some(tx) = lock(&self.inner.pending_cmd).remove(&r.tag) {
                    let _ = tx.send(r);
                }
            }
            Frame::Notify(n) => {
                // A gap in the Link sequence means we missed events: re-read (spec 8.3)
                let gap = {
                    let mut last = lock(&self.inner.last_link_seq);
                    let gap = matches!(*last, Some(p) if n.seq != p.wrapping_add(1));
                    *last = Some(n.seq);
                    gap
                };
                if n.packet.len() < 4 {
                    return;
                }
                let fw_seq = n.packet[3];
                if gap {
                    self.deliver_invalidation(n.handle, Some(fw_seq.wrapping_sub(1)));
                }
                lock(&self.inner.last_fw_seq).insert(n.handle, fw_seq);
                self.deliver(n.handle, NotifyItem { packet: n.packet, origin: n.origin });
            }
            Frame::Resync { handle, .. } => self.deliver_invalidation(handle, None),
            Frame::Poll(p) => {
                let sinks = lock(&self.inner.poll_sinks);
                if let Some(tx) = sinks.get(&p.handle) {
                    let _ = tx.send(p);
                }
            }
            _ => {}
        }
    }

    /// A synthetic `BULK_INVALIDATED` (v2 event 0x03, source 0) for `handle`,
    /// so the core re-reads everything. Its firmware `seq` makes the core's
    /// own gap check agree: `before_seq` = one less than the packet that
    /// follows, else the last one seen (a RESYNC).
    fn deliver_invalidation(&self, handle: u8, before_seq: Option<u8>) {
        let seq = before_seq.unwrap_or_else(|| lock(&self.inner.last_fw_seq).get(&handle).copied().unwrap_or(0));
        self.deliver(handle, NotifyItem { packet: vec![0x02, 0x03, 0, seq, 0, 0, 0, 0], origin: 0 });
    }

    fn deliver(&self, handle: u8, item: NotifyItem) {
        let mut sinks = lock(&self.inner.notify_sinks);
        if let Some(tx) = sinks.get(&handle) {
            if tx.send(item).is_err() {
                sinks.remove(&handle);
            }
        }
    }

    fn fire_devices_changed(&self) {
        let ev = lock(&self.inner.events).clone();
        if let Some(ev) = ev {
            ev.devices_changed();
        }
    }

    /// The session is over: fail everything outstanding, end the streams,
    /// tell the embedder once.
    fn socket_failed(&self, code: Option<u16>, reason: &str) {
        {
            let mut closed = lock(&self.inner.closed);
            if closed.is_some() {
                return;
            }
            *closed = Some((code, reason.to_owned()));
        }
        if !self.inner.stopping.load(Ordering::SeqCst) {
            self.inner.sender.close(1000);
        }
        lock(&self.inner.session).ready = false;
        let json: Vec<_> = lock(&self.inner.pending_json).drain().collect();
        for (_, tx) in json {
            let _ = tx.send(Err(LinkError::Closed { code, reason: reason.to_owned() }));
        }
        let cmds: Vec<_> = lock(&self.inner.pending_cmd).drain().collect();
        for (tag, tx) in cmds {
            let _ = tx.send(CmdResponse { tag, status: status::NO_DEVICE, payload: Vec::new() });
        }
        lock(&self.inner.notify_sinks).clear();
        lock(&self.inner.poll_sinks).clear();
        self.inner.inflight_free.notify_all();
        let ev = lock(&self.inner.events).clone();
        if let Some(ev) = ev {
            ev.closed(code, reason);
        }
    }
}

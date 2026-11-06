//! A fake DSPi Link hub for tests and for trying the app without hardware
//! (feature `fake-hub`; binary `dspi-link-fakehub`).
//!
//! It serves `/dspi/v1` (WebSocket, sub-protocol `dspi-link-1`) and
//! `/dspi/v1/info` from one simulated device seeded from the DSPi-Link
//! fixtures (`bulk_v32.bin`, `platform.bin`, `status_wvalue9.bin`). Covered:
//! hello, `auth: none` and `pin` (window or fixed PIN), tokens, roles from
//! `policy/commands.json` (viewer/control/admin, `policy.denied`),
//! `device.list`, `device.snapshot`, `poll.subscribe`, locks, CMD with
//! per-device ordering and `max_inflight`, NOTIFY with `origin` attribution
//! for SETs it knows the bulk offset of, RESYNC, close codes 4002/4003, and
//! the bridge's limits (`bridge: true`: 64 B SET, 132 B GET, no 0x0F, no
//! chunked bulk). Not a conformance reference: it is as small as the client
//! tests need.

use std::collections::{HashMap, HashSet};
use std::io::{self, Read, Write};
use std::net::{SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, Sender};
use std::sync::{Arc, Mutex, MutexGuard};
use std::thread;
use std::time::{Duration, Instant};

use serde_json::{json, Value};
use tungstenite::handshake::server::{Request, Response};
use tungstenite::protocol::frame::coding::CloseCode;
use tungstenite::protocol::CloseFrame;
use tungstenite::{Message, WebSocket};

use crate::frames::{kind, status, CmdResponse, Dir, Frame, NotifyFrame, PollFrame};

pub const BULK_V32: &[u8] = include_bytes!("../../../fixtures/link/device/bulk_v32.bin");
pub const PLATFORM: &[u8] = include_bytes!("../../../fixtures/link/device/platform.bin");
pub const STATUS_WVALUE9: &[u8] = include_bytes!("../../../fixtures/link/device/status_wvalue9.bin");
const POLICY: &str = include_str!("../../../fixtures/link/policy/commands.json");

/// Firmware request ids the fake device knows.
pub mod req {
    pub const GET_STATUS: u8 = 0x50;
    pub const GET_SERIAL: u8 = 0x7E;
    pub const GET_PLATFORM: u8 = 0x7F;
    pub const GET_ALL_PARAMS: u8 = 0xA0;
    pub const SET_ALL_PARAMS: u8 = 0xA1;
    pub const GET_ALL_PARAMS_CHUNK: u8 = 0xA2;
    pub const SET_ALL_PARAMS_CHUNK: u8 = 0xA3;
    pub const RTA_GET_BANDS_ALL: u8 = 0x0F;
    pub const SET_MASTER_VOLUME: u8 = 0xD2;
}

/// Bulk offset of the master volume in wire V32 (core `OFF_MASTER_VOLUME`).
pub const OFF_MASTER_VOLUME: u16 = 4700;

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Auth {
    None,
    /// `pin` with a PIN shown on the hub (`pin_source` absent, as a 1.1 Console hub).
    Window(String),
    /// `pin` with the hub's fixed PIN (`pin_source: fixed`, a 1.2 bridge).
    Fixed(String),
}

#[derive(Debug, Clone)]
pub struct HubConfig {
    pub bind: SocketAddr,
    pub hub_id: String,
    pub name: String,
    /// `console` or `bridge`.
    pub kind: String,
    pub proto_minor: u32,
    pub auth: Auth,
    /// Role granted by a correct PIN (a fixed-PIN bridge grants admin).
    pub pin_role: String,
    /// Apply the bridge's command limits (spec 9.2).
    pub bridge_limits: bool,
    pub caps: Vec<String>,
    pub max_inflight: u32,
    pub serial: String,
    pub device_name: String,
    pub image: Vec<u8>,
    /// SET request -> (bulk offset, size): the SETs that emit PARAM_CHANGED.
    pub set_offsets: HashMap<u8, (u16, u16)>,
    /// Requests answered TIMEOUT (the hub gave up on the device).
    pub timeout_requests: HashSet<u8>,
    pub auth_deadline: Duration,
}

impl Default for HubConfig {
    fn default() -> Self {
        Self {
            bind: "127.0.0.1:0".parse().unwrap(),
            hub_id: "6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b".into(),
            name: "Fake Hub".into(),
            kind: "console".into(),
            proto_minor: 1,
            auth: Auth::None,
            pin_role: "control".into(),
            bridge_limits: false,
            caps: ["cmd", "notify", "poll", "snapshot", "lock", "rename"].iter().map(|s| s.to_string()).collect(),
            max_inflight: 8,
            serial: "0123456789ABCDEF".into(),
            device_name: "Bench".into(),
            image: BULK_V32.to_vec(),
            set_offsets: HashMap::from([(req::SET_MASTER_VOLUME, (OFF_MASTER_VOLUME, 4))]),
            timeout_requests: HashSet::new(),
            auth_deadline: Duration::from_secs(30),
        }
    }
}

impl HubConfig {
    /// The ESP32 bridge's shape (spec 9.2, fixtures `hello_hub_bridge.json`).
    pub fn bridge() -> Self {
        Self {
            hub_id: "0d6c41e2-93b7-4f0a-b5a8-3c2e7f19a4d0".into(),
            name: "DSPi Bridge 3F2A".into(),
            kind: "bridge".into(),
            proto_minor: 2,
            auth: Auth::Fixed("482913".into()),
            pin_role: "admin".into(),
            bridge_limits: true,
            caps: ["cmd", "notify", "poll", "snapshot", "lock", "web", "rename"]
                .iter()
                .map(|s| s.to_string())
                .collect(),
            max_inflight: 4,
            ..Self::default()
        }
    }
}

/// One message to a session's socket.
enum Out {
    Text(String),
    Binary(Vec<u8>),
    Close(u16),
}

struct SessionRec {
    tx: Sender<Out>,
    role: String,
    authed: bool,
    token: Option<String>,
    link_seq: u16,
    poll_seq: u16,
    polls: HashMap<u8, (Vec<u8>, Duration, Instant)>,
}

struct State {
    cfg: HubConfig,
    image: Vec<u8>,
    fw_seq: u8,
    next_session: u16,
    sessions: HashMap<u16, SessionRec>,
    tokens: HashMap<String, String>,
    lock: Option<(u16, Instant)>,
    log: Vec<(u16, u8, u8, u16)>,
    classes: HashMap<(u8, u8), String>,
}

/// A running fake hub; stops when dropped.
pub struct FakeHub {
    addr: SocketAddr,
    state: Arc<Mutex<State>>,
    stop: Arc<AtomicBool>,
}

fn lock(m: &Mutex<State>) -> MutexGuard<'_, State> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// `(bRequest, dir)` -> class from `policy/commands.json` (spec 9.3).
fn load_policy() -> HashMap<(u8, u8), String> {
    let v: Value = serde_json::from_str(POLICY).unwrap_or_default();
    let mut out = HashMap::new();
    for c in v["commands"].as_array().into_iter().flatten() {
        let Some(code) = c["code"].as_u64() else { continue };
        let class = c["class"].as_str().unwrap_or("config");
        for (d, name) in [(0u8, "set"), (1u8, "get")] {
            if c["dirs"].as_array().is_some_and(|ds| ds.iter().any(|x| x == name)) {
                let k = c["classes"][name].as_str().unwrap_or(class);
                out.insert((code as u8, d), k.to_owned());
            }
        }
    }
    out
}

fn allowed(role: &str, class: &str) -> bool {
    match role {
        "admin" => true,
        "control" => class != "config",
        _ => class == "read",
    }
}

impl State {
    fn class(&self, b_request: u8, dir: u8) -> &str {
        self.classes.get(&(b_request, dir)).map(String::as_str).unwrap_or("config")
    }

    fn denied(&self, role: &str) -> Vec<[u8; 2]> {
        let mut d: Vec<[u8; 2]> =
            self.classes.iter().filter(|(_, c)| !allowed(role, c)).map(|((r, dir), _)| [*r, *dir]).collect();
        d.sort();
        d
    }

    fn device_json(&self) -> Value {
        let p = PLATFORM;
        json!({
            "handle": 0, "serial": self.cfg.serial, "name": self.cfg.device_name,
            "platform": p[0], "fw": format!("{}.{}.{}", p[1], p[4], p[5]), "fw_beta": p[6], "outputs": p[3],
            "wire_version": self.image[0], "state": "online",
            "link": if self.cfg.kind == "bridge" { "uart" } else { "usb" },
            "locked_by": self.lock.map(|l| l.0),
        })
    }

    fn broadcast(&mut self, text: String) {
        for s in self.sessions.values() {
            if s.authed {
                let _ = s.tx.send(Out::Text(text.clone()));
            }
        }
    }

    /// Relay a v2 packet to every authenticated session (spec 8.3).
    fn notify(&mut self, mut packet: Vec<u8>, origin: u16) {
        packet[3] = self.fw_seq;
        self.fw_seq = self.fw_seq.wrapping_add(1);
        for s in self.sessions.values_mut() {
            if s.authed {
                let f = Frame::Notify(NotifyFrame { seq: s.link_seq, handle: 0, origin, packet: packet.clone() });
                s.link_seq = s.link_seq.wrapping_add(1);
                let _ = s.tx.send(Out::Binary(f.encode()));
            }
        }
    }

    fn param_changed(&mut self, off: u16, value: &[u8], source: u8, origin: u16) {
        let mut p = vec![0x02, 0x02, 0, 0];
        p.extend_from_slice(&off.to_le_bytes());
        p.extend_from_slice(&(value.len() as u16).to_le_bytes());
        p.extend_from_slice(&[source, 0, 0, 0]);
        p.extend_from_slice(value);
        self.notify(p, origin);
    }

    /// Run one vendor request on the simulated device.
    fn device(
        &mut self,
        sid: u16,
        dir: Dir,
        b_request: u8,
        w_value: u16,
        w_length: u16,
        payload: &[u8],
    ) -> (u8, Vec<u8>) {
        if self.cfg.timeout_requests.contains(&b_request) {
            return (status::TIMEOUT, Vec::new());
        }
        match (dir, b_request) {
            (Dir::Get, req::GET_PLATFORM) => (status::OK, PLATFORM.to_vec()),
            (Dir::Get, req::GET_SERIAL) => (status::OK, self.cfg.serial.as_bytes().to_vec()),
            (Dir::Get, req::GET_STATUS) => (status::OK, STATUS_WVALUE9.to_vec()),
            (Dir::Get, req::GET_ALL_PARAMS) => (status::OK, self.image.clone()),
            (Dir::Get, req::GET_ALL_PARAMS_CHUNK) => {
                let o = (w_value as usize).min(self.image.len());
                let e = (o + w_length as usize).min(self.image.len());
                if o >= self.image.len() {
                    (status::ERROR, Vec::new())
                } else {
                    (status::OK, self.image[o..e].to_vec())
                }
            }
            (Dir::Set, req::SET_ALL_PARAMS) if payload.len() == self.image.len() => {
                self.image = payload.to_vec();
                self.notify(vec![0x02, 0x03, 0, 0, 2, 0, 0, 0], sid);
                (status::OK, Vec::new())
            }
            (Dir::Set, req::SET_ALL_PARAMS_CHUNK) => {
                let o = w_value as usize;
                if o + payload.len() <= self.image.len() {
                    self.image[o..o + payload.len()].copy_from_slice(payload);
                    if o + payload.len() == self.image.len() {
                        self.notify(vec![0x02, 0x03, 0, 0, 2, 0, 0, 0], sid);
                    }
                    (status::OK, Vec::new())
                } else {
                    (status::ERROR, Vec::new())
                }
            }
            (Dir::Set, r) => {
                if let Some(&(off, size)) = self.cfg.set_offsets.get(&r) {
                    if payload.len() == size as usize && (off as usize + payload.len()) <= self.image.len() {
                        let o = off as usize;
                        self.image[o..o + payload.len()].copy_from_slice(payload);
                        self.param_changed(off, payload, 1, sid);
                    }
                }
                (status::OK, Vec::new())
            }
            // An opcode the fake firmware does not have: a STALL
            (Dir::Get, _) => (status::ERROR, Vec::new()),
        }
    }

    fn command(&mut self, sid: u16, f: crate::frames::CmdRequest) -> CmdResponse {
        let reply = |status: u8, payload: Vec<u8>| CmdResponse { tag: f.tag, status, payload };
        let role = self.sessions.get(&sid).map(|s| s.role.clone()).unwrap_or_default();
        if f.handle != 0 {
            return reply(status::NO_DEVICE, Vec::new());
        }
        if !allowed(&role, self.class(f.b_request, f.dir as u8)) {
            return reply(status::DENIED, Vec::new());
        }
        if let Some((holder, until)) = self.lock {
            if Instant::now() > until {
                self.lock = None;
            } else if holder != sid {
                return reply(status::LOCKED, Vec::new());
            }
        }
        let bulk = matches!(f.b_request, req::GET_ALL_PARAMS | req::SET_ALL_PARAMS);
        if self.cfg.bridge_limits {
            if matches!(f.b_request, req::GET_ALL_PARAMS_CHUNK | req::SET_ALL_PARAMS_CHUNK) {
                return reply(status::BLOCKED, Vec::new());
            }
            if f.b_request == req::RTA_GET_BANDS_ALL {
                return reply(status::ERROR, Vec::new());
            }
            if f.dir == Dir::Set && !bulk && f.payload.len() > 64 {
                return reply(status::OVERSIZE, Vec::new());
            }
        }
        if f.payload.len() > 8192 || (f.dir == Dir::Get && f.w_length as usize > 8192) {
            return reply(status::TOO_LARGE, Vec::new());
        }
        self.log.push((sid, f.dir as u8, f.b_request, f.w_value));
        let (st, mut data) = self.device(sid, f.dir, f.b_request, f.w_value, f.w_length, &f.payload);
        let cap = if self.cfg.bridge_limits && !bulk { 132 } else { usize::MAX };
        data.truncate((f.w_length as usize).min(cap));
        if f.dir == Dir::Set {
            data.clear();
        }
        reply(st, data)
    }
}

impl FakeHub {
    pub fn start(cfg: HubConfig) -> io::Result<FakeHub> {
        let listener = TcpListener::bind(cfg.bind)?;
        let addr = listener.local_addr()?;
        listener.set_nonblocking(true)?;
        let state = Arc::new(Mutex::new(State {
            image: cfg.image.clone(),
            cfg,
            fw_seq: 0,
            next_session: 12,
            sessions: HashMap::new(),
            tokens: HashMap::new(),
            lock: None,
            log: Vec::new(),
            classes: load_policy(),
        }));
        let stop = Arc::new(AtomicBool::new(false));
        let (st, sp) = (Arc::clone(&state), Arc::clone(&stop));
        thread::Builder::new().name("fakehub-accept".into()).spawn(move || {
            while !sp.load(Ordering::SeqCst) {
                match listener.accept() {
                    Ok((tcp, _)) => {
                        let (st, sp) = (Arc::clone(&st), Arc::clone(&sp));
                        let _ = thread::Builder::new().name("fakehub-conn".into()).spawn(move || serve(tcp, st, sp));
                    }
                    Err(e) if e.kind() == io::ErrorKind::WouldBlock => thread::sleep(Duration::from_millis(10)),
                    Err(_) => thread::sleep(Duration::from_millis(10)),
                }
            }
        })?;
        Ok(FakeHub { addr, state, stop })
    }

    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    pub fn url(&self) -> String {
        format!("ws://{}/dspi/v1", self.addr)
    }

    /// The simulated device's current bulk image.
    pub fn image(&self) -> Vec<u8> {
        lock(&self.state).image.clone()
    }

    /// Every command the device ran: `(session, dir, bRequest, wValue)`.
    pub fn log(&self) -> Vec<(u16, u8, u8, u16)> {
        lock(&self.state).log.clone()
    }

    /// Authenticated session ids.
    pub fn sessions(&self) -> Vec<u16> {
        let mut v: Vec<u16> = lock(&self.state).sessions.iter().filter(|(_, s)| s.authed).map(|(k, _)| *k).collect();
        v.sort();
        v
    }

    /// A change made elsewhere (a knob, another console): patch the image and
    /// notify every session with `source` and `origin`.
    pub fn external_change(&self, off: u16, value: &[u8], source: u8, origin: u16) {
        let mut s = lock(&self.state);
        let o = off as usize;
        s.image[o..o + value.len()].copy_from_slice(value);
        s.param_changed(off, value, source, origin);
    }

    /// Send RESYNC (reason 2, cache rebuilt) to every session.
    pub fn resync(&self) {
        let s = lock(&self.state);
        for r in s.sessions.values() {
            let _ = r.tx.send(Out::Binary(Frame::Resync { handle: 0, reason: 2 }.encode()));
        }
    }

    /// Skip one Link NOTIFY sequence number for every session (a dropped event).
    pub fn drop_one_notification(&self) {
        let mut s = lock(&self.state);
        for r in s.sessions.values_mut() {
            r.link_seq = r.link_seq.wrapping_add(1);
        }
    }

    /// Hold the device lock as session `sid` (another console).
    pub fn hold_lock(&self, sid: u16, for_ms: u64) {
        lock(&self.state).lock = Some((sid, Instant::now() + Duration::from_millis(for_ms)));
    }

    pub fn release_lock(&self) {
        lock(&self.state).lock = None;
    }

    pub fn lock_holder(&self) -> Option<u16> {
        lock(&self.state).lock.map(|l| l.0)
    }

    /// Revoke every token: their sessions close with 4002 (spec 6.2).
    pub fn revoke_all(&self) {
        let mut s = lock(&self.state);
        s.tokens.clear();
        for r in s.sessions.values() {
            if r.token.is_some() {
                let _ = r.tx.send(Out::Close(4002));
            }
        }
    }

    /// Close every session with `code` (4003 = hub shutting down).
    pub fn close_all(&self, code: u16) {
        let s = lock(&self.state);
        for r in s.sessions.values() {
            let _ = r.tx.send(Out::Close(code));
        }
    }

    pub fn stop(&self) {
        self.close_all(4003);
        self.stop.store(true, Ordering::SeqCst);
    }
}

impl Drop for FakeHub {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Bytes already read from the socket, then the socket.
struct Replay {
    pre: io::Cursor<Vec<u8>>,
    tcp: TcpStream,
}

impl Read for Replay {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        if (self.pre.position() as usize) < self.pre.get_ref().len() {
            return self.pre.read(buf);
        }
        self.tcp.read(buf)
    }
}

impl Write for Replay {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        self.tcp.write(buf)
    }
    fn flush(&mut self) -> io::Result<()> {
        self.tcp.flush()
    }
}

#[allow(clippy::result_large_err)] // tungstenite's callback signature
fn serve(mut tcp: TcpStream, state: Arc<Mutex<State>>, stop: Arc<AtomicBool>) {
    let _ = tcp.set_nonblocking(false);
    let _ = tcp.set_read_timeout(Some(Duration::from_secs(5)));
    // Read the request head to tell the info endpoint from the upgrade
    let mut head = Vec::new();
    let mut b = [0u8; 1024];
    while !head.windows(4).any(|w| w == b"\r\n\r\n") && head.len() < 16384 {
        match tcp.read(&mut b) {
            Ok(0) | Err(_) => return,
            Ok(n) => head.extend_from_slice(&b[..n]),
        }
    }
    let text = String::from_utf8_lossy(&head).to_ascii_lowercase();
    if !text.contains("upgrade: websocket") {
        let s = lock(&state);
        let body = json!({
            "proto": {"major": 1, "minor": s.cfg.proto_minor},
            "hub": {"id": s.cfg.hub_id, "name": s.cfg.name, "kind": s.cfg.kind, "version": "0.0-fake"},
            "auth": if s.cfg.auth == Auth::None { "none" } else { "pin" }, "tls": false, "ws": "/dspi/v1",
            "devices": [{"serial": s.cfg.serial, "name": s.cfg.device_name}],
        })
        .to_string();
        drop(s);
        let status = if text.starts_with("get /dspi/v1/info ") { "200 OK" } else { "404 Not Found" };
        let _ = write!(
            tcp,
            "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nAccess-Control-Allow-Origin: *\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        return;
    }
    let raw = match tcp.try_clone() {
        Ok(t) => t,
        Err(_) => return,
    };
    let stream = Replay { pre: io::Cursor::new(head), tcp };
    let ws = tungstenite::accept_hdr(stream, |req: &Request, mut resp: Response| {
        let asked = req.headers().get("Sec-WebSocket-Protocol").and_then(|v| v.to_str().ok()).unwrap_or("");
        if asked.split(',').any(|p| p.trim() == "dspi-link-1") {
            resp.headers_mut().insert("Sec-WebSocket-Protocol", "dspi-link-1".parse().unwrap());
        }
        Ok(resp)
    });
    let Ok(mut ws) = ws else { return };
    let _ = raw.set_read_timeout(Some(Duration::from_millis(5)));
    session(&mut ws, &state, &stop);
}

fn send_close(ws: &mut WebSocket<Replay>, code: u16) {
    let _ = ws.close(Some(CloseFrame { code: CloseCode::from(code), reason: "".into() }));
    let _ = ws.flush();
}

fn session(ws: &mut WebSocket<Replay>, state: &Arc<Mutex<State>>, stop: &AtomicBool) {
    let (tx, rx): (Sender<Out>, Receiver<Out>) = mpsc::channel();
    let sid = {
        let mut s = lock(state);
        let sid = s.next_session;
        s.next_session += 1;
        s.sessions.insert(
            sid,
            SessionRec {
                tx,
                role: "admin".into(),
                authed: false,
                token: None,
                link_seq: 0,
                poll_seq: 0,
                polls: HashMap::new(),
            },
        );
        sid
    };
    let started = Instant::now();
    let mut last_ping = Instant::now();
    let mut hello_done = false;
    'outer: while !stop.load(Ordering::SeqCst) {
        // Outgoing queue
        while let Ok(o) = rx.try_recv() {
            match o {
                Out::Text(t) => {
                    if ws.send(Message::text(t)).is_err() {
                        break 'outer;
                    }
                }
                Out::Binary(b) => {
                    if ws.send(Message::binary(b)).is_err() {
                        break 'outer;
                    }
                }
                Out::Close(code) => {
                    send_close(ws, code);
                    break 'outer;
                }
            }
        }
        // Polls due
        let due: Vec<(u8, Vec<u8>)> = {
            let mut s = lock(state);
            let now = Instant::now();
            let mut due = Vec::new();
            if let Some(r) = s.sessions.get_mut(&sid) {
                for (slot, (spec, every, next)) in r.polls.iter_mut() {
                    if now >= *next {
                        *next = now + *every;
                        due.push((*slot, spec.clone()));
                    }
                }
            }
            due.into_iter()
                .map(|(slot, spec)| {
                    let (_, data) = s.device(
                        sid,
                        Dir::Get,
                        spec[0],
                        u16::from_le_bytes([spec[1], spec[2]]),
                        u16::from_le_bytes([spec[3], spec[4]]),
                        &[],
                    );
                    (slot, data)
                })
                .collect()
        };
        for (slot, payload) in due {
            let seq = {
                let mut s = lock(state);
                let Some(r) = s.sessions.get_mut(&sid) else { break 'outer };
                r.poll_seq = r.poll_seq.wrapping_add(1);
                r.poll_seq
            };
            let f = Frame::Poll(PollFrame { seq, handle: 0, slot, payload });
            if ws.send(Message::binary(f.encode())).is_err() {
                break 'outer;
            }
        }
        if last_ping.elapsed() > Duration::from_secs(10) {
            last_ping = Instant::now();
            let _ = ws.send(Message::Ping(Vec::new().into()));
        }
        {
            let s = lock(state);
            let authed = s.sessions.get(&sid).is_some_and(|r| r.authed);
            if !authed && hello_done && started.elapsed() > s.cfg.auth_deadline {
                drop(s);
                send_close(ws, 4001);
                break;
            }
        }
        match ws.read() {
            Ok(Message::Text(t)) => {
                let replies = handle_json(state, sid, t.as_str(), &mut hello_done);
                for r in replies {
                    if ws.send(Message::text(r)).is_err() {
                        break 'outer;
                    }
                }
            }
            Ok(Message::Binary(b)) => {
                let reply = {
                    let mut s = lock(state);
                    let authed = s.sessions.get(&sid).is_some_and(|r| r.authed);
                    match Frame::decode(&b) {
                        Ok(Frame::CmdRequest(f)) if authed => Some(Frame::CmdResponse(s.command(sid, f)).encode()),
                        Ok(Frame::Unknown { kind: k, tag }) if authed && k < 0x80 => Some(
                            Frame::CmdResponse(CmdResponse { tag, status: status::UNSUPPORTED, payload: vec![] })
                                .encode(),
                        ),
                        Err(_) if authed && b.len() >= 4 && b[0] == kind::CMD_REQUEST => Some(
                            Frame::CmdResponse(CmdResponse {
                                tag: u16::from_le_bytes([b[2], b[3]]),
                                status: status::BAD_FRAME,
                                payload: vec![],
                            })
                            .encode(),
                        ),
                        _ => None,
                    }
                };
                if let Some(r) = reply {
                    if ws.send(Message::binary(r)).is_err() {
                        break;
                    }
                }
            }
            Ok(Message::Close(_)) => {
                let _ = ws.flush();
                break;
            }
            Ok(_) => {}
            Err(tungstenite::Error::Io(e))
                if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
            Err(_) => break,
        }
    }
    let mut s = lock(state);
    s.sessions.remove(&sid);
    if s.lock.is_some_and(|l| l.0 == sid) {
        s.lock = None;
    }
}

fn ok(id: &Value, extra: Value) -> String {
    let mut v = json!({"t": "ok", "id": id});
    if let (Some(o), Some(e)) = (v.as_object_mut(), extra.as_object()) {
        for (k, x) in e {
            o.insert(k.clone(), x.clone());
        }
    }
    v.to_string()
}

fn err(id: &Value, code: &str, msg: &str) -> String {
    json!({"t": "err", "id": id, "code": code, "msg": msg}).to_string()
}

fn handle_json(state: &Arc<Mutex<State>>, sid: u16, text: &str, hello_done: &mut bool) -> Vec<String> {
    let Ok(v) = serde_json::from_str::<Value>(text) else { return vec![] };
    let t = v["t"].as_str().unwrap_or("");
    let id = v["id"].clone();
    let mut s = lock(state);
    let authed = s.sessions.get(&sid).is_some_and(|r| r.authed);
    match t {
        "hello" => {
            *hello_done = true;
            let mut h = json!({
                "t": "hello", "proto": {"major": 1, "minor": s.cfg.proto_minor},
                "hub": {"id": s.cfg.hub_id, "name": s.cfg.name, "kind": s.cfg.kind, "version": "0.0-fake"},
                "auth": if s.cfg.auth == Auth::None { "none" } else { "pin" },
                "caps": s.cfg.caps,
                "limits": {"max_frame": if s.cfg.bridge_limits { 16384 } else { 65536 }, "max_payload": 8192,
                           "max_inflight": s.cfg.max_inflight, "poll_max_hz": 20, "poll_budget_bps": 200000},
            });
            if let Auth::Fixed(_) = s.cfg.auth {
                h["pin_source"] = json!("fixed");
            }
            if s.cfg.auth == Auth::None {
                if let Some(r) = s.sessions.get_mut(&sid) {
                    r.authed = true;
                    r.role = "admin".into();
                }
            }
            vec![h.to_string()]
        }
        "auth.pair" => {
            let pin = v["pin"].as_str().unwrap_or("");
            let (good, role) = match &s.cfg.auth {
                Auth::None => (true, "admin".to_owned()),
                // The hub decides the role (the requested one is a wish, spec 6.2)
                Auth::Window(p) => (pin == p, s.cfg.pin_role.clone()),
                Auth::Fixed(p) => (pin == p, s.cfg.pin_role.clone()),
            };
            if !good {
                return vec![err(&id, "denied", "wrong PIN")];
            }
            let token = if s.cfg.auth == Auth::None { None } else { Some(format!("tok-{sid}-{}", s.tokens.len())) };
            if let Some(tok) = &token {
                s.tokens.insert(tok.clone(), role.clone());
            }
            let denied = s.denied(&role);
            if let Some(r) = s.sessions.get_mut(&sid) {
                r.authed = true;
                r.role = role.clone();
                r.token = token.clone();
            }
            let mut extra = json!({"session": sid, "role": role, "policy": {"denied": denied}});
            if let Some(tok) = token {
                extra["token"] = json!(tok);
            }
            vec![ok(&id, extra)]
        }
        "auth.token" => {
            let tok = v["token"].as_str().unwrap_or("").to_owned();
            match s.tokens.get(&tok).cloned() {
                Some(role) => {
                    let denied = s.denied(&role);
                    if let Some(r) = s.sessions.get_mut(&sid) {
                        r.authed = true;
                        r.role = role.clone();
                        r.token = Some(tok);
                    }
                    vec![ok(&id, json!({"session": sid, "role": role, "policy": {"denied": denied}}))]
                }
                None => vec![err(&id, "unauthenticated", "unknown token")],
            }
        }
        _ if !authed => vec![err(&id, "unauthenticated", "")],
        "device.list" => vec![ok(&id, json!({"devices": [s.device_json()]}))],
        "device.snapshot" => {
            if v["handle"].as_u64() != Some(0) {
                return vec![err(&id, "no_device", "")];
            }
            let b64 = data_encoding::BASE64.encode(&s.image);
            let st = data_encoding::BASE64.encode(STATUS_WVALUE9);
            vec![ok(
                &id,
                json!({"handle": 0, "wire_version": s.image[0], "age_ms": 0, "bulk_b64": b64, "status_b64": st}),
            )]
        }
        "poll.subscribe" => {
            let mut granted = Vec::new();
            let role = s.sessions.get(&sid).map(|r| r.role.clone()).unwrap_or_default();
            let polls = v["polls"].as_array().cloned().unwrap_or_default();
            for p in polls {
                let (slot, rq) = (p["slot"].as_u64().unwrap_or(0) as u8, p["req"].as_u64().unwrap_or(0) as u8);
                if s.class(rq, 1) != "read" || !allowed(&role, "read") {
                    return vec![err(&id, "denied", "poll commands must be read")];
                }
                let hz = p["hz"].as_u64().unwrap_or(1).clamp(1, 20);
                let val = p["val"].as_u64().unwrap_or(0) as u16;
                let len = p["len"].as_u64().unwrap_or(0) as u16;
                let mut spec = vec![rq];
                spec.extend_from_slice(&val.to_le_bytes());
                spec.extend_from_slice(&len.to_le_bytes());
                if let Some(r) = s.sessions.get_mut(&sid) {
                    r.polls.insert(slot, (spec, Duration::from_millis(1000 / hz), Instant::now()));
                }
                granted.push(json!({"slot": slot, "hz": hz}));
            }
            vec![ok(&id, json!({"granted": granted}))]
        }
        "poll.unsubscribe" => {
            let slots: Vec<u8> =
                v["slots"].as_array().into_iter().flatten().filter_map(|x| x.as_u64()).map(|x| x as u8).collect();
            if let Some(r) = s.sessions.get_mut(&sid) {
                r.polls.retain(|k, _| !slots.contains(k));
            }
            vec![ok(&id, json!({}))]
        }
        "lock.acquire" => {
            let role = s.sessions.get(&sid).map(|r| r.role.clone()).unwrap_or_default();
            if role == "viewer" {
                return vec![err(&id, "denied", "viewer")];
            }
            let ms = v["timeout_ms"].as_i64().unwrap_or(10_000);
            if ms < 0 {
                return vec![err(&id, "bad_request", "negative timeout")];
            }
            let ms = ms.min(60_000) as u64;
            if let Some((holder, until)) = s.lock {
                if holder != sid && Instant::now() < until {
                    return vec![err(&id, "locked", "Another console is applying changes")];
                }
            }
            s.lock = Some((sid, Instant::now() + Duration::from_millis(ms)));
            let changed = json!({"t": "device.changed", "device": s.device_json()}).to_string();
            s.broadcast(changed);
            vec![ok(&id, json!({"timeout_ms": ms}))]
        }
        "lock.release" => {
            if s.lock.is_some_and(|l| l.0 == sid) {
                s.lock = None;
                let changed = json!({"t": "device.changed", "device": s.device_json()}).to_string();
                s.broadcast(changed);
            }
            vec![ok(&id, json!({}))]
        }
        _ => vec![err(&id, "unknown_type", t)],
    }
}

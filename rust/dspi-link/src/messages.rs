//! JSON control-plane messages (DSPi Link spec section 7).
//!
//! Envelope `{t, id}`; replies `ok`/`err` echo `id`; events have no `id`.
//! Unknown fields are ignored and unknown event types are dropped (spec 10).
//! Port of the client half of DSPi-Console `release/v1.1.6`
//! `Link/LinkMessages.swift`.

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// The protocol this client speaks (spec 1.2; 1.1 hubs are compatible, 13.1).
pub const PROTO_MAJOR: u32 = 1;
pub const PROTO_MINOR: u32 = 2;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Proto {
    pub major: u32,
    pub minor: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ClientInfo {
    pub name: String,
    pub app: String,
    pub version: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct HubInfo {
    #[serde(default)]
    pub id: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub kind: String,
    #[serde(default)]
    pub version: Option<String>,
}

/// `hello.limits` (spec 7.2). Missing values fall back to the spec minimums.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct Limits {
    #[serde(default = "Limits::default_max_frame")]
    pub max_frame: u32,
    #[serde(default = "Limits::default_max_payload")]
    pub max_payload: u32,
    #[serde(default = "Limits::default_max_inflight")]
    pub max_inflight: u32,
    #[serde(default)]
    pub poll_max_hz: u32,
    #[serde(default)]
    pub poll_budget_bps: u32,
}

impl Limits {
    fn default_max_frame() -> u32 {
        16384
    }
    fn default_max_payload() -> u32 {
        8192
    }
    fn default_max_inflight() -> u32 {
        1
    }
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_frame: Self::default_max_frame(),
            max_payload: Self::default_max_payload(),
            max_inflight: Self::default_max_inflight(),
            poll_max_hz: 0,
            poll_budget_bps: 0,
        }
    }
}

/// The hub's `hello` (spec 7.2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct HelloHub {
    #[serde(default)]
    pub proto: Proto,
    #[serde(default)]
    pub hub: HubInfo,
    /// `none` or `pin`.
    #[serde(default)]
    pub auth: String,
    /// `window` or `fixed`; absent (1.1 hubs) means `window` (spec 7.2).
    #[serde(default)]
    pub pin_source: Option<String>,
    #[serde(default)]
    pub caps: Vec<String>,
    #[serde(default)]
    pub limits: Limits,
}

impl HelloHub {
    pub fn has_cap(&self, cap: &str) -> bool {
        self.caps.iter().any(|c| c == cap)
    }

    pub fn pin_required(&self) -> bool {
        self.auth == "pin"
    }

    /// `window` unless the hub says `fixed` (spec 7.2).
    pub fn pin_source(&self) -> &str {
        match self.pin_source.as_deref() {
            Some("fixed") => "fixed",
            _ => "window",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Policy {
    /// `[bRequest, dir]` pairs this session may not send (dir 0 = SET, 1 = GET).
    #[serde(default)]
    pub denied: Vec<[u16; 2]>,
}

/// The `ok` to `auth.token` / `auth.pair` (spec 7.3).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct AuthOk {
    #[serde(default)]
    pub session: u32,
    #[serde(default)]
    pub role: String,
    #[serde(default)]
    pub token: Option<String>,
    #[serde(default)]
    pub policy: Option<Policy>,
}

/// One entry of `device.list` / `device.added` / `device.changed` (spec 7.4).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct DeviceInfo {
    pub handle: u32,
    #[serde(default)]
    pub serial: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub platform: Option<u32>,
    #[serde(default)]
    pub fw: Option<String>,
    /// Absent = unknown, not 0 (spec 7.4).
    #[serde(default)]
    pub fw_beta: Option<u32>,
    #[serde(default)]
    pub outputs: Option<u32>,
    #[serde(default)]
    pub inputs: Option<u32>,
    #[serde(default)]
    pub wire_version: Option<u32>,
    /// `online`, `offline`, `updating`.
    #[serde(default)]
    pub state: String,
    /// `usb` or `uart`.
    #[serde(default)]
    pub link: String,
    #[serde(default)]
    pub locked_by: Option<u32>,
}

/// The `ok` to `device.snapshot` (spec 7.5).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct Snapshot {
    #[serde(default)]
    pub handle: u32,
    #[serde(default)]
    pub wire_version: Option<u32>,
    #[serde(default)]
    pub age_ms: Option<u64>,
    #[serde(default)]
    pub bulk_b64: Option<String>,
    #[serde(default)]
    pub status_b64: Option<String>,
}

impl Snapshot {
    pub fn bulk(&self) -> Option<Vec<u8>> {
        self.bulk_b64.as_deref().and_then(|s| data_encoding::BASE64.decode(s.as_bytes()).ok())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PollSpec {
    pub slot: u8,
    pub req: u8,
    pub val: u16,
    pub idx: u16,
    pub len: u16,
    pub hz: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PollGrant {
    pub slot: u8,
    pub hz: u32,
}

/// An `err` reply (spec 7.1).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct ErrReply {
    #[serde(default)]
    pub id: Option<u64>,
    #[serde(default)]
    pub code: String,
    #[serde(default)]
    pub msg: Option<String>,
    #[serde(default)]
    pub field: Option<String>,
}

/// `GET /dspi/v1/info` (spec 3.2).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct InfoDoc {
    #[serde(default)]
    pub proto: Proto,
    #[serde(default)]
    pub hub: HubInfo,
    #[serde(default)]
    pub auth: String,
    #[serde(default)]
    pub tls: bool,
    #[serde(default)]
    pub ws: Option<String>,
    #[serde(default)]
    pub devices: Vec<InfoDevice>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct InfoDevice {
    #[serde(default)]
    pub serial: String,
    #[serde(default)]
    pub name: String,
}

/// A message from the hub, by `t`.
#[derive(Debug, Clone, PartialEq)]
pub enum Inbound {
    Hello(HelloHub),
    Ok {
        id: u64,
        body: Value,
    },
    Err(ErrReply),
    DeviceAdded(DeviceInfo),
    DeviceChanged(DeviceInfo),
    DeviceRemoved {
        handle: u32,
        serial: String,
    },
    PollError {
        handle: u32,
        slot: Option<u8>,
        code: Option<String>,
    },
    /// Any other event (fw.progress, hub.config.changed, ...): ignored by this client.
    Other {
        t: String,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum MessageError {
    #[error("not JSON: {0}")]
    NotJson(String),
    #[error("no string `t`")]
    NoType,
    #[error("malformed `{t}`: {msg}")]
    Malformed { t: String, msg: String },
}

fn field<T: serde::de::DeserializeOwned>(t: &str, v: Value) -> Result<T, MessageError> {
    serde_json::from_value(v).map_err(|e| MessageError::Malformed { t: t.to_owned(), msg: e.to_string() })
}

impl Inbound {
    pub fn parse(text: &str) -> Result<Inbound, MessageError> {
        let v: Value = serde_json::from_str(text).map_err(|e| MessageError::NotJson(e.to_string()))?;
        let t = v.get("t").and_then(Value::as_str).ok_or(MessageError::NoType)?.to_owned();
        Ok(match t.as_str() {
            "hello" => Inbound::Hello(field(&t, v)?),
            "ok" => {
                let id = v
                    .get("id")
                    .and_then(Value::as_u64)
                    .ok_or(MessageError::Malformed { t: t.clone(), msg: "ok without id".into() })?;
                Inbound::Ok { id, body: v }
            }
            "err" => Inbound::Err(field(&t, v)?),
            "device.added" | "device.changed" => {
                let dev = v
                    .get("device")
                    .cloned()
                    .ok_or(MessageError::Malformed { t: t.clone(), msg: "no device".into() })?;
                let dev: DeviceInfo = field(&t, dev)?;
                if t == "device.added" {
                    Inbound::DeviceAdded(dev)
                } else {
                    Inbound::DeviceChanged(dev)
                }
            }
            "device.removed" => Inbound::DeviceRemoved {
                handle: v.get("handle").and_then(Value::as_u64).unwrap_or(u64::MAX) as u32,
                serial: v.get("serial").and_then(Value::as_str).unwrap_or_default().to_owned(),
            },
            "poll.error" => Inbound::PollError {
                handle: v.get("handle").and_then(Value::as_u64).unwrap_or(0) as u32,
                slot: v.get("slot").and_then(Value::as_u64).map(|s| s as u8),
                code: v.get("code").and_then(Value::as_str).map(str::to_owned),
            },
            _ => Inbound::Other { t },
        })
    }
}

/// Requests and the client hello, as JSON text.
pub mod out {
    use super::*;

    pub fn hello(client: &ClientInfo) -> String {
        json!({"t": "hello", "proto": {"major": PROTO_MAJOR, "minor": PROTO_MINOR}, "client": client}).to_string()
    }

    pub fn auth_token(id: u64, token: &str) -> String {
        json!({"t": "auth.token", "id": id, "token": token}).to_string()
    }

    /// `role` None leaves it to the hub (the `none`-mode courtesy probe).
    pub fn auth_pair(id: u64, pin: &str, name: &str, role: Option<&str>) -> String {
        let mut v = json!({"t": "auth.pair", "id": id, "pin": pin, "name": name});
        if let Some(r) = role {
            v["role"] = json!(r);
        }
        v.to_string()
    }

    pub fn device_list(id: u64) -> String {
        json!({"t": "device.list", "id": id}).to_string()
    }

    pub fn device_snapshot(id: u64, handle: u8) -> String {
        json!({"t": "device.snapshot", "id": id, "handle": handle}).to_string()
    }

    pub fn poll_subscribe(id: u64, handle: u8, polls: &[PollSpec]) -> String {
        json!({"t": "poll.subscribe", "id": id, "handle": handle, "polls": polls}).to_string()
    }

    pub fn poll_unsubscribe(id: u64, handle: u8, slots: &[u8]) -> String {
        json!({"t": "poll.unsubscribe", "id": id, "handle": handle, "slots": slots}).to_string()
    }

    pub fn lock_acquire(id: u64, handle: u8, reason: &str, timeout_ms: u32) -> String {
        json!({"t": "lock.acquire", "id": id, "handle": handle, "reason": reason, "timeout_ms": timeout_ms}).to_string()
    }

    pub fn lock_release(id: u64, handle: u8) -> String {
        json!({"t": "lock.release", "id": id, "handle": handle}).to_string()
    }
}

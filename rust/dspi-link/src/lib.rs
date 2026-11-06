//! DSPi Link client: protocol 1.2 (compatible with 1.1 hubs, spec 13.1).
//!
//! Spec: WeebLabs/DSPi-Link `spec/dspi_link_protocol_spec.md` at 86f10e3
//! (2026-11-02). Reference implementation: DSPi-Console `release/v1.1.6`
//! `Link/` (LinkClient, LinkFrames, LinkMessages, HubBrowser.parseAddress).
//!
//! Layers, each usable on its own:
//! - [`frames`]: the binary data plane (CMD, NOTIFY, POLL, FWDATA, RESYNC);
//! - [`messages`]: the JSON control plane;
//! - [`socket`]: the WebSocket byte pipe ([`socket::WsConnector`]; the
//!   built-in one is `tungstenite` over `std::net`);
//! - [`client`]: one session ([`LinkClient`]);
//! - [`address`]: manual entry (`host`, `host:port`) and `GET /dspi/v1/info`.
//!
//! The crate knows nothing about the DSPi core or any FFI; dspi-ffi adapts
//! a [`LinkClient`] to the core's `Transport` trait.

pub mod address;
pub mod client;
pub mod frames;
pub mod messages;
pub mod socket;

#[cfg(feature = "fake-hub")]
pub mod fakehub;

pub use client::{close_reason, should_reconnect, AuthState, ConnectOptions, LinkClient, LinkEvents, NotifyItem};
pub use frames::{status, Dir};
pub use messages::{DeviceInfo, HelloHub};

/// Default hub port: decimal of the USB VID 0x2E8B (spec 3.1).
pub const DEFAULT_PORT: u16 = 11915;
/// Default WebSocket path (spec 4).
pub const DEFAULT_PATH: &str = "/dspi/v1";

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum LinkError {
    #[error("network error: {0}")]
    Io(String),
    #[error("WebSocket handshake failed: {0}")]
    Handshake(String),
    #[error("protocol error: {0}")]
    Protocol(String),
    #[error("the hub did not answer in time")]
    Timeout,
    #[error("{reason}")]
    Closed { code: Option<u16>, reason: String },
    /// An `err` reply: `code` is the spec 7.1 vocabulary (`denied`, `locked`, ...).
    #[error("hub refused: {code} {msg}")]
    Rejected { code: String, msg: String },
}

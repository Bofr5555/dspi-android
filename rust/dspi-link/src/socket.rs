//! The WebSocket byte pipe under the Link client.
//!
//! [`WsConnector`] / [`WsSender`] / [`WsReceiver`] are the seam: the client
//! logic in `client.rs` only sees whole text and binary messages, so another
//! platform can supply its own socket (iOS `URLSessionWebSocketTask`, Android
//! OkHttp) as a foreign implementation later (CONSOLE-UNIFICATION-STUDY.md
//! section 6.2, "byte-pipe transports"). [`TcpConnector`] is the built-in
//! one: `tungstenite` over a blocking `std::net::TcpStream`, plaintext
//! (`ws://`; no hub implements TLS, spec 6.4).
//!
//! Concurrency: one reader thread blocks in [`WsReceiver::recv`] while any
//! thread may send. tungstenite has no split API, so the stream is cloned
//! into a reading and a writing `WebSocket`; both buffer their output and
//! write each frame whole under one shared lock on flush, so the reader's
//! automatic pong/close replies can never interleave with a frame the writer
//! is sending.

use std::io::{self, Read, Write};
use std::net::{Shutdown, TcpStream, ToSocketAddrs};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use tungstenite::client::IntoClientRequest;
use tungstenite::protocol::frame::coding::CloseCode;
use tungstenite::protocol::{CloseFrame, Role, WebSocketConfig};
use tungstenite::{Message, WebSocket};

use crate::LinkError;

/// One whole WebSocket message.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WsMessage {
    Text(String),
    Binary(Vec<u8>),
}

/// What [`WsReceiver::recv`] returns.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WsRecv {
    Message(WsMessage),
    /// Nothing arrived within the receiver's tick (about a second): lets the
    /// reader loop check for shutdown and keepalive.
    Idle,
    /// The peer closed (`code` from its close frame, if any) or the socket failed.
    Closed {
        code: Option<u16>,
        reason: String,
    },
}

pub trait WsSender: Send + Sync {
    fn send(&self, msg: WsMessage) -> io::Result<()>;
    fn ping(&self) -> io::Result<()>;
    /// Send a close frame (best effort) and shut the socket down, which also
    /// ends a blocked [`WsReceiver::recv`].
    fn close(&self, code: u16);
}

pub trait WsReceiver: Send {
    fn recv(&mut self) -> WsRecv;
}

/// The two halves of an open WebSocket.
pub type WsPair = (Box<dyn WsSender>, Box<dyn WsReceiver>);

/// Opens a WebSocket to `url` requesting sub-protocol `protocol`.
pub trait WsConnector: Send + Sync {
    fn connect(&self, url: &str, protocol: &str) -> Result<WsPair, LinkError>;
}

/// Largest message accepted from a hub (its `max_frame` is at most 64 KiB in
/// every known hub; a hostile peer cannot make us buffer more than this).
const MAX_MESSAGE: usize = 1 << 20;
const READ_TICK: Duration = Duration::from_millis(1000);
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);

/// `tungstenite` over `std::net::TcpStream`.
#[derive(Debug, Clone)]
pub struct TcpConnector {
    pub connect_timeout: Duration,
}

impl Default for TcpConnector {
    fn default() -> Self {
        Self { connect_timeout: Duration::from_secs(5) }
    }
}

/// A `TcpStream` that, once `buffered`, collects writes and puts them on the
/// wire whole on `flush`, under the lock shared by both halves.
struct Pipe {
    tcp: TcpStream,
    buffered: bool,
    out: Vec<u8>,
    wlock: Arc<Mutex<()>>,
}

impl Read for Pipe {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        self.tcp.read(buf)
    }
}

impl Write for Pipe {
    fn write(&mut self, buf: &[u8]) -> io::Result<usize> {
        if self.buffered {
            self.out.extend_from_slice(buf);
            Ok(buf.len())
        } else {
            self.tcp.write(buf)
        }
    }

    fn flush(&mut self) -> io::Result<()> {
        if !self.out.is_empty() {
            let _g = self.wlock.lock().unwrap_or_else(|p| p.into_inner());
            let r = self.tcp.write_all(&self.out);
            self.out.clear();
            r?;
        }
        self.tcp.flush()
    }
}

fn config() -> WebSocketConfig {
    WebSocketConfig::default().max_message_size(Some(MAX_MESSAGE)).max_frame_size(Some(MAX_MESSAGE))
}

fn io_err(e: tungstenite::Error) -> io::Error {
    match e {
        tungstenite::Error::Io(e) => e,
        other => io::Error::other(other.to_string()),
    }
}

/// Host and port out of `ws://host:port/...` (brackets kept off IPv6).
fn authority(url: &str) -> Option<(String, u16)> {
    let rest = url.strip_prefix("ws://")?;
    let auth = rest.split('/').next()?;
    if let Some(v6) = auth.strip_prefix('[') {
        let (host, tail) = v6.split_once(']')?;
        let port = tail.strip_prefix(':').map(str::parse).transpose().ok()?.unwrap_or(80);
        return Some((host.to_owned(), port));
    }
    match auth.rsplit_once(':') {
        Some((h, p)) => Some((h.to_owned(), p.parse().ok()?)),
        None => Some((auth.to_owned(), 80)),
    }
}

impl WsConnector for TcpConnector {
    fn connect(&self, url: &str, protocol: &str) -> Result<WsPair, LinkError> {
        let (host, port) = authority(url).ok_or_else(|| LinkError::Io(format!("not a ws:// URL: {url}")))?;
        let addrs: Vec<_> =
            (host.as_str(), port).to_socket_addrs().map_err(|e| LinkError::Io(e.to_string()))?.collect();
        let mut last = io::Error::new(io::ErrorKind::NotFound, "no address");
        let mut tcp = None;
        for a in &addrs {
            match TcpStream::connect_timeout(a, self.connect_timeout) {
                Ok(s) => {
                    tcp = Some(s);
                    break;
                }
                Err(e) => last = e,
            }
        }
        let tcp = tcp.ok_or_else(|| LinkError::Io(last.to_string()))?;
        let _ = tcp.set_nodelay(true);
        tcp.set_read_timeout(Some(Duration::from_secs(10))).map_err(|e| LinkError::Io(e.to_string()))?;
        tcp.set_write_timeout(Some(WRITE_TIMEOUT)).map_err(|e| LinkError::Io(e.to_string()))?;

        let mut request = url.into_client_request().map_err(|e| LinkError::Handshake(e.to_string()))?;
        request.headers_mut().insert(
            "Sec-WebSocket-Protocol",
            protocol.parse().map_err(|_| LinkError::Handshake("bad sub-protocol".into()))?,
        );
        let wlock = Arc::new(Mutex::new(()));
        let write_half = tcp.try_clone().map_err(|e| LinkError::Io(e.to_string()))?;
        let pipe = Pipe { tcp, buffered: false, out: Vec::new(), wlock: Arc::clone(&wlock) };
        let (mut reader, response) = tungstenite::client::client_with_config(request, pipe, Some(config()))
            .map_err(|e| LinkError::Handshake(e.to_string()))?;
        match response.headers().get("Sec-WebSocket-Protocol").and_then(|v| v.to_str().ok()) {
            Some(p) if p.eq_ignore_ascii_case(protocol) => {}
            other => log::warn!("dspi-link: hub did not echo sub-protocol {protocol} (got {other:?})"),
        }
        reader.get_mut().buffered = true;
        reader.get_ref().tcp.set_read_timeout(Some(READ_TICK)).map_err(|e| LinkError::Io(e.to_string()))?;
        let shutdown = write_half.try_clone().map_err(|e| LinkError::Io(e.to_string()))?;
        let writer = WebSocket::from_raw_socket(
            Pipe { tcp: write_half, buffered: true, out: Vec::new(), wlock },
            Role::Client,
            Some(config()),
        );
        Ok((
            Box::new(TcpSender { ws: Mutex::new(writer), shutdown }),
            Box::new(TcpReceiver { ws: reader, close_code: None }),
        ))
    }
}

struct TcpSender {
    ws: Mutex<WebSocket<Pipe>>,
    shutdown: TcpStream,
}

impl WsSender for TcpSender {
    fn send(&self, msg: WsMessage) -> io::Result<()> {
        let m = match msg {
            WsMessage::Text(t) => Message::text(t),
            WsMessage::Binary(b) => Message::binary(b),
        };
        self.ws.lock().unwrap_or_else(|p| p.into_inner()).send(m).map_err(io_err)
    }

    fn ping(&self) -> io::Result<()> {
        self.ws.lock().unwrap_or_else(|p| p.into_inner()).send(Message::Ping(Vec::new().into())).map_err(io_err)
    }

    fn close(&self, code: u16) {
        {
            let mut ws = self.ws.lock().unwrap_or_else(|p| p.into_inner());
            let _ = ws.close(Some(CloseFrame { code: CloseCode::from(code), reason: "".into() }));
            let _ = ws.flush();
        }
        let _ = self.shutdown.shutdown(Shutdown::Both);
    }
}

struct TcpReceiver {
    ws: WebSocket<Pipe>,
    close_code: Option<u16>,
}

impl WsReceiver for TcpReceiver {
    fn recv(&mut self) -> WsRecv {
        loop {
            match self.ws.read() {
                Ok(Message::Text(t)) => return WsRecv::Message(WsMessage::Text(t.as_str().to_owned())),
                Ok(Message::Binary(b)) => return WsRecv::Message(WsMessage::Binary(b.to_vec())),
                // Pongs and our automatic replies to pings: nothing for the client
                Ok(Message::Ping(_)) | Ok(Message::Pong(_)) | Ok(Message::Frame(_)) => continue,
                Ok(Message::Close(frame)) => {
                    self.close_code = frame.map(|f| u16::from(f.code));
                    continue; // the next read finishes the close handshake
                }
                Err(tungstenite::Error::Io(e))
                    if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) =>
                {
                    return WsRecv::Idle
                }
                Err(tungstenite::Error::ConnectionClosed) | Err(tungstenite::Error::AlreadyClosed) => {
                    return WsRecv::Closed { code: self.close_code, reason: "closed".into() }
                }
                Err(e) => return WsRecv::Closed { code: self.close_code, reason: e.to_string() },
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::authority;

    #[test]
    fn authority_parses_hosts() {
        assert_eq!(authority("ws://192.168.1.20:11915/dspi/v1"), Some(("192.168.1.20".into(), 11915)));
        assert_eq!(authority("ws://[fe80::1]:11915/dspi/v1"), Some(("fe80::1".into(), 11915)));
        assert_eq!(authority("ws://studio.local/dspi/v1"), Some(("studio.local".into(), 80)));
        assert_eq!(authority("http://x"), None);
    }
}

//! Manual hub entry (spec 3.3) and the info endpoint (spec 3.2).

use std::io::{Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::messages::InfoDoc;
use crate::{LinkError, DEFAULT_PATH, DEFAULT_PORT};

/// `host`, `host:port`, `[v6]`, `[v6]:port` or a bare IPv6 address, as the
/// user typed it (port. of macOS `HubBrowser.parseAddress`). None when it
/// cannot be an address.
pub fn parse_address(text: &str) -> Option<(String, u16)> {
    let t = text.trim();
    if t.is_empty() || t.contains(char::is_whitespace) || t.contains('/') {
        return None;
    }
    if let Some(rest) = t.strip_prefix('[') {
        let (host, tail) = rest.split_once(']')?;
        if host.is_empty() {
            return None;
        }
        if tail.is_empty() {
            return Some((host.to_owned(), DEFAULT_PORT));
        }
        let port = tail.strip_prefix(':')?.parse::<u16>().ok()?;
        return Some((host.to_owned(), port));
    }
    if t.matches(':').count() > 1 {
        return Some((t.to_owned(), DEFAULT_PORT));
    }
    if let Some((host, port)) = t.rsplit_once(':') {
        let port = port.parse::<u16>().ok()?;
        return if host.is_empty() { None } else { Some((host.to_owned(), port)) };
    }
    Some((t.to_owned(), DEFAULT_PORT))
}

fn bracket(host: &str) -> String {
    if host.contains(':') {
        format!("[{host}]")
    } else {
        host.to_owned()
    }
}

/// `ws://host:port/path` (`path` defaults to `/dspi/v1`).
pub fn ws_url(host: &str, port: u16, path: Option<&str>) -> String {
    let path = path.filter(|p| p.starts_with('/')).unwrap_or(DEFAULT_PATH);
    format!("ws://{}:{port}{path}", bracket(host))
}

/// `GET http://host:port/dspi/v1/info` (plain HTTP/1.1, no redirects).
pub fn fetch_info(host: &str, port: u16, timeout: Duration) -> Result<InfoDoc, LinkError> {
    let io = |e: std::io::Error| LinkError::Io(e.to_string());
    let addr = (host, port).to_socket_addrs().map_err(io)?.next().ok_or_else(|| LinkError::Io("no address".into()))?;
    let mut s = TcpStream::connect_timeout(&addr, timeout).map_err(io)?;
    s.set_read_timeout(Some(timeout)).map_err(io)?;
    s.set_write_timeout(Some(timeout)).map_err(io)?;
    write!(
        s,
        "GET {DEFAULT_PATH}/info HTTP/1.1\r\nHost: {}:{port}\r\nAccept: application/json\r\nConnection: close\r\n\r\n",
        bracket(host)
    )
    .map_err(io)?;
    let mut body = Vec::new();
    s.take(256 * 1024).read_to_end(&mut body).map_err(io)?;
    let text = String::from_utf8_lossy(&body);
    let (head, rest) = text.split_once("\r\n\r\n").ok_or_else(|| LinkError::Protocol("no HTTP header".into()))?;
    let status = head.split_whitespace().nth(1).unwrap_or("");
    if status != "200" {
        return Err(LinkError::Protocol(format!("info endpoint answered {status}")));
    }
    let json =
        if head.to_ascii_lowercase().contains("transfer-encoding: chunked") { dechunk(rest) } else { rest.to_owned() };
    serde_json::from_str(&json).map_err(|e| LinkError::Protocol(e.to_string()))
}

fn dechunk(s: &str) -> String {
    let mut out = String::new();
    let mut rest = s;
    while let Some((size, tail)) = rest.split_once("\r\n") {
        let Ok(n) = usize::from_str_radix(size.trim(), 16) else { break };
        if n == 0 || tail.len() < n {
            break;
        }
        out.push_str(&tail[..n]);
        rest = tail[n..].trim_start_matches("\r\n");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_parse_like_the_macos_console() {
        assert_eq!(parse_address("192.168.1.20"), Some(("192.168.1.20".into(), 11915)));
        assert_eq!(parse_address(" studio.local:8080 "), Some(("studio.local".into(), 8080)));
        assert_eq!(parse_address("[fe80::1]:11916"), Some(("fe80::1".into(), 11916)));
        assert_eq!(parse_address("[fe80::1]"), Some(("fe80::1".into(), 11915)));
        assert_eq!(parse_address("fe80::1"), Some(("fe80::1".into(), 11915)));
        assert_eq!(parse_address(""), None);
        assert_eq!(parse_address(":80"), None);
        assert_eq!(parse_address("host:port"), None);
        assert_eq!(parse_address("host:99999"), None);
        assert_eq!(parse_address("a b"), None);
    }

    #[test]
    fn urls() {
        assert_eq!(ws_url("10.0.2.2", 11915, None), "ws://10.0.2.2:11915/dspi/v1");
        assert_eq!(ws_url("fe80::1", 1, Some("/x")), "ws://[fe80::1]:1/x");
        assert_eq!(dechunk("4\r\nabcd\r\n2\r\nef\r\n0\r\n\r\n"), "abcdef");
    }
}

//! Binary data-plane frames (DSPi Link spec section 8).
//!
//! Every frame starts with `[type u8][flags u8][tag u16 LE]`. Reserved bytes
//! and flags are written as 0 and ignored on receipt (spec 10.4); unknown
//! frame types decode to [`Frame::Unknown`] so a receiver can drop them.
//! Port of DSPi-Console `release/v1.1.6` `Link/LinkFrames.swift` (codec only).

/// Frame type bytes (spec 8).
pub mod kind {
    pub const CMD_REQUEST: u8 = 0x01;
    pub const CMD_RESPONSE: u8 = 0x81;
    pub const NOTIFY: u8 = 0x02;
    pub const POLL: u8 = 0x03;
    pub const FWDATA: u8 = 0x04;
    pub const FWDATA_ACK: u8 = 0x84;
    pub const RESYNC: u8 = 0x05;
}

/// CMD response status (spec 8.1). 0x00..0x07 are the firmware's
/// `CTRL_STATUS_*`, 0x80.. are Link-level.
pub mod status {
    pub const OK: u8 = 0x00;
    pub const BUSY: u8 = 0x01;
    pub const ERROR: u8 = 0x02;
    pub const BLOCKED: u8 = 0x03;
    pub const BULK_LOCKED: u8 = 0x04;
    pub const CRC_ERROR: u8 = 0x05;
    pub const OVERSIZE: u8 = 0x06;
    pub const FRAME_ERROR: u8 = 0x07;
    pub const NO_DEVICE: u8 = 0x80;
    pub const DENIED: u8 = 0x81;
    pub const TIMEOUT: u8 = 0x82;
    pub const TOO_LARGE: u8 = 0x83;
    pub const RATE_LIMITED: u8 = 0x84;
    pub const LOCKED: u8 = 0x85;
    pub const UNSUPPORTED: u8 = 0x86;
    pub const BAD_FRAME: u8 = 0x87;

    /// Short name, for logs and errors.
    pub fn name(s: u8) -> &'static str {
        match s {
            OK => "OK",
            BUSY => "BUSY",
            ERROR => "ERROR",
            BLOCKED => "BLOCKED",
            BULK_LOCKED => "BULK_LOCKED",
            CRC_ERROR => "CRC_ERROR",
            OVERSIZE => "OVERSIZE",
            FRAME_ERROR => "FRAME_ERROR",
            NO_DEVICE => "NO_DEVICE",
            DENIED => "DENIED",
            TIMEOUT => "TIMEOUT",
            TOO_LARGE => "TOO_LARGE",
            RATE_LIMITED => "RATE_LIMITED",
            LOCKED => "LOCKED",
            UNSUPPORTED => "UNSUPPORTED",
            BAD_FRAME => "BAD_FRAME",
            _ => "UNKNOWN",
        }
    }
}

/// Transfer direction of a tunnelled vendor request (spec 8.2 `dir`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Dir {
    /// Host to device, bmRequestType 0x41.
    Set = 0,
    /// Device to host, bmRequestType 0xC1.
    Get = 1,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CmdRequest {
    pub tag: u16,
    pub handle: u8,
    pub dir: Dir,
    pub b_request: u8,
    pub w_value: u16,
    pub w_index: u16,
    /// SET: the payload length (ignored when encoding, `payload.len()` is
    /// written). GET: the bytes requested.
    pub w_length: u16,
    /// SET only.
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CmdResponse {
    pub tag: u16,
    pub status: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct NotifyFrame {
    /// Link sequence number, per session.
    pub seq: u16,
    pub handle: u8,
    /// Session the hub attributes the change to; 0 = unknown.
    pub origin: u16,
    /// Verbatim v2 notification packet (starts with 0x02).
    pub packet: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PollFrame {
    pub seq: u16,
    pub handle: u8,
    pub slot: u8,
    pub payload: Vec<u8>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Frame {
    CmdRequest(CmdRequest),
    CmdResponse(CmdResponse),
    Notify(NotifyFrame),
    Poll(PollFrame),
    FwData {
        xfer: u16,
        offset: u32,
        data: Vec<u8>,
    },
    FwDataAck {
        xfer: u16,
        offset: u32,
        status: u8,
    },
    Resync {
        handle: u8,
        reason: u8,
    },
    /// A type this build does not know (spec 8: receivers drop it; a hub
    /// answers an unknown request type with UNSUPPORTED).
    Unknown {
        kind: u8,
        tag: u16,
    },
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum FrameError {
    #[error("frame too short ({0} bytes)")]
    TooShort(usize),
    #[error("frame length field {declared} exceeds the {available} bytes present")]
    BadLength { declared: usize, available: usize },
    #[error("bad direction byte {0}")]
    BadDirection(u8),
}

fn u16_at(b: &[u8], i: usize) -> u16 {
    u16::from_le_bytes([b[i], b[i + 1]])
}

fn u32_at(b: &[u8], i: usize) -> u32 {
    u32::from_le_bytes([b[i], b[i + 1], b[i + 2], b[i + 3]])
}

fn header(out: &mut Vec<u8>, kind: u8, tag: u16) {
    out.push(kind);
    out.push(0);
    out.extend_from_slice(&tag.to_le_bytes());
}

impl Frame {
    /// The wire bytes.
    pub fn encode(&self) -> Vec<u8> {
        let mut o = Vec::new();
        match self {
            Frame::CmdRequest(r) => {
                header(&mut o, kind::CMD_REQUEST, r.tag);
                o.extend_from_slice(&[r.handle, r.dir as u8, r.b_request, 0]);
                o.extend_from_slice(&r.w_value.to_le_bytes());
                o.extend_from_slice(&r.w_index.to_le_bytes());
                let len = match r.dir {
                    Dir::Set => r.payload.len() as u16,
                    Dir::Get => r.w_length,
                };
                o.extend_from_slice(&len.to_le_bytes());
                if r.dir == Dir::Set {
                    o.extend_from_slice(&r.payload);
                }
            }
            Frame::CmdResponse(r) => {
                header(&mut o, kind::CMD_RESPONSE, r.tag);
                o.extend_from_slice(&[r.status, 0]);
                o.extend_from_slice(&(r.payload.len() as u16).to_le_bytes());
                o.extend_from_slice(&r.payload);
            }
            Frame::Notify(n) => {
                header(&mut o, kind::NOTIFY, n.seq);
                o.extend_from_slice(&[n.handle, 0]);
                o.extend_from_slice(&n.origin.to_le_bytes());
                o.extend_from_slice(&n.packet);
            }
            Frame::Poll(p) => {
                header(&mut o, kind::POLL, p.seq);
                o.extend_from_slice(&[p.handle, p.slot]);
                o.extend_from_slice(&(p.payload.len() as u16).to_le_bytes());
                o.extend_from_slice(&p.payload);
            }
            Frame::FwData { xfer, offset, data } => {
                header(&mut o, kind::FWDATA, *xfer);
                o.extend_from_slice(&offset.to_le_bytes());
                o.extend_from_slice(data);
            }
            Frame::FwDataAck { xfer, offset, status } => {
                header(&mut o, kind::FWDATA_ACK, *xfer);
                o.extend_from_slice(&offset.to_le_bytes());
                o.push(*status);
            }
            Frame::Resync { handle, reason } => {
                header(&mut o, kind::RESYNC, 0);
                o.extend_from_slice(&[*handle, *reason]);
            }
            Frame::Unknown { kind, tag } => header(&mut o, *kind, *tag),
        }
        o
    }

    /// Parse one binary WebSocket message. Never panics on hostile input.
    pub fn decode(b: &[u8]) -> Result<Frame, FrameError> {
        if b.len() < 4 {
            return Err(FrameError::TooShort(b.len()));
        }
        let (kind, tag) = (b[0], u16_at(b, 2));
        let need = |n: usize| if b.len() < n { Err(FrameError::TooShort(b.len())) } else { Ok(()) };
        Ok(match kind {
            kind::CMD_REQUEST => {
                need(14)?;
                let dir = match b[5] {
                    0 => Dir::Set,
                    1 => Dir::Get,
                    d => return Err(FrameError::BadDirection(d)),
                };
                let w_length = u16_at(b, 12);
                let payload = if dir == Dir::Set {
                    let end = 14 + w_length as usize;
                    if end > b.len() {
                        return Err(FrameError::BadLength { declared: w_length as usize, available: b.len() - 14 });
                    }
                    b[14..end].to_vec()
                } else {
                    Vec::new()
                };
                Frame::CmdRequest(CmdRequest {
                    tag,
                    handle: b[4],
                    dir,
                    b_request: b[6],
                    w_value: u16_at(b, 8),
                    w_index: u16_at(b, 10),
                    w_length,
                    payload,
                })
            }
            kind::CMD_RESPONSE => {
                need(8)?;
                let len = u16_at(b, 6) as usize;
                if 8 + len > b.len() {
                    return Err(FrameError::BadLength { declared: len, available: b.len() - 8 });
                }
                Frame::CmdResponse(CmdResponse { tag, status: b[4], payload: b[8..8 + len].to_vec() })
            }
            kind::NOTIFY => {
                need(8)?;
                Frame::Notify(NotifyFrame { seq: tag, handle: b[4], origin: u16_at(b, 6), packet: b[8..].to_vec() })
            }
            kind::POLL => {
                need(8)?;
                let len = u16_at(b, 6) as usize;
                if 8 + len > b.len() {
                    return Err(FrameError::BadLength { declared: len, available: b.len() - 8 });
                }
                Frame::Poll(PollFrame { seq: tag, handle: b[4], slot: b[5], payload: b[8..8 + len].to_vec() })
            }
            kind::FWDATA => {
                need(8)?;
                Frame::FwData { xfer: tag, offset: u32_at(b, 4), data: b[8..].to_vec() }
            }
            kind::FWDATA_ACK => {
                need(9)?;
                Frame::FwDataAck { xfer: tag, offset: u32_at(b, 4), status: b[8] }
            }
            kind::RESYNC => {
                need(6)?;
                Frame::Resync { handle: b[4], reason: b[5] }
            }
            other => Frame::Unknown { kind: other, tag },
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spec_example_11_1_round_trips() {
        let f = Frame::CmdRequest(CmdRequest {
            tag: 7,
            handle: 0,
            dir: Dir::Set,
            b_request: 0xD2,
            w_value: 0,
            w_index: 2,
            w_length: 4,
            payload: (-20.0f32).to_le_bytes().to_vec(),
        });
        let bytes = f.encode();
        assert_eq!(bytes, [0x01, 0, 7, 0, 0, 0, 0xD2, 0, 0, 0, 2, 0, 4, 0, 0, 0, 0xA0, 0xC1]);
        assert_eq!(Frame::decode(&bytes).unwrap(), f);
    }

    #[test]
    fn hostile_input_never_panics() {
        for len in 0..40 {
            for fill in [0u8, 0xFF, 0x01, 0x81, 0x02, 0x03, 0x04, 0x84, 0x05] {
                let mut b = vec![fill; len];
                if len > 0 {
                    b[0] = fill;
                }
                let _ = Frame::decode(&b);
            }
        }
        assert!(matches!(Frame::decode(&[0x81, 0, 1, 0, 0, 0, 0xFF, 0xFF]), Err(FrameError::BadLength { .. })));
        assert_eq!(Frame::decode(&[0x7E, 0, 9, 0]).unwrap(), Frame::Unknown { kind: 0x7E, tag: 9 });
    }
}

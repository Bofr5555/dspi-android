//! `DspiError`: the core's `bool` / `0xFF` / `-1` / status-byte conventions
//! (`core_spec.md` §26) as one typed error, so Kotlin never tests magic numbers.

use dspi_core::usb::UsbError;

#[derive(Debug, Clone, PartialEq, thiserror::Error, uniffi::Error)]
pub enum DspiError {
    /// No device is open, or it went away (the core marks itself disconnected).
    #[error("device not connected")]
    Disconnected,
    /// A USB transfer failed (timeout, stall, short read, flash blackout).
    #[error("USB transfer failed: {msg}")]
    Transfer { msg: String },
    /// The device answered but refused: the raw status byte (`PRESET_*`,
    /// `PIN_CONFIG_*`, `FLASH_*`, or a command-specific code such as
    /// `test_dac_mute`'s 3 = feature disabled). Decode in Kotlin with the
    /// named constants in `DspiConstants`.
    #[error("device refused the request (status {status})")]
    Refused { status: u8 },
    /// Bad argument (index out of range, malformed JSON op, ...).
    #[error("invalid argument: {msg}")]
    InvalidArgument { msg: String },
    /// The firmware or transport does not have this feature (e.g. no siggen /
    /// RTA / CS; reserved for transports such as DSPi-Link).
    #[error("not supported by the connected firmware")]
    Unsupported,
    /// DSPi Link: another console holds the device lock (status LOCKED, or
    /// `lock.acquire` refused). macOS wording: "Another console is applying changes".
    #[error("another console is applying changes")]
    Locked,
    /// DSPi Link: this session's role may not send that command (status DENIED).
    #[error("this console's role on the hub does not allow that")]
    Denied,
    /// DSPi Link session failure (connect, handshake, pairing, closed).
    /// `code`: the spec 7.1 error code (`denied`, `rate_limited`, ...) or
    /// `io`, `handshake`, `protocol`, `timeout`, `closed`.
    #[error("DSPi Link: {msg}")]
    Link { code: String, msg: String },
}

impl From<dspi_link::LinkError> for DspiError {
    fn from(e: dspi_link::LinkError) -> Self {
        use dspi_link::LinkError as L;
        let code = match &e {
            L::Io(_) => "io".to_owned(),
            L::Handshake(_) => "handshake".to_owned(),
            L::Protocol(_) => "protocol".to_owned(),
            L::Timeout => "timeout".to_owned(),
            L::Closed { .. } => "closed".to_owned(),
            L::Rejected { code, .. } => code.clone(),
        };
        let msg = match &e {
            L::Rejected { code, msg } if code == "denied" && msg.is_empty() => "the hub refused".to_owned(),
            L::Rejected { msg, code } => {
                if msg.is_empty() {
                    code.clone()
                } else {
                    msg.clone()
                }
            }
            other => other.to_string(),
        };
        DspiError::Link { code, msg }
    }
}

impl From<UsbError> for DspiError {
    fn from(e: UsbError) -> Self {
        match e {
            UsbError::NotConnected => DspiError::Disconnected,
            UsbError::Rusb(rusb::Error::NoDevice) => DspiError::Disconnected,
            // DSPi Link statuses (link.rs `map_status`); not seen on USB control transfers
            UsbError::Rusb(rusb::Error::Busy) => DspiError::Locked,
            UsbError::Rusb(rusb::Error::Access) => DspiError::Denied,
            UsbError::InvalidArgument => DspiError::InvalidArgument { msg: e.to_string() },
            other => DspiError::Transfer { msg: other.to_string() },
        }
    }
}

pub type Result<T> = std::result::Result<T, DspiError>;

/// Map a core status byte (0 = success) to `Result<()>`.
pub(crate) fn status(code: u8) -> Result<()> {
    if code == 0 {
        Ok(())
    } else {
        Err(DspiError::Refused { status: code })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn usb_errors_map_to_typed_errors() {
        assert_eq!(DspiError::from(UsbError::NotConnected), DspiError::Disconnected);
        assert_eq!(DspiError::from(UsbError::Rusb(rusb::Error::NoDevice)), DspiError::Disconnected);
        assert!(matches!(DspiError::from(UsbError::InvalidArgument), DspiError::InvalidArgument { .. }));
        assert!(matches!(DspiError::from(UsbError::Rusb(rusb::Error::Timeout)), DspiError::Transfer { .. }));
        assert!(matches!(DspiError::from(UsbError::Rusb(rusb::Error::Pipe)), DspiError::Transfer { .. }));
        assert_eq!(DspiError::from(UsbError::Rusb(rusb::Error::Busy)), DspiError::Locked);
        assert_eq!(DspiError::from(UsbError::Rusb(rusb::Error::Access)), DspiError::Denied);
        let e = DspiError::from(dspi_link::LinkError::Rejected { code: "rate_limited".into(), msg: "".into() });
        assert_eq!(e, DspiError::Link { code: "rate_limited".into(), msg: "rate_limited".into() });
        assert!(matches!(
            DspiError::from(UsbError::ShortRead { expected: 4, actual: 1 }),
            DspiError::Transfer { msg } if msg.contains("expected 4")
        ));
    }

    #[test]
    fn status_bytes_map_to_refused() {
        assert_eq!(status(0), Ok(()));
        assert_eq!(status(dspi_core::protocol::PRESET_ERR_SLOT_EMPTY), Err(DspiError::Refused { status: 2 }));
        assert_eq!(status(dspi_core::protocol::PIN_CONFIG_PIN_IN_USE), Err(DspiError::Refused { status: 2 }));
        assert_eq!(status(dspi_core::protocol::FLASH_ERR_CRC), Err(DspiError::Refused { status: 3 }));
        assert_eq!(status(0xFF), Err(DspiError::Refused { status: 0xFF }));
    }
}

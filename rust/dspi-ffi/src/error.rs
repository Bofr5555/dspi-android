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
}

impl From<UsbError> for DspiError {
    fn from(e: UsbError) -> Self {
        match e {
            UsbError::NotConnected => DspiError::Disconnected,
            UsbError::Rusb(rusb::Error::NoDevice) => DspiError::Disconnected,
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

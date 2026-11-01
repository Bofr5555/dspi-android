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
#[allow(dead_code)]
pub(crate) fn status(code: u8) -> Result<()> {
    if code == 0 {
        Ok(())
    } else {
        Err(DspiError::Refused { status: code })
    }
}

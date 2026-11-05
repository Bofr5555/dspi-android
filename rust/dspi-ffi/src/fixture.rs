//! Device captures -> `DspState`, through the core's own parsers.
//!
//! Used by `examples/dump_state.rs` (writes `fixtures/dspstate_v32.json`,
//! which BRIDGE seeds `FakeDspiCore` from) and by the fixture tests. Not part
//! of the UniFFI surface.

use dspi_core::{protocol, state, types, wirecompat};

use crate::records::DspState;

/// What `fetch_all` would hold after reading a device: the REQ_GET_PLATFORM
/// reply (`platform`, as `fetch_platform` applies it) and the V32 bulk image
/// (`protocol::decode_bulk`), with `compat` = COMPAT_OK. The per-item reads
/// `fetch_all` does afterwards (Core 1 mode, DAC mute, control interfaces,
/// preset directory and names) are not part of the capture and keep their
/// defaults.
pub fn state_from_capture(platform: &[u8], bulk: &[u8]) -> Result<DspState, String> {
    let mut s = state::DspState::default();
    let info = protocol::parse_platform(platform).ok_or("platform reply shorter than 4 bytes")?;
    s.platform_id = info.platform_id;
    s.num_output_channels = info.num_output_channels.min(types::MAX_OUTPUTS as u8);
    s.fw_major = info.fw_major;
    s.fw_minor = info.fw_minor;
    s.fw_patch = info.fw_patch;
    s.fw_beta = info.fw_beta;
    protocol::decode_bulk(bulk, &mut s).map_err(|e| format!("bulk image rejected: {e:?}"))?;
    s.compat = state::COMPAT_OK;
    Ok(DspState::from(&s))
}

/// Like [state_from_capture], but through the version-tolerant path
/// `fetch_all` takes (core patch 0003, `wirecompat::decode_bulk_compat`):
/// an older or newer image is decoded for display and `compat` is set the
/// way `fetch_all` sets it (OK, TOO_OLD or TOO_NEW).
pub fn state_from_capture_compat(platform: &[u8], bulk: &[u8]) -> Result<DspState, String> {
    let mut s = state::DspState::default();
    let info = protocol::parse_platform(platform).ok_or("platform reply shorter than 4 bytes")?;
    s.platform_id = info.platform_id;
    s.num_output_channels = info.num_output_channels.min(types::MAX_OUTPUTS as u8);
    s.fw_major = info.fw_major;
    s.fw_minor = info.fw_minor;
    s.fw_patch = info.fw_patch;
    s.fw_beta = info.fw_beta;
    let read = wirecompat::decode_bulk_compat(bulk, &mut s).map_err(|e| format!("bulk image rejected: {e:?}"))?;
    s.compat = match read {
        wirecompat::WIRE_READ_EXACT => state::COMPAT_OK,
        wirecompat::WIRE_READ_NEWER => state::COMPAT_FIRMWARE_TOO_NEW,
        _ => state::COMPAT_FIRMWARE_TOO_OLD,
    };
    Ok(DspState::from(&s))
}

/// The record as pretty JSON with the Kotlin (camelCase) field names.
pub fn to_json(s: &DspState) -> String {
    let mut out = serde_json::to_string_pretty(s).expect("DspState serialises");
    out.push('\n');
    out
}

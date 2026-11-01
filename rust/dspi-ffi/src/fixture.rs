//! Device captures -> `DspState`, through the core's own parsers.
//!
//! Used by `examples/dump_state.rs` (writes `fixtures/dspstate_v32.json`,
//! which BRIDGE seeds `FakeDspiCore` from) and by the fixture tests. Not part
//! of the UniFFI surface.

use dspi_core::{protocol, state, types};

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

/// The record as pretty JSON with the Kotlin (camelCase) field names.
pub fn to_json(s: &DspState) -> String {
    let mut out = serde_json::to_string_pretty(s).expect("DspState serialises");
    out.push('\n');
    out
}

//! dspi-ffi — UniFFI glue between the official DSPi Rust core
//! (`dspi-core`, DSPi-Console-Linux `core/`, pinned submodule) and Kotlin.
//!
//! Flavour: UniFFI **proc-macros** (no UDL). The records mirror the core's
//! Rust structs, so keeping the interface next to the Rust definitions (and
//! letting `convert.rs` make the compiler check the mirror) is cheaper and
//! safer across submodule bumps than a hand-maintained UDL file.
//!
//! Kotlin bindings: `uniffi-bindgen generate --library libdspi_ffi.so
//! --language kotlin` (driven by the `:ffi` Gradle module). Package
//! `se.bofr.dspi.ffi.uniffi` (see uniffi.toml). Kotlin code outside `:ffi`
//! never sees these types: it uses `:core-api`'s `DspiCoreApi`.

uniffi::setup_scaffolding!();

mod callbacks;
mod convert;
mod core;
mod error;
mod records;

pub use crate::callbacks::{DeviceEvents, NotifyListener, RtaListener};
pub use crate::core::DspiCore;
pub use crate::error::DspiError;
pub use crate::records::*;

use dspi_core::{dsp_math, protocol};

// ═══ Free functions (stateless; no device needed) ═══════════════════

/// Magnitude in dB at one frequency for a chain of bands (`dspi_compute_response`).
#[uniffi::export]
pub fn compute_response(filters: Vec<FilterParams>, freq: f32) -> f32 {
    let f: Vec<_> = filters.into_iter().map(Into::into).collect();
    if f.is_empty() {
        return 0.0;
    }
    dsp_math::response_at(freq, &f)
}

/// 201-point magnitude curve, dB, 10 Hz..20 kHz log-spaced.
#[uniffi::export]
pub fn compute_magnitude_curve(filters: Vec<FilterParams>) -> Vec<f64> {
    let f: Vec<_> = filters.into_iter().map(Into::into).collect();
    dsp_math::compute_magnitude_curve(&f).to_vec()
}

/// 201-point phase curve in degrees on the same grid; continuous when
/// `unwrap`, else wrapped to ±180°.
#[uniffi::export]
pub fn compute_phase_curve(filters: Vec<FilterParams>, unwrap: bool) -> Vec<f64> {
    let f: Vec<_> = filters.into_iter().map(Into::into).collect();
    dsp_math::compute_phase_curve(&f, unwrap).to_vec()
}

/// Frequency (Hz) of curve point `i` (0..200), the x axis of the curves above.
#[uniffi::export]
pub fn curve_freq(i: u32) -> f64 {
    dsp_math::curve_freq(i as usize)
}

/// Check a `.dspipreset` document: JSON {ok, error, name, platform, firmware,
/// savedUtc} (`dspi_config_inspect`).
#[uniffi::export]
pub fn config_inspect(text: String) -> String {
    let _ = text;
    todo!("dspi-ffi: config_inspect not implemented yet (package FFI)")
}

/// Boards in BOOTSEL on the bus (`dspi_bootloader_counts`). 0/0 on Android.
#[uniffi::export]
pub fn bootloader_counts() -> BootloaderCounts {
    todo!("dspi-ffi: bootloader_counts not implemented yet (package FFI)")
}

/// Version facts of the linked core.
#[uniffi::export]
pub fn core_info() -> CoreInfo {
    CoreInfo {
        ffi_version: env!("CARGO_PKG_VERSION").to_string(),
        wire_format_version: protocol::WIRE_FORMAT_VERSION,
        min_wire_format_version: protocol::MIN_WIRE_FORMAT_VERSION,
        bulk_params_size: protocol::BULK_PARAMS_SIZE as u32,
        magnitude_points: dsp_math::MAGNITUDE_POINTS as u32,
    }
}

/// Route the core's `log` output to logcat (Android) / stderr (host). Call once
/// at process start; later calls are no-ops.
#[uniffi::export]
pub fn init_logging() {
    #[cfg(target_os = "android")]
    android_logger::init_once(
        android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("dspi-core"),
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn core_info_matches_protocol() {
        let i = core_info();
        assert_eq!(i.wire_format_version, 32);
        assert_eq!(i.bulk_params_size, 6136);
        assert_eq!(i.magnitude_points, 201);
    }

    #[test]
    fn flat_curve_is_zero() {
        let c = compute_magnitude_curve(vec![]);
        assert_eq!(c.len(), 201);
        assert!(c.iter().all(|v| v.abs() < 1e-9));
    }

    #[test]
    fn stubs_report_unimplemented() {
        let core = DspiCore::new();
        assert!(matches!(core.fetch_all(), Err(DspiError::Unimplemented { .. })));
        assert_eq!(core.get_state().num_output_channels, 5);
    }
}

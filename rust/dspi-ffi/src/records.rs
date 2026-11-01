//! UniFFI records mirroring the core's `#[repr(C)]` structs field for field.
//!
//! Rule: same struct name, same field names, same order as in
//! `third_party/DSPi-Console-Linux/core/src/{state,types,notify,rta,siggen,stats,monitor}.rs`.
//! Fixed-size C arrays become `Vec`s of the same length; NUL-padded name
//! buffers become `String`s. UniFFI camel-cases the names for Kotlin
//! (`master_volume_db` -> `masterVolumeDb`).
//!
//! Code values (filter types, `COMPAT_*`, `PRESET_*`, …) stay raw integers
//! exactly as in the core; their named constants live in Kotlin
//! (`:core-api` `DspiConstants`). `convert.rs` holds the From impls, which
//! make the compiler check this mirror against the pinned core.

// ── types.rs ──────────────────────────────────────────────────────────

/// One filter band, PEQ or crossover (`types.rs` `FilterParams`).
#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FilterParams {
    /// Firmware filter type (`FILTER_*`): 0..13 PEQ, 32..63 crossover.
    pub filter_type: u8,
    pub bypass: bool,
    pub freq: f32,
    pub q: f32,
    /// Gain in dB; for the Linkwitz Transform, the target frequency fp in Hz.
    pub gain: f32,
    /// Linkwitz Transform target Q (ignored by every other type).
    pub qp: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct InputLockStatus {
    pub state: u8,
    pub clock_mode: u8,
    pub detected_rate: u32,
    pub measured_hz: u32,
    pub rate_ok: bool,
    pub lock_count: u8,
    pub loss_count: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct AdatOutStatus {
    pub enabled: bool,
    pub active: bool,
    pub pin: u8,
    pub rate_ok: bool,
    pub resync_count: u16,
    pub slip_count: u16,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct LgStatus {
    pub enabled: bool,
    pub present: bool,
    /// TV volume 0..100; 0xFF until one has been decoded.
    pub volume: u8,
    pub muted: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UartConfig {
    pub enabled: bool,
    pub tx_pin: u8,
    pub rx_pin: u8,
    pub notify: bool,
    pub baud: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct I2cConfig {
    pub enabled: bool,
    pub sda_pin: u8,
    pub scl_pin: u8,
    pub address: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CtrlIfaceStatus {
    pub uart_last_status: u8,
    pub uart_live: bool,
    pub i2c_last_status: u8,
    pub i2c_live: bool,
    pub protocol_version: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct UpmixStatus {
    pub active: bool,
    pub parked_reason: u8,
    pub correlation: f32,
    pub balance: f32,
    pub center_gain: f32,
    pub ls_gain: f32,
    pub rs_gain: f32,
}

/// `types.rs` `SystemStatus` (REQ_GET_STATUS wValue 9), polled every 60 ms.
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct SystemStatus {
    /// Peak per wire channel, 0..1 (MAX_CHANNELS = 17 entries).
    pub peaks: Vec<f32>,
    pub cpu0: u8,
    pub cpu1: u8,
    /// Sticky clip flags, bit per wire channel.
    pub clip_flags: u32,
    pub active_input_channels: u8,
    pub num_channels: u8,
}

/// `types.rs` `DeviceInfo`; `serial` replaces the `serial`/`serial_len` pair.
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct DeviceInfo {
    pub serial: String,
    pub location_id: u32,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct PresetDirectory {
    pub occupied_mask: u16,
    pub startup_mode: u8,
    pub default_slot: u8,
    pub last_active: u8,
    pub output_config_mode: u8,
    pub master_volume_mode: u8,
}

// ── state.rs ──────────────────────────────────────────────────────────

/// Full mirror of `state.rs` `DspState` (wire format V32).
///
/// Channel-indexed vectors use wire channel indices (`[inputs][outputs]`,
/// MAX_CHANNELS = 17 entries); output-indexed ones have MAX_OUTPUTS = 9,
/// input-indexed MAX_INPUTS = 8. Only the first `num_*` entries are live.
#[derive(Debug, Clone, PartialEq, uniffi::Record, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DspState {
    // Device / firmware
    pub platform_id: u8,
    pub num_channels: u8,
    pub num_input_channels: u8,
    pub num_output_channels: u8,
    pub format_version: u8,
    pub fw_major: u8,
    pub fw_minor: u8,
    pub fw_patch: u8,
    pub fw_beta: u8,
    /// `COMPAT_*` (0 unknown, 1 ok, 2 firmware too old, 3 too new).
    pub compat: u8,
    // Global
    pub preamp_db: f32,
    pub bypass: bool,
    pub input_preamp_db: Vec<f32>,
    // Volume
    pub master_volume_db: f32,
    pub master_volume_mode: u8,
    pub user_volume_db: f32,
    pub user_mute: bool,
    // Loudness
    pub loudness_enabled: bool,
    pub loudness_ref_spl: f32,
    pub loudness_intensity: f32,
    pub loudness_output_mask: u16,
    // Crossfeed
    pub crossfeed_enabled: bool,
    pub crossfeed_preset: u8,
    pub crossfeed_freq: f32,
    pub crossfeed_feed: f32,
    pub crossfeed_itd: bool,
    pub crossfeed_output_pair_mask: u8,
    /// Outputs of each crossfeed pair, 4 bytes (K340 firmware fork; zero
    /// from other firmware). Byte p: 0 = default (2p, 2p+1), else
    /// `(right << 4) | left`, 0-based output indices.
    pub crossfeed_pair_map: Vec<u8>,
    // Delays (wire channel)
    pub channel_delays: Vec<f32>,
    // Matrix [input][output]
    pub matrix_routing: Vec<Vec<bool>>,
    pub matrix_gain: Vec<Vec<f32>>,
    pub matrix_invert: Vec<Vec<bool>>,
    // Outputs
    pub output_enabled: Vec<bool>,
    pub output_muted: Vec<bool>,
    pub output_gain_db: Vec<f32>,
    pub output_delay_ms: Vec<f32>,
    // Limiter
    pub limiter_enabled: Vec<bool>,
    pub limiter_link_group: Vec<u8>,
    pub limiter_threshold_db: Vec<f32>,
    pub limiter_release_ms: Vec<f32>,
    // Pins
    pub num_pin_outputs: u8,
    pub output_pins: Vec<u8>,
    pub output_config_mode: u8,
    // EQ [wire channel][band]
    pub filters: Vec<Vec<FilterParams>>,
    pub xover: Vec<Vec<FilterParams>>,
    // Names (wire channel)
    pub channel_names: Vec<String>,
    // Leveller
    pub leveller_enabled: bool,
    pub leveller_speed: u8,
    pub leveller_lookahead: bool,
    pub leveller_amount: f32,
    pub leveller_max_gain_db: f32,
    pub leveller_gate_db: f32,
    pub leveller_detector_mask: u8,
    pub leveller_apply_mask: u8,
    // Input / misc
    pub input_source: u8,
    pub lg_sound_sync_enabled: bool,
    pub spdif_inputs_enabled: u8,
    pub adat_input_enabled: bool,
    pub spdif_rx_pins: Vec<u8>,
    pub i2s_rx_pins: Vec<u8>,
    pub i2s_input_channels: u8,
    pub i2s_input_rate: u8,
    pub i2s_clock_mode: u8,
    pub adat_input_pin: u8,
    pub adat_input_clock_mode: u8,
    // Psychoacoustic bass
    pub psybass_enabled: bool,
    pub psybass_output_mask: u16,
    pub psybass_cutoff_hz: f32,
    pub psybass_harmonics_db: f32,
    pub psybass_drive_db: f32,
    pub psybass_character_pct: f32,
    pub psybass_original_db: f32,
    // Upmixer / subharm / tube, by *_PARAM_* id
    pub upmix: Vec<f32>,
    pub subharm: Vec<f32>,
    pub subharm_solo: bool,
    pub tube: Vec<f32>,
    // Output types and clocks
    pub output_types: Vec<u8>,
    pub i2s_bck_pin: u8,
    pub i2s_bck_pin_slave: u8,
    pub i2s_clock_pin_mode: u8,
    pub mck_enabled: bool,
    pub mck_pin: u8,
    pub mck_multiplier: u8,
    pub adat_out_enabled: bool,
    pub adat_out_pin: u8,
    // DAC hardware mute
    pub dac_mute_supported: bool,
    pub dac_mute_enabled: bool,
    pub dac_mute_active_low: bool,
    pub dac_mute_pin: u8,
    pub dac_mute_hold_ms: u16,
    pub dac_mute_release_ms: u16,
    // Control interfaces
    pub ctrl_iface_supported: bool,
    pub uart: UartConfig,
    pub i2c: I2cConfig,
    pub ctrl_status: CtrlIfaceStatus,
    // Core 1
    pub core1_mode: u8,
    // Presets
    pub preset_occupied: u16,
    pub preset_names: Vec<String>,
    pub active_preset_slot: u8,
    pub preset_startup_mode: u8,
    pub preset_default_slot: u8,
    // Raw bulk image (BULK_PARAMS_SIZE = 6136 bytes)
    pub bulk_valid: bool,
    pub bulk_raw: Vec<u8>,
}

// ── notify.rs ─────────────────────────────────────────────────────────

/// What `process_notifications` changed: `NOTIFY_*` flags and a bitmask of
/// wire channels whose filters changed.
#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct NotifyResult {
    pub flags: u32,
    pub filter_channels: u32,
}

// ── rta.rs ────────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct RtaRequest {
    /// `RTA_TAP_INPUT` (0) / `RTA_TAP_OUTPUT` (1).
    pub tap: u8,
    /// Channel mask within the tap; 0 releases the analyser.
    pub mask: u16,
    pub wants_bins: bool,
    pub fft_order: u8,
    pub avg_ms: u16,
    pub peak_decay_db_s: u8,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct RtaCapsInfo {
    pub supported: bool,
    pub input_channels: u8,
    pub output_channels: u8,
    pub order_min: u8,
    pub order_max: u8,
    pub order_default: u8,
    pub bass_bands: u8,
    pub max_bands: u8,
    pub dynamic_range_db: u8,
    pub bass_dynamic_range_db: u16,
    /// Band centre frequencies, RTA_MAX_BANDS = 37 entries.
    pub centres: Vec<u16>,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct RtaChannelFrame {
    pub channel: u8,
    pub n_bands: u8,
    pub silent: bool,
    pub avg_db: Vec<f32>,
    pub peak_db: Vec<f32>,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct RtaSnapshot {
    pub version: u32,
    pub telemetry_version: u32,
    pub tap: u8,
    pub count: u8,
    /// RTA_MAX_CHANNELS = 16 entries; the first `count` are the analysed
    /// channels (packed, ascending channel), the rest are defaults.
    pub frames: Vec<RtaChannelFrame>,
    /// Bands that carry a level (`rta::populated_bands`): bit b = band b
    /// (RTA_MAX_BANDS = 37); the bass bank always, an FFT band only when a bin
    /// centre falls inside its edges, none below `first_band`.
    pub populated: u64,
    pub bins_count: u16,
    pub bins_channel: u8,
    pub bins_sample_rate: u32,
    /// First `bins_count` of RTA_MAX_BINS = 512 entries are valid.
    pub bins_db: Vec<f32>,
    pub running: bool,
    pub live_count: u8,
    pub live_mask: u16,
    pub frames_per_s: u16,
    pub busy_us_per_s: u16,
    pub last_frame_us: u16,
    pub bass_busy_us_per_s: u16,
    pub sample_rate: u32,
    pub first_band: u8,
    pub fft_order: u8,
    pub config_rejected: bool,
    pub refresh_interval_s: f32,
}

// ── siggen.rs ─────────────────────────────────────────────────────────

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct SiggenConfig {
    pub signal_type: u8,
    pub channel_mask: u16,
    pub invert_mask: u16,
    /// `SIGGEN_FLAG_*`.
    pub flags: u8,
    pub level_db: f32,
    pub duration_ms: u32,
    pub repeat: u16,
    pub gap_ms: u16,
    /// Type-specific parameters, exactly 4.
    pub p: Vec<f32>,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct SiggenStatus {
    pub state: u8,
    pub signal_type: u8,
    pub active_channel: u8,
    pub elapsed_ms: u32,
    pub cycles_done: u16,
    pub stop_reason: u8,
    pub current_freq: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct SiggenParam {
    pub semantic: u8,
    pub min: f32,
    pub max: f32,
    pub def: f32,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct SiggenTypeInfo {
    pub id: u8,
    pub name: String,
    pub timing: u8,
    /// Exactly 4.
    pub params: Vec<SiggenParam>,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct SiggenCaps {
    pub supported: bool,
    pub type_count: u8,
    pub output_channels: u8,
    pub multitone_max: u8,
    pub valid_mask: u16,
    /// SIGGEN_TYPE_COUNT = 15 entries; first `type_count` valid.
    pub types: Vec<SiggenTypeInfo>,
}

// ── stats.rs ──────────────────────────────────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct SpdifBufferStats {
    pub free: u8,
    pub prepared: u8,
    pub playing: u8,
    pub fill_pct: u8,
    pub min_pct: u8,
    pub max_pct: u8,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct PdmBufferStats {
    pub dma_fill_pct: u8,
    pub dma_min_pct: u8,
    pub dma_max_pct: u8,
    pub ring_fill_pct: u8,
    pub ring_min_pct: u8,
    pub ring_max_pct: u8,
}

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct BufferStats {
    pub num_spdif: u8,
    pub pdm_active: bool,
    pub streaming: bool,
    pub sequence: u16,
    /// Exactly 4.
    pub spdif: Vec<SpdifBufferStats>,
    pub pdm: PdmBufferStats,
}

#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct SpdifRxStatus {
    pub state: u8,
    pub input_source: u8,
    pub lock_count: u8,
    pub loss_count: u8,
    pub sample_rate: u32,
    pub parity_errors: u32,
    pub fifo_fill_pct: u16,
    pub lib_state: u8,
    pub stable_callbacks: u8,
    pub lost_callbacks: u8,
}

// ── monitor.rs (typed replacement of the C ABI's TSV text) ───────────

#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct MonitorEntry {
    pub id: u64,
    /// Host time, ms since the Unix epoch.
    pub time_ms: i64,
    pub text: String,
}

/// Result of `monitor_read(after_id)`: the entries after `after_id` and the
/// id to pass next time (`after_id` itself if there were none).
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct MonitorBatch {
    pub last_id: u64,
    pub entries: Vec<MonitorEntry>,
}

// ── Glue-only records ────────────────────────────────────────────────

/// One GPIO held by a live control-surface binding (`dspi_cs_pin_uses`).
#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct PinUse {
    pub pin: u8,
    pub slot: u8,
}

/// `dspi_bootloader_counts`. Always 0/0 on Android (libusb cannot
/// enumerate there); Android uses the Kotlin BootloaderLocator instead.
#[derive(Debug, Clone, Copy, PartialEq, Default, uniffi::Record)]
pub struct BootloaderCounts {
    pub rp2040: u32,
    pub rp2350: u32,
}

/// Version facts of the linked core, for the About page and sanity checks.
#[derive(Debug, Clone, PartialEq, Default, uniffi::Record)]
pub struct CoreInfo {
    /// dspi-ffi crate version.
    pub ffi_version: String,
    /// `protocol::WIRE_FORMAT_VERSION` (32).
    pub wire_format_version: u8,
    /// `protocol::MIN_WIRE_FORMAT_VERSION` (16).
    pub min_wire_format_version: u8,
    /// `protocol::BULK_PARAMS_SIZE` (6136).
    pub bulk_params_size: u32,
    /// Number of points in `compute_magnitude_curve` / `compute_phase_curve` (201).
    pub magnitude_points: u32,
}

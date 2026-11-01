//! Conversions between the core's C structs and the UniFFI records.
//!
//! Every field is listed explicitly (no `..Default::default()`), so a field
//! added/renamed/removed upstream breaks the build here — that is the point:
//! a submodule bump cannot silently drop state on its way to Kotlin.

use dspi_core::{monitor, notify, rta, siggen, state, stats, types};

use crate::records::*;

/// NUL-padded byte buffer -> String (lossy, stops at the first NUL).
fn c_name(buf: &[u8]) -> String {
    let end = buf.iter().position(|&b| b == 0).unwrap_or(buf.len());
    String::from_utf8_lossy(&buf[..end]).into_owned()
}

/// String -> NUL-padded fixed buffer (truncated, always NUL-terminated).
#[allow(dead_code)] // used by the FFI package for names written back to the core
pub(crate) fn to_c_name<const N: usize>(s: &str) -> [u8; N] {
    let mut out = [0u8; N];
    let n = s.len().min(N.saturating_sub(1));
    out[..n].copy_from_slice(&s.as_bytes()[..n]);
    out
}

// ── types.rs ──────────────────────────────────────────────────────────

impl From<types::FilterParams> for FilterParams {
    fn from(p: types::FilterParams) -> Self {
        Self { filter_type: p.filter_type, bypass: p.bypass, freq: p.freq, q: p.q, gain: p.gain, qp: p.qp }
    }
}

impl From<FilterParams> for types::FilterParams {
    fn from(p: FilterParams) -> Self {
        Self { filter_type: p.filter_type, bypass: p.bypass, freq: p.freq, q: p.q, gain: p.gain, qp: p.qp }
    }
}

impl From<types::InputLockStatus> for InputLockStatus {
    fn from(s: types::InputLockStatus) -> Self {
        Self {
            state: s.state,
            clock_mode: s.clock_mode,
            detected_rate: s.detected_rate,
            measured_hz: s.measured_hz,
            rate_ok: s.rate_ok,
            lock_count: s.lock_count,
            loss_count: s.loss_count,
        }
    }
}

impl From<types::AdatOutStatus> for AdatOutStatus {
    fn from(s: types::AdatOutStatus) -> Self {
        Self {
            enabled: s.enabled,
            active: s.active,
            pin: s.pin,
            rate_ok: s.rate_ok,
            resync_count: s.resync_count,
            slip_count: s.slip_count,
        }
    }
}

impl From<types::LgStatus> for LgStatus {
    fn from(s: types::LgStatus) -> Self {
        Self { enabled: s.enabled, present: s.present, volume: s.volume, muted: s.muted }
    }
}

impl From<types::UartConfig> for UartConfig {
    fn from(c: types::UartConfig) -> Self {
        Self { enabled: c.enabled, tx_pin: c.tx_pin, rx_pin: c.rx_pin, notify: c.notify, baud: c.baud }
    }
}

impl From<UartConfig> for types::UartConfig {
    fn from(c: UartConfig) -> Self {
        Self { enabled: c.enabled, tx_pin: c.tx_pin, rx_pin: c.rx_pin, notify: c.notify, baud: c.baud }
    }
}

impl From<types::I2cConfig> for I2cConfig {
    fn from(c: types::I2cConfig) -> Self {
        Self { enabled: c.enabled, sda_pin: c.sda_pin, scl_pin: c.scl_pin, address: c.address }
    }
}

impl From<I2cConfig> for types::I2cConfig {
    fn from(c: I2cConfig) -> Self {
        Self { enabled: c.enabled, sda_pin: c.sda_pin, scl_pin: c.scl_pin, address: c.address }
    }
}

impl From<types::CtrlIfaceStatus> for CtrlIfaceStatus {
    fn from(s: types::CtrlIfaceStatus) -> Self {
        Self {
            uart_last_status: s.uart_last_status,
            uart_live: s.uart_live,
            i2c_last_status: s.i2c_last_status,
            i2c_live: s.i2c_live,
            protocol_version: s.protocol_version,
        }
    }
}

impl From<types::UpmixStatus> for UpmixStatus {
    fn from(s: types::UpmixStatus) -> Self {
        Self {
            active: s.active,
            parked_reason: s.parked_reason,
            correlation: s.correlation,
            balance: s.balance,
            center_gain: s.center_gain,
            ls_gain: s.ls_gain,
            rs_gain: s.rs_gain,
        }
    }
}

impl From<types::SystemStatus> for SystemStatus {
    fn from(s: types::SystemStatus) -> Self {
        Self {
            peaks: s.peaks.to_vec(),
            cpu0: s.cpu0,
            cpu1: s.cpu1,
            clip_flags: s.clip_flags,
            active_input_channels: s.active_input_channels,
            num_channels: s.num_channels,
        }
    }
}

impl From<&types::DeviceInfo> for DeviceInfo {
    fn from(d: &types::DeviceInfo) -> Self {
        Self { serial: d.serial_str().to_owned(), location_id: d.location_id }
    }
}

impl From<types::PresetDirectory> for PresetDirectory {
    fn from(d: types::PresetDirectory) -> Self {
        Self {
            occupied_mask: d.occupied_mask,
            startup_mode: d.startup_mode,
            default_slot: d.default_slot,
            last_active: d.last_active,
            output_config_mode: d.output_config_mode,
            master_volume_mode: d.master_volume_mode,
        }
    }
}

// ── state.rs ──────────────────────────────────────────────────────────

impl From<&state::DspState> for DspState {
    fn from(s: &state::DspState) -> Self {
        Self {
            platform_id: s.platform_id,
            num_channels: s.num_channels,
            num_input_channels: s.num_input_channels,
            num_output_channels: s.num_output_channels,
            format_version: s.format_version,
            fw_major: s.fw_major,
            fw_minor: s.fw_minor,
            fw_patch: s.fw_patch,
            fw_beta: s.fw_beta,
            compat: s.compat,
            preamp_db: s.preamp_db,
            bypass: s.bypass,
            input_preamp_db: s.input_preamp_db.to_vec(),
            master_volume_db: s.master_volume_db,
            master_volume_mode: s.master_volume_mode,
            user_volume_db: s.user_volume_db,
            user_mute: s.user_mute,
            loudness_enabled: s.loudness_enabled,
            loudness_ref_spl: s.loudness_ref_spl,
            loudness_intensity: s.loudness_intensity,
            loudness_output_mask: s.loudness_output_mask,
            crossfeed_enabled: s.crossfeed_enabled,
            crossfeed_preset: s.crossfeed_preset,
            crossfeed_freq: s.crossfeed_freq,
            crossfeed_feed: s.crossfeed_feed,
            crossfeed_itd: s.crossfeed_itd,
            crossfeed_output_pair_mask: s.crossfeed_output_pair_mask,
            channel_delays: s.channel_delays.to_vec(),
            matrix_routing: s.matrix_routing.iter().map(|r| r.to_vec()).collect(),
            matrix_gain: s.matrix_gain.iter().map(|r| r.to_vec()).collect(),
            matrix_invert: s.matrix_invert.iter().map(|r| r.to_vec()).collect(),
            output_enabled: s.output_enabled.to_vec(),
            output_muted: s.output_muted.to_vec(),
            output_gain_db: s.output_gain_db.to_vec(),
            output_delay_ms: s.output_delay_ms.to_vec(),
            limiter_enabled: s.limiter_enabled.to_vec(),
            limiter_link_group: s.limiter_link_group.to_vec(),
            limiter_threshold_db: s.limiter_threshold_db.to_vec(),
            limiter_release_ms: s.limiter_release_ms.to_vec(),
            num_pin_outputs: s.num_pin_outputs,
            output_pins: s.output_pins.to_vec(),
            output_config_mode: s.output_config_mode,
            filters: s.filters.iter().map(|ch| ch.iter().map(|&p| p.into()).collect()).collect(),
            xover: s.xover.iter().map(|ch| ch.iter().map(|&p| p.into()).collect()).collect(),
            channel_names: s.channel_names.iter().map(|n| c_name(n)).collect(),
            leveller_enabled: s.leveller_enabled,
            leveller_speed: s.leveller_speed,
            leveller_lookahead: s.leveller_lookahead,
            leveller_amount: s.leveller_amount,
            leveller_max_gain_db: s.leveller_max_gain_db,
            leveller_gate_db: s.leveller_gate_db,
            leveller_detector_mask: s.leveller_detector_mask,
            leveller_apply_mask: s.leveller_apply_mask,
            input_source: s.input_source,
            lg_sound_sync_enabled: s.lg_sound_sync_enabled,
            spdif_inputs_enabled: s.spdif_inputs_enabled,
            adat_input_enabled: s.adat_input_enabled,
            spdif_rx_pins: s.spdif_rx_pins.to_vec(),
            i2s_rx_pins: s.i2s_rx_pins.to_vec(),
            i2s_input_channels: s.i2s_input_channels,
            i2s_input_rate: s.i2s_input_rate,
            i2s_clock_mode: s.i2s_clock_mode,
            adat_input_pin: s.adat_input_pin,
            adat_input_clock_mode: s.adat_input_clock_mode,
            psybass_enabled: s.psybass_enabled,
            psybass_output_mask: s.psybass_output_mask,
            psybass_cutoff_hz: s.psybass_cutoff_hz,
            psybass_harmonics_db: s.psybass_harmonics_db,
            psybass_drive_db: s.psybass_drive_db,
            psybass_character_pct: s.psybass_character_pct,
            psybass_original_db: s.psybass_original_db,
            upmix: s.upmix.to_vec(),
            subharm: s.subharm.to_vec(),
            subharm_solo: s.subharm_solo,
            tube: s.tube.to_vec(),
            output_types: s.output_types.to_vec(),
            i2s_bck_pin: s.i2s_bck_pin,
            i2s_bck_pin_slave: s.i2s_bck_pin_slave,
            i2s_clock_pin_mode: s.i2s_clock_pin_mode,
            mck_enabled: s.mck_enabled,
            mck_pin: s.mck_pin,
            mck_multiplier: s.mck_multiplier,
            adat_out_enabled: s.adat_out_enabled,
            adat_out_pin: s.adat_out_pin,
            dac_mute_supported: s.dac_mute_supported,
            dac_mute_enabled: s.dac_mute_enabled,
            dac_mute_active_low: s.dac_mute_active_low,
            dac_mute_pin: s.dac_mute_pin,
            dac_mute_hold_ms: s.dac_mute_hold_ms,
            dac_mute_release_ms: s.dac_mute_release_ms,
            ctrl_iface_supported: s.ctrl_iface_supported,
            uart: s.uart.into(),
            i2c: s.i2c.into(),
            ctrl_status: s.ctrl_status.into(),
            core1_mode: s.core1_mode,
            preset_occupied: s.preset_occupied,
            preset_names: s.preset_names.iter().map(|n| c_name(n)).collect(),
            active_preset_slot: s.active_preset_slot,
            preset_startup_mode: s.preset_startup_mode,
            preset_default_slot: s.preset_default_slot,
            bulk_valid: s.bulk_valid,
            bulk_raw: s.bulk_raw.to_vec(),
        }
    }
}

// ── notify.rs ─────────────────────────────────────────────────────────

impl From<notify::NotifyResult> for NotifyResult {
    fn from(r: notify::NotifyResult) -> Self {
        Self { flags: r.flags, filter_channels: r.filter_channels }
    }
}

// ── rta.rs ────────────────────────────────────────────────────────────

impl From<RtaRequest> for rta::RtaRequest {
    fn from(r: RtaRequest) -> Self {
        Self {
            tap: r.tap,
            mask: r.mask,
            wants_bins: r.wants_bins,
            fft_order: r.fft_order,
            avg_ms: r.avg_ms,
            peak_decay_db_s: r.peak_decay_db_s,
        }
    }
}

impl From<rta::RtaRequest> for RtaRequest {
    fn from(r: rta::RtaRequest) -> Self {
        Self {
            tap: r.tap,
            mask: r.mask,
            wants_bins: r.wants_bins,
            fft_order: r.fft_order,
            avg_ms: r.avg_ms,
            peak_decay_db_s: r.peak_decay_db_s,
        }
    }
}

impl From<rta::RtaCapsInfo> for RtaCapsInfo {
    fn from(c: rta::RtaCapsInfo) -> Self {
        Self {
            supported: c.supported,
            input_channels: c.input_channels,
            output_channels: c.output_channels,
            order_min: c.order_min,
            order_max: c.order_max,
            order_default: c.order_default,
            bass_bands: c.bass_bands,
            max_bands: c.max_bands,
            dynamic_range_db: c.dynamic_range_db,
            bass_dynamic_range_db: c.bass_dynamic_range_db,
            centres: c.centres.to_vec(),
        }
    }
}

impl From<&rta::RtaChannelFrame> for RtaChannelFrame {
    fn from(f: &rta::RtaChannelFrame) -> Self {
        Self {
            channel: f.channel,
            n_bands: f.n_bands,
            silent: f.silent,
            avg_db: f.avg_db.to_vec(),
            peak_db: f.peak_db.to_vec(),
        }
    }
}

impl From<&rta::RtaSnapshot> for RtaSnapshot {
    fn from(s: &rta::RtaSnapshot) -> Self {
        Self {
            version: s.version,
            telemetry_version: s.telemetry_version,
            tap: s.tap,
            count: s.count,
            frames: s.frames.iter().map(RtaChannelFrame::from).collect(),
            populated: s.populated,
            bins_count: s.bins_count,
            bins_channel: s.bins_channel,
            bins_sample_rate: s.bins_sample_rate,
            bins_db: s.bins_db.to_vec(),
            running: s.running,
            live_count: s.live_count,
            live_mask: s.live_mask,
            frames_per_s: s.frames_per_s,
            busy_us_per_s: s.busy_us_per_s,
            last_frame_us: s.last_frame_us,
            bass_busy_us_per_s: s.bass_busy_us_per_s,
            sample_rate: s.sample_rate,
            first_band: s.first_band,
            fft_order: s.fft_order,
            config_rejected: s.config_rejected,
            refresh_interval_s: s.refresh_interval_s,
        }
    }
}

// ── siggen.rs ─────────────────────────────────────────────────────────

impl From<siggen::SiggenConfig> for SiggenConfig {
    fn from(c: siggen::SiggenConfig) -> Self {
        Self {
            signal_type: c.signal_type,
            channel_mask: c.channel_mask,
            invert_mask: c.invert_mask,
            flags: c.flags,
            level_db: c.level_db,
            duration_ms: c.duration_ms,
            repeat: c.repeat,
            gap_ms: c.gap_ms,
            p: c.p.to_vec(),
        }
    }
}

impl From<&SiggenConfig> for siggen::SiggenConfig {
    /// Missing parameters (`p` shorter than 4) read as 0; extras are dropped.
    fn from(c: &SiggenConfig) -> Self {
        let mut p = [0f32; 4];
        for (dst, src) in p.iter_mut().zip(c.p.iter()) {
            *dst = *src;
        }
        Self {
            signal_type: c.signal_type,
            channel_mask: c.channel_mask,
            invert_mask: c.invert_mask,
            flags: c.flags,
            level_db: c.level_db,
            duration_ms: c.duration_ms,
            repeat: c.repeat,
            gap_ms: c.gap_ms,
            p,
        }
    }
}

impl From<siggen::SiggenStatus> for SiggenStatus {
    fn from(s: siggen::SiggenStatus) -> Self {
        Self {
            state: s.state,
            signal_type: s.signal_type,
            active_channel: s.active_channel,
            elapsed_ms: s.elapsed_ms,
            cycles_done: s.cycles_done,
            stop_reason: s.stop_reason,
            current_freq: s.current_freq,
        }
    }
}

impl From<siggen::SiggenParam> for SiggenParam {
    fn from(p: siggen::SiggenParam) -> Self {
        Self { semantic: p.semantic, min: p.min, max: p.max, def: p.def }
    }
}

impl From<&siggen::SiggenTypeInfo> for SiggenTypeInfo {
    fn from(t: &siggen::SiggenTypeInfo) -> Self {
        Self { id: t.id, name: c_name(&t.name), timing: t.timing, params: t.params.iter().map(|&p| p.into()).collect() }
    }
}

impl From<&siggen::SiggenCaps> for SiggenCaps {
    fn from(c: &siggen::SiggenCaps) -> Self {
        Self {
            supported: c.supported,
            type_count: c.type_count,
            output_channels: c.output_channels,
            multitone_max: c.multitone_max,
            valid_mask: c.valid_mask,
            types: c.types.iter().map(SiggenTypeInfo::from).collect(),
        }
    }
}

// ── stats.rs ──────────────────────────────────────────────────────────

impl From<stats::SpdifBufferStats> for SpdifBufferStats {
    fn from(s: stats::SpdifBufferStats) -> Self {
        Self {
            free: s.free,
            prepared: s.prepared,
            playing: s.playing,
            fill_pct: s.fill_pct,
            min_pct: s.min_pct,
            max_pct: s.max_pct,
        }
    }
}

impl From<stats::PdmBufferStats> for PdmBufferStats {
    fn from(s: stats::PdmBufferStats) -> Self {
        Self {
            dma_fill_pct: s.dma_fill_pct,
            dma_min_pct: s.dma_min_pct,
            dma_max_pct: s.dma_max_pct,
            ring_fill_pct: s.ring_fill_pct,
            ring_min_pct: s.ring_min_pct,
            ring_max_pct: s.ring_max_pct,
        }
    }
}

impl From<stats::BufferStats> for BufferStats {
    fn from(s: stats::BufferStats) -> Self {
        Self {
            num_spdif: s.num_spdif,
            pdm_active: s.pdm_active,
            streaming: s.streaming,
            sequence: s.sequence,
            spdif: s.spdif.iter().map(|&b| b.into()).collect(),
            pdm: s.pdm.into(),
        }
    }
}

impl From<stats::SpdifRxStatus> for SpdifRxStatus {
    fn from(s: stats::SpdifRxStatus) -> Self {
        Self {
            state: s.state,
            input_source: s.input_source,
            lock_count: s.lock_count,
            loss_count: s.loss_count,
            sample_rate: s.sample_rate,
            parity_errors: s.parity_errors,
            fifo_fill_pct: s.fifo_fill_pct,
            lib_state: s.lib_state,
            stable_callbacks: s.stable_callbacks,
            lost_callbacks: s.lost_callbacks,
        }
    }
}

// ── monitor.rs ────────────────────────────────────────────────────────

impl From<&monitor::MonitorEntry> for MonitorEntry {
    fn from(e: &monitor::MonitorEntry) -> Self {
        Self { id: e.id, time_ms: e.time_ms, text: e.text.clone() }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn default_state_round_trips_dimensions() {
        let core = state::DspState::default();
        let s = DspState::from(&core);
        assert_eq!(s.filters.len(), types::MAX_CHANNELS);
        assert_eq!(s.filters[0].len(), types::BANDS_PER_CHANNEL);
        assert_eq!(s.xover[0].len(), types::MAX_XOVER_BANDS);
        assert_eq!(s.matrix_gain.len(), types::MAX_INPUTS);
        assert_eq!(s.matrix_gain[0].len(), types::MAX_OUTPUTS);
        assert_eq!(s.channel_names.len(), types::MAX_CHANNELS);
        assert_eq!(s.preset_names.len(), types::MAX_PRESETS);
        assert_eq!(s.bulk_raw.len(), dspi_core::protocol::BULK_PARAMS_SIZE);
        assert_eq!(s.master_volume_db, -20.0);
    }

    #[test]
    fn filter_params_round_trip() {
        let p = FilterParams { filter_type: 11, bypass: true, freq: 40.0, q: 0.5, gain: 20.0, qp: 0.9 };
        let back: FilterParams = types::FilterParams::from(p).into();
        assert_eq!(p, back);
    }

    #[test]
    fn names_are_nul_terminated() {
        let b: [u8; 8] = to_c_name("abcdefghij");
        assert_eq!(&b, b"abcdefg\0");
        assert_eq!(c_name(&b), "abcdefg");
    }

    #[test]
    fn siggen_config_pads_params() {
        let c = SiggenConfig { p: vec![1.0], ..Default::default() };
        let core = siggen::SiggenConfig::from(&c);
        assert_eq!(core.p, [1.0, 0.0, 0.0, 0.0]);
    }
}

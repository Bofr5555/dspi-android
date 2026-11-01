//! `DspiCore`: the UniFFI object wrapping `dspi_core::DspiCore`.
//!
//! One method per `pub extern "C" fn dspi_*` in
//! `third_party/DSPi-Console-Linux/core/src/lib.rs`, same name minus the
//! `dspi_` prefix and in the same order, so `core_spec.md` doubles as the API
//! doc. Differences from the C ABI, all mechanical:
//! - out-pointers become return values; `bool`/`0xFF`/`-1`/status-byte
//!   returns become `Result<_, DspiError>` (`Refused { status }` carries the
//!   device's code);
//! - caller-supplied text buffers become `String` / `Vec<String>`;
//! - C function-pointer callbacks become callback interfaces
//!   (`set_notify_callback` -> `set_notify_listener`, `rta_set_callback` ->
//!   `rta_set_listener`); `dspi_monitor_read`'s TSV becomes `MonitorBatch`;
//! - stateless math and `bootloader_counts`/`config_inspect` are free
//!   functions (see `lib.rs`);
//! - Android additions: `open_fd`, `close`.
//!
//! Channel arguments are firmware WIRE indices; output arguments are output
//! indices 0..num_output_channels-1; input arguments input indices (as in the
//! core). App-id mapping is the Kotlin bridge's job.
//!
//! WAVE 0 STATUS: signatures are final; bodies are stubs owned by package FFI
//! (docs/PACKAGES.md). Fallible stubs return `DspiError::Unimplemented`,
//! infallible ones `todo!()` (UniFFI turns the panic into a Kotlin
//! `InternalException`). Implemented already: `get_state`, `max_delay_ms`,
//! `preamp_max_db` and the free math functions.

use std::sync::{Arc, Mutex, MutexGuard};

use crate::callbacks::{DeviceEvents, NotifyListener, RtaListener};
use crate::error::{DspiError, Result};
use crate::records::*;

macro_rules! stub {
    ($name:literal) => {
        Err(DspiError::Unimplemented { what: $name.to_string() })
    };
}

macro_rules! stub_infallible {
    ($name:literal) => {
        todo!(concat!("dspi-ffi: ", $name, " not implemented yet (package FFI)"))
    };
}

/// Thread-safe handle on one core instance. All methods may block on USB
/// (up to the core's 1 s transfer timeout; `load_preset`/bulk reads take
/// 10–300 ms): call them off the main thread, from one serial dispatcher.
#[derive(uniffi::Object)]
pub struct DspiCore {
    inner: Mutex<dspi_core::DspiCore>,
    /// Kept alive while registered with the core (FFI package wires them).
    #[allow(dead_code)]
    listeners: Mutex<Listeners>,
}

#[derive(Default)]
#[allow(dead_code)] // filled in by package FFI
struct Listeners {
    notify: Option<Box<dyn NotifyListener>>,
    rta: Option<Box<dyn RtaListener>>,
    device_events: Option<Box<dyn DeviceEvents>>,
}

impl DspiCore {
    fn core(&self) -> MutexGuard<'_, dspi_core::DspiCore> {
        // A poisoned lock means a panic inside the core; keep serving the state.
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

#[uniffi::export]
impl DspiCore {
    // ═══ Lifecycle (dspi_core_new / dspi_core_free = drop) ═══════════

    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self { inner: Mutex::new(dspi_core::DspiCore::new()), listeners: Mutex::new(Listeners::default()) })
    }

    // ═══ Android additions ═══════════════════════════════════════════

    /// Adopt an already-open usbfs fd (`UsbDeviceConnection.getFileDescriptor()`).
    /// Claims interface 2 only (never the audio interfaces 0/1), starts the
    /// notify listener, resets state like `select_device`. The caller keeps
    /// the `UsbDeviceConnection` open until after `close()`; rusb does not
    /// take ownership of the fd. Needs third_party/patches/0001-android-fd.patch
    /// (`DeviceManager::select_device_handle`, PLAN Appendix A).
    pub fn open_fd(&self, fd: i32, serial: String, vid: u16) -> Result<()> {
        let _ = (fd, serial, vid);
        stub!("open_fd")
    }

    /// Stop the listener/RTA threads and drop the device handle (= `disconnect`),
    /// after which the Kotlin side may close the `UsbDeviceConnection`.
    pub fn close(&self) {
        stub_infallible!("close")
    }

    // ═══ Device management ═══════════════════════════════════════════

    /// libusb enumeration. Always empty on Android (discovery disabled).
    pub fn scan_devices(&self) -> Vec<DeviceInfo> {
        stub_infallible!("scan_devices")
    }

    /// Highest preamp (dB) the chip applies correctly: +18 RP2040, +24 RP2350.
    pub fn preamp_max_db(&self) -> f32 {
        self.core().state().preamp_range().1
    }

    pub fn inaccessible_devices(&self) -> u32 {
        stub_infallible!("inaccessible_devices")
    }

    pub fn select_device(&self, serial: String) -> Result<()> {
        let _ = serial;
        stub!("select_device")
    }

    pub fn disconnect(&self) {
        stub_infallible!("disconnect")
    }

    pub fn is_connected(&self) -> bool {
        stub_infallible!("is_connected")
    }

    // ═══ Sync / status ═══════════════════════════════════════════════

    /// Identify the firmware and read every parameter. Err only when the device
    /// could not be talked to; check `DspState.compat` for compatibility.
    pub fn fetch_all(&self) -> Result<()> {
        stub!("fetch_all")
    }

    pub fn refresh_params(&self) -> Result<()> {
        stub!("refresh_params")
    }

    pub fn apply_all_params(&self) -> Result<()> {
        stub!("apply_all_params")
    }

    pub fn fetch_status(&self) -> Result<SystemStatus> {
        stub!("fetch_status")
    }

    /// Copy of the cached state (the C ABI returns a borrowed pointer).
    pub fn get_state(&self) -> DspState {
        DspState::from(self.core().state())
    }

    pub fn max_delay_ms(&self) -> f32 {
        self.core().max_delay_ms()
    }

    // ═══ EQ ══════════════════════════════════════════════════════════

    /// PEQ band 0..9 of wire channel `ch`.
    pub fn set_filter(&self, ch: u8, band: u8, params: FilterParams) -> Result<()> {
        let _ = (ch, band, params);
        stub!("set_filter")
    }

    /// Crossover band 0..3 of an output's wire channel `ch`.
    pub fn set_crossover(&self, ch: u8, xband: u8, params: FilterParams) -> Result<()> {
        let _ = (ch, xband, params);
        stub!("set_crossover")
    }

    /// `band`: PEQ 0..9 or crossover 20..23.
    pub fn set_band_bypass(&self, ch: u8, band: u8, bypass: bool) -> Result<()> {
        let _ = (ch, band, bypass);
        stub!("set_band_bypass")
    }

    // ═══ Preamp / bypass / volume ════════════════════════════════════

    /// Legacy preamp: every input to the same value.
    pub fn set_preamp(&self, db: f32) -> Result<()> {
        let _ = db;
        stub!("set_preamp")
    }

    pub fn set_input_preamp(&self, input: u8, db: f32) -> Result<()> {
        let _ = (input, db);
        stub!("set_input_preamp")
    }

    pub fn set_bypass(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_bypass")
    }

    /// dB: −128 = mute, otherwise −127..0.
    pub fn set_master_volume(&self, db: f32) -> Result<()> {
        let _ = db;
        stub!("set_master_volume")
    }

    /// 0 = independent of presets, 1 = saved with presets.
    pub fn set_master_volume_mode(&self, mode: u8) -> Result<()> {
        let _ = mode;
        stub!("set_master_volume_mode")
    }

    /// FLASH WRITE. Refused { PRESET_* } on failure.
    pub fn save_master_volume(&self) -> Result<()> {
        stub!("save_master_volume")
    }

    pub fn set_user_volume(&self, db: f32) -> Result<()> {
        let _ = db;
        stub!("set_user_volume")
    }

    pub fn set_user_mute(&self, muted: bool) -> Result<()> {
        let _ = muted;
        stub!("set_user_mute")
    }

    // ═══ Delay ═══════════════════════════════════════════════════════

    pub fn set_delay(&self, ch: u8, ms: f32) -> Result<()> {
        let _ = (ch, ms);
        stub!("set_delay")
    }

    // ═══ Loudness ════════════════════════════════════════════════════

    pub fn set_loudness(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_loudness")
    }

    pub fn set_loudness_ref(&self, spl: f32) -> Result<()> {
        let _ = spl;
        stub!("set_loudness_ref")
    }

    pub fn set_loudness_intensity(&self, pct: f32) -> Result<()> {
        let _ = pct;
        stub!("set_loudness_intensity")
    }

    pub fn set_loudness_mask(&self, mask: u16) -> Result<()> {
        let _ = mask;
        stub!("set_loudness_mask")
    }

    // ═══ Crossfeed ═══════════════════════════════════════════════════

    pub fn set_crossfeed(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_crossfeed")
    }

    pub fn set_crossfeed_preset(&self, preset: u8) -> Result<()> {
        let _ = preset;
        stub!("set_crossfeed_preset")
    }

    pub fn set_crossfeed_freq(&self, freq: f32) -> Result<()> {
        let _ = freq;
        stub!("set_crossfeed_freq")
    }

    pub fn set_crossfeed_feed(&self, feed: f32) -> Result<()> {
        let _ = feed;
        stub!("set_crossfeed_feed")
    }

    pub fn set_crossfeed_itd(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_crossfeed_itd")
    }

    pub fn set_crossfeed_outputs(&self, pair_mask: u8) -> Result<()> {
        let _ = pair_mask;
        stub!("set_crossfeed_outputs")
    }

    // ═══ Volume leveller ═════════════════════════════════════════════

    pub fn set_leveller_enabled(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_leveller_enabled")
    }

    pub fn set_leveller_amount(&self, pct: f32) -> Result<()> {
        let _ = pct;
        stub!("set_leveller_amount")
    }

    /// 0 slow, 1 medium, 2 fast.
    pub fn set_leveller_speed(&self, speed: u8) -> Result<()> {
        let _ = speed;
        stub!("set_leveller_speed")
    }

    pub fn set_leveller_max_gain(&self, db: f32) -> Result<()> {
        let _ = db;
        stub!("set_leveller_max_gain")
    }

    pub fn set_leveller_lookahead(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_leveller_lookahead")
    }

    pub fn set_leveller_gate(&self, db: f32) -> Result<()> {
        let _ = db;
        stub!("set_leveller_gate")
    }

    pub fn set_leveller_masks(&self, detector: u8, apply: u8) -> Result<()> {
        let _ = (detector, apply);
        stub!("set_leveller_masks")
    }

    pub fn set_input_source(&self, source: u8) -> Result<()> {
        let _ = source;
        stub!("set_input_source")
    }

    // ═══ Psychoacoustic bass ═════════════════════════════════════════

    /// `param`: PSYBASS_PARAM_*.
    pub fn set_psybass_param(&self, param: u8, value: f32) -> Result<()> {
        let _ = (param, value);
        stub!("set_psybass_param")
    }

    pub fn set_psybass_mask(&self, mask: u16) -> Result<()> {
        let _ = mask;
        stub!("set_psybass_mask")
    }

    pub fn set_psybass_enabled(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_psybass_enabled")
    }

    // ═══ Matrix / outputs ════════════════════════════════════════════

    pub fn set_matrix_route(&self, input: u8, output: u8, enabled: bool, gain: f32, invert: bool) -> Result<()> {
        let _ = (input, output, enabled, gain, invert);
        stub!("set_matrix_route")
    }

    /// Returns the resulting state: the firmware may refuse (PDM / Core 1 conflict).
    pub fn set_output_enable(&self, output: u8, enabled: bool) -> Result<bool> {
        let _ = (output, enabled);
        stub!("set_output_enable")
    }

    pub fn set_output_gain(&self, output: u8, db: f32) -> Result<()> {
        let _ = (output, db);
        stub!("set_output_gain")
    }

    pub fn set_output_mute(&self, output: u8, muted: bool) -> Result<()> {
        let _ = (output, muted);
        stub!("set_output_mute")
    }

    pub fn set_output_delay(&self, output: u8, ms: f32) -> Result<()> {
        let _ = (output, ms);
        stub!("set_output_delay")
    }

    /// `output` = LIMITER_ALL_OUTPUTS (0xFF) for every output; `param` LIMITER_PARAM_*.
    pub fn set_limiter_param(&self, output: u8, param: u8, value: f32) -> Result<()> {
        let _ = (output, param, value);
        stub!("set_limiter_param")
    }

    /// Gain reduction (dB, > 0 while limiting) per output, MAX_OUTPUTS entries.
    pub fn fetch_limiter_meter(&self) -> Result<Vec<f32>> {
        stub!("fetch_limiter_meter")
    }

    // ═══ Pins / output config ════════════════════════════════════════

    /// Refused { PIN_CONFIG_* } on a rejected pin.
    pub fn set_output_pin(&self, slot: u8, pin: u8) -> Result<()> {
        let _ = (slot, pin);
        stub!("set_output_pin")
    }

    pub fn fetch_output_pin(&self, slot: u8) -> Result<u8> {
        let _ = slot;
        stub!("fetch_output_pin")
    }

    /// 0 = output config stored independently, 1 = saved with presets.
    pub fn set_output_config_mode(&self, mode: u8) -> Result<()> {
        let _ = mode;
        stub!("set_output_config_mode")
    }

    /// FLASH WRITE. Refused { PRESET_* }.
    pub fn save_output_config(&self) -> Result<()> {
        stub!("save_output_config")
    }

    // ═══ Channel names ═══════════════════════════════════════════════

    pub fn set_channel_name(&self, ch: u8, name: String) -> Result<()> {
        let _ = (ch, name);
        stub!("set_channel_name")
    }

    /// Reads the name from the device.
    pub fn get_channel_name(&self, ch: u8) -> Result<String> {
        let _ = ch;
        stub!("get_channel_name")
    }

    // ═══ Presets ═════════════════════════════════════════════════════

    /// FLASH WRITE (waits for the deferred save). Refused { PRESET_* }.
    pub fn save_preset(&self, slot: u8) -> Result<()> {
        let _ = slot;
        stub!("save_preset")
    }

    /// Load and re-read every parameter. Refused { PRESET_* }.
    pub fn load_preset(&self, slot: u8) -> Result<()> {
        let _ = slot;
        stub!("load_preset")
    }

    /// FLASH WRITE: live state -> `dest`, re-save `source` (stays active).
    pub fn copy_preset(&self, source: u8, dest: u8) -> Result<()> {
        let _ = (source, dest);
        stub!("copy_preset")
    }

    /// FLASH WRITE.
    pub fn delete_preset(&self, slot: u8) -> Result<()> {
        let _ = slot;
        stub!("delete_preset")
    }

    pub fn set_preset_name(&self, slot: u8, name: String) -> Result<()> {
        let _ = (slot, name);
        stub!("set_preset_name")
    }

    pub fn get_preset_name(&self, slot: u8) -> Result<String> {
        let _ = slot;
        stub!("get_preset_name")
    }

    pub fn get_preset_directory(&self) -> Result<PresetDirectory> {
        stub!("get_preset_directory")
    }

    /// mode 0 = load `default_slot` at boot, 1 = last active slot.
    pub fn set_preset_startup(&self, mode: u8, default_slot: u8) -> Result<()> {
        let _ = (mode, default_slot);
        stub!("set_preset_startup")
    }

    // ═══ Flash / system ══════════════════════════════════════════════

    /// FLASH WRITE. Refused { FLASH_* }.
    pub fn save_params(&self) -> Result<()> {
        stub!("save_params")
    }

    /// Revert to saved (reloads the active preset). Refused { FLASH_* }.
    pub fn load_params(&self) -> Result<()> {
        stub!("load_params")
    }

    /// FLASH WRITE. Refused { FLASH_* }.
    pub fn factory_reset(&self) -> Result<()> {
        stub!("factory_reset")
    }

    /// Reboots the device into its USB bootloader (it disappears).
    pub fn enter_bootloader(&self) -> Result<()> {
        stub!("enter_bootloader")
    }

    /// 0 idle, 1 PDM, 2 EQ worker.
    pub fn fetch_core1_mode(&self) -> Result<u8> {
        stub!("fetch_core1_mode")
    }

    /// Whether enabling `output` would conflict with Core 1's mode.
    pub fn check_core1_conflict(&self, output: u8) -> Result<bool> {
        let _ = output;
        stub!("check_core1_conflict")
    }

    pub fn clear_clips(&self) -> Result<()> {
        stub!("clear_clips")
    }

    // ═══ Hot-plug ════════════════════════════════════════════════════

    /// Register (Some) or clear (None) the arrival/departure listener.
    pub fn set_hotplug_callback(&self, listener: Option<Box<dyn DeviceEvents>>) {
        let _ = listener;
        stub_infallible!("set_hotplug_callback")
    }

    /// libusb poll (~500 ms on desktop). No-op on Android.
    pub fn poll_hotplug(&self) {
        stub_infallible!("poll_hotplug")
    }

    // ═══ Upmixer, subharmonic synth, tube modeller ══════════════════

    /// UPMIX_PARAM_*; RP2350 only.
    pub fn set_upmix_param(&self, id: u8, value: f32) -> Result<()> {
        let _ = (id, value);
        stub!("set_upmix_param")
    }

    pub fn fetch_upmix_status(&self) -> Result<UpmixStatus> {
        stub!("fetch_upmix_status")
    }

    /// SUBHARM_PARAM_*.
    pub fn set_subharm_param(&self, id: u8, value: f32) -> Result<()> {
        let _ = (id, value);
        stub!("set_subharm_param")
    }

    pub fn set_subharm_solo(&self, solo: bool) -> Result<()> {
        let _ = solo;
        stub!("set_subharm_solo")
    }

    /// Headroom the subharm settings cost, dB.
    pub fn fetch_subharm_headroom(&self) -> Result<f32> {
        stub!("fetch_subharm_headroom")
    }

    /// Synthesized sub peak per output 0..1, MAX_OUTPUTS entries.
    pub fn fetch_subharm_meter(&self) -> Result<Vec<f32>> {
        stub!("fetch_subharm_meter")
    }

    /// TUBE_PARAM_*.
    pub fn set_tube_param(&self, idx: u8, value: f32) -> Result<()> {
        let _ = (idx, value);
        stub!("set_tube_param")
    }

    // ═══ Hardware IO — Refused { PIN_CONFIG_* } on rejection ═════════

    /// 0 = S/PDIF, 1 = I2S.
    pub fn set_output_type(&self, slot: u8, kind: u8) -> Result<()> {
        let _ = (slot, kind);
        stub!("set_output_type")
    }

    /// role 0 = master/shared, 1 = slave pair.
    pub fn set_i2s_bck_pin(&self, role: u8, pin: u8) -> Result<()> {
        let _ = (role, pin);
        stub!("set_i2s_bck_pin")
    }

    pub fn set_i2s_clock_pin_mode(&self, mode: u8) -> Result<()> {
        let _ = mode;
        stub!("set_i2s_clock_pin_mode")
    }

    pub fn set_mck_enabled(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_mck_enabled")
    }

    pub fn set_mck_pin(&self, pin: u8) -> Result<()> {
        let _ = pin;
        stub!("set_mck_pin")
    }

    /// 0 = 128×fs, 1 = 256×fs.
    pub fn set_mck_multiplier(&self, mult: u8) -> Result<()> {
        let _ = mult;
        stub!("set_mck_multiplier")
    }

    pub fn set_adat_out_enabled(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_adat_out_enabled")
    }

    pub fn set_adat_out_pin(&self, pin: u8) -> Result<()> {
        let _ = pin;
        stub!("set_adat_out_pin")
    }

    pub fn set_spdif_rx_pin(&self, index: u8, pin: u8) -> Result<()> {
        let _ = (index, pin);
        stub!("set_spdif_rx_pin")
    }

    /// index 1..3 (input 1 is always on).
    pub fn set_spdif_input_enabled(&self, index: u8, enabled: bool) -> Result<()> {
        let _ = (index, enabled);
        stub!("set_spdif_input_enabled")
    }

    pub fn set_i2s_rx_pin(&self, pair: u8, pin: u8) -> Result<()> {
        let _ = (pair, pin);
        stub!("set_i2s_rx_pin")
    }

    /// 2, 4, 6 or 8.
    pub fn set_i2s_input_channels(&self, channels: u8) -> Result<()> {
        let _ = channels;
        stub!("set_i2s_input_channels")
    }

    pub fn set_adat_input_enabled(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_adat_input_enabled")
    }

    /// 0xFF clears the pin.
    pub fn set_adat_input_pin(&self, pin: u8) -> Result<()> {
        let _ = pin;
        stub!("set_adat_input_pin")
    }

    pub fn set_adat_input_clock_mode(&self, mode: u8) -> Result<()> {
        let _ = mode;
        stub!("set_adat_input_clock_mode")
    }

    /// 0 = 44.1k, 1 = 48k, 2 = 96k.
    pub fn set_input_rate(&self, index: u8) -> Result<()> {
        let _ = index;
        stub!("set_input_rate")
    }

    /// Hz the pipeline runs at now (0 if unknown).
    pub fn fetch_input_rate(&self) -> Result<u32> {
        stub!("fetch_input_rate")
    }

    pub fn set_i2s_clock_mode(&self, mode: u8) -> Result<()> {
        let _ = mode;
        stub!("set_i2s_clock_mode")
    }

    /// `which`: 0 = I2S slave lock, 1 = ADAT input lock.
    pub fn fetch_input_lock(&self, which: u8) -> Result<InputLockStatus> {
        let _ = which;
        stub!("fetch_input_lock")
    }

    pub fn fetch_adat_out_status(&self) -> Result<AdatOutStatus> {
        stub!("fetch_adat_out_status")
    }

    // ═══ DAC mute, LG Sound Sync, UART / I2C ═════════════════════════

    /// Applied later; read back with `fetch_dac_mute` ~150 ms after.
    pub fn set_dac_mute(&self, enabled: bool, active_low: bool, pin: u8, hold_ms: u16, release_ms: u16) -> Result<()> {
        let _ = (enabled, active_low, pin, hold_ms, release_ms);
        stub!("set_dac_mute")
    }

    /// Re-reads the DAC mute config into the state.
    pub fn fetch_dac_mute(&self) -> Result<()> {
        stub!("fetch_dac_mute")
    }

    /// Pulse the mute output ~1 s. Refused { 3 } = feature disabled.
    pub fn test_dac_mute(&self) -> Result<()> {
        stub!("test_dac_mute")
    }

    pub fn set_lg_sound_sync(&self, enabled: bool) -> Result<()> {
        let _ = enabled;
        stub!("set_lg_sound_sync")
    }

    pub fn fetch_lg_status(&self) -> Result<LgStatus> {
        stub!("fetch_lg_status")
    }

    /// Re-read UART/I2C configs + status into the state.
    pub fn fetch_ctrl_ifaces(&self) -> Result<()> {
        stub!("fetch_ctrl_ifaces")
    }

    /// Outcome readable via `fetch_ctrl_ifaces` ~250 ms later.
    pub fn set_uart(&self, config: UartConfig) -> Result<()> {
        let _ = config;
        stub!("set_uart")
    }

    pub fn set_i2c(&self, config: I2cConfig) -> Result<()> {
        let _ = config;
        stub!("set_i2c")
    }

    // ═══ Device notifications ════════════════════════════════════════

    /// Register (Some) or clear (None) the wake-up listener of the notify thread.
    pub fn set_notify_listener(&self, listener: Option<Box<dyn NotifyListener>>) {
        let _ = listener;
        stub_infallible!("set_notify_listener")
    }

    /// Apply queued notifications to the state; returns what changed.
    pub fn process_notifications(&self) -> NotifyResult {
        stub_infallible!("process_notifications")
    }

    // ═══ Spectrum analyser ═══════════════════════════════════════════

    /// Register (Some) or clear (None) the analyser wake-up listener. Once it
    /// returns after None, no call is in progress.
    pub fn rta_set_listener(&self, listener: Option<Box<dyn RtaListener>>) {
        let _ = listener;
        stub_infallible!("rta_set_listener")
    }

    /// Probe and start polling (compatible firmware only); stops on disconnect.
    pub fn rta_start(&self) {
        stub_infallible!("rta_start")
    }

    /// What to analyse; mask 0 releases the analyser.
    pub fn rta_set_request(&self, request: RtaRequest) {
        let _ = request;
        stub_infallible!("rta_set_request")
    }

    pub fn rta_get_caps(&self) -> RtaCapsInfo {
        stub_infallible!("rta_get_caps")
    }

    pub fn rta_get_snapshot(&self) -> RtaSnapshot {
        stub_infallible!("rta_get_snapshot")
    }

    // ═══ Signal generator ════════════════════════════════════════════

    /// Ok with `supported = false` when the firmware has no generator.
    pub fn siggen_fetch_caps(&self) -> Result<SiggenCaps> {
        stub!("siggen_fetch_caps")
    }

    /// The config the generator runs, as the device sanitised it.
    pub fn siggen_get_config(&self) -> Result<SiggenConfig> {
        stub!("siggen_get_config")
    }

    /// Stage a config (a running generator restarts). The device ACKs even a
    /// refused config: read it back.
    pub fn siggen_set_config(&self, config: SiggenConfig) -> Result<()> {
        let _ = config;
        stub!("siggen_set_config")
    }

    /// SIGGEN_CTL_START / STOP (fade) / STOP_NOW.
    pub fn siggen_control(&self, action: u16) -> Result<()> {
        let _ = action;
        stub!("siggen_control")
    }

    pub fn siggen_get_status(&self) -> Result<SiggenStatus> {
        stub!("siggen_get_status")
    }

    // ═══ Statistics ══════════════════════════════════════════════════

    /// One REQ_GET_STATUS u32 counter (STAT_*).
    pub fn fetch_stat(&self, which: u16) -> Result<u32> {
        let _ = which;
        stub!("fetch_stat")
    }

    pub fn fetch_buffer_stats(&self) -> Result<BufferStats> {
        stub!("fetch_buffer_stats")
    }

    pub fn reset_buffer_stats(&self) -> Result<()> {
        stub!("reset_buffer_stats")
    }

    pub fn fetch_spdif_rx_status(&self) -> Result<SpdifRxStatus> {
        stub!("fetch_spdif_rx_status")
    }

    /// 24 bytes of IEC 60958 channel status.
    pub fn fetch_spdif_rx_channel_status(&self) -> Result<Vec<u8>> {
        stub!("fetch_spdif_rx_channel_status")
    }

    /// GPIO of S/PDIF receiver `index`; Err if it has none.
    pub fn fetch_spdif_rx_pin(&self, index: u8) -> Result<u8> {
        let _ = index;
        stub!("fetch_spdif_rx_pin")
    }

    // ═══ Interrupt monitor ═══════════════════════════════════════════

    /// Log entries after `after_id` (typed replacement of the TSV buffer).
    pub fn monitor_read(&self, after_id: u64) -> MonitorBatch {
        let _ = after_id;
        stub_infallible!("monitor_read")
    }

    pub fn monitor_clear(&self) {
        stub_infallible!("monitor_clear")
    }

    // ═══ Configuration and filter files (JSON passthrough) ═══════════

    /// The device configuration as a `.dspipreset` document. `links` = the
    /// app's input-pair link mask.
    pub fn config_export(&self, name: String, app_version: String, links: u8) -> String {
        let _ = (name, app_version, links);
        stub_infallible!("config_export")
    }

    /// Apply a document (`options`: 1 = volumes, 2 = hardware I/O). JSON
    /// {ok, error, lines, clean, links (or null), hardwareWritten}.
    pub fn config_import(&self, text: String, options: u32) -> String {
        let _ = (text, options);
        stub_infallible!("config_import")
    }

    /// Every channel's filters as text (`inputs` = live inputs to write).
    pub fn filters_export(&self, inputs: u8) -> String {
        let _ = inputs;
        stub_infallible!("filters_export")
    }

    /// What a filter file holds, for the channel picker (JSON).
    pub fn filters_inspect(&self, text: String, inputs: u8) -> String {
        let _ = (text, inputs);
        stub_infallible!("filters_inspect")
    }

    /// Apply a filter file to `wires` (wire indices). JSON {ok, error, lines}.
    pub fn filters_import(&self, text: String, wires: Vec<u8>) -> String {
        let _ = (text, wires);
        stub_infallible!("filters_import")
    }

    // ═══ Control surfaces (JSON passthrough, see core cs.rs) ═════════

    /// Read the whole CS configuration from the device.
    pub fn cs_fetch(&self) -> Result<()> {
        stub!("cs_fetch")
    }

    /// The CS configuration as JSON (`cs::cs_snapshot`).
    pub fn cs_snapshot(&self) -> String {
        stub_infallible!("cs_snapshot")
    }

    /// One CS operation `{op, index, ...}`; JSON {ok, status, error, ...}.
    /// Some ops write flash (save) — the bridge gates them (DangerousOps).
    pub fn cs_apply(&self, op: String) -> String {
        let _ = op;
        stub_infallible!("cs_apply")
    }

    /// GPIOs held by live CS bindings.
    pub fn cs_pin_uses(&self) -> Vec<PinUse> {
        stub_infallible!("cs_pin_uses")
    }

    // ═══ Unsaved changes ═════════════════════════════════════════════

    /// The live state now matches the active preset.
    pub fn capture_baseline(&self) {
        stub_infallible!("capture_baseline")
    }

    pub fn preset_dirty(&self) -> bool {
        stub_infallible!("preset_dirty")
    }

    /// What differs from the active preset, one sentence each.
    pub fn preset_changes(&self) -> Vec<String> {
        stub_infallible!("preset_changes")
    }
}

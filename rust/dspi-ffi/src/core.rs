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
//! - Android additions: `open_fd`, `close_fd` (the plan's `close`; renamed
//!   because UniFFI's Kotlin object reserves `close()` for freeing the core).
//!
//! Channel arguments are firmware WIRE indices; output arguments are output
//! indices 0..num_output_channels-1; input arguments input indices (as in the
//! core). App-id mapping is the Kotlin bridge's job.
//!
//! Error mapping: `UsbError::NotConnected` and `rusb::Error::NoDevice` ->
//! `Disconnected`; other transfer errors -> `Transfer`; a non-zero status
//! byte (`PRESET_*`, `FLASH_*`, `PIN_CONFIG_*`, `test_dac_mute`'s 3) ->
//! `Refused { status }`. JSON-returning calls keep the C ABI's JSON
//! (`{ok:false, error}` instead of an error).
//!
//! Threads (PLAN §5.4): every call locks the core's mutex, as the C ABI
//! does. Listener callbacks run on the core's notify/RTA threads (or the
//! `poll_hotplug` caller) and are invoked through `extern "C"` trampolines
//! registered once, at construction, with `NotifyHub::set_waker` /
//! `RtaHub::set_waker`; the Kotlin listeners live in `Listeners` (which
//! outlives every registration, see `Drop`). A trampoline clones the
//! listener's `Arc` out of its slot and calls it with no lock of ours held,
//! so a callback may register, replace or clear listeners without deadlock
//! (review F1); `set_*_listener` only swaps the slot and never takes the
//! core lock or a hub lock.
//!
//! Calling into the core from a callback is UNSUPPORTED: `disconnect`,
//! `close_fd`, `select_device`, `open_fd` and `Drop` hold the core lock
//! while they join the notify thread and the RTA worker, and the RTA
//! worker's shutdown fires the RTA callback on that very thread, so a core
//! call from a callback can deadlock. Callbacks only schedule work (the
//! Kotlin bridge posts to its FFI dispatcher), as in the C ABI.
//!
//! Clearing a listener (None) waits for its calls in flight on other
//! threads, so after it returns the old listener is not running (unless the
//! clearing thread is itself inside that same listener's callback).
//! Replacing one (Some -> Some) does not wait; a call already running keeps
//! its reference, so the old listener may be finalised on the hub thread
//! when that call ends. A panic or Kotlin exception inside a listener is
//! caught and logged, never unwound through the core's threads.

use std::cell::RefCell;
use std::ffi::c_void;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Condvar, Mutex, MutexGuard, OnceLock};

use dspi_core::usb::UsbError;
use dspi_core::{filterfile, presetfile};
use serde_json::json;

use crate::callbacks::{DeviceEvents, NotifyListener, RtaListener};
use crate::error::{status, DspiError, Result};
use crate::records::*;

/// Thread-safe handle on one core instance. All methods may block on USB
/// (up to the core's 1 s transfer timeout; `load_preset`/bulk reads take
/// 10–300 ms): call them off the main thread, from one serial dispatcher.
#[derive(uniffi::Object)]
pub struct DspiCore {
    inner: Mutex<dspi_core::DspiCore>,
    /// Kept alive while registered with the core. Boxed so the address handed
    /// to the core's wakers as `user_data` never moves.
    listeners: Box<Listeners>,
}

#[derive(Default)]
struct Listeners {
    notify: Slot<dyn NotifyListener>,
    rta: Slot<dyn RtaListener>,
    device_events: Slot<dyn DeviceEvents>,
}

/// One registered listener plus a count of its calls in flight.
struct Slot<T: ?Sized> {
    current: Mutex<Option<Arc<T>>>,
    busy: Mutex<usize>,
    idle: Condvar,
}

impl<T: ?Sized> Default for Slot<T> {
    fn default() -> Self {
        Self { current: Mutex::new(None), busy: Mutex::new(0), idle: Condvar::new() }
    }
}

thread_local! {
    /// The slots (by address) whose callback this thread is inside, nested
    /// calls included: a thread never waits for its own calls.
    static IN_CALLBACK: RefCell<Vec<usize>> = const { RefCell::new(Vec::new()) };
}

impl<T: ?Sized> Slot<T> {
    fn key(&self) -> usize {
        self as *const Self as *const () as usize
    }

    /// Replace the listener. Clearing it (None) waits until no call of this
    /// slot is in flight on another thread; calls of this slot the clearing
    /// thread is itself inside are not waited for. Replacing does not wait.
    fn set(&self, listener: Option<Box<T>>) {
        let clearing = listener.is_none();
        *lock(&self.current) = listener.map(Arc::from);
        if clearing {
            let key = self.key();
            let own = IN_CALLBACK.with(|v| v.borrow().iter().filter(|&&k| k == key).count());
            let mut busy = lock(&self.busy);
            while *busy > own {
                busy = self.idle.wait(busy).unwrap_or_else(|p| p.into_inner());
            }
        }
    }

    /// Call `f` on the current listener, holding no lock during the call.
    fn call(&self, f: impl FnOnce(&T)) {
        let cb = {
            let cur = lock(&self.current);
            let cb = cur.clone();
            if cb.is_some() {
                *lock(&self.busy) += 1; // counted while `current` is held: `set` sees it
            }
            cb
        };
        let Some(cb) = cb else { return };
        let key = self.key();
        IN_CALLBACK.with(|v| v.borrow_mut().push(key));
        guarded(|| f(&cb));
        IN_CALLBACK.with(|v| {
            let mut v = v.borrow_mut();
            if let Some(i) = v.iter().rposition(|&k| k == key) {
                v.remove(i);
            }
        });
        drop(cb);
        let mut busy = lock(&self.busy);
        *busy -= 1;
        if *busy == 0 {
            self.idle.notify_all();
        }
    }
}

impl DspiCore {
    fn core(&self) -> MutexGuard<'_, dspi_core::DspiCore> {
        // A poisoned lock means a panic inside the core; keep serving the state.
        self.inner.lock().unwrap_or_else(|p| p.into_inner())
    }
}

impl Drop for DspiCore {
    fn drop(&mut self) {
        // Stop the worker threads, then point both wakers away from
        // `listeners` before it is freed. `set_waker` takes the hub's waker
        // lock, under which a callback runs, so no call is in flight after it.
        let mut c = self.core();
        c.device_manager_mut().disconnect();
        c.device_manager().notify_hub().set_waker(noop_trampoline, std::ptr::null_mut());
        c.device_manager().rta_hub().set_waker(None, std::ptr::null_mut());
    }
}

fn lock<T>(m: &Mutex<T>) -> MutexGuard<'_, T> {
    m.lock().unwrap_or_else(|p| p.into_inner())
}

/// Run a listener call; a panic (UniFFI turns a Kotlin exception in a
/// callback into one) must not unwind into the core's threads.
fn guarded(f: impl FnOnce()) {
    if catch_unwind(AssertUnwindSafe(f)).is_err() {
        log::error!("dspi-ffi: a listener callback failed");
    }
}

extern "C" fn notify_trampoline(user_data: *mut c_void) {
    if user_data.is_null() {
        return;
    }
    // SAFETY: `user_data` is `DspiCore::listeners`, alive until `Drop`
    // re-registers the waker (which waits for this call to return).
    let l = unsafe { &*(user_data as *const Listeners) };
    l.notify.call(|cb| cb.on_notifications());
}

extern "C" fn rta_trampoline(user_data: *mut c_void) {
    if user_data.is_null() {
        return;
    }
    // SAFETY: as in `notify_trampoline`.
    let l = unsafe { &*(user_data as *const Listeners) };
    l.rta.call(|cb| cb.on_frame());
}

extern "C" fn noop_trampoline(_: *mut c_void) {}

/// `rusb::disable_device_discovery()` must run once, before the first libusb
/// context exists; later contexts (GlobalContext included) then skip the
/// enumeration Android forbids. Returns the outcome of that single call.
pub(crate) fn disable_discovery_once() -> std::result::Result<(), String> {
    static DONE: OnceLock<std::result::Result<(), String>> = OnceLock::new();
    DONE.get_or_init(|| rusb::disable_device_discovery().map_err(|e| e.to_string())).clone()
}

/// Wrap an already-open usbfs fd in a libusb device handle.
fn open_fd_handle(fd: i32) -> Result<rusb::DeviceHandle<rusb::GlobalContext>> {
    if fd < 0 {
        return Err(DspiError::InvalidArgument { msg: format!("bad file descriptor {fd}") });
    }
    if let Err(e) = disable_discovery_once() {
        log::warn!("dspi-ffi: libusb device discovery could not be disabled: {e}");
    }
    use rusb::UsbContext;
    // SAFETY: the fd is a usbfs fd owned by the caller's UsbDeviceConnection,
    // which stays open until after `close_fd` (libusb never closes it).
    unsafe { rusb::GlobalContext::default().open_device_with_fd(fd) }.map_err(|e| UsbError::Rusb(e).into())
}

#[uniffi::export]
impl DspiCore {
    // ═══ Lifecycle (dspi_core_new / dspi_core_free = drop) ═══════════

    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        // Android: libusb must never try to enumerate (no access to
        // /dev/bus/usb), so turn discovery off before any context exists.
        #[cfg(target_os = "android")]
        let _ = disable_discovery_once();
        let core = dspi_core::DspiCore::new();
        let listeners: Box<Listeners> = Box::default();
        // Register the trampolines once; listeners come and go in their slots.
        let ptr = &*listeners as *const Listeners as *mut c_void;
        core.device_manager().notify_hub().set_waker(notify_trampoline, ptr);
        core.device_manager().rta_hub().set_waker(Some(rta_trampoline), ptr);
        Arc::new(Self { inner: Mutex::new(core), listeners })
    }

    // ═══ Android additions ═══════════════════════════════════════════

    /// Adopt an already-open usbfs fd (`UsbDeviceConnection.getFileDescriptor()`).
    /// Claims interface 2 only (never the audio interfaces 0/1), starts the
    /// notify listener, resets state like `select_device`. The caller keeps
    /// the `UsbDeviceConnection` open until after `close_fd()`; rusb does not
    /// take ownership of the fd. Needs third_party/patches/0001-android-fd.patch
    /// (`DeviceManager::select_device_handle`, PLAN Appendix A).
    pub fn open_fd(&self, fd: i32, serial: String, vid: u16) -> Result<()> {
        open_fd_handle(fd).and_then(|handle| {
            let mut c = self.core();
            c.device_manager_mut().select_device_handle(handle, &serial, vid)?;
            c.reset_device_state();
            Ok(())
        })
    }

    /// Stop the listener/RTA threads and drop the device handle opened by
    /// `open_fd` (= `disconnect`), after which the Kotlin side may close the
    /// `UsbDeviceConnection`. Named `close_fd`, not `close`, because UniFFI's
    /// Kotlin object already has `close()` (AutoCloseable = free the core).
    pub fn close_fd(&self) {
        self.core().device_manager_mut().disconnect();
    }

    // ═══ Device management ═══════════════════════════════════════════

    /// libusb enumeration. Always empty on Android (discovery disabled).
    pub fn scan_devices(&self) -> Vec<DeviceInfo> {
        self.core().device_manager_mut().scan().iter().map(DeviceInfo::from).collect()
    }

    /// Highest preamp (dB) the chip applies correctly: +18 RP2040, +24 RP2350.
    pub fn preamp_max_db(&self) -> f32 {
        self.core().state().preamp_range().1
    }

    pub fn inaccessible_devices(&self) -> u32 {
        self.core().device_manager().inaccessible()
    }

    pub fn select_device(&self, serial: String) -> Result<()> {
        let mut c = self.core();
        c.device_manager_mut().select_device(&serial)?;
        c.reset_device_state();
        Ok(())
    }

    pub fn disconnect(&self) {
        self.core().device_manager_mut().disconnect();
    }

    pub fn is_connected(&self) -> bool {
        self.core().device_manager().is_connected()
    }

    // ═══ Sync / status ═══════════════════════════════════════════════

    /// Identify the firmware and read every parameter. Err only when the device
    /// could not be talked to; check `DspState.compat` for compatibility.
    pub fn fetch_all(&self) -> Result<()> {
        Ok(self.core().fetch_all()?)
    }

    pub fn refresh_params(&self) -> Result<()> {
        Ok(self.core().refresh_params()?)
    }

    /// Write the whole cached state in one chunked bulk transfer. Like the
    /// core, `Disconnected` also when no image has been read yet (nothing to
    /// apply: call `fetch_all` first).
    pub fn apply_all_params(&self) -> Result<()> {
        Ok(self.core().apply_all_params()?)
    }

    pub fn fetch_status(&self) -> Result<SystemStatus> {
        Ok(self.core().fetch_status()?.into())
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
        Ok(self.core().set_filter(ch, band, params.into())?)
    }

    /// Crossover band 0..3 of an output's wire channel `ch`.
    pub fn set_crossover(&self, ch: u8, xband: u8, params: FilterParams) -> Result<()> {
        Ok(self.core().set_crossover(ch, xband, params.into())?)
    }

    /// `band`: PEQ 0..9 or crossover 20..23.
    pub fn set_band_bypass(&self, ch: u8, band: u8, bypass: bool) -> Result<()> {
        Ok(self.core().set_band_bypass(ch, band, bypass)?)
    }

    // ═══ Preamp / bypass / volume ════════════════════════════════════

    /// Legacy preamp: every input to the same value.
    pub fn set_preamp(&self, db: f32) -> Result<()> {
        Ok(self.core().set_preamp(db)?)
    }

    pub fn set_input_preamp(&self, input: u8, db: f32) -> Result<()> {
        Ok(self.core().set_input_preamp(input, db)?)
    }

    pub fn set_bypass(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_bypass(enabled)?)
    }

    /// dB: −128 = mute, otherwise −127..0.
    pub fn set_master_volume(&self, db: f32) -> Result<()> {
        Ok(self.core().set_master_volume(db)?)
    }

    /// 0 = independent of presets, 1 = saved with presets.
    pub fn set_master_volume_mode(&self, mode: u8) -> Result<()> {
        Ok(self.core().set_master_volume_mode(mode)?)
    }

    /// FLASH WRITE. Refused { PRESET_* } on failure.
    pub fn save_master_volume(&self) -> Result<()> {
        status(self.core().save_master_volume()?)
    }

    pub fn set_user_volume(&self, db: f32) -> Result<()> {
        Ok(self.core().set_user_volume(db)?)
    }

    pub fn set_user_mute(&self, muted: bool) -> Result<()> {
        Ok(self.core().set_user_mute(muted)?)
    }

    // ═══ Delay ═══════════════════════════════════════════════════════

    pub fn set_delay(&self, ch: u8, ms: f32) -> Result<()> {
        Ok(self.core().set_delay(ch, ms)?)
    }

    // ═══ Loudness ════════════════════════════════════════════════════

    pub fn set_loudness(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_loudness(enabled)?)
    }

    pub fn set_loudness_ref(&self, spl: f32) -> Result<()> {
        Ok(self.core().set_loudness_ref(spl)?)
    }

    pub fn set_loudness_intensity(&self, pct: f32) -> Result<()> {
        Ok(self.core().set_loudness_intensity(pct)?)
    }

    pub fn set_loudness_mask(&self, mask: u16) -> Result<()> {
        Ok(self.core().set_loudness_mask(mask)?)
    }

    // ═══ Crossfeed ═══════════════════════════════════════════════════

    pub fn set_crossfeed(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_crossfeed(enabled)?)
    }

    pub fn set_crossfeed_preset(&self, preset: u8) -> Result<()> {
        Ok(self.core().set_crossfeed_preset(preset)?)
    }

    pub fn set_crossfeed_freq(&self, freq: f32) -> Result<()> {
        Ok(self.core().set_crossfeed_freq(freq)?)
    }

    pub fn set_crossfeed_feed(&self, feed: f32) -> Result<()> {
        Ok(self.core().set_crossfeed_feed(feed)?)
    }

    pub fn set_crossfeed_itd(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_crossfeed_itd(enabled)?)
    }

    pub fn set_crossfeed_outputs(&self, pair_mask: u8) -> Result<()> {
        Ok(self.core().set_crossfeed_outputs(pair_mask)?)
    }

    /// K340 firmware fork (third_party/patches/0002): the outputs of every
    /// crossfeed pair at once, 4 bytes (`DspState.crossfeed_pair_map`
    /// encoding). `InvalidArgument` (no USB traffic) for a map the firmware
    /// would refuse (see `crossfeed_pair_map_valid`); stored normalized.
    pub fn set_crossfeed_pair_map(&self, map: Vec<u8>) -> Result<()> {
        let map: [u8; 4] = map.try_into().map_err(|m: Vec<u8>| DspiError::InvalidArgument {
            msg: format!("crossfeed pair map needs 4 bytes, got {}", m.len()),
        })?;
        Ok(self.core().set_crossfeed_pair_map(map)?)
    }

    /// Reads the live pair map (4 bytes) into the state. An error from a
    /// connected device means the firmware does not have the feature (it
    /// STALLs the request); the state then keeps the bulk image's zero map.
    pub fn fetch_crossfeed_pair_map(&self) -> Result<Vec<u8>> {
        Ok(self.core().fetch_crossfeed_pair_map()?.to_vec())
    }

    // ═══ Volume leveller ═════════════════════════════════════════════

    pub fn set_leveller_enabled(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_leveller_enabled(enabled)?)
    }

    pub fn set_leveller_amount(&self, pct: f32) -> Result<()> {
        Ok(self.core().set_leveller_amount(pct)?)
    }

    /// 0 slow, 1 medium, 2 fast.
    pub fn set_leveller_speed(&self, speed: u8) -> Result<()> {
        Ok(self.core().set_leveller_speed(speed)?)
    }

    pub fn set_leveller_max_gain(&self, db: f32) -> Result<()> {
        Ok(self.core().set_leveller_max_gain(db)?)
    }

    pub fn set_leveller_lookahead(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_leveller_lookahead(enabled)?)
    }

    pub fn set_leveller_gate(&self, db: f32) -> Result<()> {
        Ok(self.core().set_leveller_gate(db)?)
    }

    pub fn set_leveller_masks(&self, detector: u8, apply: u8) -> Result<()> {
        Ok(self.core().set_leveller_masks(detector, apply)?)
    }

    pub fn set_input_source(&self, source: u8) -> Result<()> {
        Ok(self.core().set_input_source(source)?)
    }

    // ═══ Psychoacoustic bass ═════════════════════════════════════════

    /// `param`: PSYBASS_PARAM_*.
    pub fn set_psybass_param(&self, param: u8, value: f32) -> Result<()> {
        Ok(self.core().set_psybass_param(param, value)?)
    }

    pub fn set_psybass_mask(&self, mask: u16) -> Result<()> {
        Ok(self.core().set_psybass_mask(mask)?)
    }

    pub fn set_psybass_enabled(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_psybass_enabled(enabled)?)
    }

    // ═══ Matrix / outputs ════════════════════════════════════════════

    pub fn set_matrix_route(&self, input: u8, output: u8, enabled: bool, gain: f32, invert: bool) -> Result<()> {
        Ok(self.core().set_matrix_route(input, output, enabled, gain, invert)?)
    }

    /// Returns the resulting state: the firmware may refuse (PDM / Core 1 conflict).
    pub fn set_output_enable(&self, output: u8, enabled: bool) -> Result<bool> {
        Ok(self.core().set_output_enable(output, enabled)?)
    }

    pub fn set_output_gain(&self, output: u8, db: f32) -> Result<()> {
        Ok(self.core().set_output_gain(output, db)?)
    }

    pub fn set_output_mute(&self, output: u8, muted: bool) -> Result<()> {
        Ok(self.core().set_output_mute(output, muted)?)
    }

    pub fn set_output_delay(&self, output: u8, ms: f32) -> Result<()> {
        Ok(self.core().set_output_delay(output, ms)?)
    }

    /// `output` = LIMITER_ALL_OUTPUTS (0xFF) for every output; `param` LIMITER_PARAM_*.
    pub fn set_limiter_param(&self, output: u8, param: u8, value: f32) -> Result<()> {
        Ok(self.core().set_limiter_param(output, param, value)?)
    }

    /// Gain reduction (dB, > 0 while limiting) per output, MAX_OUTPUTS entries.
    pub fn fetch_limiter_meter(&self) -> Result<Vec<f32>> {
        Ok(self.core().fetch_limiter_meter()?.to_vec())
    }

    // ═══ Pins / output config ════════════════════════════════════════

    /// Refused { PIN_CONFIG_* } on a rejected pin.
    pub fn set_output_pin(&self, slot: u8, pin: u8) -> Result<()> {
        status(self.core().set_output_pin(slot, pin)?)
    }

    pub fn fetch_output_pin(&self, slot: u8) -> Result<u8> {
        Ok(self.core().fetch_output_pin(slot)?)
    }

    /// 0 = output config stored independently, 1 = saved with presets.
    pub fn set_output_config_mode(&self, mode: u8) -> Result<()> {
        Ok(self.core().set_output_config_mode(mode)?)
    }

    /// FLASH WRITE. Refused { PRESET_* }.
    pub fn save_output_config(&self) -> Result<()> {
        status(self.core().save_output_config()?)
    }

    // ═══ Channel names ═══════════════════════════════════════════════

    pub fn set_channel_name(&self, ch: u8, name: String) -> Result<()> {
        Ok(self.core().set_channel_name(ch, &name)?)
    }

    /// Reads the name from the device.
    pub fn get_channel_name(&self, ch: u8) -> Result<String> {
        Ok(self.core().fetch_channel_name(ch)?)
    }

    // ═══ Presets ═════════════════════════════════════════════════════

    /// FLASH WRITE (waits for the deferred save). Refused { PRESET_* }.
    pub fn save_preset(&self, slot: u8) -> Result<()> {
        status(self.core().save_preset(slot)?)
    }

    /// Load and re-read every parameter. Refused { PRESET_* }.
    pub fn load_preset(&self, slot: u8) -> Result<()> {
        status(self.core().load_preset(slot)?)
    }

    /// FLASH WRITE: live state -> `dest`, re-save `source` (stays active).
    pub fn copy_preset(&self, source: u8, dest: u8) -> Result<()> {
        // The core reports "a deferred save never confirmed" as 0xFF, which
        // is not a PRESET_* code: surface it as a transfer failure.
        match self.core().copy_preset(source, dest)? {
            0xFF => Err(DspiError::Transfer { msg: "the device did not confirm the preset save in time".into() }),
            code => status(code),
        }
    }

    /// FLASH WRITE.
    pub fn delete_preset(&self, slot: u8) -> Result<()> {
        status(self.core().delete_preset(slot)?)
    }

    pub fn set_preset_name(&self, slot: u8, name: String) -> Result<()> {
        Ok(self.core().set_preset_name(slot, &name)?)
    }

    pub fn get_preset_name(&self, slot: u8) -> Result<String> {
        Ok(self.core().get_preset_name(slot)?)
    }

    pub fn get_preset_directory(&self) -> Result<PresetDirectory> {
        Ok(self.core().get_preset_directory()?.into())
    }

    /// mode 0 = load `default_slot` at boot, 1 = last active slot.
    pub fn set_preset_startup(&self, mode: u8, default_slot: u8) -> Result<()> {
        Ok(self.core().set_preset_startup(mode, default_slot)?)
    }

    // ═══ Flash / system ══════════════════════════════════════════════

    /// FLASH WRITE. Refused { FLASH_* }.
    pub fn save_params(&self) -> Result<()> {
        status(self.core().save_params()?)
    }

    /// Revert to saved (reloads the active preset). Refused { FLASH_* }.
    pub fn load_params(&self) -> Result<()> {
        status(self.core().load_params()?)
    }

    /// FLASH WRITE. Refused { FLASH_* }.
    pub fn factory_reset(&self) -> Result<()> {
        status(self.core().factory_reset()?)
    }

    /// Reboots the device into its USB bootloader (it disappears). Always Ok,
    /// as in the core: the transfer result is ignored because the device
    /// drops off the bus (also Ok without a device).
    pub fn enter_bootloader(&self) -> Result<()> {
        Ok(self.core().enter_bootloader()?)
    }

    /// 0 idle, 1 PDM, 2 EQ worker.
    pub fn fetch_core1_mode(&self) -> Result<u8> {
        Ok(self.core().fetch_core1_mode()?)
    }

    /// Whether enabling `output` would conflict with Core 1's mode.
    pub fn check_core1_conflict(&self, output: u8) -> Result<bool> {
        Ok(self.core().check_core1_conflict(output)?)
    }

    pub fn clear_clips(&self) -> Result<()> {
        self.core().clear_clips()?;
        Ok(())
    }

    // ═══ Hot-plug ════════════════════════════════════════════════════

    /// Register (Some) or clear (None) the arrival/departure listener.
    /// Clearing waits for a call in progress on another thread; replacing
    /// (Some -> Some) does not wait.
    pub fn set_hotplug_callback(&self, listener: Option<Box<dyn DeviceEvents>>) {
        self.listeners.device_events.set(listener);
    }

    /// libusb poll (~500 ms on desktop). No-op on Android.
    pub fn poll_hotplug(&self) {
        // Android: libusb cannot enumerate (discovery is off); arrivals and
        // departures come from USB broadcasts in :usb instead.
        if cfg!(target_os = "android") {
            return;
        }
        // Like dspi_poll_hotplug, but the listener runs after the core lock
        // is released, so a listener that calls back cannot deadlock.
        let (arrivals, departures) = self.core().device_manager_mut().poll_changes();
        for serial in arrivals {
            self.listeners.device_events.call(|l| l.on_arrived(serial));
        }
        for serial in departures {
            self.listeners.device_events.call(|l| l.on_departed(serial));
        }
    }

    // ═══ Upmixer, subharmonic synth, tube modeller ══════════════════

    /// UPMIX_PARAM_*; RP2350 only.
    pub fn set_upmix_param(&self, id: u8, value: f32) -> Result<()> {
        Ok(self.core().set_upmix_param(id, value)?)
    }

    pub fn fetch_upmix_status(&self) -> Result<UpmixStatus> {
        Ok(self.core().fetch_upmix_status()?.into())
    }

    /// SUBHARM_PARAM_*.
    pub fn set_subharm_param(&self, id: u8, value: f32) -> Result<()> {
        Ok(self.core().set_subharm_param(id, value)?)
    }

    pub fn set_subharm_solo(&self, solo: bool) -> Result<()> {
        Ok(self.core().set_subharm_solo(solo)?)
    }

    /// Headroom the subharm settings cost, dB.
    pub fn fetch_subharm_headroom(&self) -> Result<f32> {
        Ok(self.core().fetch_subharm_headroom()?)
    }

    /// Synthesized sub peak per output 0..1, MAX_OUTPUTS entries.
    pub fn fetch_subharm_meter(&self) -> Result<Vec<f32>> {
        Ok(self.core().fetch_subharm_meter()?.to_vec())
    }

    /// TUBE_PARAM_*.
    pub fn set_tube_param(&self, idx: u8, value: f32) -> Result<()> {
        Ok(self.core().set_tube_param(idx, value)?)
    }

    // ═══ Hardware IO — Refused { PIN_CONFIG_* } on rejection ═════════

    /// 0 = S/PDIF, 1 = I2S.
    pub fn set_output_type(&self, slot: u8, kind: u8) -> Result<()> {
        status(self.core().set_output_type(slot, kind)?)
    }

    /// role 0 = master/shared, 1 = slave pair.
    pub fn set_i2s_bck_pin(&self, role: u8, pin: u8) -> Result<()> {
        status(self.core().set_i2s_bck_pin(role, pin)?)
    }

    pub fn set_i2s_clock_pin_mode(&self, mode: u8) -> Result<()> {
        status(self.core().set_i2s_clock_pin_mode(mode)?)
    }

    pub fn set_mck_enabled(&self, enabled: bool) -> Result<()> {
        status(self.core().set_mck_enabled(enabled)?)
    }

    pub fn set_mck_pin(&self, pin: u8) -> Result<()> {
        status(self.core().set_mck_pin(pin)?)
    }

    /// 0 = 128×fs, 1 = 256×fs.
    pub fn set_mck_multiplier(&self, mult: u8) -> Result<()> {
        status(self.core().set_mck_multiplier(mult)?)
    }

    pub fn set_adat_out_enabled(&self, enabled: bool) -> Result<()> {
        status(self.core().set_adat_out_enabled(enabled)?)
    }

    pub fn set_adat_out_pin(&self, pin: u8) -> Result<()> {
        status(self.core().set_adat_out_pin(pin)?)
    }

    pub fn set_spdif_rx_pin(&self, index: u8, pin: u8) -> Result<()> {
        status(self.core().set_spdif_rx_pin(index, pin)?)
    }

    /// index 1..3 (input 1 is always on).
    pub fn set_spdif_input_enabled(&self, index: u8, enabled: bool) -> Result<()> {
        status(self.core().set_spdif_input_enabled(index, enabled)?)
    }

    pub fn set_i2s_rx_pin(&self, pair: u8, pin: u8) -> Result<()> {
        status(self.core().set_i2s_rx_pin(pair, pin)?)
    }

    /// 2, 4, 6 or 8.
    pub fn set_i2s_input_channels(&self, channels: u8) -> Result<()> {
        status(self.core().set_i2s_input_channels(channels)?)
    }

    pub fn set_adat_input_enabled(&self, enabled: bool) -> Result<()> {
        status(self.core().set_adat_input_enabled(enabled)?)
    }

    /// 0xFF clears the pin.
    pub fn set_adat_input_pin(&self, pin: u8) -> Result<()> {
        status(self.core().set_adat_input_pin(pin)?)
    }

    pub fn set_adat_input_clock_mode(&self, mode: u8) -> Result<()> {
        status(self.core().set_adat_input_clock_mode(mode)?)
    }

    /// I2S/ADAT-master input rate: 0 = 44.1k, 1 = 48k, 2 = 96k (others clamp
    /// to 48k). REQ_SET_INPUT_RATE 0xED is a payload SET with no status byte:
    /// Ok means the transfer completed. The firmware stores the rate and
    /// applies it live only while it is the rate authority (I2S or ADAT input
    /// in master clock mode).
    pub fn set_input_rate(&self, index: u8) -> Result<()> {
        Ok(self.core().set_input_rate(index)?)
    }

    /// Hz the pipeline runs at now (0 if unknown): the LIVE rate (firmware
    /// `audio_state.freq`, REQ_GET_INPUT_RATE 0xEE word 0, same as status
    /// wValue 15) for every source, not the configured I2S rate (word 1,
    /// ignored; that one is `DspState.i2s_input_rate`).
    pub fn fetch_input_rate(&self) -> Result<u32> {
        Ok(self.core().fetch_input_rate()?)
    }

    /// I2S clock mode, 0 master / 1 slave. REQ_SET_I2S_CLOCK_MODE 0x88 is a
    /// payload SET with no status byte (Ok = transfer completed); the
    /// firmware applies it deferred in its main loop.
    pub fn set_i2s_clock_mode(&self, mode: u8) -> Result<()> {
        Ok(self.core().set_i2s_clock_mode(mode)?)
    }

    /// `which`: 0 = I2S slave lock, 1 = ADAT input lock.
    pub fn fetch_input_lock(&self, which: u8) -> Result<InputLockStatus> {
        let c = self.core();
        let st = if which == 0 { c.fetch_i2s_slave_status() } else { c.fetch_adat_input_status() };
        Ok(st?.into())
    }

    pub fn fetch_adat_out_status(&self) -> Result<AdatOutStatus> {
        Ok(self.core().fetch_adat_out_status()?.into())
    }

    // ═══ DAC mute, LG Sound Sync, UART / I2C ═════════════════════════

    /// Applied later; read back with `fetch_dac_mute` ~150 ms after.
    pub fn set_dac_mute(&self, enabled: bool, active_low: bool, pin: u8, hold_ms: u16, release_ms: u16) -> Result<()> {
        Ok(self.core().set_dac_mute(enabled, active_low, pin, hold_ms, release_ms)?)
    }

    /// Re-reads the DAC mute config into the state.
    pub fn fetch_dac_mute(&self) -> Result<()> {
        Ok(self.core().fetch_dac_mute()?)
    }

    /// Pulse the mute output ~1 s. Refused { 3 } = feature disabled.
    pub fn test_dac_mute(&self) -> Result<()> {
        status(self.core().test_dac_mute()?)
    }

    pub fn set_lg_sound_sync(&self, enabled: bool) -> Result<()> {
        Ok(self.core().set_lg_sound_sync(enabled)?)
    }

    pub fn fetch_lg_status(&self) -> Result<LgStatus> {
        Ok(self.core().fetch_lg_status()?.into())
    }

    /// Re-read UART/I2C configs + status into the state.
    pub fn fetch_ctrl_ifaces(&self) -> Result<()> {
        Ok(self.core().fetch_ctrl_ifaces()?)
    }

    /// Outcome readable via `fetch_ctrl_ifaces` ~250 ms later.
    pub fn set_uart(&self, config: UartConfig) -> Result<()> {
        Ok(self.core().set_uart(config.into())?)
    }

    pub fn set_i2c(&self, config: I2cConfig) -> Result<()> {
        Ok(self.core().set_i2c(config.into())?)
    }

    // ═══ Device notifications ════════════════════════════════════════

    /// Register (Some) or clear (None) the wake-up listener of the notify thread.
    /// Clearing waits for a call in progress on another thread; replacing
    /// (Some -> Some) does not wait, and the old listener may then be
    /// released on the notify thread when its running call ends.
    pub fn set_notify_listener(&self, listener: Option<Box<dyn NotifyListener>>) {
        // The trampoline is registered with the hub in `new`; no core or hub
        // lock is taken here (a callback may call this).
        self.listeners.notify.set(listener);
    }

    /// Apply queued notifications to the state; returns what changed.
    pub fn process_notifications(&self) -> NotifyResult {
        self.core().process_notifications().into()
    }

    // ═══ Spectrum analyser ═══════════════════════════════════════════

    /// Register (Some) or clear (None) the analyser wake-up listener. Once it
    /// returns after None, no call is in progress (on another thread).
    /// Replacing (Some -> Some) does not wait, and the old listener may then
    /// be released on the RTA thread when its running call ends.
    pub fn rta_set_listener(&self, listener: Option<Box<dyn RtaListener>>) {
        // As `set_notify_listener`; None waits for calls in flight elsewhere.
        self.listeners.rta.set(listener);
    }

    /// Probe and start polling (compatible firmware only); stops on disconnect.
    pub fn rta_start(&self) {
        self.core().device_manager_mut().start_rta();
    }

    /// What to analyse; mask 0 releases the analyser.
    pub fn rta_set_request(&self, request: RtaRequest) {
        self.core().device_manager().rta_hub().set_request(request.into());
    }

    pub fn rta_get_caps(&self) -> RtaCapsInfo {
        self.core().device_manager().rta_hub().caps().into()
    }

    pub fn rta_get_snapshot(&self) -> RtaSnapshot {
        // Copy the hub out under the core lock, read the snapshot outside it
        // (as dspi_rta_get_snapshot does).
        let hub = self.core().device_manager().rta_hub();
        RtaSnapshot::from(&hub.snapshot())
    }

    // ═══ Signal generator ════════════════════════════════════════════

    /// Ok with `supported = false` when the firmware has no generator.
    pub fn siggen_fetch_caps(&self) -> Result<SiggenCaps> {
        Ok(SiggenCaps::from(&self.core().fetch_siggen_caps()?))
    }

    /// The config the generator runs, as the device sanitised it.
    pub fn siggen_get_config(&self) -> Result<SiggenConfig> {
        Ok(self.core().fetch_siggen_config()?.into())
    }

    /// Stage a config (a running generator restarts). The device ACKs even a
    /// refused config: read it back.
    pub fn siggen_set_config(&self, config: SiggenConfig) -> Result<()> {
        Ok(self.core().set_siggen_config(&(&config).into())?)
    }

    /// SIGGEN_CTL_START / STOP (fade) / STOP_NOW.
    pub fn siggen_control(&self, action: u16) -> Result<()> {
        Ok(self.core().siggen_control(action)?)
    }

    pub fn siggen_get_status(&self) -> Result<SiggenStatus> {
        Ok(self.core().fetch_siggen_status()?.into())
    }

    // ═══ Statistics ══════════════════════════════════════════════════

    /// One REQ_GET_STATUS u32 counter (STAT_*).
    pub fn fetch_stat(&self, which: u16) -> Result<u32> {
        Ok(self.core().fetch_stat(which)?)
    }

    pub fn fetch_buffer_stats(&self) -> Result<BufferStats> {
        Ok(self.core().fetch_buffer_stats()?.into())
    }

    pub fn reset_buffer_stats(&self) -> Result<()> {
        Ok(self.core().reset_buffer_stats()?)
    }

    pub fn fetch_spdif_rx_status(&self) -> Result<SpdifRxStatus> {
        Ok(self.core().fetch_spdif_rx_status()?.into())
    }

    /// 24 bytes of IEC 60958 channel status.
    pub fn fetch_spdif_rx_channel_status(&self) -> Result<Vec<u8>> {
        Ok(self.core().fetch_spdif_rx_channel_status()?.to_vec())
    }

    /// GPIO of S/PDIF receiver `index`; Err if it has none.
    pub fn fetch_spdif_rx_pin(&self, index: u8) -> Result<u8> {
        Ok(self.core().fetch_spdif_rx_pin(index)?)
    }

    // ═══ Interrupt monitor ═══════════════════════════════════════════

    /// Log entries after `after_id` (typed replacement of the TSV buffer).
    pub fn monitor_read(&self, after_id: u64) -> MonitorBatch {
        let hub = self.core().device_manager().notify_hub();
        let mut entries = Vec::new();
        hub.monitor.read_after(after_id, |e| entries.push(MonitorEntry::from(e)));
        MonitorBatch { last_id: entries.last().map_or(after_id, |e| e.id), entries }
    }

    pub fn monitor_clear(&self) {
        self.core().device_manager().notify_hub().monitor.clear();
    }

    // ═══ Configuration and filter files (JSON passthrough) ═══════════

    /// The device configuration as a `.dspipreset` document. `links` = the
    /// app's input-pair link mask.
    pub fn config_export(&self, name: String, app_version: String, links: u8) -> String {
        presetfile::export(self.core().state(), &name, &app_version, links)
    }

    /// Apply a document (`options`: 1 = volumes, 2 = hardware I/O). JSON
    /// {ok, error, lines, clean, links (or null), hardwareWritten}.
    pub fn config_import(&self, text: String, options: u32) -> String {
        let out = match presetfile::parse(&text) {
            Err(e) => json!({ "ok": false, "error": e }),
            Ok(v) => match self.core().import_preset(&v, options) {
                Ok(r) => json!({ "ok": true, "lines": r.lines, "clean": r.clean, "links": r.links,
                                 "hardwareWritten": r.hardware_written }),
                Err(e) => json!({ "ok": false, "error": format!("The device could not be written: {e}.") }),
            },
        };
        out.to_string()
    }

    /// Every channel's filters as text (`inputs` = live inputs to write).
    pub fn filters_export(&self, inputs: u8) -> String {
        filterfile::export(self.core().state(), inputs as usize)
    }

    /// What a filter file holds, for the channel picker (JSON).
    pub fn filters_inspect(&self, text: String, inputs: u8) -> String {
        filterfile::inspect(self.core().state(), &text, inputs as usize).to_string()
    }

    /// Apply a filter file to `wires` (wire indices). JSON {ok, error, lines}.
    pub fn filters_import(&self, text: String, wires: Vec<u8>) -> String {
        let chosen: Vec<usize> = wires.iter().map(|&w| w as usize).collect();
        let out = match self.core().import_filters(&text, &chosen) {
            Ok(lines) => json!({ "ok": true, "lines": lines }),
            Err(e) => json!({ "ok": false, "error": format!("The device could not be written: {e}.") }),
        };
        out.to_string()
    }

    // ═══ Control surfaces (JSON passthrough, see core cs.rs) ═════════

    /// Read the whole CS configuration from the device.
    pub fn cs_fetch(&self) -> Result<()> {
        Ok(self.core().cs_fetch_all()?)
    }

    /// The CS configuration as JSON (`cs::cs_snapshot`).
    pub fn cs_snapshot(&self) -> String {
        self.core().cs_snapshot().to_string()
    }

    /// One CS operation `{op, index, ...}`; JSON {ok, status, error, ...}.
    /// Some ops write flash (save) — the bridge gates them (DangerousOps).
    pub fn cs_apply(&self, op: String) -> String {
        let out = match serde_json::from_str::<serde_json::Value>(&op) {
            Ok(v) => match self.core().cs_apply(&v) {
                Ok(r) => r,
                Err(e) => json!({ "ok": false, "error": format!("{e}") }),
            },
            Err(e) => json!({ "ok": false, "error": format!("{e}") }),
        };
        out.to_string()
    }

    /// GPIOs held by live CS bindings.
    pub fn cs_pin_uses(&self) -> Vec<PinUse> {
        self.core().cs_pin_uses().into_iter().map(|(pin, slot)| PinUse { pin, slot }).collect()
    }

    // ═══ Unsaved changes ═════════════════════════════════════════════

    /// The live state now matches the active preset.
    pub fn capture_baseline(&self) {
        self.core().capture_baseline();
    }

    pub fn preset_dirty(&self) -> bool {
        !self.core().preset_changes().is_empty()
    }

    /// What differs from the active preset, one sentence each.
    pub fn preset_changes(&self) -> Vec<String> {
        self.core().preset_changes()
    }
}

#[cfg(test)]
mod tests {
    //! Listener re-entrancy (review F1). The hubs only fire with a device,
    //! so these call the trampolines directly, as the hub threads would.
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::sync::mpsc;
    use std::sync::Weak;
    use std::time::Duration;

    use super::*;

    fn fire_notify(core: &DspiCore) {
        notify_trampoline(&*core.listeners as *const Listeners as *mut c_void);
    }

    fn fire_rta(core: &DspiCore) {
        rta_trampoline(&*core.listeners as *const Listeners as *mut c_void);
    }

    struct Count(Arc<AtomicUsize>);
    impl NotifyListener for Count {
        fn on_notifications(&self) {
            self.0.fetch_add(1, Ordering::SeqCst);
        }
    }

    /// On its first call, replaces itself and clears the RTA listener.
    struct Reregister {
        core: Weak<DspiCore>,
        hits: Arc<AtomicUsize>,
    }
    impl NotifyListener for Reregister {
        fn on_notifications(&self) {
            let core = self.core.upgrade().unwrap();
            core.set_notify_listener(Some(Box::new(Count(self.hits.clone()))));
            core.rta_set_listener(None);
            core.set_hotplug_callback(None);
        }
    }

    #[test]
    fn a_callback_can_reregister_listeners() {
        let core = DspiCore::new();
        let hits = Arc::new(AtomicUsize::new(0));
        core.set_notify_listener(Some(Box::new(Reregister { core: Arc::downgrade(&core), hits: hits.clone() })));
        let (tx, rx) = mpsc::channel();
        let c = core.clone();
        std::thread::spawn(move || {
            fire_notify(&c); // Reregister swaps itself for Count
            fire_notify(&c); // Count
            tx.send(()).unwrap();
        });
        rx.recv_timeout(Duration::from_secs(5)).expect("deadlock: callback re-registering a listener");
        assert_eq!(hits.load(Ordering::SeqCst), 1);
    }

    /// Blocks inside its callback until released (signals when inside). It
    /// does not call into the core: that is unsupported from a callback
    /// (see the module doc).
    struct Blocking {
        inside: Mutex<Option<mpsc::Sender<()>>>,
        release: Mutex<mpsc::Receiver<()>>,
    }
    impl RtaListener for Blocking {
        fn on_frame(&self) {
            if let Some(tx) = lock(&self.inside).take() {
                tx.send(()).unwrap();
            }
            let _ = lock(&self.release).recv_timeout(Duration::from_secs(5));
        }
    }

    #[test]
    fn listeners_change_while_a_callback_is_in_flight() {
        let core = DspiCore::new();
        let (inside_tx, inside_rx) = mpsc::channel();
        let (release_tx, release_rx) = mpsc::channel();
        core.rta_set_listener(Some(Box::new(Blocking {
            inside: Mutex::new(Some(inside_tx)),
            release: Mutex::new(release_rx),
        })));
        let (done_tx, done_rx) = mpsc::channel();
        let c = core.clone();
        let worker = std::thread::spawn(move || {
            fire_rta(&c);
            done_tx.send(()).unwrap();
        });
        inside_rx.recv_timeout(Duration::from_secs(5)).unwrap();
        // The RTA callback is in flight: other slots are independent, the core
        // lock is free, and replacing the RTA listener does not wait.
        core.set_notify_listener(Some(Box::new(Count(Arc::new(AtomicUsize::new(0))))));
        core.set_notify_listener(None);
        core.set_hotplug_callback(None);
        let _ = core.get_state();
        core.rta_set_listener(Some(Box::new(Blocking {
            inside: Mutex::new(None),
            release: Mutex::new(mpsc::channel().1),
        })));
        release_tx.send(()).unwrap();
        // Clearing waits for the old listener's call in flight.
        core.rta_set_listener(None);
        done_rx.recv_timeout(Duration::from_secs(5)).expect("callback did not finish");
        worker.join().unwrap();
    }

    #[test]
    fn clearing_from_another_slots_callback_still_waits() {
        // Review N2: being inside the RTA callback must not skip the wait for
        // a notify call running on another thread.
        struct SlowNotify(Arc<AtomicUsize>);
        impl NotifyListener for SlowNotify {
            fn on_notifications(&self) {
                std::thread::sleep(Duration::from_millis(200));
                self.0.store(1, Ordering::SeqCst);
            }
        }
        struct ClearsNotify {
            core: Weak<DspiCore>,
            seen: Arc<AtomicUsize>,
            result: Arc<AtomicUsize>,
        }
        impl RtaListener for ClearsNotify {
            fn on_frame(&self) {
                self.core.upgrade().unwrap().set_notify_listener(None);
                // After the clear returns, the slow notify call has finished.
                self.result.store(self.seen.load(Ordering::SeqCst) + 10, Ordering::SeqCst);
            }
        }
        let core = DspiCore::new();
        let seen = Arc::new(AtomicUsize::new(0));
        let result = Arc::new(AtomicUsize::new(0));
        core.set_notify_listener(Some(Box::new(SlowNotify(seen.clone()))));
        core.rta_set_listener(Some(Box::new(ClearsNotify {
            core: Arc::downgrade(&core),
            seen: seen.clone(),
            result: result.clone(),
        })));
        let c = core.clone();
        let notify = std::thread::spawn(move || fire_notify(&c));
        std::thread::sleep(Duration::from_millis(50));
        fire_rta(&core);
        notify.join().unwrap();
        assert_eq!(result.load(Ordering::SeqCst), 11, "cleared while the notify call was still running");
    }

    #[test]
    fn clearing_waits_for_the_call_in_flight() {
        struct Slow(Arc<AtomicUsize>);
        impl RtaListener for Slow {
            fn on_frame(&self) {
                std::thread::sleep(Duration::from_millis(200));
                self.0.store(1, Ordering::SeqCst);
            }
        }
        let core = DspiCore::new();
        let done = Arc::new(AtomicUsize::new(0));
        core.rta_set_listener(Some(Box::new(Slow(done.clone()))));
        let c = core.clone();
        let t = std::thread::spawn(move || fire_rta(&c));
        std::thread::sleep(Duration::from_millis(50));
        core.rta_set_listener(None);
        assert_eq!(done.load(Ordering::SeqCst), 1, "rta_set_listener(None) returned while a call was running");
        t.join().unwrap();
    }

    #[test]
    fn a_panicking_listener_is_contained() {
        struct Boom;
        impl NotifyListener for Boom {
            fn on_notifications(&self) {
                panic!("listener failure");
            }
        }
        let core = DspiCore::new();
        core.set_notify_listener(Some(Box::new(Boom)));
        fire_notify(&core);
        core.set_notify_listener(None); // busy count went back to 0
    }
}

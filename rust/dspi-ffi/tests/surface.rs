//! Host-side checks of the UniFFI surface without a device: defaults, the
//! disconnected error path, JSON passthroughs, listeners and curve math.
//! Nothing here opens, enumerates or claims a USB device.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

use dspi_ffi::*;

#[test]
fn default_state_carries_the_core_defaults() {
    // protocol.rs UPMIX_DEFAULTS / SUBHARM_DEFAULTS / TUBE_DEFAULTS (pub(crate)
    // upstream, so pinned here; BRIDGE's fake uses the same numbers).
    let s = DspiCore::new().get_state();
    assert_eq!(s.upmix, [0.0, 1.0, 2.0, 100.0, 25.0, 30.0, 10.0, 100.0, 200.0, 12.0, 300.0, 7000.0, 90.0, 0.0]);
    assert_eq!(s.subharm, [0.0, 65535.0, 0.0, 0.0, -30.0, 0.0, 0.0, 100.0, 150.0, 0.0, 1.0]);
    assert_eq!(s.tube, [0.0, 65535.0, 1.0, -12.0, 10.0, 3.0, 40.0, 15.0, 1.0, 1.0, 2.0, 95.0, 100.0, 0.0]);
    assert_eq!(s.upmix.len(), dspi_core::protocol::UPMIX_PARAM_COUNT);
    assert_eq!(s.subharm.len(), dspi_core::protocol::SUBHARM_PARAM_COUNT);
    assert_eq!(s.tube.len(), dspi_core::protocol::TUBE_PARAM_COUNT);
    assert_eq!(s.compat, 0);
    assert!(!s.bulk_valid);
}

#[test]
fn every_device_call_fails_as_disconnected() {
    let c = DspiCore::new();
    let p = FilterParams { filter_type: 1, bypass: false, freq: 1000.0, q: 1.0, gain: 3.0, qp: 0.707 };
    assert_eq!(c.fetch_status(), Err(DspiError::Disconnected));
    assert_eq!(c.set_filter(0, 0, p), Err(DspiError::Disconnected));
    assert_eq!(c.set_output_enable(0, true), Err(DspiError::Disconnected));
    assert_eq!(c.set_output_pin(0, 2), Err(DspiError::Disconnected));
    assert_eq!(c.load_preset(1), Err(DspiError::Disconnected));
    assert_eq!(c.copy_preset(0, 1), Err(DspiError::Disconnected));
    assert_eq!(c.factory_reset(), Err(DspiError::Disconnected));
    assert_eq!(c.fetch_limiter_meter(), Err(DspiError::Disconnected));
    assert_eq!(c.fetch_input_lock(1), Err(DspiError::Disconnected));
    assert_eq!(c.siggen_fetch_caps(), Err(DspiError::Disconnected));
    assert_eq!(c.fetch_spdif_rx_channel_status(), Err(DspiError::Disconnected));
    assert_eq!(c.get_channel_name(0), Err(DspiError::Disconnected));
    assert_eq!(c.test_dac_mute(), Err(DspiError::Disconnected));
    // apply_all_params refuses without a decoded image (core: NotConnected).
    assert_eq!(c.apply_all_params(), Err(DspiError::Disconnected));
    // enter_bootloader ignores the transfer result, as in the core.
    assert_eq!(c.enter_bootloader(), Ok(()));
}

#[test]
fn bad_fd_is_an_invalid_argument() {
    let c = DspiCore::new();
    assert!(matches!(c.open_fd(-1, "X".into(), 0x2E8B), Err(DspiError::InvalidArgument { .. })));
    assert!(!c.is_connected());
    c.close_fd(); // no-op without a device
}

#[test]
fn json_passthroughs_match_the_c_abi() {
    let v: serde_json::Value = serde_json::from_str(&config_inspect("not json".into())).unwrap();
    assert_eq!(v["ok"], false);
    assert!(v["error"].is_string());

    let c = DspiCore::new();
    let doc = c.config_export("Test".into(), "0.1".into(), 0);
    let v: serde_json::Value = serde_json::from_str(&config_inspect(doc.clone())).unwrap();
    assert_eq!(v["ok"], true, "{v}");
    assert_eq!(v["name"], "Test");
    for k in ["platform", "firmware", "savedUtc"] {
        assert!(v.get(k).is_some(), "missing {k}");
    }

    let v: serde_json::Value = serde_json::from_str(&c.config_import("{".into(), 0)).unwrap();
    assert_eq!(v["ok"], false);

    let v: serde_json::Value = serde_json::from_str(&c.cs_apply("nope".into())).unwrap();
    assert_eq!(v["ok"], false);

    let text = c.filters_export(2);
    assert!(!text.is_empty());
    let v: serde_json::Value = serde_json::from_str(&c.filters_inspect(text, 2)).unwrap();
    assert!(v.is_object());

    let v: serde_json::Value = serde_json::from_str(&c.cs_snapshot()).unwrap();
    assert!(v.is_object());
    assert!(c.cs_pin_uses().is_empty());
}

#[test]
fn baseline_and_dirty_tracking() {
    let c = DspiCore::new();
    c.capture_baseline();
    assert!(!c.preset_dirty());
    assert!(c.preset_changes().is_empty());
    // The setter changes the cached state before the (failing) transfer.
    let _ = c.set_master_volume(-30.0);
    let _ = c.set_input_preamp(0, 3.0);
    assert!(c.preset_dirty() == !c.preset_changes().is_empty());
}

#[test]
fn monitor_is_empty_without_a_device() {
    let c = DspiCore::new();
    let b = c.monitor_read(7);
    assert_eq!(b.last_id, 7);
    assert!(b.entries.is_empty());
    c.monitor_clear();
    assert_eq!(c.process_notifications(), NotifyResult::default());
}

struct Count(Arc<AtomicUsize>);
impl NotifyListener for Count {
    fn on_notifications(&self) {
        self.0.fetch_add(1, Ordering::SeqCst);
    }
}
impl RtaListener for Count {
    fn on_frame(&self) {
        self.0.fetch_add(1, Ordering::SeqCst);
    }
}
impl DeviceEvents for Count {
    fn on_arrived(&self, _: String) {
        self.0.fetch_add(1, Ordering::SeqCst);
    }
    fn on_departed(&self, _: String) {
        self.0.fetch_add(1, Ordering::SeqCst);
    }
}

#[test]
fn listeners_register_clear_and_outlive_nothing() {
    let n = Arc::new(AtomicUsize::new(0));
    {
        let c = DspiCore::new();
        c.set_notify_listener(Some(Box::new(Count(n.clone()))));
        c.rta_set_listener(Some(Box::new(Count(n.clone()))));
        c.set_hotplug_callback(Some(Box::new(Count(n.clone()))));
        c.rta_set_request(RtaRequest {
            tap: 1,
            mask: 0b11,
            wants_bins: false,
            fft_order: 12,
            avg_ms: 300,
            peak_decay_db_s: 20,
        });
        assert!(!c.rta_get_caps().supported);
        let snap = c.rta_get_snapshot();
        assert_eq!(snap.frames.len(), 16);
        c.rta_start(); // no connection: does nothing
        c.rta_set_listener(None);
        c.set_notify_listener(None);
        c.set_hotplug_callback(None);
        c.set_notify_listener(Some(Box::new(Count(n.clone()))));
        // Dropped with a listener registered: Drop unhooks the wakers first.
    }
    // No device, no worker threads: nothing fired; the Arc is ours again.
    assert_eq!(n.load(Ordering::SeqCst), 0);
    assert_eq!(Arc::strong_count(&n), 1);
}

#[test]
fn curve_math_matches_the_core() {
    let peak = FilterParams { filter_type: 1, bypass: false, freq: 1000.0, q: 1.0, gain: 6.0, qp: 0.707 };
    assert!((compute_response(vec![peak], 1000.0) - 6.0).abs() < 0.05);
    assert_eq!(compute_response(vec![], 1000.0), 0.0);

    let mag = compute_magnitude_curve(vec![peak]);
    let ph = compute_phase_curve(vec![peak], true);
    assert_eq!(mag.len(), 201);
    assert_eq!(ph.len(), 201);
    let core: Vec<dspi_core::types::FilterParams> = vec![peak.into()];
    assert_eq!(mag, dspi_core::dsp_math::compute_magnitude_curve(&core).to_vec());
    assert_eq!(ph, dspi_core::dsp_math::compute_phase_curve(&core, true).to_vec());
    // Peak of the curve sits at the band's centre.
    let (imax, _) = mag.iter().enumerate().fold((0, f64::MIN), |a, (i, &v)| if v > a.1 { (i, v) } else { a });
    assert!((curve_freq(imax as u32) / 1000.0 - 1.0).abs() < 0.05);
    assert!((curve_freq(0) - 10.0).abs() < 1e-6);
    assert!((curve_freq(200) - 20000.0).abs() < 1e-3);

    // Bypassed bands are flat.
    let flat = compute_magnitude_curve(vec![FilterParams { bypass: true, ..peak }]);
    assert!(flat.iter().all(|v| v.abs() < 1e-9));
}

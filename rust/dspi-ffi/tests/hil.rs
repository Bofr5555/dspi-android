//! Hardware-in-the-loop checks against a real DSPi (desktop libusb path).
//!
//! AGENTS NEVER RUN THESE. They are `#[ignore]`d and additionally require
//! `DSPI_HIL=1`; the coordinator runs them on the owner's units:
//!
//!   DSPI_HIL=1 cargo test -p dspi-ffi --test hil -- --ignored --test-threads=1
//!
//! Read-only: nothing here writes parameters or flash.

use dspi_ffi::*;

fn hil() -> bool {
    std::env::var("DSPI_HIL").as_deref() == Ok("1")
}

#[test]
#[ignore = "hardware: set DSPI_HIL=1 and run with --ignored"]
fn select_fetch_and_read_back() {
    if !hil() {
        return;
    }
    let core = DspiCore::new();
    let devices = core.scan_devices();
    let dev = devices.first().expect("no DSPi on the bus");
    core.select_device(dev.serial.clone()).expect("select");
    assert!(core.is_connected());
    core.fetch_all().expect("fetch_all");
    let s = core.get_state();
    assert_eq!(s.compat, 1, "firmware not wire-compatible (format {})", s.format_version);
    assert_eq!(s.format_version, 32);
    assert!(s.bulk_valid);
    let st = core.fetch_status().expect("status");
    assert_eq!(st.peaks.len(), 17);
    core.get_preset_directory().expect("preset directory");
    core.siggen_fetch_caps().expect("siggen caps");
    core.capture_baseline();
    assert!(!core.preset_dirty());
    core.disconnect();
    assert!(!core.is_connected());
}

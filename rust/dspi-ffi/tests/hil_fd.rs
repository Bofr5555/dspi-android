//! Hardware-in-the-loop check of the Android fd path on a Linux desktop:
//! open the usbfs node ourselves (as UsbDeviceConnection does) and hand the
//! fd to `open_fd`. Own test binary, because `open_fd` turns libusb device
//! discovery off for the whole process.
//!
//! AGENTS NEVER RUN THIS. `#[ignore]` + `DSPI_HIL=1`, and the node to open:
//!
//!   DSPI_HIL=1 DSPI_HIL_USBFS=/dev/bus/usb/BBB/DDD DSPI_HIL_SERIAL=<serial> \
//!     cargo test -p dspi-ffi --test hil_fd -- --ignored
//!
//! Read-only: nothing here writes parameters or flash.

use std::os::fd::AsRawFd;

use dspi_ffi::*;

#[test]
#[ignore = "hardware: set DSPI_HIL=1, DSPI_HIL_USBFS, DSPI_HIL_SERIAL and run with --ignored"]
fn open_fd_fetch_close() {
    if std::env::var("DSPI_HIL").as_deref() != Ok("1") {
        return;
    }
    let node = std::env::var("DSPI_HIL_USBFS").expect("DSPI_HIL_USBFS=/dev/bus/usb/BBB/DDD");
    let serial = std::env::var("DSPI_HIL_SERIAL").unwrap_or_else(|_| "HIL".into());
    let file = std::fs::OpenOptions::new().read(true).write(true).open(&node).expect("open usbfs node");
    let core = DspiCore::new();
    core.open_fd(file.as_raw_fd(), serial, dspi_core::protocol::VENDOR_ID).expect("open_fd");
    assert!(core.is_connected());
    core.fetch_all().expect("fetch_all");
    assert_eq!(core.get_state().format_version, 32);
    core.fetch_status().expect("status");
    core.close_fd();
    assert!(!core.is_connected());
    drop(file); // the fd outlives the handle, as on Android
}

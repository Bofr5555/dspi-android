//! The core over DSPi Link (patch 0004 Transport + dspi-link), end to end
//! against the in-process fake hub: connect, pair, bind a device, fetch,
//! write, notifications with origin attribution, locks, roles, RESYNC and a
//! revoked session. No USB.

use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dspi_core::notify::{NOTIFY_REFRESHED, NOTIFY_STATE};
use dspi_ffi::*;
use dspi_link::fakehub::{req, Auth, FakeHub, HubConfig, OFF_MASTER_VOLUME};

#[derive(Default)]
struct Counter(AtomicUsize);
struct CounterL(Arc<Counter>);
impl NotifyListener for CounterL {
    fn on_notifications(&self) {
        self.0 .0.fetch_add(1, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct Closed(Mutex<Option<(i32, String)>>, AtomicUsize);
struct ClosedL(Arc<Closed>);
impl LinkListener for ClosedL {
    fn on_link_devices_changed(&self) {
        self.0 .1.fetch_add(1, Ordering::SeqCst);
    }
    fn on_link_closed(&self, code: i32, reason: String) {
        *self.0 .0.lock().unwrap() = Some((code, reason));
    }
}

fn wait(mut f: impl FnMut() -> bool) -> bool {
    for _ in 0..400 {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    false
}

fn url(hub: &FakeHub) -> String {
    link_ws_url(hub.addr().ip().to_string(), hub.addr().port(), String::new())
}

/// Connected and bound to the hub's device; returns the notify counter.
fn bound(hub: &FakeHub) -> (Arc<DspiCore>, Arc<Counter>) {
    let core = DspiCore::new();
    let hello = core.link_connect(url(hub), "Pixel 9".into(), "test".into(), String::new()).unwrap();
    assert!(!hello.needs_pairing);
    let devices = core.link_devices();
    assert_eq!(devices.len(), 1);
    let n = Arc::new(Counter::default());
    core.set_notify_listener(Some(Box::new(CounterL(Arc::clone(&n)))));
    core.link_select_device(devices[0].handle, devices[0].serial.clone()).unwrap();
    assert!(core.is_connected());
    core.fetch_all().unwrap();
    (core, n)
}

fn master_volume(core: &DspiCore) -> f32 {
    core.get_state().master_volume_db
}

#[test]
fn fetch_uses_the_snapshot_then_single_shot_bulk_never_chunks() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (core, n) = bound(&hub);
    let s = core.get_state();
    assert_eq!(s.compat, 1, "COMPAT_OK");
    assert_eq!(s.format_version, 32);
    assert_eq!(master_volume(&core), -20.0);
    let log = hub.log();
    assert!(!log.iter().any(|r| r.2 == req::GET_ALL_PARAMS), "the first bulk read is the snapshot");
    assert!(!log.iter().any(|r| r.2 == req::GET_ALL_PARAMS_CHUNK || r.2 == req::SET_ALL_PARAMS_CHUNK));
    assert!(log.iter().any(|r| r.2 == req::GET_PLATFORM));

    // RESYNC -> a synthetic BULK_INVALIDATED -> the core re-reads, single-shot 0xA0
    let before = n.0.load(Ordering::SeqCst);
    hub.resync();
    assert!(wait(|| n.0.load(Ordering::SeqCst) > before));
    let r = core.process_notifications();
    assert_ne!(r.flags & NOTIFY_REFRESHED, 0);
    assert!(hub.log().iter().any(|r| r.2 == req::GET_ALL_PARAMS));

    // A whole-state write is one 0xA1, under the hub lock, released after
    core.apply_all_params().unwrap();
    assert_eq!(hub.log().iter().filter(|r| r.2 == req::SET_ALL_PARAMS).count(), 1);
    assert!(wait(|| hub.lock_holder().is_none()));
    assert!(core.fetch_status().is_ok());
}

#[test]
fn own_echoes_are_dropped_and_other_sessions_changes_applied() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (core, n) = bound(&hub);
    let session = core.link_status().session;
    assert_ne!(session, 0);

    core.set_master_volume(-12.0).unwrap();
    assert_eq!(&hub.image()[OFF_MASTER_VOLUME as usize..][..4], &(-12.0f32).to_le_bytes());
    assert!(wait(|| n.0.load(Ordering::SeqCst) > 0), "our echo arrives");
    assert_eq!(core.process_notifications().flags, 0, "and is recognised as ours");
    assert_eq!(master_volume(&core), -12.0);

    // HOST_SET from another Link client (origin 99): the upstream core would drop it
    let before = n.0.load(Ordering::SeqCst);
    hub.external_change(OFF_MASTER_VOLUME, &(-30.0f32).to_le_bytes(), 1, 99);
    assert!(wait(|| n.0.load(Ordering::SeqCst) > before));
    let r = core.process_notifications();
    assert_ne!(r.flags & NOTIFY_STATE, 0);
    assert_eq!(master_volume(&core), -30.0);

    // A second phone on the same hub sees the first one's write
    let (other, n2) = bound(&hub);
    let before = n2.0.load(Ordering::SeqCst);
    core.set_master_volume(-6.0).unwrap();
    assert!(wait(|| n2.0.load(Ordering::SeqCst) > before));
    other.process_notifications();
    assert_eq!(master_volume(&other), -6.0);
}

/// Channel names live in the bulk image (OFF_CHANNEL_NAMES + ch * 32); firmware
/// 1.1.6 reports a rename as PARAM_CHANGED there, like any other parameter.
#[test]
fn another_clients_channel_rename_reaches_the_state() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (core, n) = bound(&hub);
    let off = (dspi_core::protocol::OFF_CHANNEL_NAMES + 7 * 32) as u16;
    let mut name = [0u8; 32];
    name[..8].copy_from_slice(b"Mac side");
    let before = n.0.load(Ordering::SeqCst);
    hub.external_change(off, &name, 1, 99);
    assert!(wait(|| n.0.load(Ordering::SeqCst) > before));
    let r = core.process_notifications();
    assert_ne!(r.flags & NOTIFY_STATE, 0);
    assert_eq!(core.get_state().channel_names[7], "Mac side");
}

#[test]
fn flash_flows_take_the_hub_lock_and_stop_when_it_is_held() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (core, _) = bound(&hub);
    hub.hold_lock(999, 10_000);
    let sent = hub.log().len();
    assert_eq!(core.save_preset(0), Err(DspiError::Locked));
    assert_eq!(core.save_params(), Err(DspiError::Locked));
    assert_eq!(hub.log().len(), sent, "nothing is sent when the lock is refused");
    // An ordinary command answered LOCKED
    assert_eq!(core.set_master_volume(-1.0), Err(DspiError::Locked));
    hub.release_lock();
    assert_eq!(core.set_master_volume(-1.0), Ok(()));
}

#[test]
fn pairing_viewer_role_and_errors() {
    let hub = FakeHub::start(HubConfig {
        auth: Auth::Window("123456".into()),
        pin_role: "viewer".into(),
        ..HubConfig::default()
    })
    .unwrap();
    let core = DspiCore::new();
    let hello = core.link_connect(url(&hub), "Pixel 9".into(), "test".into(), String::new()).unwrap();
    assert!(hello.needs_pairing);
    assert_eq!(hello.pin_source, "window");
    assert_eq!(hello.hub_id, "6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b");
    assert!(core.link_devices().is_empty());
    assert_eq!(core.link_select_device(0, "x".into()), Err(DspiError::Disconnected));
    assert!(matches!(core.link_pair("000000".into()), Err(DspiError::Link { code, .. }) if code == "denied"));
    let token = core.link_pair(" 123456 ".into()).unwrap();
    assert!(!token.is_empty());
    let st = core.link_status();
    assert!(st.ready && st.connected && !st.closed);
    assert_eq!(st.role, "viewer");
    assert!(st.denied.contains(&LinkDenied { request: req::SET_MASTER_VOLUME, dir: 0 }));
    let d = &core.link_devices()[0];
    core.link_select_device(d.handle, d.serial.clone()).unwrap();
    core.fetch_all().unwrap();
    assert_eq!(core.set_master_volume(-3.0), Err(DspiError::Denied));

    // The stored token works on the next connection
    let again = DspiCore::new();
    assert!(!again.link_connect(url(&hub), "Pixel 9".into(), "test".into(), token).unwrap().needs_pairing);
    assert_eq!(again.link_devices().len(), 1);
}

#[test]
fn a_revoked_session_closes_and_the_core_disconnects() {
    let hub = FakeHub::start(HubConfig { auth: Auth::Fixed("482913".into()), ..HubConfig::default() }).unwrap();
    let core = DspiCore::new();
    let closed = Arc::new(Closed::default());
    core.set_link_listener(Some(Box::new(ClosedL(Arc::clone(&closed)))));
    let hello = core.link_connect(url(&hub), "Pixel 9".into(), "test".into(), String::new()).unwrap();
    assert_eq!(hello.pin_source, "fixed");
    core.link_pair("482913".into()).unwrap();
    assert!(closed.1.load(Ordering::SeqCst) >= 1, "device list event");
    let d = &core.link_devices()[0];
    core.link_select_device(d.handle, d.serial.clone()).unwrap();
    core.fetch_all().unwrap();
    hub.revoke_all();
    assert!(wait(|| closed.0.lock().unwrap().is_some()));
    assert_eq!(closed.0.lock().unwrap().as_ref().unwrap().0, 4002);
    let st = core.link_status();
    assert!(st.closed && !st.ready);
    assert_eq!(st.close_code, 4002);
    assert_eq!(st.close_reason, "access revoked");
    assert_eq!(core.fetch_status(), Err(DspiError::Disconnected));
    core.link_close();
    assert!(!core.is_connected());
}

#[test]
fn bridge_hub_works_within_its_limits() {
    let hub = FakeHub::start(HubConfig { auth: Auth::None, ..HubConfig::bridge() }).unwrap();
    let (core, _) = bound(&hub);
    assert_eq!(core.get_state().compat, 1);
    assert_eq!(core.link_devices()[0].link, "uart");
    assert!(!hub.log().iter().any(|r| r.2 == req::GET_ALL_PARAMS_CHUNK));
    core.set_master_volume(-9.0).unwrap();
    assert_eq!(&hub.image()[OFF_MASTER_VOLUME as usize..][..4], &(-9.0f32).to_le_bytes());
}

#[test]
fn addresses_and_info() {
    assert_eq!(
        link_parse_address("studio.local".into()).unwrap(),
        LinkAddress { host: "studio.local".into(), port: 11915 }
    );
    assert!(matches!(link_parse_address("a b".into()), Err(DspiError::InvalidArgument { .. })));
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let info = link_fetch_info(hub.addr().ip().to_string(), hub.addr().port()).unwrap();
    assert_eq!(info.hub_name, "Fake Hub");
    assert_eq!(info.ws_path, "/dspi/v1");
    assert_eq!(info.serials, vec!["0123456789ABCDEF".to_string()]);
    assert!(matches!(link_fetch_info("127.0.0.1".into(), 1), Err(DspiError::Link { code, .. }) if code == "io"));
}

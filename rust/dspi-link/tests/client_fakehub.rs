//! The Link client against the in-process fake hub (feature `fake-hub`).

use std::sync::atomic::{AtomicU32, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use dspi_link::fakehub::{req, Auth, FakeHub, HubConfig, OFF_MASTER_VOLUME};
use dspi_link::frames::status;
use dspi_link::messages::PollSpec;
use dspi_link::socket::TcpConnector;
use dspi_link::{address, AuthState, ConnectOptions, Dir, LinkClient, LinkError, LinkEvents};

fn opts(token: Option<String>) -> ConnectOptions {
    ConnectOptions {
        client_name: "Pixel 9".into(),
        app: "DSPi Console for Android".into(),
        app_version: "test".into(),
        token,
    }
}

fn connect(hub: &FakeHub, token: Option<String>) -> (LinkClient, AuthState) {
    LinkClient::connect(&TcpConnector::default(), &hub.url(), &opts(token), None).unwrap()
}

#[derive(Default)]
struct Events {
    devices: AtomicU32,
    closed: Mutex<Option<Option<u16>>>,
}

impl LinkEvents for Events {
    fn devices_changed(&self) {
        self.devices.fetch_add(1, Ordering::SeqCst);
    }
    fn closed(&self, code: Option<u16>, _reason: &str) {
        *self.closed.lock().unwrap() = Some(code);
    }
}

fn wait(mut f: impl FnMut() -> bool) -> bool {
    for _ in 0..300 {
        if f() {
            return true;
        }
        std::thread::sleep(Duration::from_millis(10));
    }
    false
}

#[test]
fn open_hub_session_probe_device_list_and_commands() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (c, state) = connect(&hub, None);
    assert_eq!(state, AuthState::Ready);
    assert_eq!(c.session_id(), Some(12), "the none-mode auth.pair probe learns our session id");
    assert_eq!(c.role(), "admin");
    let devices = c.devices();
    assert_eq!(devices.len(), 1);
    assert_eq!(devices[0].serial, "0123456789ABCDEF");
    assert_eq!(devices[0].link, "usb");

    let platform = c.command(0, Dir::Get, req::GET_PLATFORM, 0, 2, 7, &[]);
    assert_eq!(platform.status, status::OK);
    assert_eq!(platform.payload, [1, 1, 0x16, 9, 1, 6, 5]);
    let bulk = c.command(0, Dir::Get, req::GET_ALL_PARAMS, 0, 2, 6136, &[]);
    assert_eq!(bulk.payload.len(), 6136);
    assert_eq!(c.command(3, Dir::Get, req::GET_PLATFORM, 0, 2, 7, &[]).status, status::NO_DEVICE);
    assert_eq!(c.command(0, Dir::Get, 0x99, 0, 2, 4, &[]).status, status::ERROR, "unknown GET stalls");

    let snap = c.snapshot(0).unwrap();
    assert_eq!(snap.bulk().unwrap(), hub.image());

    // Own write: the echo carries our session id
    let rx = c.notifications(0);
    let r = c.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &(-12.0f32).to_le_bytes());
    assert_eq!(r.status, status::OK);
    let echo = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(echo.origin, 12);
    assert_eq!(&echo.packet[..2], &[0x02, 0x02]);
    assert_eq!(&hub.image()[OFF_MASTER_VOLUME as usize..][..4], &(-12.0f32).to_le_bytes());

    // Someone else's change
    hub.external_change(OFF_MASTER_VOLUME, &(-30.0f32).to_le_bytes(), 1, 99);
    assert_eq!(rx.recv_timeout(Duration::from_secs(2)).unwrap().origin, 99);
    c.close();
    assert!(c.is_closed());
}

#[test]
fn second_client_sees_the_first_clients_writes_attributed() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (a, _) = connect(&hub, None);
    let (b, _) = connect(&hub, None);
    assert_ne!(a.session_id(), b.session_id());
    let rx = b.notifications(0);
    a.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &(-7.0f32).to_le_bytes());
    let n = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(Some(n.origin), a.session_id());
    assert_ne!(Some(n.origin), b.session_id());
}

#[test]
fn window_pin_pairing_then_token() {
    let hub = FakeHub::start(HubConfig { auth: Auth::Window("123456".into()), ..HubConfig::default() }).unwrap();
    let (c, state) = connect(&hub, None);
    assert_eq!(state, AuthState::NeedsPairing { pin_source: "window".into() });
    assert!(c.devices().is_empty());
    assert!(matches!(c.pair("000000", "control"), Err(LinkError::Rejected { .. })), "wrong PIN");
    let token = c.pair("123456", "control").unwrap();
    assert!(!token.is_empty());
    assert_eq!(c.role(), "control");
    assert!(!c.denied().is_empty(), "a control session gets the config rows denied");
    assert_eq!(c.devices().len(), 1);
    c.close();

    let (c2, state) = connect(&hub, Some(token));
    assert_eq!(state, AuthState::Ready);
    assert_eq!(c2.devices().len(), 1);
    // A stale token is the same as none
    let (_c3, state) = connect(&hub, Some("bogus".into()));
    assert_eq!(state, AuthState::NeedsPairing { pin_source: "window".into() });
}

#[test]
fn bridge_fixed_pin_and_limits() {
    let hub = FakeHub::start(HubConfig::bridge()).unwrap();
    let (c, state) = connect(&hub, None);
    assert_eq!(state, AuthState::NeedsPairing { pin_source: "fixed".into() });
    c.pair("482913", "control").unwrap();
    assert_eq!(c.role(), "admin", "a fixed-PIN bridge grants admin");
    assert_eq!(c.hello().limits.max_inflight, 4);
    assert_eq!(c.devices()[0].link, "uart");
    assert_eq!(c.command(0, Dir::Get, req::GET_ALL_PARAMS_CHUNK, 0, 2, 2048, &[]).status, status::BLOCKED);
    assert_eq!(c.command(0, Dir::Get, req::RTA_GET_BANDS_ALL, 0, 2, 1312, &[]).status, status::ERROR);
    assert_eq!(c.command(0, Dir::Set, 0x2A, 0, 2, 65, &[0; 65]).status, status::OVERSIZE);
    assert_eq!(c.command(0, Dir::Get, req::GET_ALL_PARAMS, 0, 2, 6136, &[]).payload.len(), 6136, "bulk is exempt");
}

#[test]
fn viewer_is_denied_writes() {
    let hub = FakeHub::start(HubConfig {
        auth: Auth::Window("111111".into()),
        pin_role: "viewer".into(),
        ..HubConfig::default()
    })
    .unwrap();
    let (c, _) = connect(&hub, None);
    c.pair("111111", "viewer").unwrap();
    assert_eq!(c.role(), "viewer");
    assert!(c.denied().contains(&(req::SET_MASTER_VOLUME, 0)));
    assert_eq!(c.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &[0; 4]).status, status::DENIED);
    assert_eq!(c.command(0, Dir::Get, req::GET_PLATFORM, 0, 2, 7, &[]).status, status::OK);
    assert!(
        matches!(c.lock_acquire(0, "Saving preset", 5000), Err(LinkError::Rejected { code, .. }) if code == "denied")
    );
}

#[test]
fn locks_keep_other_sessions_out() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (a, _) = connect(&hub, None);
    let (b, _) = connect(&hub, None);
    a.lock_acquire(0, "Saving preset", 5000).unwrap();
    assert_eq!(hub.lock_holder(), a.session_id());
    assert!(
        matches!(b.lock_acquire(0, "Saving preset", 5000), Err(LinkError::Rejected { code, .. }) if code == "locked")
    );
    assert_eq!(b.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &[0; 4]).status, status::LOCKED);
    assert_eq!(a.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &[0; 4]).status, status::OK);
    // device.changed carries locked_by
    assert!(wait(|| b.devices().first().and_then(|d| d.locked_by).map(|s| s as u16) == a.session_id()));
    a.lock_release(0);
    assert!(wait(|| hub.lock_holder().is_none()));
    assert_eq!(b.command(0, Dir::Set, req::SET_MASTER_VOLUME, 0, 2, 4, &[0; 4]).status, status::OK);
}

#[test]
fn link_sequence_gap_and_resync_become_an_invalidation() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (c, _) = connect(&hub, None);
    let rx = c.notifications(0);
    hub.external_change(OFF_MASTER_VOLUME, &[0; 4], 5, 0);
    let first = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    hub.drop_one_notification();
    hub.external_change(OFF_MASTER_VOLUME, &[0; 4], 5, 0);
    let synthetic = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(&synthetic.packet[..2], &[0x02, 0x03], "BULK_INVALIDATED");
    let next = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(synthetic.packet[3], next.packet[3].wrapping_sub(1), "the core's own seq check sees no second gap");
    assert_eq!(next.packet[3], first.packet[3].wrapping_add(1));
    hub.resync();
    let r = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!(&r.packet[..2], &[0x02, 0x03]);
}

#[test]
fn close_codes_reach_the_embedder() {
    let hub = FakeHub::start(HubConfig { auth: Auth::Window("123456".into()), ..HubConfig::default() }).unwrap();
    let ev = Arc::new(Events::default());
    let (c, _) = LinkClient::connect(&TcpConnector::default(), &hub.url(), &opts(None), Some(ev.clone())).unwrap();
    c.pair("123456", "control").unwrap();
    assert!(ev.devices.load(Ordering::SeqCst) >= 1);
    hub.revoke_all();
    assert!(wait(|| ev.closed.lock().unwrap().is_some()));
    assert_eq!(*ev.closed.lock().unwrap(), Some(Some(4002)));
    assert!(!dspi_link::should_reconnect(Some(4002)));
    assert_eq!(c.command(0, Dir::Get, req::GET_PLATFORM, 0, 2, 7, &[]).status, status::NO_DEVICE);
    assert!(matches!(c.snapshot(0), Err(LinkError::Closed { code: Some(4002), .. })));

    let hub2 = FakeHub::start(HubConfig::default()).unwrap();
    let ev2 = Arc::new(Events::default());
    let (_c, _) = LinkClient::connect(&TcpConnector::default(), &hub2.url(), &opts(None), Some(ev2.clone())).unwrap();
    hub2.close_all(4003);
    assert!(wait(|| ev2.closed.lock().unwrap().is_some()));
    assert_eq!(*ev2.closed.lock().unwrap(), Some(Some(4003)));
    assert!(dspi_link::should_reconnect(Some(4003)));
}

#[test]
fn timeouts_report_timeout_status() {
    let mut cfg = HubConfig::default();
    cfg.timeout_requests.insert(req::GET_STATUS);
    let hub = FakeHub::start(cfg).unwrap();
    let (c, _) = connect(&hub, None);
    assert_eq!(c.command(0, Dir::Get, req::GET_STATUS, 9, 2, 41, &[]).status, status::TIMEOUT);
}

#[test]
fn polls_deliver_frames() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let (c, _) = connect(&hub, None);
    let rx = c.polls(0);
    let g =
        c.poll_subscribe(0, &[PollSpec { slot: 2, req: req::GET_STATUS, val: 9, idx: 2, len: 41, hz: 20 }]).unwrap();
    assert_eq!(g[0].slot, 2);
    let f = rx.recv_timeout(Duration::from_secs(2)).unwrap();
    assert_eq!((f.slot, f.payload.len()), (2, 41));
    c.poll_unsubscribe(0, &[2]);
}

#[test]
fn info_endpoint_and_concurrent_commands() {
    let hub = FakeHub::start(HubConfig::default()).unwrap();
    let info = address::fetch_info(&hub.addr().ip().to_string(), hub.addr().port(), Duration::from_secs(2)).unwrap();
    assert_eq!(info.hub.id, "6f1a2b3c-4d5e-4f60-8a9b-0c1d2e3f4a5b");
    assert_eq!(info.auth, "none");
    assert_eq!(info.ws.as_deref(), Some("/dspi/v1"));
    assert_eq!(info.devices[0].serial, "0123456789ABCDEF");

    let (c, _) = connect(&hub, None);
    let threads: Vec<_> = (0..16)
        .map(|_| {
            let c = c.clone();
            std::thread::spawn(move || {
                for _ in 0..20 {
                    assert_eq!(c.command(0, Dir::Get, req::GET_PLATFORM, 0, 2, 7, &[]).payload.len(), 7);
                }
            })
        })
        .collect();
    for t in threads {
        t.join().unwrap();
    }
}

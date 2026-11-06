//! `dspi-link-fakehub`: a fake DSPi Link hub on the LAN, for trying the app
//! without a Mac or an ESP32 bridge (docs/LINK-HIL-CHECKLIST.md).
//!
//!   dspi-link-fakehub [--bind 0.0.0.0:11915] [--auth none|window:PIN|fixed:PIN]
//!                     [--bridge] [--role viewer|control|admin] [--name NAME]
//!
//! Advertise it for discovery with avahi on the same host, e.g.
//!   avahi-publish -s "Fake Hub" _dspi._tcp 11915 v=1 hid=<id> kind=console auth=none n=1
//! (the Android emulator cannot see mDNS: add 10.0.2.2:11915 by address).

use std::time::Duration;

use dspi_link::fakehub::{Auth, FakeHub, HubConfig};

fn main() {
    let mut cfg = HubConfig { bind: "0.0.0.0:11915".parse().unwrap(), ..HubConfig::default() };
    let mut args = std::env::args().skip(1);
    while let Some(a) = args.next() {
        let mut val = || args.next().unwrap_or_else(|| panic!("{a} needs a value"));
        match a.as_str() {
            "--bind" => cfg.bind = val().parse().expect("--bind host:port"),
            "--bridge" => {
                let bind = cfg.bind;
                cfg = HubConfig { bind, ..HubConfig::bridge() };
            }
            "--auth" => {
                let v = val();
                cfg.auth = match v.split_once(':') {
                    Some(("window", p)) => Auth::Window(p.into()),
                    Some(("fixed", p)) => Auth::Fixed(p.into()),
                    _ if v == "none" => Auth::None,
                    _ => panic!("--auth none|window:PIN|fixed:PIN"),
                }
            }
            "--role" => cfg.pin_role = val(),
            "--name" => cfg.name = val(),
            other => panic!("unknown argument {other}"),
        }
    }
    let hub = FakeHub::start(cfg.clone()).expect("bind");
    println!(
        "fake DSPi Link hub \"{}\" ({}) on {} id {} auth {:?}",
        cfg.name,
        cfg.kind,
        hub.addr(),
        cfg.hub_id,
        cfg.auth
    );
    loop {
        std::thread::sleep(Duration::from_secs(3600));
    }
}

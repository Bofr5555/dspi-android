//! The upstream DSPi-Link fixtures (`fixtures/link/`, WeebLabs/DSPi-Link
//! 86f10e3): every frame decodes to its `frames.json` meaning and re-encodes
//! byte for byte; every message parses; the device captures match
//! `capture.json`.

use std::fs;
use std::path::PathBuf;

use dspi_link::frames::{Dir, Frame};
use dspi_link::messages::{out, AuthOk, ClientInfo, DeviceInfo, ErrReply, HelloHub, Inbound, PollGrant};
use serde_json::Value;

fn dir(sub: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures/link").join(sub)
}

fn hex(s: &str) -> Vec<u8> {
    (0..s.len()).step_by(2).map(|i| u8::from_str_radix(&s[i..i + 2], 16).unwrap()).collect()
}

fn msg(name: &str) -> String {
    fs::read_to_string(dir("messages").join(name)).unwrap()
}

#[test]
fn every_frame_fixture_decodes_and_re_encodes() {
    let index: Value = serde_json::from_str(&fs::read_to_string(dir("frames").join("frames.json")).unwrap()).unwrap();
    let frames = index["frames"].as_array().unwrap();
    let on_disk = fs::read_dir(dir("frames"))
        .unwrap()
        .filter(|e| e.as_ref().unwrap().path().extension().is_some_and(|x| x == "bin"))
        .count();
    assert_eq!(frames.len(), on_disk, "every .bin is described in frames.json");
    for f in frames {
        let bytes = fs::read(dir("frames").join(f["file"].as_str().unwrap())).unwrap();
        assert_eq!(bytes, hex(f["hex"].as_str().unwrap()), "{}", f["file"]);
        let decoded = Frame::decode(&bytes).unwrap();
        assert_eq!(decoded.encode(), bytes, "{} re-encodes", f["file"]);
        let n = |k: &str| f[k].as_u64().unwrap();
        match (f["type"].as_str().unwrap(), decoded) {
            ("cmd_request", Frame::CmdRequest(r)) => {
                assert_eq!(r.tag as u64, n("tag"));
                assert_eq!(r.handle as u64, n("handle"));
                assert_eq!(r.dir, if f["direction"] == "set" { Dir::Set } else { Dir::Get });
                assert_eq!(r.b_request as u64, n("bRequest"));
                assert_eq!(r.w_value as u64, n("wValue"));
                assert_eq!(r.w_index as u64, n("wIndex"));
                assert_eq!(r.w_length as u64, n("wLength"));
                if let Some(p) = f["payload_hex"].as_str() {
                    assert_eq!(r.payload, hex(p));
                }
            }
            ("cmd_response", Frame::CmdResponse(r)) => {
                assert_eq!(r.tag as u64, n("tag"));
                assert_eq!(r.status as u64, n("status"));
                assert_eq!(r.payload, f["payload_hex"].as_str().map(hex).unwrap_or_default());
            }
            ("notify", Frame::Notify(nf)) => {
                assert_eq!(nf.seq as u64, n("tag"));
                assert_eq!(nf.handle as u64, n("handle"));
                assert_eq!(nf.origin as u64, n("origin"));
                let p = &f["packet"];
                assert_eq!(nf.packet[0] as u64, p["version"].as_u64().unwrap());
                assert_eq!(nf.packet[1] as u64, p["event"].as_u64().unwrap());
                assert_eq!(nf.packet[3] as u64, p["seq"].as_u64().unwrap());
                assert_eq!(u16::from_le_bytes([nf.packet[4], nf.packet[5]]) as u64, p["wire_offset"].as_u64().unwrap());
                assert_eq!(u16::from_le_bytes([nf.packet[6], nf.packet[7]]) as u64, p["wire_size"].as_u64().unwrap());
                assert_eq!(nf.packet[8] as u64, p["source"].as_u64().unwrap());
            }
            ("poll", Frame::Poll(p)) => {
                assert_eq!(p.seq as u64, n("tag"));
                assert_eq!(p.handle as u64, n("handle"));
                assert_eq!(p.slot as u64, n("slot"));
                assert_eq!(p.payload.len() as u64, n("payload_length"));
            }
            ("fwdata", Frame::FwData { xfer, offset, data }) => {
                assert_eq!(xfer as u64, n("tag"));
                assert_eq!(offset as u64, n("offset"));
                assert_eq!(data, hex(f["data_hex"].as_str().unwrap()));
            }
            ("fwdata_ack", Frame::FwDataAck { xfer, offset, status }) => {
                assert_eq!(xfer as u64, n("tag"));
                assert_eq!(offset as u64, n("offset"));
                assert_eq!(status as u64, n("status"));
            }
            ("resync", Frame::Resync { handle, reason }) => {
                assert_eq!(handle as u64, n("handle"));
                assert_eq!(reason as u64, n("reason"));
            }
            (t, d) => panic!("{t}: decoded as {d:?}"),
        }
    }
}

#[test]
fn hub_hellos_parse() {
    let Inbound::Hello(console) = Inbound::parse(&msg("hello_hub.json")).unwrap() else { panic!() };
    assert_eq!((console.proto.major, console.proto.minor), (1, 1));
    assert!(console.pin_required());
    assert_eq!(console.pin_source(), "window", "1.1 hub: no pin_source means window");
    assert!(console.has_cap("snapshot") && console.has_cap("lock") && !console.has_cap("fw_install"));
    assert_eq!(console.limits.max_inflight, 8);
    assert_eq!(console.hub.kind, "console");

    let bridge: HelloHub = match Inbound::parse(&msg("hello_hub_bridge.json")).unwrap() {
        Inbound::Hello(h) => h,
        _ => panic!(),
    };
    assert_eq!(bridge.pin_source(), "fixed");
    assert_eq!(bridge.limits.max_frame, 16384);
    assert_eq!(bridge.limits.max_inflight, 4);
    assert_eq!(bridge.hub.id, "0d6c41e2-93b7-4f0a-b5a8-3c2e7f19a4d0");
}

#[test]
fn replies_and_events_parse() {
    let Inbound::Ok { id, body } = Inbound::parse(&msg("auth_ok.json")).unwrap() else { panic!() };
    assert_eq!(id, 1);
    let auth: AuthOk = serde_json::from_value(body).unwrap();
    assert_eq!(auth.session, 12);
    assert_eq!(auth.role, "control");
    assert_eq!(auth.token.as_deref(), Some("Zm9vYmFyYmF6cXV4"));
    assert_eq!(auth.policy.unwrap().denied, vec![[240, 1], [83, 1]]);

    let Inbound::Ok { body, .. } = Inbound::parse(&msg("device_list_ok.json")).unwrap() else { panic!() };
    let devices: Vec<DeviceInfo> = serde_json::from_value(body["devices"].clone()).unwrap();
    assert_eq!(devices.len(), 1);
    let d = &devices[0];
    assert_eq!((d.handle, d.serial.as_str(), d.name.as_str()), (0, "E46058388B1A2E2C", "Living room"));
    assert_eq!(
        (d.fw.as_deref(), d.fw_beta, d.wire_version, d.link.as_str()),
        (Some("1.1.7"), Some(0), Some(32), "usb")
    );
    assert_eq!(d.locked_by, None);

    let Inbound::DeviceAdded(a) = Inbound::parse(&msg("device_added.json")).unwrap() else { panic!() };
    assert_eq!((a.handle, a.fw_beta), (1, None), "a missing fw_beta is unknown, not 0");
    assert_eq!(
        Inbound::parse(&msg("device_removed.json")).unwrap(),
        Inbound::DeviceRemoved { handle: 1, serial: "0123456789ABCDEF".into() }
    );

    let Inbound::Ok { body, .. } = Inbound::parse(&msg("poll_subscribe_ok.json")).unwrap() else { panic!() };
    let granted: Vec<PollGrant> = serde_json::from_value(body["granted"].clone()).unwrap();
    assert_eq!(granted, vec![PollGrant { slot: 0, hz: 10 }, PollGrant { slot: 1, hz: 12 }]);

    let Inbound::Err(e) = Inbound::parse(&msg("err_denied.json")).unwrap() else { panic!() };
    assert_eq!(
        e,
        ErrReply { id: Some(7), code: "denied".into(), msg: Some("admin role required".into()), field: None }
    );
    let Inbound::Err(e) = Inbound::parse(&msg("err_bad_field.json")).unwrap() else { panic!() };
    assert_eq!(e.field.as_deref(), Some("uart.tx_pin"));
    let Inbound::Err(e) = Inbound::parse(&msg("err_unauthenticated.json")).unwrap() else { panic!() };
    assert_eq!(e.code, "unauthenticated");
}

#[test]
fn every_message_fixture_is_valid_json_with_a_type() {
    for e in fs::read_dir(dir("messages")).unwrap() {
        let p = e.unwrap().path();
        let text = fs::read_to_string(&p).unwrap();
        // Client requests do not parse as hub messages but must be objects with `t`
        let v: Value = serde_json::from_str(&text).unwrap();
        assert!(v["t"].is_string(), "{p:?}");
        let _ = Inbound::parse(&text).unwrap();
    }
}

/// Our requests carry the same fields as the upstream request fixtures.
#[test]
fn requests_match_the_fixtures() {
    fn same(ours: &str, fixture: &str) {
        let a: Value = serde_json::from_str(ours).unwrap();
        let b: Value = serde_json::from_str(&msg(fixture)).unwrap();
        assert_eq!(a, b, "{fixture}");
    }
    same(&out::auth_token(1, "Zm9vYmFyYmF6cXV4"), "auth_token.json");
    same(&out::auth_pair(1, "482913", "Living Room iPad", Some("control")), "auth_pair.json");
    same(&out::device_snapshot(6, 0), "device_snapshot.json");
    same(&out::lock_acquire(10, 0, "Applying configuration", 10000), "lock_acquire.json");
    let mut hello: Value = serde_json::from_str(&out::hello(&ClientInfo {
        name: "Living Room iPad".into(),
        app: "DSPi Mobile".into(),
        version: "0.1".into(),
    }))
    .unwrap();
    // We speak 1.2; the fixture is a 1.1 client
    assert_eq!(hello["proto"]["minor"], 2);
    hello["proto"]["minor"] = 1.into();
    assert_eq!(hello, serde_json::from_str::<Value>(&msg("hello_client.json")).unwrap());
    let sub: Value = serde_json::from_str(&msg("poll_subscribe.json")).unwrap();
    let polls: Vec<dspi_link::messages::PollSpec> = serde_json::from_value(sub["polls"].clone()).unwrap();
    same(
        &out::poll_subscribe(sub["id"].as_u64().unwrap(), sub["handle"].as_u64().unwrap() as u8, &polls),
        "poll_subscribe.json",
    );
}

#[test]
fn device_captures_match_capture_json() {
    let cap: Value = serde_json::from_str(&fs::read_to_string(dir("device").join("capture.json")).unwrap()).unwrap();
    let bulk = fs::read(dir("device").join("bulk_v32.bin")).unwrap();
    assert_eq!(bulk.len() as u64, cap["bulk"]["size"].as_u64().unwrap());
    assert_eq!(bulk[0] as u64, cap["bulk"]["wire_version"].as_u64().unwrap());
    assert_eq!(bulk[2] as u64, cap["bulk"]["num_channels"].as_u64().unwrap());
    let mv = f32::from_le_bytes(bulk[4700..4704].try_into().unwrap());
    assert_eq!(mv as f64, cap["bulk"]["master_volume_db"].as_f64().unwrap());
    let platform = fs::read(dir("device").join("platform.bin")).unwrap();
    assert_eq!(platform, hex(cap["platform"]["raw_hex"].as_str().unwrap()));
    let status = fs::read(dir("device").join("status_wvalue9.bin")).unwrap();
    assert_eq!(status.len() as u64, cap["status"]["size"].as_u64().unwrap());
}

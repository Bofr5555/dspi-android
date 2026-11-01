//! The V32 device capture in `fixtures/`, decoded by the core's own parser,
//! and the JSON BRIDGE seeds `FakeDspiCore` from.

use std::collections::BTreeSet;
use std::path::PathBuf;

use dspi_ffi::fixture::{state_from_capture, to_json};
use dspi_ffi::DspState;

fn root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../..")
}

fn read(name: &str) -> Vec<u8> {
    std::fs::read(root().join("fixtures").join(name)).unwrap_or_else(|e| panic!("{name}: {e}"))
}

fn fixture_state() -> DspState {
    state_from_capture(&read("platform.bin"), &read("bulk_v32.bin")).expect("fixture decodes")
}

#[test]
fn bulk_fixture_is_the_documented_capture() {
    // DSPi-Link 05949b8 fixtures/device/bulk_v32.bin (see fixtures/README.md).
    let bulk = read("bulk_v32.bin");
    assert_eq!(bulk.len(), dspi_core::protocol::BULK_PARAMS_SIZE);
    assert_eq!(bulk[0], 32, "wire format byte");
    assert_eq!(read("platform.bin"), [0x01, 0x01, 0x16, 0x09, 0x01, 0x06, 0x05]);
}

#[test]
fn decodes_known_values() {
    // Values from fixtures/capture.json (written by DSPi-Link when it took the capture).
    let s = fixture_state();
    assert_eq!(s.format_version, 32);
    assert_eq!(s.compat, 1); // COMPAT_OK
    assert_eq!(s.platform_id, 1); // RP2350
    assert_eq!((s.fw_major, s.fw_minor, s.fw_patch, s.fw_beta), (1, 1, 6, 5));
    assert_eq!(s.num_channels, 17);
    assert_eq!(s.num_input_channels, 8);
    assert_eq!(s.num_output_channels, 9);
    assert_eq!(s.master_volume_db, -20.0);
    assert_eq!(s.preamp_db, 0.0);
    assert!(s.bulk_valid);
    assert_eq!(s.bulk_raw, read("bulk_v32.bin"));
    // Shapes the bridge relies on.
    assert_eq!(s.filters.len(), 17);
    assert!(s.filters.iter().all(|ch| ch.len() == 10));
    assert_eq!(s.xover.len(), 17);
    assert_eq!(s.matrix_routing.len(), 8);
    assert!(s.matrix_routing.iter().all(|r| r.len() == 9));
    // Every band decodes to a known filter type (PEQ 0..13 or crossover 32..63).
    for b in s.filters.iter().flatten() {
        assert!(b.filter_type <= 13, "PEQ type {}", b.filter_type);
    }
    for b in s.xover.iter().flatten() {
        assert!(b.filter_type == 0 || (32..=63).contains(&b.filter_type), "xover type {}", b.filter_type);
    }
}

#[test]
fn decoded_image_reencodes_identically() {
    // encode_bulk(decode_bulk(x)) == x: nothing in the capture is lost on its
    // way through the core (the property apply_all_params relies on).
    let bulk = read("bulk_v32.bin");
    let mut s = dspi_core::state::DspState::default();
    dspi_core::protocol::decode_bulk(&bulk, &mut s).unwrap();
    assert_eq!(dspi_core::protocol::encode_bulk(&s), bulk);
}

#[test]
fn json_fixture_is_up_to_date() {
    let want = to_json(&fixture_state());
    let have = std::fs::read_to_string(root().join("fixtures/dspstate_v32.json")).unwrap_or_default();
    assert!(have == want, "fixtures/dspstate_v32.json is stale: cargo run -p dspi-ffi --example dump_state");
}

#[test]
fn default_json_fixture_is_up_to_date() {
    let want = to_json(&dspi_ffi::DspiCore::new().get_state());
    let have = std::fs::read_to_string(root().join("fixtures/dspstate_default.json")).unwrap_or_default();
    assert!(have == want, "fixtures/dspstate_default.json is stale: cargo run -p dspi-ffi --example dump_state");
}

/// Field names of `data class <name>(` in the generated Kotlin `CoreTypes.kt`.
fn kotlin_fields(class: &str) -> BTreeSet<String> {
    let text = std::fs::read_to_string(root().join("core-api/src/main/kotlin/se/bofr/dspi/core/CoreTypes.kt")).unwrap();
    let start = text.find(&format!("data class {class}(")).unwrap_or_else(|| panic!("no data class {class}"));
    let body = &text[start..];
    let end = body.find("\n)").unwrap();
    body[..end]
        .lines()
        .filter_map(|l| l.trim().strip_prefix("val ").map(|r| r.split(':').next().unwrap().trim().to_string()))
        .collect()
}

#[test]
fn json_names_are_the_kotlin_names() {
    let v: serde_json::Value = serde_json::from_str(&to_json(&fixture_state())).unwrap();
    let keys = |v: &serde_json::Value| v.as_object().unwrap().keys().cloned().collect::<BTreeSet<_>>();
    assert_eq!(keys(&v), kotlin_fields("DspState"));
    assert_eq!(keys(&v["filters"][0][0]), kotlin_fields("FilterParams"));
    assert_eq!(keys(&v["uart"]), kotlin_fields("UartConfig"));
    assert_eq!(keys(&v["i2c"]), kotlin_fields("I2cConfig"));
    assert_eq!(keys(&v["ctrlStatus"]), kotlin_fields("CtrlIfaceStatus"));
}

#[test]
fn rejects_other_wire_versions() {
    let mut bulk = read("bulk_v32.bin");
    bulk[0] = 31;
    assert!(state_from_capture(&read("platform.bin"), &bulk).is_err());
    assert!(state_from_capture(&read("platform.bin"), &bulk[..100]).is_err());
    assert!(state_from_capture(&[1, 2], &read("bulk_v32.bin")).is_err());
}

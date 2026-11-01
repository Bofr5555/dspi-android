//! Write `fixtures/dspstate_v32.json`: the V32 device capture in `fixtures/`
//! decoded by the core (`dspi_ffi::fixture::state_from_capture`) and
//! serialised as the `DspState` record with Kotlin field names. Also writes
//! `fixtures/dspstate_default.json`, the core's state before any device
//! (`DspState::default()`, e.g. the `*_DEFAULTS` tables).
//!
//!   tools/dev.sh bash -c 'cd rust && cargo run --locked -p dspi-ffi --example dump_state'
//!
//! `--check` compares instead of writing (the fixture tests do the same).

use std::path::PathBuf;
use std::process::ExitCode;

fn main() -> ExitCode {
    let dir = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../fixtures");
    let read = |name: &str| std::fs::read(dir.join(name)).unwrap_or_else(|e| panic!("{name}: {e}"));
    let state = match dspi_ffi::fixture::state_from_capture(&read("platform.bin"), &read("bulk_v32.bin")) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("dump_state: {e}");
            return ExitCode::FAILURE;
        }
    };
    let outputs = [
        ("dspstate_v32.json", dspi_ffi::fixture::to_json(&state)),
        ("dspstate_default.json", dspi_ffi::fixture::to_json(&dspi_ffi::DspiCore::new().get_state())),
    ];
    let check = std::env::args().any(|a| a == "--check");
    let mut ok = true;
    for (name, json) in outputs {
        let out = dir.join(name);
        if check {
            if std::fs::read_to_string(&out).unwrap_or_default() != json {
                eprintln!("dump_state: {} is stale; run without --check", out.display());
                ok = false;
            }
        } else {
            std::fs::write(&out, json).expect("write fixture");
            println!("dump_state: wrote {}", out.display());
        }
    }
    if ok {
        ExitCode::SUCCESS
    } else {
        ExitCode::FAILURE
    }
}

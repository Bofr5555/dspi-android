# Fixtures (owned by package FFI)

## Device capture (V32, RP2350, firmware 1.1.6-beta5)

Copied unchanged from WeebLabs/DSPi-Link, commit
`05949b845b9c2e7df3c9541fde7ea39fdd5d7522` (`05949b8`), directory
`fixtures/device/` (local clone: `../_ref/DSPi-Link`). DSPi-Link captured
them from a unit on 2026-10-17 (see `capture.json`).

| File | Size | SHA-256 | What |
|---|---|---|---|
| `bulk_v32.bin` | 6136 B | `23bea903d678108f6c29febab040bc5ebc84ac91e5801a4c73d83dec998d79d2` | REQ_GET_ALL_PARAMS reply, wire format V32 |
| `platform.bin` | 7 B | `64090dda861b20722d2ecb5deccc7e37ff76b57174a7d19da72ea70c5fac7c36` | REQ_GET_PLATFORM reply (`01 01 16 09 01 06 05`: RP2350, 9 outputs, 1.1.6 beta 5) |
| `capture.json` | 939 B | `af307f2be49a5c0a906a463182683fcd9015824d53831dc41b912e30215dab01` | DSPi-Link's description of the capture |

## Derived (generated, checked in)

| File | Produced by | Contents |
|---|---|---|
| `dspstate_v32.json` | `rust/dspi-ffi/examples/dump_state.rs` | `platform.bin` + `bulk_v32.bin` decoded by the pinned core (`protocol::parse_platform` as `fetch_platform` applies it, then `protocol::decode_bulk`; `compat` = 1 OK), serialised as the dspi-ffi `DspState` record |
| `dspstate_default.json` | same | the core's `DspState::default()` (state before any device; includes the `UPMIX/SUBHARM/TUBE_DEFAULTS` tables) |

Field names are the Kotlin names of `se.bofr.dspi.core.DspState` (camelCase;
a test checks them against the generated `CoreTypes.kt`). Values are the raw
core values: integers for `u8`/`u16` codes, `bulkRaw` as 6136 integers 0..255
(Kotlin: `ByteArray`), nested lists for `filters`/`xover` `[wire channel][band]`
and the matrix `[input][output]`.

Not in the capture, so left at their defaults in `dspstate_v32.json`: what
`fetch_all` reads item by item after the bulk image (Core 1 mode, subharm
solo, DAC mute, UART/I2C configs and status, preset directory and names).

Regenerate after a submodule bump (the dspi-ffi tests fail while stale):

```
tools/dev.sh bash -c 'tools/stage-core.sh && cd rust && cargo run --locked -p dspi-ffi --example dump_state'
```

## Firmware opcodes

`firmware_req_opcodes.txt`: the `#define REQ_*` lines of the firmware's
`firmware/DSPi/config.h` at the commits pinned in `tools/REFERENCE.toml`
`[firmware]`, extracted read-only with `git show`. `tools/opcode_coverage.py`
checks against it (CI has no access to the firmware repository); refresh with
`python3 tools/opcode_coverage.py --refresh-firmware ~/prog/DSPi` on the host.

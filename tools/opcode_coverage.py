#!/usr/bin/env python3
"""Opcode coverage check (PLAN-NATIVE-ANDROID.md §7 step 2).

WAVE 0 STUB — owned by package FFI. Today it only reports what the pinned
core defines. TODO(FFI):
  1. Read `#define REQ_*` from the firmware config.h at the commit pinned in
     tools/REFERENCE.toml ([firmware] tag_sha / branch_head_sha). Source it
     WITHOUT device access: `--firmware-config <path>` (a local checkout of
     WeebLabs/DSPi) or `gh api repos/WeebLabs/DSPi/contents/firmware/DSPi/config.h?ref=<sha>`.
  2. Diff against `pub const REQ_*` in third_party/DSPi-Console-Linux/core/src/protocol.rs:
     fail on an opcode in firmware but absent from the core (known: 0x82
     REQ_GET_CLOCK_DIAG, report upstream) unless listed in an allowlist file.
  3. Map every core command function that uses a REQ_* to a dspi-ffi method
     (grep commands.rs/*.rs for REQ_ uses -> fn names -> rust/dspi-ffi core.rs)
     and fail on an opcode reachable in the core but not from Kotlin.
Exit 0 always until then.
"""
import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PROTOCOL = ROOT / "third_party/DSPi-Console-Linux/core/src/protocol.rs"


def core_opcodes() -> dict[str, int]:
    rx = re.compile(r"^pub const (REQ_[A-Z0-9_]+): u8 = (0x[0-9A-Fa-f]+|\d+);")
    return {m.group(1): int(m.group(2), 0) for ln in PROTOCOL.read_text().splitlines() if (m := rx.match(ln))}


def firmware_opcodes(path: Path) -> dict[str, int]:
    rx = re.compile(r"^#define (REQ_[A-Z0-9_]+)\s+(0x[0-9A-Fa-f]+|\d+)")
    return {m.group(1): int(m.group(2), 0) for ln in path.read_text().splitlines() if (m := rx.match(ln))}


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--firmware-config", type=Path, help="path to firmware/DSPi/config.h (optional)")
    a = ap.parse_args()
    core = core_opcodes()
    print(f"opcode_coverage: core defines {len(core)} REQ_* opcodes (protocol.rs)")
    if a.firmware_config:
        fw = firmware_opcodes(a.firmware_config)
        missing = sorted(set(fw) - set(core), key=lambda n: fw[n])
        print(f"opcode_coverage: firmware defines {len(fw)}; missing in core: "
              + (", ".join(f"{n}=0x{fw[n]:02X}" for n in missing) or "none"))
    print("opcode_coverage: STUB — reachability from dspi-ffi not checked yet (package FFI)")
    return 0


if __name__ == "__main__":
    sys.exit(main())

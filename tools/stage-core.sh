#!/usr/bin/env bash
# Produce rust/.staged/dspi-core/core = third_party/DSPi-Console-Linux/core
# with third_party/patches/*.patch applied (in name order, `patch -p1` from
# the submodule root). The submodule itself is never modified.
#
# dspi-ffi depends on the staged copy (rust/dspi-ffi/Cargo.toml), so run this
# before any cargo command; tools/ci.sh and the :ffi Gradle tasks do.
# Re-stages only when the submodule commit, its working tree or a patch changed
# (so cargo does not rebuild the core every time).
set -euo pipefail
ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)"
SUB="$ROOT/third_party/DSPi-Console-Linux"
OUT="$ROOT/rust/.staged/dspi-core"
PATCHES=("$ROOT"/third_party/patches/*.patch)

if [[ ! -f "$SUB/core/Cargo.toml" ]]; then
  echo "stage-core: submodule missing; run: git submodule update --init" >&2
  exit 1
fi

key="$( { git -C "$SUB" rev-parse HEAD; git -C "$SUB" status --porcelain; git -C "$SUB" diff; \
          for p in "${PATCHES[@]}"; do [[ -f "$p" ]] && { echo "$p"; cat "$p"; }; done; } | sha256sum | cut -d' ' -f1)"
if [[ -f "$OUT/.stamp" && "$(cat "$OUT/.stamp")" == "$key" ]]; then
  exit 0
fi

rm -rf "$OUT"
mkdir -p "$OUT"
cp -a "$SUB/core" "$OUT/core"
for p in "${PATCHES[@]}"; do
  [[ -f "$p" ]] || continue
  echo "stage-core: applying $(basename "$p")" >&2
  patch -d "$OUT" -p1 --forward --batch --quiet < "$p"
done
echo "$key" > "$OUT/.stamp"

#!/usr/bin/env bash
# Produce rust/.staged/dspi-core/core = the PINNED commit of
# third_party/DSPi-Console-Linux/core with third_party/patches/*.patch applied
# (in name order, `patch -p1` from the submodule root). The submodule itself is
# never modified.
#
# The content comes from `git archive <pin>` (tools/REFERENCE.toml [core].sha),
# not from the working tree, so local edits inside the submodule can never be
# built (CONTRIBUTING-AGENTS rule 5). It refuses to stage when the submodule is
# checked out at another commit or has modified tracked files; untracked files
# are ignored (they are not part of the archive anyway).
#
# dspi-ffi depends on the staged copy (rust/dspi-ffi/Cargo.toml), so run this
# before any cargo command; tools/ci.sh and the :ffi Gradle tasks do.
# Re-stages only when the pin or a patch changed (so cargo does not rebuild the
# core every time).
set -euo pipefail
ROOT="$(git -C "$(dirname "${BASH_SOURCE[0]}")/.." rev-parse --show-toplevel)"
SUB="$ROOT/third_party/DSPi-Console-Linux"
OUT="$ROOT/rust/.staged/dspi-core"
PATCHES=("$ROOT"/third_party/patches/*.patch)

fail() { echo "stage-core: $*" >&2; exit 1; }

[[ -f "$SUB/core/Cargo.toml" ]] || fail "submodule missing; run: git submodule update --init"

PIN="$(sed -n '/^\[core\]/,/^\[/{s/^sha = "\([0-9a-f]\{40\}\)".*/\1/p}' "$ROOT/tools/REFERENCE.toml")"
[[ -n "$PIN" ]] || fail "no [core] sha in tools/REFERENCE.toml"
HEAD="$(git -C "$SUB" rev-parse HEAD)"
[[ "$HEAD" == "$PIN" ]] || fail "submodule is at $HEAD, tools/REFERENCE.toml pins $PIN (git submodule update)"
if [[ -n "$(git -C "$SUB" status --porcelain --untracked-files=no)" ]]; then
  git -C "$SUB" status --short --untracked-files=no >&2
  fail "submodule has local modifications; the core is never edited in place (use third_party/patches/)"
fi

key="$( { echo "$PIN"; for p in "${PATCHES[@]}"; do [[ -f "$p" ]] && { echo "$p"; cat "$p"; }; done; } \
        | sha256sum | cut -d' ' -f1)"
if [[ -f "$OUT/.stamp" && "$(cat "$OUT/.stamp")" == "$key" ]]; then
  exit 0
fi

rm -rf "$OUT"
mkdir -p "$OUT"
git -C "$SUB" archive --format=tar "$PIN" core | tar -x -C "$OUT"
for p in "${PATCHES[@]}"; do
  [[ -f "$p" ]] || continue
  echo "stage-core: applying $(basename "$p")" >&2
  patch -d "$OUT" -p1 --forward --batch --quiet < "$p"
done
echo "$key" > "$OUT/.stamp"

#!/usr/bin/env bash
# tools/ci.sh <package>   run exactly that package's checks
# tools/ci.sh all         run everything
# tools/ci.sh list        list packages
#
# Always runs inside the build container (re-executes itself via tools/dev.sh).
# See docs/PACKAGES.md for what each package owns.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$here/.." && pwd)"

if [[ "${DSPI_IN_CONTAINER:-}" != 1 ]]; then
  exec "$here/dev.sh" "$here/ci.sh" "$@"
fi
cd "$ROOT"

GRADLE=(./gradlew --no-daemon --console=plain -q)
WRAPPER_JAR_SHA256="7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172" # gradle-8.14.3-wrapper.jar.sha256

step() { printf '\n==> %s\n' "$*"; }

android_module() { # :module -> lint + unit tests + assemble
  local m="$1"
  "${GRADLE[@]}" "$m:ktlintCheck" "$m:testDebugUnitTest" "$m:assembleDebug"
}

jvm_module() {
  local m="$1"
  "${GRADLE[@]}" "$m:ktlintCheck" "$m:test" "$m:assemble"
}

pkg_supply() {
  step "supply chain: gradle wrapper jar checksum"
  echo "$WRAPPER_JAR_SHA256  gradle/wrapper/gradle-wrapper.jar" | sha256sum -c -
  grep -q '^distributionSha256Sum=' gradle/wrapper/gradle-wrapper.properties
  test -f gradle/verification-metadata.xml
}

pkg_contract() {
  step "contract: generated Kotlin is up to date with rust/dspi-ffi"
  python3 tools/gen_kotlin_contract.py --check
  step "contract: :core-api"
  jvm_module :core-api
}

pkg_ffi() {
  step "ffi: stage core"
  tools/stage-core.sh
  step "ffi: rustfmt / clippy / tests"
  (cd rust && cargo fmt --check -p dspi-ffi -p uniffi-bindgen)
  (cd rust && cargo clippy --locked -p dspi-ffi --all-targets -- -D warnings)
  (cd rust && cargo test --locked -p dspi-ffi)
  step "ffi: staged (pinned + patched) core unit tests"
  # --lib: the patches' tests (e.g. 0002 crossfeed pair map) live in dspi-core;
  # the upstream doctest in core/src/filterfile.rs fails on the pinned core too.
  (cd rust && cargo test --locked -p dspi-core --lib)
  step "ffi: cross-compile for every shipped ABI (arm64-v8a, armeabi-v7a, x86_64)"
  (cd rust && cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -P 26 build --locked -p dspi-ffi)
  step "ffi: generated Kotlin contract up to date"
  python3 tools/gen_kotlin_contract.py --check
  step "ffi: opcode coverage"
  python3 tools/opcode_coverage.py
  step "ffi: :ffi module (UniFFI bindings + jniLibs + adapter)"
  android_module :ffi
}

pkg_usb() { step "usb: :usb"; android_module :usb; }

pkg_bridge() {
  step "bridge: :bridge :fake-core"
  jvm_module :bridge
  jvm_module :fake-core
}

pkg_home() {
  step "home: :ui-common :feature-home"
  android_module :ui-common
  android_module :feature-home
}

feature() { step "$1: :feature-$1"; android_module ":feature-$1"; }
pkg_graph() { feature graph; }
pkg_matrix() { feature matrix; }
pkg_tools() { feature tools; }
pkg_presets() { feature presets; }
pkg_settings() { feature settings; }
pkg_analysis() { feature analysis; }
pkg_firmware() { feature firmware; }

pkg_app() { step "app: :app"; android_module :app; apk_fixtures; release_deps; }

# E2: `debugImplementation(project(":fake-core"))` is allowed (for @Previews);
# release runtime classpaths must not contain :fake-core.
# E8: the ONE exception is :app, which ships the clearly labelled
# "Demo (no device)" mode (FakeDspiCore.demo()); its APK may carry only the
# fixtures/dspstate_*.json files (fake-core/build.gradle.kts) and nothing
# else from fixtures/.
has_fake_core_in() { # :module configuration -> 0 if :fake-core is on that classpath
  "${GRADLE[@]}" "$1:dependencies" --configuration "$2" | grep -q -- "project :fake-core"
}

RELEASE_ANDROID_MODULES=(:ffi :usb :ui-common :feature-home :feature-graph :feature-matrix :feature-tools
  :feature-presets :feature-settings :feature-analysis :feature-firmware)
RELEASE_JVM_MODULES=(:core-api :bridge)
# Documented allowance (E8): module -> why it may have :fake-core in release.
FAKE_CORE_RELEASE_ALLOWED=(":app") # demo mode

release_deps() {
  step "release-deps: no :fake-core in release runtime classpaths (E2), except :app for demo mode (E8)"
  local m bad=0
  for m in "${RELEASE_ANDROID_MODULES[@]}"; do
    if has_fake_core_in "$m" releaseRuntimeClasspath; then echo "ERROR: $m releaseRuntimeClasspath contains :fake-core" >&2; bad=1; fi
  done
  for m in "${RELEASE_JVM_MODULES[@]}"; do
    if has_fake_core_in "$m" runtimeClasspath; then echo "ERROR: $m runtimeClasspath contains :fake-core" >&2; bad=1; fi
  done
  echo "allowed (E8, demo mode): ${FAKE_CORE_RELEASE_ALLOWED[*]}"
  return "$bad"
}
pkg_release_deps() { release_deps; }

# E8: the app APK carries fixtures/dspstate_*.json (demo mode needs
# dspstate_v32.json) and no other file from fixtures/.
apk_fixtures() {
  step "app: APK fixtures are dspstate_*.json only (E8)"
  python3 - app/build/outputs/apk/debug/app-debug.apk fixtures <<'PY'
import fnmatch, os, sys, zipfile
apk, fixtures = sys.argv[1], sys.argv[2]
names = set(zipfile.ZipFile(apk).namelist())
bad = [f for f in sorted(os.listdir(fixtures))
       if not fnmatch.fnmatch(f, "dspstate_*.json") and (f in names or "fixtures/" + f in names)]
if bad:
    sys.exit("ERROR: APK carries non-dspstate fixtures: " + ", ".join(bad))
if "dspstate_v32.json" not in names:
    sys.exit("ERROR: APK lacks dspstate_v32.json (demo mode seed)")
print("ok: " + ", ".join(sorted(n for n in names if fnmatch.fnmatch(n, "dspstate_*.json"))))
PY
}

PACKAGES=(supply contract ffi usb bridge home graph matrix tools presets settings analysis firmware app)

case "${1:-}" in
  ""|-h|--help|list)
    echo "usage: tools/ci.sh <package>|all"; echo "packages: ${PACKAGES[*]} (+ release-deps, run by app)"; exit 0 ;;
  all)
    for p in "${PACKAGES[@]}"; do "pkg_$p"; done
    step "all: OK" ;;
  *)
    if declare -F "pkg_${1//-/_}" >/dev/null; then "pkg_${1//-/_}"; step "$1: OK"
    else echo "unknown package: $1 (packages: ${PACKAGES[*]})" >&2; exit 2; fi ;;
esac

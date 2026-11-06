#!/usr/bin/env bash
# Run the DSPi Link fake hub (rust/dspi-link, feature fake-hub) in the build
# container, published on this host's port 11915, so a phone on the same
# LAN (or the emulator, as 10.0.2.2:11915) can connect to it without a Mac or
# an ESP32 bridge. docs/LINK-HIL-CHECKLIST.md.
#
#   tools/link-fakehub.sh [--auth none|window:PIN|fixed:PIN] [--bridge] [--role viewer|control|admin] [--name NAME]
#
# Discovery: the container cannot multicast onto the LAN; add the hub by
# address in the app, or advertise it from the host (avahi-publish, see
# rust/dspi-link/src/bin/fakehub.rs).
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
args=$(printf ' %q' "$@")
DSPI_DEV_PUBLISH="${DSPI_FAKEHUB_PORT:-11915}:11915" exec "$here/dev.sh" bash -c \
  "cd rust && cargo run --release --locked -p dspi-link --features fake-hub --bin dspi-link-fakehub -- --bind 0.0.0.0:11915$args"

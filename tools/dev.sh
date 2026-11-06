#!/usr/bin/env bash
# Run a command inside the pinned build container (container/Containerfile).
#
#   tools/dev.sh <cmd...>          run <cmd> in the container, cwd preserved
#   tools/dev.sh                   interactive shell
#   tools/dev.sh --build-image     (re)build the image
#   tools/dev.sh --print-image     print the image reference
#
# Nothing is installed on the host. The repo (or worktree) is bind-mounted at
# its host path; caches live in NAMED podman volumes:
#   dspi-android-cargo    CARGO_HOME (crates.io registry/git cache)
#   dspi-android-gradle   GRADLE_USER_HOME (wrapper dists, Maven caches)
#   dspi-android-target-<hash of checkout path>   CARGO_TARGET_DIR (per worktree)
#
# HARD RULE: the container gets no USB/hardware access. No --device, no
# --privileged, no /dev/bus/usb. Do not add any.
set -euo pipefail

IMAGE_TAG="2026-11-01-play"
IMAGE="localhost/dspi-android-build:${IMAGE_TAG}"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(git -C "$here/.." rev-parse --show-toplevel)"

case "${1:-}" in
  --build-image)
    exec podman build -t "$IMAGE" -f "$ROOT/container/Containerfile" "$ROOT/container" ;;
  --print-image)
    echo "$IMAGE"; exit 0 ;;
esac

# Already inside the container: just run it.
if [[ "${DSPI_IN_CONTAINER:-}" == 1 ]]; then
  if [[ $# -eq 0 ]]; then exec bash; fi
  exec "$@"
fi

if ! podman image exists "$IMAGE"; then
  echo "dev.sh: image $IMAGE missing; building it (tools/dev.sh --build-image)" >&2
  podman build -t "$IMAGE" -f "$ROOT/container/Containerfile" "$ROOT/container" >&2
fi

mounts=(-v "$ROOT:$ROOT:z")
# A git worktree keeps its metadata in the main repo's .git; mount that too so
# git (and submodules) work inside the container.
common="$(git -C "$ROOT" rev-parse --path-format=absolute --git-common-dir)"
case "$common/" in
  "$ROOT"/*) ;;
  *) mounts+=(-v "$common:$common:z") ;;
esac

wt_hash="$(printf '%s' "$ROOT" | sha256sum | cut -c1-10)"
mounts+=(
  -v dspi-android-cargo:/cache/cargo
  -v dspi-android-gradle:/cache/gradle
  -v "dspi-android-target-${wt_hash}:/cache/target"
)

workdir="$PWD"
case "$workdir/" in
  "$ROOT"/*) ;;
  *) workdir="$ROOT" ;;
esac

tty=()
if [[ -t 0 && -t 1 ]]; then tty=(-t); fi

# DSPI_DEV_PUBLISH=hostport:containerport publishes one TCP port (only the
# DSPi Link fake hub uses it: tools/link-fakehub.sh). Network only, no devices.
publish=()
if [[ -n "${DSPI_DEV_PUBLISH:-}" ]]; then publish=(-p "$DSPI_DEV_PUBLISH"); fi

if [[ $# -eq 0 ]]; then set -- bash; fi

exec podman run --rm -i "${tty[@]}" \
  --userns=keep-id \
  --security-opt no-new-privileges \
  --cap-drop=all \
  "${mounts[@]}" \
  "${publish[@]}" \
  -w "$workdir" \
  -e TERM="${TERM:-dumb}" \
  -e CI="${CI:-}" \
  "$IMAGE" "$@"

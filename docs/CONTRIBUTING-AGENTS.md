# Contributing (agents)

Read first: `PACKAGES.md` (what you own), `PORT-MAP.md` (what you port),
`../PLAN-NATIVE-ANDROID.md` (why).

## Hard rules

1. **No device access, ever.**
   - Do not run adb, fastboot, lsusb-driven tools, picotool, dspi_poke, or anything else that opens USB devices.
   - Do not start emulators that need /dev/kvm.
   - The owner's DSPi units and other USB gear are attached to this machine. Hardware-in-the-loop testing is done by the coordinator only.
   - Rust HIL tests stay `#[ignore]` + `DSPI_HIL=1` and you never set that.
2. **Everything runs in the build container**, through `tools/dev.sh` or `tools/ci.sh`. Never install toolchains, SDKs, crates or Gradle on the host. Never run `./gradlew` or `cargo` on the host either: they are not installed there on purpose.
3. **Only run your package's CI:** `tools/ci.sh <your-package>`. `tools/ci.sh all` is for the coordinator.
4. **Stay inside the directories you own** (PACKAGES.md). Need a contract change, a new dependency, or a change in someone else's module? Write it up in your final report or ask the coordinator. Do not edit it yourself.
5. **Never modify:**
   - `third_party/DSPi-Console-Linux` (the submodule; use patches in `third_party/patches/`, which FFI owns);
   - anything under `~/prog/DSPi*`, `~/prog/dspi_k340_amplifier`, or `../_ref/`.
6. **No pushing, PRs, or posting anywhere.** Commit locally only.
7. **Supply chain:**
   - New dependencies only from Google Maven or Maven Central (Gradle) and crates.io (Rust).
   - Pin the version in `gradle/libs.versions.toml` / `Cargo.toml`.
   - Regenerate `gradle/verification-metadata.xml` or `Cargo.lock` inside the container and review the diff (`SUPPLY-CHAIN.md`).
   - These are coordinator-approved changes.

## The container

```
tools/dev.sh --build-image        # once (or after container/Containerfile changes)
tools/dev.sh                      # interactive shell in the container
tools/dev.sh ./gradlew :feature-tools:assembleDebug
tools/dev.sh bash -c 'cd rust && cargo test -p dspi-ffi'
tools/ci.sh <package>             # your package's checks (re-execs itself in the container)
```

What `dev.sh` does:
- Runs `localhost/dspi-android-build:<IMAGE_TAG>` with rootless podman and `--userns=keep-id`. Files you create are owned by the host user.
- Uses `--cap-drop=all`, `no-new-privileges`, and **no devices**.
- Bind-mounts the checkout (or worktree) at its host path, plus the main repository's `.git` when you are in a worktree.
- Keeps caches in named volumes:
  - `dspi-android-cargo` (crates registry),
  - `dspi-android-gradle` (Gradle home),
  - `dspi-android-target-<hash of checkout path>` (one Rust target dir per worktree, so parallel agents don't thrash each other's builds).

Inside the container these are set:
- `ANDROID_HOME`, `ANDROID_NDK_HOME` (r27c)
- `JAVA_HOME` (Temurin 21)
- `CARGO_HOME=/cache/cargo`, `CARGO_TARGET_DIR=/cache/target`, `GRADLE_USER_HOME=/cache/gradle`
- `DSPI_IN_CONTAINER=1`

The Rust toolchain is pinned by `rust-toolchain.toml` (1.99.1) and already baked into the image.

Only `:ffi` and `:app` builds compile Rust: cargo-ndk, the NDK, and roughly 1 to 2 minutes on a cold cache. Feature and bridge modules never need Rust.

## Branches and worktrees

- One branch per package: `pkg/<name>`, e.g. `pkg/ffi`, `pkg/usb`, `pkg/bridge`, `pkg/home`, `pkg/graph`, …, branched from `main`.
- Work in a worktree beside the main checkout:
  ```
  git -C dspi-android worktree add ../dspi-android-wt/pkg-<name> -b pkg/<name> main
  cd ../dspi-android-wt/pkg-<name>
  git submodule update --init          # the submodule is needed by :ffi/:app/contract only
  tools/ci.sh <name>
  ```
- Never commit on `main`. The coordinator merges `pkg/*` branches.
- Rebase on `main` when the coordinator asks (contracts may have moved). Do not merge `main` into your branch.

## Commits

- Small, logical commits. Subject: `<package>: <imperative summary>` (≤ 72 chars), e.g. `bridge: port status poll and clip latch from DSPiBridge.cpp`.
- The body says *what was ported from where* (file + function), and any deviation from the reference.
- End every commit message with the trailer the coordinator gives you (e.g. `Co-Authored-By: …`).
- Never commit build outputs (`build/`, `rust/.staged/`, generated UniFFI Kotlin, jniLibs). `.gitignore` already covers them.
- Generated contract files (`DspiCoreApi.kt`, `CoreTypes.kt`, `DspiConstants.kt`, `UnimplementedDspiCore.kt`, `FfiDspiCore.kt`) change only through `tools/gen_kotlin_contract.py`, and only FFI runs it.

## Porting style

- Faithful to the official clients, file by file. Reading order:
  1. the macOS view, for UI and wording;
  2. the Linux QML/bridge, for how a GUI drives this exact core;
  3. Windows, for the graph editor, pending changes and pin navigation.
- Keep names parallel (see the naming rule in PORT-MAP.md).
- Put a header comment on each ported file: `Port of <repo>:<path> @ <ref>`.
- Record intentional deviations (touch gestures, phone layout, no hub) in `docs/UPSTREAM-DEVIATIONS.md` under your package's heading.
- Wave B UI must never call a flash-writing or bootloader operation without a confirmation dialog that uses the macOS wording. The bridge enforces this through its DangerousOps token.

## Definition of done (every package)

- `tools/ci.sh <package>` is green in a fresh worktree.
- The package's DoD items in PACKAGES.md are ticked in your final report, and anything left open is listed.
- PORT-MAP.md status cells for your rows are updated. That one file is shared: edit only your own rows.

# DSPi Console for Android

A native Android console for the [Weeb Labs DSPi](https://github.com/WeebLabs/DSPi) USB audio DSP
firmware (Raspberry Pi Pico / Pico 2). It aims to do everything the official desktop consoles do,
from a phone or tablet — including while that phone is the USB audio source playing into the DSPi.

The app is a faithful port of the official clients rather than a re-implementation:

- **Protocol and state:** the official Rust core from
  [DSPi-Console-Linux](https://github.com/WeebLabs/DSPi-Console-Linux) (`core/`), used unmodified as a
  git submodule plus two small patches (`third_party/patches/`): opening the device from the file
  descriptor Android hands the app, and the optional crossfeed pair map (see below).
- **Behaviour, wording and layout:** the macOS DSPi Console (v1.1.6-beta5) is the reference, page by
  page; the Qt bridge (`DSPiBridge.cpp`) is ported 1:1 as `DspiBridge`. Every deliberate difference is
  listed in [`docs/UPSTREAM-DEVIATIONS.md`](docs/UPSTREAM-DEVIATIONS.md).

> Status: **closed test on Google Play** (0.9.0). Testers wanted — see [Join the Google Play test](#join-the-google-play-test).

## Join the Google Play test

**DSPi Console for Android** is in a closed test on Google Play. Google requires **at least 12 testers
to stay opted in for 14 days** before the app can be published to everyone, so every tester helps.

1. **Join the tester group** with the same Google account you use for the Play Store on your phone:
   <https://groups.google.com/a/bofr.se/g/dspi-console-android-testers> (click **Join group**).
2. **Opt in to the test:** <https://play.google.com/apps/testing/se.bofr.dspi_console_android>
3. **Install "DSPi Console for Android"** from the Play Store link on that page.
4. **Stay opted in for at least 14 days** (leaving and rejoining restarts the count).

What you need: an Android phone or tablet with Android 8.0 or newer. **No DSPi required** — the app
has a Demo mode, so every screen can be tried without hardware. With a DSPi: firmware **v1.1.6** for
full features (other versions connect read-only) and a USB-C/OTG cable or adapter.

If the Play Store says the app is not available, check that the group, the opt-in page and the
Play Store all use the same Google account.

Feedback — bugs, crashes, anything that behaves differently from the desktop console — is very
welcome in [Issues](../../issues) or on the DSPi Discord.

## Features

Home and channel pages with live meters · response graph with on-graph PEQ editing (touch gestures) ·
crossovers, delays, gains, input routing · matrix mixer · loudness, crossfeed, volume leveller,
psychoacoustic bass, subharmonic synth, tube modeller, upmixer, output limiters · presets with the
official Unsaved Changes flow, import/export, filter files, AutoEQ · settings with the Save / Revert
bar (pins, outputs, I2S, inputs, global parameters, control interfaces, control surfaces) · test
signals, spectrum analyser, statistics, interrupt monitor · firmware installer (bundled official
v1.1.6-beta5 or a local `.uf2`) · demo mode without a device.

Android specifics: auto-launch on plug-in, a foreground-service notification with volume / mute /
next preset, and the app claims only the DSPi's vendor control interface, so audio keeps playing
(Android's own USB audio or a player such as USB Audio Player PRO).

## Requirements

- Android 8.0 (API 26) or newer with USB host (OTG) support; arm64, armv7 and x86_64 builds.
- **DSPi firmware v1.1.6** (bulk wire format V32), as for the official v1.1.6 consoles. With other
  firmware versions the app connects **read-only**: it shows a banner, disables every control and
  never writes to the device (the firmware updater stays available).

## Installing a test build without Google Play

Download the `.apk` from [Releases](../../releases) and sideload it (allow installs from your browser
or file manager). Test builds are debug-signed; a future Play-signed build will need the test build
uninstalled first. Check the `.sha256` file next to each APK.

## Fork-only extra: crossfeed output pairs

DSPi crossfeed runs on fixed output pairs (1/2, 3/4, …). Boards that wire **one DAC per ear** (the
K340 amplifier this was built for: out1 R woofer, out2 R tweeter, out3 L woofer, out4 L tweeter) need
crossfeed between outputs 3↔1 and 4↔2 instead. The firmware branch
[`Bofr5555/DSPi` → `k340-xfeed`](https://github.com/Bofr5555/DSPi/tree/k340-xfeed) makes the pairs
configurable (default unchanged, stored in the preset without a format change); the app then shows a
**Pairs** row in the crossfeed tool. On official firmware the row is hidden and nothing changes.

## Building

All toolchains run in a podman container (JDK 21, Android SDK/NDK, Rust + cargo-ndk, UniFFI), built
from [`container/Containerfile`](container/Containerfile) with every download pinned and checksum-
verified ([`docs/SUPPLY-CHAIN.md`](docs/SUPPLY-CHAIN.md)). Nothing is installed on the host.

```bash
git clone --recurse-submodules https://github.com/Bofr5555/dspi-android.git
cd dspi-android
tools/debug-keystore.sh                     # once: persistent debug signing key (gitignored)
tools/ci.sh all                             # every package's checks, in the container
tools/dev.sh ./gradlew :app:assembleDebug -Pdspi.rustProfile=release
```

The APK lands in `app/build/outputs/apk/debug/`. `tools/dev.sh <cmd>` runs any command in the
container; the container never gets USB or device access.

## Publishing

Release builds are signed Android App Bundles for Google Play (target API 36):
`tools/upload-key.sh` once (keep the key safe), then `tools/dev.sh ./gradlew :app:bundleRelease`;
`tools/ci.sh release` validates a bundle with a throwaway key. The full checklist — Play Console,
app ID, Play App Signing, testing tracks, store listing, privacy and licensing — is in
[`docs/RELEASE.md`](docs/RELEASE.md).

## Project layout

| Path | What |
|---|---|
| `third_party/DSPi-Console-Linux` | official core (submodule, pinned in `tools/REFERENCE.toml`) |
| `rust/dspi-ffi` | UniFFI glue over the core (Kotlin contract generated by `tools/gen_kotlin_contract.py`) |
| `core-api`, `ffi` | Kotlin contract + generated bindings / native libraries |
| `bridge` | `DspiBridge`: port of the Qt bridge (polling, notifications, throttled live edits, confirmation gate for flash writes, read-only guard) |
| `fake-core` | stateful fake device for tests, previews and demo mode |
| `usb` | USB permission / attach / foreground service / bootloader locator |
| `ui-common`, `feature-*` | Compose UI (home, graph, matrix, tools, presets, settings, analysis, firmware) |
| `app` | wiring, navigation, demo mode |
| `docs/` | package map, macOS→Android port map, deviations, supply chain |

## Credits and licensing

DSPi firmware and the official consoles are by **Weeb Labs** — all protocol knowledge, the core and
the UI design come from their work. Android port by **Bofr5555**.

This app is free software under the **GNU GPL v3.0** ([`LICENSE`](LICENSE)) with additional terms
under section 7 ([`NOTICE.md`](NOTICE.md)): keep the attributions, mark modified versions and give
them their own name, app ID and icon, and no trademark rights to "DSPi" or the icon. Third-party
components (the Weeb Labs core, firmware and AutoEQ database, libusb, UniFFI, Rust crates, AndroidX…)
and their licences are listed in `NOTICE.md` and in the app under Settings › About › Licences.

Privacy: the app collects no data and has no network access — see [`PRIVACY.md`](PRIVACY.md).

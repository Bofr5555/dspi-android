# Work packages

Plan: `../PLAN-NATIVE-ANDROID.md` (§3 architecture, §4 protocol, §5 Android,
§7 tracking, §8 tests). Mapping of macOS files to Android files: `PORT-MAP.md`.
Agent workflow (branches, worktrees, container, CI): `CONTRIBUTING-AGENTS.md`.

Each package owns a disjoint set of directories. A package changes only what it
owns. To change a **contract** (see below), the package asks the coordinator.

## Contracts (Wave 0, owned by the coordinator)

| Contract | Where | Consumers |
|---|---|---|
| Rust FFI surface | `rust/dspi-ffi/src/{core,records,callbacks,error,lib}.rs`: **signatures** only. Bodies belong to FFI. | FFI, generator |
| `DspiCoreApi` + domain types | `core-api/` (`DspiCoreApi.kt`, `CoreTypes.kt`, `DspiConstants.kt`, `UnimplementedDspiCore.kt` are **generated** by `tools/gen_kotlin_contract.py`; `DspiException`, `Listeners`, `StatusCodes` are hand-written) | FFI (implements), BRIDGE (consumes, fakes) |
| `DspiBridge` + controllers | `bridge/src/main/kotlin/se/bofr/dspi/bridge/{DspiBridge,BridgeTypes,Controllers}.kt` | every UI package |
| `UsbSession`, `BootloaderLocator` | `usb/src/main/kotlin/se/bofr/dspi/usb/{UsbSession,BootloaderLocator}.kt` | app, FIRMWARE |
| Routes and entry composables | `ui-common/.../DspiRoutes.kt`; each feature's entry `@Composable fun X(bridge, [arg,] onNavigate, onBack, modifier)`; `feature-graph/.../GraphView.kt` | app, HOME (GraphView) |
| Build and CI | `container/`, `tools/dev.sh`, `tools/ci.sh`, `buildSrc/`, `gradle/`, `settings.gradle.kts`, root `*.gradle.kts`/`.properties`, `rust-toolchain.toml`, `tools/REFERENCE.toml` | all |
| App wiring | `app/` | — |

Rules for changing a contract:
- Rust FFI signatures are **final** for Wave A. If FFI finds a signature that cannot be implemented, it proposes a change. Once the coordinator approves, FFI edits the Rust and regenerates with `tools/dev.sh python3 tools/gen_kotlin_contract.py`. FFI owns that tool. FFI then fixes `UnimplementedDspiCore` users if needed.
- New members on `DspiBridge` go through the coordinator, which adds them to the interface. BRIDGE implements them. Default implementations are not allowed.
- Dependencies between modules are fixed: features depend on `:bridge` and `:ui-common` only, and `:feature-home` also depends on `:feature-graph` (`GraphView`), `:feature-firmware` also on `:usb`. Features never depend on each other otherwise, and never on `:ffi`/`:fake-core` except in tests (`testImplementation(":fake-core")`).
- New libraries go into `gradle/libs.versions.toml` and `gradle/verification-metadata.xml`. That is a coordinator change: ask for it. See `SUPPLY-CHAIN.md`.

---

## Wave A (parallel)

### FFI: dspi-ffi implementation, Android fd patch, opcode coverage

- **Owns:** `rust/` (everything under it, contract signatures excepted), `third_party/patches/`, `tools/stage-core.sh`, `tools/gen_kotlin_contract.py`, `tools/opcode_coverage.py`, `ffi/`, new top-level `fixtures/`.
- **Consumes:** `third_party/DSPi-Console-Linux` at `4683a2c` (never edit the submodule; patches only), the Rust contract signatures.
- **Provides:** a working `DspiCore` (Rust), `FfiDspiCore : DspiCoreApi` (Kotlin, generated adapter), `libdspi_ffi.so` for arm64-v8a/armeabi-v7a/x86_64, and a V32 state fixture for BRIDGE.
- **CI:** `tools/ci.sh ffi`, which also runs `tools/ci.sh contract`.
- **Definition of done:**
  1. `third_party/patches/0001-android-fd.patch`: `DeviceManager::select_device_handle(handle, serial, vid)` per PLAN Appendix A. Claim interface 2 only and never detach kernel drivers. It must also make the core reachable from an external crate: `lib.rs`'s wrappers use `pub(crate)` fields (`device_manager`, `cs`, `baseline`, `state`), so either add `pub` accessors or call the core's `extern "C"` functions. Keep the patch small and upstreamable, and document the choice in the patch header.
  2. Every stub in `rust/dspi-ffi/src/core.rs` and `lib.rs` (`config_inspect`, `bootloader_counts`) delegates to the core with the semantics of `core/src/lib.rs` and `Documentation/core_spec.md`. Status bytes map to `DspiError::Refused`. `open_fd`: `rusb::disable_device_discovery()` exactly once before any libusb context exists, then `GlobalContext::default().open_device_with_fd(fd)`, then the patched `select_device_handle`, then reset state/cs/baseline as `dspi_select_device` does. `close_fd` = disconnect. Callback interfaces are wired through `NotifyHub::set_waker` / `RtaHub::set_waker` trampolines that keep the `Box<dyn …>` alive in `Listeners`; `set_hotplug_callback`/`poll_hotplug` follow `dspi_poll_hotplug`.
  3. Rust tests: record round-trips, error mapping, a decode of the V32 fixture through `protocol::decode_bulk` checking known values, and every `*_DEFAULTS`. HIL tests may be written in `rust/dspi-ffi/tests/hil.rs`, but they must stay `#[ignore]` and gated on `DSPI_HIL=1`. **Agents never run them.** The coordinator runs them on the owner's units.
  4. `fixtures/bulk_v32.bin`: a copy of `_ref/DSPi-Link/fixtures/device/bulk_v32.bin` (6136 B, DSPi-Link `05949b8`; record the provenance next to it). `fixtures/dspstate_v32.json`: that bulk decoded by the core and serialised as the `DspState` record (an `examples/dump_state.rs` producing it; field names = Kotlin camelCase). BRIDGE reads this JSON.
  5. `tools/opcode_coverage.py` is real (see its TODO): firmware `config.h` at the pinned SHA vs `protocol.rs` vs the opcodes reachable from dspi-ffi methods. The known gap 0x82 `REQ_GET_CLOCK_DIAG` goes in an allowlist. CI fails on anything new.
  6. The release `.so` size is reasonable (`dspi.rustProfile=release`, LTO is already set).
- **Ports (as code, not translation):** `linux:core/src/lib.rs` (the C ABI semantics), and PLAN §2.2 / Appendix A.

### USB: attach, permission, fd ownership, foreground service, bootloader locator

- **Owns:** `usb/`.
- **Consumes:** `DspiBridge.attachDevice` / `detachDevice` / `setUiVisible`, `UsbIds`.
- **Provides:** `AndroidUsbSession : UsbSession`, `AndroidBootloaderLocator : BootloaderLocator`, `DspiConnectionService` (foreground, `connectedDevice`) with a notification showing user volume, mute and preset, with actions for each.
- **CI:** `tools/ci.sh usb`
- **Definition of done:** PLAN §5.2/§5.3 behaviour:
  - Cold start: `getDeviceList` + `requestPermission`.
  - `USB_DEVICE_ATTACHED` with implicit permission (the app's manifest already points at `@xml/dspi_device_filter`).
  - Open, keep the `UsbDeviceConnection`, hand over the fd.
  - Detach: `detachDevice` first, then `connection.close()`.
  - Legacy VID devices connect and show a banner (the bridge reports `compat`).
  - The service starts on open and stops on close; status polling stops when no UI is visible.
  - Never claim interfaces 0/1, never `setConfiguration`, never `forceClaim=true`.
  - The locator lists VID 0x2E8A PID 0x0003/0x000F with live updates. **No flashing** (that is FIRMWARE).
  - Logic sits behind small interfaces over `UsbManager`/broadcasts so it is unit-tested on the JVM with fakes.
  - No real device testing. Phone HIL is the coordinator's.
- **Ports:** macOS `USBDevice.swift` (lifecycle concepts only), `BootloaderLocator.swift`, `MenuBarMode.swift` (spirit of the notification). Plus `linux:qt/src/DSPiBridge.cpp` `pollHotplug`/reconnect behaviour.

### BRIDGE: DspiBridge implementation and the full FakeDspiCore

- **Owns:** `bridge/` (except the contract files listed above), `fake-core/`.
- **Consumes:** `DspiCoreApi`, `fixtures/dspstate_v32.json` from FFI. Until that lands, use `defaultDspState()`.
- **Provides:** `DspiBridgeImpl : DspiBridge` and the controller implementations, then deletes `StubDspiBridge`. The coordinator switches `:app` to `DspiBridgeImpl`. Also a full `FakeDspiCore`.
- **CI:** `tools/ci.sh bridge`
- **Definition of done:**
  - A 1:1 port of `linux:qt/src/DSPiBridge.cpp` (1,765 lines), following `linux:CLAUDE.md` "Rendering and updates":
    - all FFI calls run on one serial dispatcher;
    - 60 ms status poll (peaks, CPU, clip latch like `m_lastClipMs`), off when `setUiVisible(false)`;
    - notification drain batched to at most one state emission per 30 ms, recomputing only the curves of changed channels;
    - magnitude/phase cache per app id including the gain offset;
    - `sendOnly` semantics;
    - input-pair links persisted per device serial through an injected `LinkStore`;
    - channel clipboard, limiter helpers, `pinOwners`, `inputSources`, Core 1 conflict helpers, `directRouting`/`clearRouting`, `clearPeq`/`setAllBandsBypass`, dirty tracking (`captureBaseline` after fetch/load/save, `presetDirty` after each setter), retry 4× / 150 ms for idempotent GETs after flash ops (never for SETs).
  - A `DangerousOps` gate: the flash-writing and bootloader calls require an explicit confirmation token (PLAN §4 "Write-as-read safety").
  - Controllers ported from `RtaController.cpp`, `SiggenController.cpp`, `StatsController.cpp`, `CsController.cpp`, `MonitorModel.cpp`, `ConfigFiles.cpp`.
  - `FakeDspiCore` is seeded from the V32 fixture, models every setter on the state the way the core does (`commands.rs`), and computes curves with a Kotlin port of `dsp_math.rs` that lives in fake-core only (so demo graphs look real; its tests compare against values from the Rust tests). It also simulates notifications, the RTA, the siggen and presets well enough for the UI packages' tests and demo mode.
  - Unit tests: mapping, throttle, notify batching, dirty prompt, links, every setter path against the fake.
- **Ports:** `linux:qt/src/DSPiBridge.{h,cpp}`, `RtaController.*`, `SiggenController.*`, `StatsController.*`, `CsController.*`, `MonitorModel.*`, `ConfigFiles.*`, `qt/qml/components/Throttle.qml`; `win:Core/Models/ChannelMap.cs`. Fake curves from `linux:core/src/dsp_math.rs`.

---

## Wave B: UI feature packages (parallel, after the Wave A contracts are stable)

Common to all Wave B packages:
- **Consume:** `DspiBridge` (and its controllers), `:ui-common`.
- **Provide:** the entry composable(s) listed below. The signatures are fixed and `:app` wires them.
- **CI:** `tools/ci.sh <package>`. **Do not run** other packages' CI or `all`.
- **Definition of done:**
  - Behaviour and wording follow the macOS README (`_ref/DSPi-Console-b5/README.md`) sections in `PORT-MAP.md`, adapted for touch.
  - File, composable and state names mirror the Swift ones (PLAN §3.3 naming rule).
  - Phone layout first; tablet two-pane where the macOS view is a split view.
  - Live drags use `sendOnly = true` through `Throttle` and commit on release.
  - Flash and bootloader actions are always confirmed with the macOS wording.
  - `@Preview`s run against `FakeDspiCore`. Non-UI logic (formatters, state reducers, gesture math) is JVM-tested.
  - CI green. Deliberate deviations are recorded in `docs/UPSTREAM-DEVIATIONS.md`, in a section headed with the package name.

| Package | Owns | Entry points (route) | Ports (macOS `v1.1.6-beta5` unless noted) |
|---|---|---|---|
| **HOME** | `ui-common/`, `feature-home/` | `HomeScreen` (`home`), `ChannelScreen` (`channel/{ch}`) | `ContentView.swift` (SidebarSelection, ContentView, OutputChannelDetail, UserVolumeSection, MasterModeSection); `Components.swift` (meters, sidebar rows, volume taper, ChannelSettingsView, input routing panel, input header, FilterList, FilterRow, crossover rows, Linkwitz editor, value fields); `DashboardView.swift`; `CustomSlider.swift`; `ChannelPalette.swift`; compat banner (README "Firmware Version Warning"); Linux `qt/qml/{Sidebar,ChannelSettingsView,FilterListView,DashboardView}.qml` + `components/` for how they drive the core. Uses `GraphView` from GRAPH. |
| **GRAPH** | `feature-graph/` | `GraphScreen` (`graph`), `GraphView` (embeddable) | `GraphView.swift`, `GraphSpectrumOverlay.swift`, `LiveGraphHost.swift`, `PeqGraphEditor.swift`, `PeqGraphModel.swift`, `PeqGraphRenderer.swift`, `PeqGraphSupport.swift`, `PeqBandHUD.swift`; **editor logic from `win:DSPiConsole.Core/GraphEditing/PeqGraphEditor.cs` with `PeqGraphEditorTests.cs` translated**; Linux `PeqEditorItem.cpp`, `BodePlotItem.cpp`; README "Response Graph" + "On-Graph Filter Editing" (touch gesture table goes into PORT-MAP). |
| **MATRIX** | `feature-matrix/` | `MatrixMixerView` (`matrix`) | `MatrixMixerView.swift`; Linux `MatrixMixerWindow.qml`. Phone: one page per output; tablet: grid. |
| **TOOLS** | `feature-tools/` | `ToolsScreen` (`tools`), `OutputLimiterView` (`tools/limiter/{out}`), tool sub-routes internal | `LoudnessView`, `CrossfeedView`, `VolumeLevellerView`, `PsychoacousticBassView`, `SubharmonicSynthView`, `TubeModellerView` (+`TubeIllustration`), `UpmixerView`, `OutputLimiterView`, `ToolParameters.swift`; Linux `*Window.qml` of the same names and `components/{ChannelChips,…}`. |
| **PRESETS** | `feature-presets/` | `PresetsScreen` (`presets`) | ContentView preset menu (Copy to…, Save as default, Rename, Clear), Unsaved Changes sheet (`unsaved_changes_spec.md`), Commit/Revert/Factory Reset, Save Master Volume / Output Configuration; `PresetDocument*.swift`/`FilterFile.swift` UX over the core's JSON (`ConfigFiles`), SAF pickers + share; `AutoEQ/AutoEQManager.swift`, `AutoEQBrowser.swift` (DB bundled as an asset; Linux `AutoEqLibrary.cpp`). |
| **SETTINGS** | `feature-settings/` | `SettingsScreen` (`settings`) | `DSPi_ConsoleApp.swift` SettingsView + `PinOverviewTab`, `GeneralSettingsTab`, `GlobalSettingsTab`, `SpectrumSettingsTab`, `GraphingSettingsTab`, `AdvancedSettingsTab`, `ControlInterfacesSettingsTab`, `ControlSurfacesSettingsTab`, `HardwareSettingsTab`, `SettingsSaveCoordinator`, `GlobalSettingsDraft`, `OutputConfigSnapshot`, `SettingsSaveBar`; `win:Settings/PendingChangeTracker.cs`, `PinNavigation.cs`; Linux `qt/qml/settings/pages/*Page.qml` for the page split. App preferences (DataStore) for General/Graphing/Spectrum. |
| **ANALYSIS** | `feature-analysis/` | `AnalysisScreen` (`analysis`), sub-routes internal | `TestSignalsView.swift`, `SpectrumAnalyser.swift`, `SpectrumAnalyserView.swift` (Canvas/AGSL instead of Metal; tablet first), `StatsView.swift`, `InterruptMonitor.swift`; Linux `SignalGeneratorWindow.qml`, `SpectrumAnalyserWindow.qml`, `RtaViews.cpp`, `StatsWindow.qml`, `InterruptMonitorWindow.qml`. |
| **FIRMWARE** | `feature-firmware/` | `FirmwareUpdateView` (`firmware`) | `FirmwareUpdateView.swift`, `FirmwareInstallUI.swift`, `FirmwareInstaller.swift` (state machine 1:1), `FirmwareVersion.swift`, bundled UF2s (assets, `release/v1.1.6` beta5 tag); Linux `FirmwareUpdater.cpp`. Android write path: PICOBOOT over the bootloader's vendor interface (`picoboot-rs` and the RP2350 datasheet as references), with an SAF fallback. It uses `:usb`'s `BootloaderLocator`. **Never flash anything during development.** Test the installer against a fake PICOBOOT transport. Flashing unit B is the coordinator's HIL step. |

Not assigned yet (Phase 6): onboarding / What's New (`Onboarding/*`, `WhatsNew.json`), tablet two-pane polish, `tools/upstream-diff.sh`, `docs/ports/<tag>.md` checklists.

## CI commands

| Package | Command | Runs |
|---|---|---|
| (coordinator) | `tools/ci.sh supply` | wrapper jar checksum, wrapper sha pin, verification metadata present |
| (coordinator) | `tools/ci.sh contract` | `gen_kotlin_contract.py --check`, `:core-api` ktlint + tests |
| FFI | `tools/ci.sh ffi` | stage core; cargo fmt/clippy (-D warnings)/test; cargo-ndk aarch64; contract check; opcode coverage; `:ffi` ktlint + unit tests + assemble |
| USB | `tools/ci.sh usb` | `:usb` ktlint + unit tests + assemble |
| BRIDGE | `tools/ci.sh bridge` | `:bridge`, `:fake-core` ktlint + tests |
| HOME | `tools/ci.sh home` | `:ui-common`, `:feature-home` |
| GRAPH … FIRMWARE | `tools/ci.sh graph\|matrix\|tools\|presets\|settings\|analysis\|firmware` | that feature module |
| (coordinator) | `tools/ci.sh app`, `tools/ci.sh all` | `:app` assembleDebug (builds Rust too); everything |

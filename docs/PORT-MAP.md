# Port map: macOS Console → Android (living document)

The living version of PLAN-NATIVE-ANDROID.md §3.3. Reference versions are pinned in
`tools/REFERENCE.toml`:
- macOS `v1.1.6-beta5` = b5fdeac (`_ref/DSPi-Console-b5`)
- Linux core `main` 4683a2c (the submodule)
- Windows `v1.1.6-beta4`

Update this file whenever a port lands or moves.

**Naming rule:**
- Kotlin file = Swift file name.
- Compose function = Swift `View` struct name.
- State property = `@Published` name in camelCase.
- Bridge member = `DSPiBridge` invokable/property name, Qt casing kept.
- Core call = `dspi_*` minus the prefix, in camelCase.

`DSPi_ConsoleApp.swift` (11k lines) is split by its top-level declarations. The names stay the same.

Status: `—` not started · `W0` placeholder exists · `WIP` · `done`.

## Protocol / state (replaced by the core)

| macOS | Android | Package | Status |
|---|---|---|---|
| `Commands.swift`, `Constants.swift`, `DSPViewModel.swift` (wire structs + view model), `USBDevice.swift`, `USBNotificationReader.swift`, `Models.swift`, `DSPMath.swift` | `third_party/DSPi-Console-Linux/core` (unmodified + `third_party/patches/`) → `rust/dspi-ffi` → `core-api` `DspiCoreApi` → `bridge` `DspiBridge` (`StateFlow<DspState>` + per-feature flows) | FFI, BRIDGE | FFI: done (`DspiCore` implemented, `0001-android-fd.patch`, V32 fixture; HIL pending) · BRIDGE: W0 |
| `PresetSnapshot.swift`, `PresetDocument.swift`, `PresetDocumentTransfer.swift`, `FilterFile.swift` | in the core: `presetdiff.rs` (`captureBaseline`, `presetDirty`, `presetChanges`), `presetfile.rs` (`configExport/Inspect/Import`), `filterfile.rs` (`filtersExport/Inspect/Import`); UI in `feature-presets` | FFI / PRESETS | FFI: done (JSON passthroughs) · PRESETS: done (`PresetAlerts.kt` diff summary, `FileMenuActions.kt`, `PresetLabels.kt`; JVM-tested) |
| `HostAudioFormatMonitor.swift` (CoreAudio) | `AudioManager`/`AudioDeviceInfo` badge in `feature-home` | HOME | — |
| Linux `qt/src/DSPiBridge.{h,cpp}` | `bridge/…/DspiBridge.kt` (interface) + `DspiBridgeImpl.kt`, `DangerousOpsImpl.kt`, `LinkStore.kt` | BRIDGE | done (tested); `:app` uses `DspiBridgeImpl` (APP integration, D9), the Wave 0 `StubDspiBridge` goes with it |
| Linux `RtaController`, `SiggenController`, `StatsController`, `CsController`, `MonitorModel`, `ConfigFiles` | `bridge/…/Controllers.kt` interfaces + `ControllersImpl.kt` | BRIDGE | done (tested) |
| Linux `core/src/dsp_math.rs` (+ `commands.rs`/`preset.rs`/`presetdiff.rs` semantics, for the fake) | `fake-core/…/DspMath.kt`, `FakeDspiCore.kt`, `FakePresetDiff.kt`, `FakeFiles.kt`, `DspStateJson.kt` | BRIDGE | done (tested; fixture hookup pending FFI's `fixtures/dspstate_v32.json`) |
| Linux `qml/components/Throttle.qml` | `bridge/…/Throttle.kt` | BRIDGE | done (tested) |
| Windows `Core/Models/ChannelMap.cs` / DSPiBridge `wire/appId` | `bridge/…/ChannelMap.kt` | BRIDGE | done (tested) |

## App shell, USB, firmware

| macOS | Lines | Android | Package | Status |
|---|---|---|---|---|
| `DSPi_ConsoleApp.swift` → `AppState`, `AppSettings` | ~400 | `app/…/AppState.kt`, `AppSettings.kt` (DataStore) | coordinator / SETTINGS (prefs UI) | — |
| `FileMenuActions`, `ToolsMenuActions`, `AutoEQMenuActions`, window controllers, `AppDelegate` | ~1.5k | `app/…/AppScaffold.kt` (NavHost; tool windows → routes) | coordinator | W0 |
| `USBDevice.swift` lifecycle, menu-bar mode spirit | — | `usb/…/UsbSession.kt` (contract), `AndroidUsbSession.kt` + `UsbSessionEngine.kt` (logic), `AndroidUsbHost.kt`, `DspiConnectionService.kt` + `NotificationControls.kt`, `UiVisibilityTracker.kt` | USB | done (JVM-tested; phone HIL pending) |
| `BootloaderLocator.swift` | — | `usb/…/BootloaderLocator.kt` (contract), `AndroidBootloaderLocator.kt` (`BootloaderScanner`) | USB | done (locating only; HIL pending) |
| `FirmwareUpdateView.swift`, `FirmwareInstallUI.swift`, `FirmwareInstaller.swift`, `FirmwareVersion.swift`, `Firmware/*.uf2` | ~1.5k | `feature-firmware/…/FirmwareUpdateView.kt`, `FirmwareInstaller.kt`, PICOBOOT writer, UF2 assets | FIRMWARE | W0 |
| `Onboarding/*`, `WhatsNew.json` | ~3k | `GettingStartedView.kt`, `WhatsNewView.kt` (simplified) | unassigned (Phase 6) | — |
| `SoftwareUpdateSettingsTab`, `AppUpdateChecker/Installer.swift`, `MenuBarMode.swift`, `NetworkingSettingsView.swift`, `Link/*` | ~8.5k | **not ported** (Play/F-Droid updates; no hub role on a phone) | — | n/a |

## UI

| macOS (beta5) | Lines | Android target | Backed by (bridge / core) | Package | Status |
|---|---|---|---|---|---|
| `ContentView.swift` (`SidebarSelection`, `ContentView`, `OutputChannelDetail`, `UserVolumeSection`, `MasterModeSection`, preset context menu) | 1,382 | `feature-home`: `HomeScreen.kt` (sidebar content first on phone), `ChannelScreen.kt`; preset menu → `feature-presets` | `peakLevel/isClipping`, `setMasterVolume/Mode`, `setUserVolume/Mute`, `setInputSource`, presets | HOME (+PRESETS menu) | W0 · PRESETS menu: done (`feature-presets/…/PresetMenuActions.kt`, `ToolsMenuActions.kt`, `PresetsScreen.kt`; JVM-tested) |
| `Components.swift` (26 views) | 2,821 | `feature-home/…/components/*.kt`, one file per MARK section | `setFilter`, `setBandBypass`, `setInputPreamp`, `setChannelDelay`, `setInputLinked`, `copyChannel/pasteChannel` | HOME | — |
| `DashboardView.swift` | 579 | `feature-home/…/DashboardView.kt` | state + `magnitudeCurve` | HOME | — |
| `CustomSlider.swift`, `ChannelPalette.swift`, NSView wrappers | ~0.6k | `ui-common/…/CustomSlider.kt`, `ChannelPalette.kt`, `Theme.kt` | — | HOME | W0 |
| `GraphView.swift`, `GraphSpectrumOverlay.swift`, `LiveGraphHost.swift`, pop-out | ~1.5k | `feature-graph/…/GraphView.kt` (embeddable `BodePlotView`, Canvas; `GraphResizeHandle`, `GraphLegend`), `GraphSpectrumOverlay.kt`, `GraphOptions.kt` (gear panel + Graph Setup), `GraphSettings.kt` (AppSettings graph/RTA keys, `RtaChannelSelection`), `LiveGraphHost.kt` (`GraphLive`), `GraphScreen.kt` (pop-out = full-screen route + legend + edit-channel picker) | `magnitudeCurve/phaseCurve`, `curveFrequencies`, `channelVisible`, `rta.update/release/snapshot/caps` | GRAPH | done (JVM-tested logic; previews on FakeDspiCore; RTA Bars, spectrum time smoothing open) |
| `PeqGraphEditor.swift`, `PeqGraphModel.swift`, `PeqGraphRenderer.swift`, `PeqGraphShaders.swift`, `PeqGraphSupport.swift`, `PeqBandHUD.swift` | ~3.4k | `feature-graph/…/PeqGraphEditor.kt` + `PeqGraphModel.kt` + `PeqEditorTypes.kt` + `PeqShapeChooserState.kt` (logic from Windows `GraphEditing/*.cs`; `PeqGraphEditorTests.cs` translated: 51/51 pass), `PeqGraphSupport.kt` (selection + host), `PeqGraphEditorView.kt` (overlay, `BridgePeqEditorHost`), `PeqGraphRenderer.kt` (Canvas; shaders → gradients), `PeqBandHud.kt`, `PeqTouchGestures.kt` (touch mapping), `PeqDspMath.kt` (copy of fake-core's dsp_math port) | `channelBands`, `channelGainOffset`, `sendBandLive` (via `Throttle`), `commitBands`, `setBandBypass` | GRAPH | done (JVM-tested; HIL pending) |
| `MatrixMixerView.swift` | 817 | `feature-matrix/…/MatrixMixerView.kt` (+ `MatrixModel.kt`, `MatrixActions.kt`, `MatrixPoint.kt`, `CompactFields.kt`) | `setMatrixRoute`, `setOutput*`, `directRouting/clearRouting` | MATRIX | done (JVM-tested; phone pages + tablet grid; previews in `src/debug`) |
| `LoudnessView`, `CrossfeedView`, `VolumeLevellerView`, `PsychoacousticBassView`, `SubharmonicSynthView`, `TubeModellerView` (+`TubeIllustration`), `UpmixerView`, `ToolParameters.swift` | ~5.3k | `feature-tools/…/*.kt`, same names | `setLoudness*`, `setCrossfeed*`, `setLeveller*`, `setPsybass*`, `setSubharm*`/`fetchSubharm*`, `setTubeParam`, `setUpmixParam`/`fetchUpmixStatus` | TOOLS | W0 (`ToolsScreen`) |
| `OutputLimiterView.swift` | — | `feature-tools/…/OutputLimiterView.kt` (route `tools/limiter/{out}`) | `limiter*`, `setLimiter*` | TOOLS | W0 |
| `TestSignalsView.swift` | 1,072 | `feature-analysis/…/TestSignalsView.kt` | `siggen` controller | ANALYSIS | — |
| `SpectrumAnalyser.swift`, `SpectrumAnalyserView.swift`, `RtaMetal*.swift`, `RtaRenderCache.swift` | ~4.7k | `feature-analysis/…/SpectrumAnalyserView.kt` (Canvas/AGSL; tablet first) | `rta` controller | ANALYSIS | — |
| `StatsView.swift` | 1,682 | `feature-analysis/…/StatsView.kt` | `stats` controller | ANALYSIS | — |
| `InterruptMonitor.swift` | 1,070 | `feature-analysis/…/InterruptMonitorView.kt` | `monitor` | ANALYSIS | — |
| Settings: `SettingsView` + `PinOverviewTab`, `GeneralSettingsTab`, `GlobalSettingsTab`, `SpectrumSettingsTab`, `GraphingSettingsTab`, `AdvancedSettingsTab`, `ControlInterfacesSettingsTab`, `ControlSurfacesSettingsTab`, `HardwareSettingsTab`, `SettingsSaveCoordinator`, `GlobalSettingsDraft`, `OutputConfigSnapshot`, `SettingsSaveBar` | ~9k | `feature-settings/…/SettingsScreen.kt` + one file per tab, `SettingsSaveCoordinator.kt`, `PendingChangeTracker.kt` (Windows) | hardware IO setters, `pinOwners`, `cs` controller, `setOutputConfigMode`, `saveOutputConfig` | SETTINGS | W0 |
| `AutoEQ/AutoEQManager.swift`, `AutoEQBrowser.swift`, `autoeq_database.json` | ~1k | `feature-presets/…/autoeq/*.kt`, DB as asset | `setInputPreamp` + `setFilter` × 10 on inputs 1–2 | PRESETS | done (`AutoEQManager.kt`, `AutoEQBrowser.kt`, `AutoEQMenuActions.kt`; DB asset from v1.1.6-beta5; no GitHub rebuild) |

## Touch gestures (On-Graph Filter Editing, README l.572–792)

Implemented by `feature-graph/…/PeqTouchGestures.kt` over the ported editor
(`PeqGraphEditor.kt`); tested in `PeqTouchGesturesTest`. A mouse (and
hardware keyboard modifiers) on Android goes to the editor exactly as on
macOS/Windows (Cmd → Ctrl/Meta, Option → Alt). Touch targets: dots 24 dp,
curve 14 dp (macOS 10/6 pt). A move within the system touch slop is a tap.

| macOS | Android touch | Notes |
|---|---|---|
| Click a dot | Tap a dot | Selects that band only. |
| Click empty graph / a fill | Tap empty graph / a fill | Deselects; never adds. |
| Double-click empty graph / a fill | Double-tap | Adds a Bell at the finger's frequency and level, Q 1. |
| Double-click a dot | Double-tap a dot | Chip opens and a dialog asks for Freq. |
| Drag a dot | Drag a dot | Frequency + gain (Q for 12 dB cuts; frequency only for notch/all-pass/6 dB cuts). Live `sendOnly` through `Throttle`, one commit on release. Multi-selection moves together (same ratio / same dB). |
| Drag the curve | Drag the curve | Pulls a new band out of it (Low Shelf left, High Shelf right, Bell elsewhere). |
| Drag empty graph | Drag empty graph | Marquee selection. (While editing, the graph keeps the finger; the page scrolls elsewhere.) |
| Right-click a dot | Long-press a dot | Band menu: Band N / N Bands, Slope/Order, Bypass/Enable, Invert Gain, Delete. |
| Right-click empty graph | Long-press empty graph | Graph menu, headed by **Add Band…**, then Select All Bands, Deselect All, Delete Selected. |
| Cmd-click empty graph (Add Band card) | Long-press empty graph > Add Band… | Same card: shape, then slope. |
| Scroll over a dot/fill (Q) | Pinch over a band (spread = higher Q) or two-finger vertical drag (up = higher Q, 60 dp per octave like Cmd-drag) | Acts on the selection when the band is part of it; commits 0.45 s after the fingers stop. |
| Cmd-drag (Q) | Pinch / two-finger drag | As above. |
| Cmd-scroll (gain) | Drag the dot vertically, or the chip's Gain | No separate touch gesture. |
| Scroll over the dB labels (zoom) | Pinch over empty graph (any graph, also the dashboard) | Changes Graph Setup > Range (10–100 dB). |
| Option-click a dot (bypass) | Chip power button, or long-press > Bypass | |
| Cmd-click / Shift-click a dot (add to / range select) | Marquee, long-press > Select All Bands, or the band list (HOME) | No touch modifier; a hardware keyboard's Ctrl/Shift still work. |
| Shift (fine), Option (axis lock), Control (scale gains) while dragging | Hardware keyboard only | |
| Hover readouts, ghost dot, chip on hover | Mouse/stylus hover only | A finger has no hover; lifting it clears them. The chip stays on the selection. |
| Chip value: drag vertically / scroll / double-click to type | Drag vertically / tap to type (dialog) | Returns false + haptic "beep" on unreadable text. |
| Chip shape button / power button | Tap | Shape page: shape, then slope. |
| Keys: Delete, Escape, Tab, arrows, Option-arrows, Cmd-A | Same, from a hardware keyboard | Graph takes focus on a mouse click. |

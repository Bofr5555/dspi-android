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
| `DSPi_ConsoleApp.swift` → `AppState`, `AppSettings`; `DSPViewModel` `activeEqChannel`/`isOverviewMode` | ~400 | `ui-common/…/settings/AppSettings.kt` + `DataStoreAppSettingsStore.kt` (one store, macOS keys, `LocalAppSettings`; one-time import of the interim stores), `ui-common/…/state/AppState.kt` (`LocalAppState`); `app/…/DspiApplication.kt` provides both, `RtaOptionsBinding.kt` (analyser options, app-wide) | CONSOLIDATION | done (F3) |
| `FileMenuActions`, `ToolsMenuActions`, `AutoEQMenuActions`, window controllers, `AppDelegate` | ~1.5k | `app/…/AppScaffold.kt` (NavHost; tool windows → routes; one shared `BootloaderLocator` for FirmwareUpdateView) | coordinator | W0 |
| `USBDevice.swift` lifecycle, menu-bar mode spirit | — | `usb/…/UsbSession.kt` (contract), `AndroidUsbSession.kt` + `UsbSessionEngine.kt` (logic), `AndroidUsbHost.kt`, `DspiConnectionService.kt` + `NotificationControls.kt`, `UiVisibilityTracker.kt` | USB | done (JVM-tested; phone HIL pending) |
| `BootloaderLocator.swift` | — | `usb/…/BootloaderLocator.kt` (contract), `AndroidBootloaderLocator.kt` (`BootloaderScanner`) | USB | done (locating only; HIL pending) |
| `FirmwareUpdateView.swift`, `FirmwareInstallUI.swift`, `FirmwareInstaller.swift`, `FirmwareVersion.swift`, `Firmware/*.uf2` | ~1.5k | `feature-firmware/…/FirmwareUpdateView.kt` (+ `FirmwareUpdateModel.kt`), `FirmwareInstallUI.kt`, `FirmwareInstaller.kt`, `FirmwareVersion.kt`, `BootloaderLocating.kt`, `Uf2.kt`, `Picoboot.kt`, `FirmwareWriters.kt`, `AndroidFirmwareIo.kt`; UF2 assets (official release, verified) | FIRMWARE | done (JVM-tested on fakes; review B-2 fixes applied; flashing HIL pending) |
| `Onboarding/*`, `WhatsNew.json` | ~3k | `GettingStartedView.kt`, `WhatsNewView.kt` (simplified) | unassigned (Phase 6) | — |
| `SoftwareUpdateSettingsTab`, `AppUpdateChecker/Installer.swift`, `MenuBarMode.swift`, `NetworkingSettingsView.swift`, `Link/*` | ~8.5k | **not ported** (Play/F-Droid updates; no hub role on a phone) | — | n/a |

## UI

| macOS (beta5) | Lines | Android target | Backed by (bridge / core) | Package | Status |
|---|---|---|---|---|---|
| `ContentView.swift` (`SidebarSelection`, `ContentView`, `OutputChannelDetail`, `UserVolumeSection`, `MasterModeSection`, preset context menu) | 1,382 | `feature-home`: `HomeScreen.kt` → `ContentView.kt` (tablet split / phone home), `ChannelScreen.kt`, `ChannelDetail.kt` (OutputChannelDetail, input pane), `PresetPickerRow.kt` (picker row + context menu UI over PRESETS' `PresetsViewModel`/`PresetMenuActions`, F1), `HomeModel.kt`, `FirmwareMismatchBanner.kt` | `peakLevel/isClipping`, `setMasterVolume/Mode`, `setUserVolume/Mute`, `setInputSource`, presets | HOME | done (graph-selection sync, resize handle: CONSOLIDATION; SpectrumBarStrip open: UPSTREAM-DEVIATIONS HOME 9) |
| `Components.swift` (26 views) | 2,821 | `feature-home/…/components/`: `SidebarRows.kt`, `ConnectionStatusIndicator.kt`, `VolumeMode.kt`, `ChannelSettings.kt` (+InputRoutingPanel, limiter cell), `InputChannelHeader.kt` (+InputLinkAlerts), `FilterList.kt`, `FilterRow.kt` (+Linkwitz panel, XO row, `PeqRowHighlight`/`RowGraphLink`), `LiveDrag.kt`; shared parts in `ui-common` (`ValueField.kt`, `CompactFields.kt`, `Meters.kt`, `Controls.kt`, `Rows.kt`, `Glyphs.kt`, `Formatters.kt`, `FilterTypes.kt`) | `setFilter`, `setLinkwitzTransform`, `setBandBypass`, `setAllBandsBypass`, `setCrossover*`, `setInputPreamp`, `setOutput*`, `setMatrixRoute`, `setInputLinked`, `copyChannel/pasteChannel` | HOME | done |
| `DashboardView.swift` | 579 | `feature-home/…/DashboardView.kt` | state + `channelBands` | HOME | done |
| `CustomSlider.swift`, `ChannelPalette.swift`, NSView wrappers; NSAlert of the menu actions | ~0.6k | `ui-common/…/CustomSlider.kt` (CustomSlider, ParameterRow), `SliderValueDelivery.kt`, `ChannelPalette.kt` (macOS values, `fromHex`, `hex`), `Theme.kt`, `MacColors.kt` (system colours), `DspiIcons.kt` (Linux Icon.qml), `alert/ConsoleAlert.kt` + `alert/ConsoleAlertDialog.kt` (NSAlert model, `AlertHost`, `ConsoleAlertHost`), `json/Json.kt`, `WhileVisible.kt` | — | HOME / CONSOLIDATION | done |
| `GraphView.swift`, `GraphSpectrumOverlay.swift`, `LiveGraphHost.swift`, pop-out | ~1.5k | `feature-graph/…/GraphView.kt` (embeddable `BodePlotView`, Canvas; `GraphResizeHandle`, `GraphLegend`), `GraphSpectrumOverlay.kt`, `GraphOptions.kt` (gear panel + Graph Setup), `GraphSettings.kt` (graph extensions of :ui-common AppSettings, `RtaChannelSelection`), `LiveGraphHost.kt` (`GraphLive`), `GraphScreen.kt` (pop-out = full-screen route + legend; Follow Channel Selection via AppState, else edit-channel picker) | `magnitudeCurve/phaseCurve`, `curveFrequencies`, `channelVisible`, `rta.update/release/snapshot/caps` | GRAPH | done (JVM-tested logic; previews on FakeDspiCore; RTA Bars open) |
| `PeqGraphEditor.swift`, `PeqGraphModel.swift`, `PeqGraphRenderer.swift`, `PeqGraphShaders.swift`, `PeqGraphSupport.swift`, `PeqBandHUD.swift` | ~3.4k | `feature-graph/…/PeqGraphEditor.kt` + `PeqGraphModel.kt` + `PeqEditorTypes.kt` + `PeqShapeChooserState.kt` (logic from Windows `GraphEditing/*.cs`; `PeqGraphEditorTests.cs` translated: 51/51 pass), `PeqGraphSupport.kt` (selection + host), `PeqGraphEditorView.kt` (overlay, `BridgePeqEditorHost`), `PeqGraphRenderer.kt` (Canvas; shaders → gradients), `PeqBandHud.kt`, `PeqTouchGestures.kt` (touch mapping), `PeqDspMath.kt` (copy of fake-core's dsp_math port; not merged: needs a new JVM module + a :fake-core edit) | `channelBands`, `channelGainOffset`, `sendBandLive` (via `Throttle`), `commitBands`, `setBandBypass` | GRAPH | done (JVM-tested; HIL pending) |
| `MatrixMixerView.swift` | 817 | `feature-matrix/…/MatrixMixerView.kt` (+ `MatrixModel.kt`, `MatrixActions.kt`, `MatrixPoint.kt`; compact fields/glyphs in `ui-common`) | `setMatrixRoute`, `setOutput*`, `directRouting/clearRouting` | MATRIX | done (JVM-tested; phone pages + tablet grid; previews in `src/debug`) |
| `LoudnessView`, `CrossfeedView`, `VolumeLevellerView`, `PsychoacousticBassView`, `SubharmonicSynthView`, `TubeModellerView` (+`TubeIllustration`), `UpmixerView`, `ToolParameters.swift` | ~5.3k | `feature-tools/…/*.kt`, same names; `ToolsScreen.kt` (Tools menu + internal sub-routes), `ToolComponents.kt` (tool-window layout, GraphLive)/`ToolFormat.kt`/`ToolGraph.kt`; ParameterRow/ValueField/header/chips/menus from `ui-common` | `setLoudness*`, `setCrossfeed*`, `setLeveller*`, `setPsybass*`, `setSubharm*`/`fetchSubharm*`, `setTubeParam`, `setUpmixParam`/`fetchUpmixStatus` | TOOLS | done (JVM-tested; previews on FakeDspiCore; no device test) |
| `OutputLimiterView.swift` | — | `feature-tools/…/OutputLimiterView.kt` (route `tools/limiter/{out}`; `OutputLimiterCell` on the output page is HOME's) | `limiter*`, `setLimiter*` | TOOLS | done (settings screen; edits via beginOutputEdit, ganging mirrored by the bridge) |
| `TestSignalsView.swift` | 1,072 | `feature-analysis/…/TestSignalsView.kt`, `TestSignalsModel.kt`, `SiggenGlyph.kt` | `siggen` controller | ANALYSIS | done (tested) |
| `SpectrumAnalyser.swift`, `SpectrumAnalyserView.swift`, `RtaMetal*.swift`, `RtaRenderCache.swift` | ~4.7k | `feature-analysis/…/SpectrumAnalyserView.kt` (Canvas), `SpectrumAnalyser.kt` (scale, smoother, selection, curve columns from Linux `RtaViews.cpp`) | `rta` controller | ANALYSIS | done (tested) |
| `StatsView.swift` | 1,682 | `feature-analysis/…/StatsView.kt`, `StatsModel.kt` | `stats` controller, `fetchSpdifRxStatus`/`fetchSpdifChannelStatus`/`fetchActiveSpdifRxPin`, `fetchLgStatus`/`fetchAdatOutStatus`/`fetchInputLock` | ANALYSIS | done (tested) |
| `InterruptMonitor.swift` | 1,070 | `feature-analysis/…/InterruptMonitorView.kt`, `InterruptMonitorModel.kt` (decoding in the core) | `monitor` | ANALYSIS | done (tested) |
| Settings: `SettingsView` + `PinOverviewTab`, `GeneralSettingsTab`, `GlobalSettingsTab`, `SpectrumSettingsTab`, `GraphingSettingsTab`, `AdvancedSettingsTab`, `ControlInterfacesSettingsTab`, `ControlSurfacesSettingsTab`, `HardwareSettingsTab`, `SettingsSaveCoordinator`, `GlobalSettingsDraft`, `OutputConfigSnapshot`, `SettingsSaveBar` | ~9k | `feature-settings/…/SettingsScreen.kt` + one file per tab, `SettingsSaveCoordinator.kt`, `HardwareChange.kt` (Windows TopoSort) | hardware IO setters, `pinOwners`, `cs` controller, `setOutputConfigMode`, `saveOutputConfig` | SETTINGS | done (live hardware apply per E1b + save bar, all tabs; CS editor partial — see UPSTREAM-DEVIATIONS SETTINGS) |
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
| Scroll over a dot/fill (Q) | Pinch over a band (spread = higher Q) or two-finger vertical drag (up = higher Q, 60 dp per octave like Cmd-drag) | Any selection owns it: with bands selected the whole selection changes, wherever the fingers are (as the wheel, `selectionOwnsTheWheel`); otherwise the band under the fingers. After a pause longer than 0.5 s the band is looked up again. Committed 0.45 s after the last pinch step. |
| Cmd-drag (Q) | Pinch / two-finger drag | As above. |
| Cmd-scroll (gain) | Drag the dot vertically, or the chip's Gain | No separate touch gesture. |
| Scroll over the dB labels (zoom) | Pinch over empty graph (any graph, also the dashboard) | Changes Graph Setup > Range (10–100 dB), by the pinch ratio. A mouse wheel over the left 40 dp strip zooms as macOS: range − Δ·3 dB per notch. |
| Option-click a dot (bypass) | Chip power button, or long-press > Bypass | |
| Cmd-click / Shift-click a dot (add to / range select) | Marquee, long-press > Select All Bands, or the band list (HOME) | No touch modifier; a hardware keyboard's Ctrl/Shift still work. |
| Shift (fine), Option (axis lock), Control (scale gains) while dragging | Hardware keyboard only | |
| Hover readouts, ghost dot, chip on hover | Mouse/stylus hover only | A finger has no hover; lifting it clears them. The chip stays on the selection. |
| Chip value: drag vertically / scroll / double-click to type | Drag vertically / tap to type (dialog); a mouse wheel scrolls the value (Shift fine, Cmd/Ctrl or a running graph scroll goes to the graph) | Drag: Shift (hardware keyboard) = ×0.12. Dialog: Return/Done applies, Tab/Shift-Tab apply and move, unreadable text keeps the field open with a haptic "beep". |
| Chip shape button / power button | Tap | Shape page: shape, then slope. |
| Keys: Delete, Escape, Tab, arrows, Option-arrows, Cmd-A | Same, from a hardware keyboard | Graph takes focus on a mouse click. |

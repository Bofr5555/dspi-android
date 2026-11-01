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
| `DSPi_ConsoleApp.swift` → `AppState`, `AppSettings` | ~400 | `app/…/AppState.kt`; `feature-settings/…/AppSettings.kt` + `DataStoreAppSettingsStore.kt` (`AppSettingsStore`, macOS keys) | coordinator / SETTINGS (prefs UI) | AppSettings done (SETTINGS); interface to move to :ui-common |
| `FileMenuActions`, `ToolsMenuActions`, `AutoEQMenuActions`, window controllers, `AppDelegate` | ~1.5k | `app/…/AppScaffold.kt` (NavHost; tool windows → routes) | coordinator | W0 |
| `USBDevice.swift` lifecycle, menu-bar mode spirit | — | `usb/…/UsbSession.kt` (contract), `AndroidUsbSession.kt` + `UsbSessionEngine.kt` (logic), `AndroidUsbHost.kt`, `DspiConnectionService.kt` + `NotificationControls.kt`, `UiVisibilityTracker.kt` | USB | done (JVM-tested; phone HIL pending) |
| `BootloaderLocator.swift` | — | `usb/…/BootloaderLocator.kt` (contract), `AndroidBootloaderLocator.kt` (`BootloaderScanner`) | USB | done (locating only; HIL pending) |
| `FirmwareUpdateView.swift`, `FirmwareInstallUI.swift`, `FirmwareInstaller.swift`, `FirmwareVersion.swift`, `Firmware/*.uf2` | ~1.5k | `feature-firmware/…/FirmwareUpdateView.kt` (+ `FirmwareUpdateModel.kt`), `FirmwareInstallUI.kt`, `FirmwareInstaller.kt`, `FirmwareVersion.kt`, `BootloaderLocating.kt`, `Uf2.kt`, `Picoboot.kt`, `FirmwareWriters.kt`, `AndroidFirmwareIo.kt`; UF2 assets (official release, verified) | FIRMWARE | done (JVM-tested on fakes; review B-2 fixes applied; flashing HIL pending) |
| `Onboarding/*`, `WhatsNew.json` | ~3k | `GettingStartedView.kt`, `WhatsNewView.kt` (simplified) | unassigned (Phase 6) | — |
| `SoftwareUpdateSettingsTab`, `AppUpdateChecker/Installer.swift`, `MenuBarMode.swift`, `NetworkingSettingsView.swift`, `Link/*` | ~8.5k | **not ported** (Play/F-Droid updates; no hub role on a phone) | — | n/a |

## UI

| macOS (beta5) | Lines | Android target | Backed by (bridge / core) | Package | Status |
|---|---|---|---|---|---|
| `ContentView.swift` (`SidebarSelection`, `ContentView`, `OutputChannelDetail`, `UserVolumeSection`, `MasterModeSection`, preset context menu) | 1,382 | `feature-home`: `HomeScreen.kt` → `ContentView.kt` (tablet split / phone home), `ChannelScreen.kt`, `ChannelDetail.kt` (OutputChannelDetail, input pane), `PresetPickerRow.kt` (picker row + context menu UI over PRESETS' `PresetsViewModel`/`PresetMenuActions`, F1), `HomeModel.kt`, `FirmwareMismatchBanner.kt` | `peakLevel/isClipping`, `setMasterVolume/Mode`, `setUserVolume/Mute`, `setInputSource`, presets | HOME | done (SpectrumBarStrip, graph-selection sync: see UPSTREAM-DEVIATIONS HOME 9) |
| `Components.swift` (26 views) | 2,821 | `feature-home/…/components/`: `SidebarRows.kt`, `ConnectionStatusIndicator.kt`, `VolumeMode.kt`, `ChannelSettings.kt` (+InputRoutingPanel, limiter cell), `InputChannelHeader.kt` (+InputLinkAlerts), `FilterList.kt`, `FilterRow.kt` (+Linkwitz panel, XO row), `LiveDrag.kt`; shared parts in `ui-common` (`ValueField.kt`, `Meters.kt`, `Controls.kt`, `Formatters.kt`, `FilterTypes.kt`) | `setFilter`, `setLinkwitzTransform`, `setBandBypass`, `setAllBandsBypass`, `setCrossover*`, `setInputPreamp`, `setOutput*`, `setMatrixRoute`, `setInputLinked`, `copyChannel/pasteChannel` | HOME | done |
| `DashboardView.swift` | 579 | `feature-home/…/DashboardView.kt` | state + `channelBands` | HOME | done |
| `CustomSlider.swift`, `ChannelPalette.swift`, NSView wrappers | ~0.6k | `ui-common/…/CustomSlider.kt` (CustomSlider, ParameterRow), `SliderValueDelivery.kt`, `ChannelPalette.kt`, `Theme.kt` (MacColors), `DspiIcons.kt` (Linux Icon.qml), `AlertHost.kt` | — | HOME | done |
| `GraphView.swift`, `GraphSpectrumOverlay.swift`, `LiveGraphHost.swift`, pop-out | ~1.5k | `feature-graph/…/GraphView.kt` (embeddable, Canvas), `GraphScreen.kt` (pop-out = fullscreen landscape) | `magnitudeCurve/phaseCurve`, `curveFrequencies`, `rta.snapshot` | GRAPH | W0 |
| `PeqGraphEditor.swift`, `PeqGraphModel.swift`, `PeqGraphRenderer.swift`, `PeqGraphShaders.swift`, `PeqGraphSupport.swift`, `PeqBandHUD.swift` | ~3.4k | `feature-graph/…/PeqGraphEditor.kt` (logic from Windows `PeqGraphEditor.cs` + tests), `PeqGraphEditorView.kt`, `PeqBandHud.kt` | `channelBands`, `sendBandLive`, `commitBands` | GRAPH | — |
| `MatrixMixerView.swift` | 817 | `feature-matrix/…/MatrixMixerView.kt` (+ `MatrixModel.kt`, `MatrixActions.kt`, `MatrixPoint.kt`, `CompactFields.kt`) | `setMatrixRoute`, `setOutput*`, `directRouting/clearRouting` | MATRIX | done (JVM-tested; phone pages + tablet grid; previews in `src/debug`) |
| `LoudnessView`, `CrossfeedView`, `VolumeLevellerView`, `PsychoacousticBassView`, `SubharmonicSynthView`, `TubeModellerView` (+`TubeIllustration`), `UpmixerView`, `ToolParameters.swift` | ~5.3k | `feature-tools/…/*.kt`, same names; `ToolsScreen.kt` (Tools menu + internal sub-routes), `ToolComponents.kt`/`ToolFormat.kt`/`ToolGraph.kt` (local ParameterRow/ValueField/chips, to fold into ui-common) | `setLoudness*`, `setCrossfeed*`, `setLeveller*`, `setPsybass*`, `setSubharm*`/`fetchSubharm*`, `setTubeParam`, `setUpmixParam`/`fetchUpmixStatus` | TOOLS | done (JVM-tested; previews on FakeDspiCore; no device test) |
| `OutputLimiterView.swift` | — | `feature-tools/…/OutputLimiterView.kt` (route `tools/limiter/{out}`; `OutputLimiterCell` on the output page is HOME's) | `limiter*`, `setLimiter*` | TOOLS | done (settings screen; edits via beginOutputEdit, ganging mirrored by the bridge) |
| `TestSignalsView.swift` | 1,072 | `feature-analysis/…/TestSignalsView.kt`, `TestSignalsModel.kt`, `SiggenGlyph.kt` | `siggen` controller | ANALYSIS | done (tested) |
| `SpectrumAnalyser.swift`, `SpectrumAnalyserView.swift`, `RtaMetal*.swift`, `RtaRenderCache.swift` | ~4.7k | `feature-analysis/…/SpectrumAnalyserView.kt` (Canvas), `SpectrumAnalyser.kt` (scale, smoother, selection, curve columns from Linux `RtaViews.cpp`) | `rta` controller | ANALYSIS | done (tested) |
| `StatsView.swift` | 1,682 | `feature-analysis/…/StatsView.kt`, `StatsModel.kt` | `stats` controller, `fetchSpdifRxStatus`/`fetchSpdifChannelStatus`/`fetchActiveSpdifRxPin`, `fetchLgStatus`/`fetchAdatOutStatus`/`fetchInputLock` | ANALYSIS | done (tested) |
| `InterruptMonitor.swift` | 1,070 | `feature-analysis/…/InterruptMonitorView.kt`, `InterruptMonitorModel.kt` (decoding in the core) | `monitor` | ANALYSIS | done (tested) |
| Settings: `SettingsView` + `PinOverviewTab`, `GeneralSettingsTab`, `GlobalSettingsTab`, `SpectrumSettingsTab`, `GraphingSettingsTab`, `AdvancedSettingsTab`, `ControlInterfacesSettingsTab`, `ControlSurfacesSettingsTab`, `HardwareSettingsTab`, `SettingsSaveCoordinator`, `GlobalSettingsDraft`, `OutputConfigSnapshot`, `SettingsSaveBar` | ~9k | `feature-settings/…/SettingsScreen.kt` + one file per tab, `SettingsSaveCoordinator.kt`, `HardwareChange.kt` (Windows TopoSort) | hardware IO setters, `pinOwners`, `cs` controller, `setOutputConfigMode`, `saveOutputConfig` | SETTINGS | done (live hardware apply per E1b + save bar, all tabs; CS editor partial — see UPSTREAM-DEVIATIONS SETTINGS) |
| `AutoEQ/AutoEQManager.swift`, `AutoEQBrowser.swift`, `autoeq_database.json` | ~1k | `feature-presets/…/autoeq/*.kt`, DB as asset | `setInputPreamp` + `setFilter` × 10 on inputs 1–2 | PRESETS | done (`AutoEQManager.kt`, `AutoEQBrowser.kt`, `AutoEQMenuActions.kt`; DB asset from v1.1.6-beta5; no GitHub rebuild) |

## Touch gestures (On-Graph Filter Editing, README l.572–792)

GRAPH fills in this table. Starting point from PLAN §3.3:
- drag = frequency/gain
- pinch or two-finger drag = Q
- long-press = band menu
- double-tap = add a band

Each row will give the macOS gesture, the Android gesture, and notes.

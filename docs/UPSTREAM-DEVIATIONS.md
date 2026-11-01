# Deliberate deviations from the upstream clients

Each package records here what it changed on purpose relative to the
reference it ports (PACKAGES.md), and why. One section per package.

## Gating policy (coordinator, contract)

Decisions D1, D3, D5 of `reviews/decisions-wave-0A.md` and E1/E1b of
`reviews/decisions-waveB-1.md` (E1b supersedes D2). Guiding rule from the
owner: stay as close to the official clients as possible (macOS
v1.1.6-beta5 is the behavioural reference). Confirmation is a safety net for
irreversible/flash operations, not a UX change. Contract:
`bridge/.../DangerousOpsContract.kt` (`DangerousOp`, `ConfirmationToken`,
`DangerousOps`, `DangerousOpNotConfirmed`) and the GATED members of
`DspiBridge`/`CsController`/`ConfigFiles`.

1. **Gated** (deviation: Qt confirms nothing in the bridge; the official UIs
   confirm these in dialogs, which the token makes mandatory): the flash
   writes the official clients treat as "save" — SAVE_PARAMS 0x51,
   SAVE_OUTPUT_CONFIG 0x52, FACTORY_RESET 0x53, SAVE_PRESET 0x90,
   DELETE_PRESET 0x92, CS_SAVE 0x9D, SAVE_MASTER_VOLUME 0xD6, COPY_PRESET —
   plus ENTER_BOOTLOADER 0xF0 and CONFIG_IMPORT (bulk apply 0xA3/0xA1 + pin
   writes).
2. **HARDWARE_APPLY** (E1b, `reviews/decisions-waveB-1.md`; supersedes
   D2): output pins/types, I2S BCK pin, clock pin mode and clock mode, MCK,
   ADAT/S/PDIF/I2S input and output pins and enables, input source and input
   rate rebuild the audio pipeline. As on macOS (`DSPi_ConsoleApp.swift`
   `SettingsSaveCoordinator`) they apply LIVE to RAM when the user operates
   the control; the token is minted by that explicit interaction (picker /
   toggle / menu choice, incl. HOME's input-source menu) — the deviation is
   only that a token exists at all. In independent mode
   `beginOutputEdit()` captures the revert baseline and sets
   `outputConfigDirty` (limiter edits too); the Save bar flashes with
   `saveOutputConfig` (SAVE_OUTPUT_CONFIG minted by the Save tap) and Revert
   re-applies the baseline (`revertOutputConfig`, HARDWARE_APPLY minted by the
   Revert tap). Global parameters are a staged draft applied by Save. No
   pending-hardware queue. (Qt also applies them live, without a Save bar.)
3. **Not gated** (one click in the official clients): load preset, revert to
   saved (`loadParams`), clear clips, DAC mute test, buffer-stats reset,
   siggen start/identify, CS revert / IR learn / macro fire. The only gate on
   preset load is the official Unsaved Changes flow (dirty →
   Save/Discard/Cancel), owned by the UI and by the USB notification's
   `canCyclePreset`.
4. **Ungated flash writes** (D3): preset rename 0x94, startup 0x96,
   output-config mode 0x98, master-volume mode, DAC HW mute config, UART/I2C
   persist write flash, but the official clients do not confirm them, so
   neither do we. The bridge arms its retry blackout after them, and also
   after preset load, pin moves, input-source switch and bulk apply
   (commands.md §1.3).
5. **No silent no-ops:** gated members take the token as a parameter. An
   invalid token (other op, spent, revoked, expired, other bridge) throws
   `DangerousOpNotConfirmed` before any USB traffic: a programming error,
   never a transport-style failure value.

## BRIDGE

Reference: DSPi-Console-Linux `qt/src/DSPiBridge.{h,cpp}` and controllers
@ `4683a2c`. Gating is the coordinator's policy above; this section is what
the implementation does beyond it.

1. **Threading.** Qt calls the core on the GUI thread. Here every core call
   runs on one serial FFI dispatcher and results are published on the main
   dispatcher (PLAN §3.4/§5.4). Getters read the last published `DspState`
   snapshot instead of the core live; plain `sendOnly` calls therefore do not
   refresh getters until the commit. The "preview" setters
   (`setInputPreamp(sendOnly)`, `sendOutputGainToDevice`) refresh the
   snapshot silently and emit `PreviewChanged` (Qt `previewChanged`);
   `sendBandLive` refreshes the snapshot and the dragged channels' cached
   curves without any signal (Qt: `markDirty`, recomputed on the next draw).
   Read-modify-write setters (`setFilter`, `setCrossover`, …) read the band
   from the core on the FFI thread so queued edits compose; every emitting
   setter re-reads the whole state (`getState()` copy) once afterwards — cost
   to profile on a phone. The bridge reads the state once at construction
   (before it hands the core to anyone; no USB) and starts disconnected.
2. **Curves.** `magnitudeCurve` includes the channel's gain offset (contract);
   Qt leaves that to `BodePlotItem`. Curves are recomputed eagerly on the FFI
   thread for the channels a change touched (Qt: lazily on draw).
   `magnitudesRevision` is bumped whenever a curve or a gain offset changed,
   and on every link change (Qt `magnitudesChanged`).
3. **Android devices.** No libusb enumeration: `:usb` hands fds to
   `attachDevice`/`detachDevice`. `scanDevices`/`selectDevice` keep Qt's
   behaviour for cores that can enumerate (desktop, the fake). The 500 ms
   hotplug timer runs only with `hotplugPolling = true`. `reconnect`/`rescan`
   re-open the remembered fd instead of reselecting by serial. Connection
   error texts mention reconnecting instead of the udev rule;
   `installUdevRule` has no Android equivalent. `scanDevices` also selects
   the first device when none is selected (not only when disconnected), so
   a core that starts connected (the fake, demo mode) is picked up.
4. **Status poll at 0 Hz** when no UI is visible (`setUiVisible(false)`,
   PLAN §5.3) and while disconnected; Qt's timer always runs. The limiter
   meter is part of `BridgeStatus.limiterReduction` (D4), so its changes are
   status changes as in Qt.
5. **Gating** follows the policy above. Tokens (`DangerousOpsImpl`) are valid
   for their own op on their own bridge (identity), single use (HARDWARE_APPLY:
   reusable, each use renews its 10 s lifetime), and are not bound to call
   arguments (the contract token carries only the op). A refused call throws
   `DangerousOpNotConfirmed` before any device traffic and leaves the
   caller's token untouched. `cs.apply`/`cs.sendOnly` with `{op: "save"}`
   throw; the flash save goes through `cs.save(token)` only.
6. **Retry blackout** (no Qt equivalent; DESIGN §4.5 rule 4): idempotent GETs
   are retried up to 4× at 150 ms on a transfer error within 2 s after any of:
   preset load/save/delete/copy, revert (`loadParams`), factory reset, save
   master volume / output config, CS save, configuration import (bulk apply),
   every HARDWARE_APPLY call (pins, output type, input source/rate, clocks —
   commands.md §1.3), and the ungated D3 directory/config writes (preset
   rename/startup, output-config mode, master-volume mode, DAC HW mute
   config, UART/I2C). Plain SETs never arm it, and SETs are never retried.
7. **Errors.** Qt ignores the core's bool for fire-and-forget setters; here a
   failure is also reported as `BridgeEvent.CommandFailed`.
8. **Limits from the core.** `preampMaxDB`/`maxDelayMs` come from
   `DspiCoreApi.preampMaxDb()/maxDelayMs()`, read with every state snapshot
   (Qt reads them live).
9. **Notification drain.** Batched as Qt (≤ 1 per 30 ms); a wake arriving
   while a drain is in flight re-arms it, so the last notification of a burst
   always lands (Qt's single-shot timer is inactive by then). For
   `NOTIFY_INPUT_FORMAT` the status is polled before the state is published,
   as Qt does.
10. **StatsController.info** carries the STAT_* counters only (contract); the
    receiver/LG/ADAT/I2S-slave readings Qt folds into `info` come from the
    bridge's `fetch*` calls. Buffer history for the traces is the UI's job.
11. **ConfigFiles** works on text, not file URLs (SAF lives in the UI);
    `inspectConfiguration` adds `crossPlatform` as Qt does, not the localised
    `saved` string.

12. **Limiter ganging (macOS `LimiterGang`).** The core keeps per-output
    limiter state and does not mirror the firmware's link-group ganging, so
    the bridge repeats a committed enable/threshold/release SET for every
    other member of the group, and re-sends the adopted values to an output
    joining a group — the values the device already applied (idempotent).
    This keeps the core's state, preset diff and export equal to the device
    at the cost of a few extra control transfers per commit (not per drag:
    `sendOnly` sends once). A core-side mirror would remove them (FFI
    follow-up). Revert and applyLimiterSettings ungang, set, regang as macOS.
13. **Matrix helpers.** `directRouting(rows)`/`clearRouting(rows)` follow
    macOS `applyDirectRouting`/`clearAllRoutes` over the active input rows
    (`effectiveInputChannels` when not given); Qt touched every input row and
    left off-diagonal gains/polarity alone. Graph visibility follows macOS
    overview-mode `setOutputEnable` when `overview = true`.
14. **Capabilities.** `hardwareCaps` is derived (V32 firmware always carries
    multi-S/PDIF, I2S input, clock mode and clock-pin mode; ADAT out/in and
    multichannel I2S are RP2350-only) instead of macOS's per-feature probes;
    the S/PDIF receiver count is the wire maximum (4) because the core has no
    0xEF inventory. `siggenSupported` is the caps probe, as macOS.

## USB

1. **Notification instead of the macOS menu-bar item** (`MenuBarMode.swift`,
   spirit only): user volume (−/+ 2 dB steps, −60..0 dB as
   `UserVolumeSection`), mute toggle, "Next preset" (cycles occupied slots;
   hidden while the preset is dirty, the notification's equivalent of the
   Unsaved Changes gate). No hub/sharing items (not ported).
2. **Permission prompts** are Android-only: a denial is remembered per
   attached device until an explicit request or a re-plug, and dialogs are
   only shown while a UI is visible (queued otherwise).
3. **Bootloader locator** has no volume half (macOS pairs USB match + mounted
   volume); boards are identified by usbfs device name, and the app does not
   auto-launch for BOOTSEL boards (D8).
4. Multi-device: like macOS, a second unit never takes over an open
   connection; when the open unit leaves, the session switches to a survivor,
   preferring the last serial (Linux `rescan()`).
## MATRIX

Reference: macOS `MatrixMixerView.swift` (+ the matrix helpers of
`DSPViewModel.swift`, `Commands.swift`) @ v1.1.6-beta5; bridge usage as Linux
`qt/qml/MatrixMixerWindow.qml` @ 4683a2c.

1. **Phone layout: one page per output.** Below 600 dp the grid becomes a
   tab row of outputs (name + OUTn, disabled outputs dimmed) over a pager;
   each page lists the matrix rows for that output (name, input trim in
   8-channel mode, gain field, INV, the crosspoint dot) and the output's
   ENABLE / GAIN / DELAY / MUTE. The ROUTING band (with Direct 1:1 / Clear in
   8-channel mode) and the OUTPUT band keep their macOS labels and order.
   From 600 dp the macOS grid is drawn as-is (scrolls both ways).
2. **Compact fields on touch.** macOS: click to type, plain scroll wheel to
   adjust. Here: tap opens a small editor (unit optional, a decimal comma is
   accepted; non-numbers are ignored as on macOS); long-press then drag
   (up/right = more) replaces the scroll wheel at 0.1 dB per 3 dp (delay
   1 ms per 4 dp). Crosspoint gain moves unquantised, like the macOS wheel
   (the core quantises on the wire); output gain and input trim snap to
   0.1 dB, which the Swift setters round to anyway. Drags are live through
   `Throttle` (30 ms) and commit once on release: output gain via
   `sendOutputGainToDevice` → `setOutputGain`, delay via
   `sendOutputDelayToDevice` → `setOutputDelay`, input trim via
   `setInputPreamp(sendOnly = true)` → `sendOnly = false`, crosspoint gain
   via `setMatrixRoute(sendOnly = true)` → `sendOnly = false`.
3. **Rounding as macOS:** output gain 0.1 dB, output delay whole ms
   (README: "Applied in whole milliseconds"); delay max 42 ms (RP2040) /
   85 ms (RP2350) as `CompactDelayField(maxDelay:)`. Gain fields −60…+12 dB.
4. **Hover help → captions/labels.** ENABLE's tooltip text is a caption on the
   phone page and the click label for accessibility; MatrixPoint's
   "Connect/Disconnect" and "Phase inverted/Normal phase" are content
   descriptions. The row's long name (e.g. "Upmix Centre") is shown under the
   short one on the phone page.
5. **Column header menu** opens on tap or long-press (tablet) and from a "•••"
   button or long-press on the name (phone); items and wording as macOS
   (Identify only when connected and the generator is supported, disabled on a
   disabled output; Rename; Copy/Paste Parameters). Rename is a dialog
   (≤ 31 characters, trimmed, empty ignored) instead of in-place editing.
6. **Row count.** macOS sizes USB input rows from the host's CoreAudio
   format; Android has no equivalent, so the device-reported active input
   count is used (macOS's own fallback, Qt's behaviour). S/PDIF, I2S and ADAT
   follow macOS `effectiveInputChannels`.
7. **PDM conflict and graph visibility.** Same alert text and buttons; the
   confirm runs macOS switchToPDM (every EQ-worker output off, then PDM on)
   or switchFromPDM (PDM off, then the output on) as confirmed
   `setOutputEnable` calls; the bridge's snapshot after each call stands in
   for the macOS read-backs. As Commands.swift setOutputEnable, graph
   visibility follows ENABLE only in the graph's overview mode, and only
   when the device accepted the change. No component exposes overview mode
   to features yet, so the matrix currently never touches visibility
   (`MatrixActions(isOverviewMode = …)` is the hook). Direct 1:1 visibility
   is the bridge's (E5).
8. **Icons** ("power", speaker) are drawn locally (no icon library in the
   build); disabled crosspoints are greyed (macOS `.saturation(0)`) and dimmed.
9. **Lifecycle (E9).** The screen's controller (`MatrixActions.forBridge`)
   lives as long as the bridge on an app scope: enable/conflict sequences,
   the open "Warning" alert and the rename dialog survive rotation. Typed
   text in the dialogs is saved across rotation.
10. **8-channel extras** (Direct 1:1, Clear, input trims) need RP2350, the
    client's exact wire format (`WIRE_FORMAT_VERSION`) and 8 inputs, as macOS
    `supports8chInput`.


## APP

1. **Demo mode** ("Demo (no device)"): no official client has one. Offered
   only while no DSPi is attached; runs `FakeDspiCore.demo()` (V32 capture)
   behind its own `DspiBridgeImpl`, never through the USB session, is
   labelled in the title and in a strip under it, and ends by itself as soon
   as a DSPi appears. Its input-pair links are not persisted.
2. **Input-pair links** are kept in a Preferences DataStore
   (`dspi_bridge`, key `inputLinks/<serial>`) instead of QSettings, same keys
   and bit layout.
3. **Connection strip** under the top bar for the Android-only USB states
   (permission required/denied/pending, open failed) with "Allow USB access";
   macOS and Linux have no permission step.


## PRESETS

Reference: DSPi-Console (macOS) v1.1.6-beta5 — ContentView preset picker and
`PresetContextMenuItems`, `PresetSnapshot.swift` (`PresetDiff`, `PresetAlerts`),
`DSPi_ConsoleApp.swift` `FileMenuActions` / `ToolsMenuActions` /
`AutoEQMenuActions`, `AutoEQ/*`; Linux `qt/qml/components/PresetMenu.qml`,
`main.qml`, `qt/src/ConfigFiles.cpp`, `AutoEqLibrary.cpp` @ 4683a2c for how a
GUI drives this core.

Confirmations follow decision E1 exactly: Save, Copy to…, Save Master Volume
and Save Output Configuration act on the tap (the tap mints the token; Copy
to… asks only the Unsaved Changes alert, when dirty); Commit Parameters,
Revert, Factory Reset, Clear / Clear All and Import Device Configuration show
the macOS alerts and mint in their confirm handler. No added dialogs.

1. **Clear All Slots** mints one single-use DELETE_PRESET token per occupied
   slot in the one "Clear All Presets?" handler. Clearing the active slot (or
   Clear All) reloads the active slot afterwards, as macOS does (Linux does
   not).
2. **AutoEQ.** "Rebuild from GitHub" is not offered (it crawls the GitHub API
   and downloads thousands of files; the app has no network permission).
   "Update Database..." offers Import File... and Reset to Built-in (the user
   copy lives in the app's files dir). The browser is a full-screen dialog
   instead of a window; the favourite heart is always shown (no hover). The
   database is parsed only when the browser, a favourite or Update Database
   needs it; favourites are stored as id + "manufacturer model" (as Linux
   AutoEqLibrary) so the menu lists them without a parse. Apply with no device
   connected shows "No device connected." (macOS writes into a disconnected
   model, Linux returns false silently).
3. **One page.** macOS spreads these actions over the sidebar's Preset picker,
   its right-click menu and the File / Tools / AutoEQ menus. Here they are one
   "Presets & Files" page (phone: one column; ≥ 840 dp: two panes) with the
   macOS item titles. The picker is a radio list of the ten slots with the
   macOS labels ("N: name", "Preset N", "Empty", "*" on the dirty active slot)
   and the startup default marked; the startup mode is shown read-only
   (Settings › Global Parameters owns it).
4. **No quit prompt.** Android has no app quit / main-window close; the
   connection lives in the foreground service. The Unsaved Changes flow is
   `PresetMenuActions.withUnsavedChanges`, reusable by whoever switches
   devices (macOS also asks there).
5. **Lifecycle (decision E9).** The controller, its alert slot and progress
   sheet live in `PresetsViewModel`; every device sequence (save → load,
   save/reload → copy, delete(s) → reload, bulk import, the single saves with
   their result alert) is launched non-cancellable on its scope, so neither a
   rotation nor leaving the screen stops it between flash writes. macOS has no
   equivalent concern (modal alerts, background queue).
6. **Files.** NSOpenPanel / NSSavePanel → the Storage Access Framework, started
   from a translucent framework activity (`DocumentPickerActivity`) and awaited
   on the ViewModel. E3 approves `rememberLauncherForActivityResult`, but the
   verification metadata it needs (`androidx.compose.runtime:runtime:1.7.1`)
   is not on main yet, so the working picker stays; a pick that returns after
   process death is lost. The configuration's document name is the chosen
   file name without its extension; extra `.dspipreset` / `.json` / `.bin`
   extensions some providers append are also stripped. Added: "Share Device
   Configuration..." (Android share sheet via a FileProvider cache file; the
   share directory is emptied before each share and when the screen's
   ViewModel starts).
7. **Import results** use the core's report lines (presetfile.rs /
   filterfile.rs, as Linux): configuration — "Success" when clean, else an
   "Import Device Configuration" alert (macOS rule); filters — "Success" with
   the core's lines (macOS composes "Filters imported to N channel(s)" plus
   notes itself). The configuration progress sheet is indeterminate (the core
   applies in one bulk write). The export summary counts exactly as macOS
   (every crosspoint entry of the document).
8. **Status codes.** A transfer failure has no firmware status; alerts print
   it as "error 255" (the C API's no-reply code). No DSPi Link lock exists on
   Android, so `presetFailureText` never says "locked".
9. **Rename** limits the field to 31 characters (README; macOS truncates in
   firmware).

## TOOLS

Reference: macOS `v1.1.6-beta5` (b5fdeac) LoudnessView, CrossfeedView,
VolumeLevellerView, PsychoacousticBassView, SubharmonicSynthView,
TubeModellerView + TubeIllustration, UpmixerView, OutputLimiterView,
ToolParameters.swift; core driving as Linux `qt/qml/*Window.qml`. Wording,
ranges, defaults, presets and starting points are the macOS ones.

1. **Windows -> screens.** Each tool window is an internal sub-route of
   `ToolsScreen` (`tools/loudness`, `tools/crossfeed`, `tools/psybass`,
   `tools/subharm`, `tools/tube`, `tools/upmix`, `tools/leveller`); the Tools
   list mirrors the macOS Tools menu order and links Matrix Mixer and the
   analysis tools to their packages' routes. The Output Limiter popover is the
   full screen `tools/limiter/{out}`. Keyboard shortcuts do not exist.
2. **Layout.** The two `toolColumn()`s sit side by side from 600 dp (tablet,
   landscape) and stack left-then-right on a phone; single-column windows
   (Leveller, Upmixer, limiter popover) stay single-column. Everything
   scrolls. Fonts and chips are larger for touch (chips 36 dp high).
3. **Touch for hover/right-click/scroll.** `.help(...)` tooltips are shown on
   long-press (Material `TooltipBox`); channel-chip names likewise. Cmd+scroll
   stepping of value fields has no touch equivalent; tap the value to type
   (unit optional, clamped to the row's range, committed on Done or when the
   field loses focus).
4. **Live drags** send `sendOnly = true` through the bridge `Throttle` (first
   value at once, then the latest every 30 ms, pending value dropped before
   the commit), exactly as `ParameterRow`/`ParamRow.qml`; graphs follow the
   drag via a local `GraphLive`. Same semantics as macOS.
5. **Firmware gates** use `DspState.formatVersion` with the macOS thresholds
   (loudness mask V19, crossfeed pair mask V20, psybass V23, subharm V29/V30,
   tube V31, limiter V32, upmixer V26 + RP2350). The limiter screen shows an
   upgrade note below V32 (macOS hides the cell instead; the route can still
   be reached here).
6. **Icons.** SF Symbols are not available: headers have no symbol except
   the Tube Modeller (`TubeIcon`, ported) and the Output Limiter
   (`LimiterIcon`, ported). The "unsupported" note uses a Unicode warning sign.
7. **TubeIllustration.** Drawn with three Canvas layers instead of CALayers;
   SwiftUI `.blur` on the contact shadow, heater streak and filament glow is
   approximated with unblurred translucent strokes (no DrawScope blur below
   API 31). The audio bloom follows `BridgeStatus.peaks` (app ids 2..10 =
   outputs), as macOS follows the status peaks.
8. **Tube Basic/Advanced** is remembered in `SharedPreferences`
   (`dspi_tools`/`tubeModellerAdvanced`) instead of `@AppStorage`, and the
   segmented switch sits under the header rather than in it.
9. **Subharmonic solo** is switched off when the view leaves composition
   (macOS: when the window closes); headroom is re-read whenever the subharm
   parameters change and on open; the sub meter polls at 10 Hz while the
   view is up, extended (V30) and the effect is on.
10. **Upmixer telemetry** is polled every 60 ms while the view is up (macOS
    ~16 Hz shared timer; Linux 100 ms).
11. **System back** inside an open tool currently leaves the Tools route
    (the in-screen "‹ Tools" button closes the tool). `BackHandler` needs
    `androidx.activity:activity-compose` on `:feature-tools`; adding it pulls
    `androidx.core:core:1.13.0` and `compose.runtime:1.7.1` module metadata
    that `gradle/verification-metadata.xml` does not cover — coordinator
    change requested.
12. **Limiter link-group ganging** is not mirrored locally. macOS mirrors the
    firmware's ganging (`LimiterGang`, ported and tested in ToolParameters.kt)
    because writes are never echoed; the core does not, so a peer output in
    the same group shows its stale values until the next bulk read. Also the
    macOS `SettingsSaveCoordinator.beginOutputEdit()` (limiter edits mark the
    output configuration unsaved in independent mode) has no bridge hook.
    Both are bridge change requests; the UI calls the plain setters.

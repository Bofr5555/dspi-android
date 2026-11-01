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

## HOME

Reference: macOS `ContentView.swift`, `Components.swift`, `DashboardView.swift`,
`CustomSlider.swift`, `ChannelPalette.swift`, `FirmwareUpdateView.swift`
(FirmwareMismatchBanner) @ `b5fdeac`; Linux `qt/qml` @ `4683a2c` for how the
core is driven. Gating follows coordinator E1/E1b (`reviews/decisions-waveB-1.md`).

1. **Layout.** Tablet (>= 840 dp): the macOS split view (sidebar | graph +
   detail, selection toggled in place). Phone: the sidebar content is the
   home page (graph, INPUTS/OUTPUTS, tool icons, Preset/Source/volume, CPU,
   then the dashboard) and a channel row opens `channel/{ch}`
   (`ChannelScreen`: graph soloed on the channel + the detail pane). Cards
   that are one row on macOS (ChannelSettingsView, InputChannelHeader, filter
   rows) keep that row from 600/560 dp up and stack below it.
2. **Right-click -> long-press.** Channel row context menu (Identify, Rename,
   Copy/Paste Parameters), tool icons (open the tool page: the TOOLS route,
   tool sub-routes are TOOLS-internal), device picker (reconnect), limiter
   icon (`tools/limiter/{out}`), preset picker (context menu; also a ⋯
   button, since touch has no discoverable secondary click). Slider resets
   (right-click on macOS) are a double-tap on the slider; a value field
   without a slider (matrix crosspoint gain) resets on long-press.
3. **Value fields** edit on tap with the decimal keyboard and accept a decimal
   comma / Unicode minus; the macOS Cmd-scroll stepping has no touch
   equivalent (`scrollStep` kept for API parity). Tooltips (`.help`) have no
   hover: kept as accessibility labels; the dashboard layout gear is always
   visible instead of on hover.
4. **Popovers -> dialogs / menus.** Linkwitz Transform panel is a dialog
   (Revert / Apply as macOS, plus Done to close; closing still drops unapplied
   edits). Hierarchical menus (PEQ type shapes, Copy to…) open their
   submenu in place with a back row.
5. **Sliders** (`CustomSlider`) move relative to where they were on a
   horizontal drag; a tap does not jump the thumb (macOS click-to-jump), so a
   stray tap or a scroll brushing a volume slider never changes a level, and
   a double-tap reset has no intermediate values (review H8). Nothing is sent
   on the grab (F4).
   **Live drags** go to the device through `SliderValueDelivery` (30/s,
   `sendOnly = true`) for every slider; macOS sends gain/volume/preamp on every
   change event. Commit on release as macOS.
6. **Preset flows** are PRESETS' `PresetMenuActions` (coordinator F1), held
   in `HomeViewModel` (E9); HOME keeps the picker row and the context-menu
   UI. Wording, tokens and failure texts are therefore PRESETS'. The
   "LOCKED" failure text of macOS `presetFailureText` has no `PresetStatus`
   entry in core-api (review H12, core-api gap).
7. **Source picker** mints `HARDWARE_APPLY` from the menu choice and applies
   immediately (E1b), as macOS does.
8. **Firmware banner** is driven by the bridge's `compat` (too old / too new),
   not by a comparison with a bundled firmware (FIRMWARE owns
   `FirmwareVersion`); the expected version shown is `1.1.6-beta5`. Hidden
   for the process lifetime ("this launch").
9. **Not ported here:** `SpectrumBarStrip` above the channel pages and the
   dashboard (RTA, ANALYSIS); the PEQ list's graph-selection sync
   (`LocalPeqGraphSelection`, live readouts, band colours) until GRAPH is
   on main (review H10, follow-up after the GRAPH merge); onboarding anchors (Phase 6); graph resize
   handle (fixed heights).
10. **Capability gates** (`firmwareSupportsNotch`, …) are all true: the core
    only talks to 1.1.6-compatible firmware.
11. **Linked input pairs** are mirrored by the bridge (`targets()`), not at
    the call site; Clear PEQ calls `clearPeq` once.
12. **Preferences** `sidebarVolumeMode` and `dashboardCardsPerRow` live in
    SharedPreferences `dspi_home` under the macOS key names until the F3
    consolidation moves AppSettings into :ui-common (review H16 / X1).
13. **Icons** are the Linux client's stroke set (`Icon.qml`) in place of SF
    Symbols; the bass clef is the Linux glyph.
14. **Light theme**: `DspiColors.Light` is AppKit's light appearance; no
    reference client ships a light palette (review H15), it exists because
    Android follows the system theme. The dark set is `MacColors.qml`.
15. **isDeviceReady** (row/dashboard fade) is `connected && bulkValid`: the
    core marks the state valid once the connect fetch landed (review H13).
16. **Typed band values** go to the bridge unclamped, as macOS (review H7).

## SETTINGS

Reference: macOS `DSPi_ConsoleApp.swift` (v1.1.6-beta5) SettingsView and tabs;
Windows `Settings/PendingChangeTracker.cs` (only its `TopoSort`),
`PinNavigation.cs`; Linux `qt/qml/settings/` for the page split.
Gating per E1b, output-config tracking per F2: behaviour is macOS's.

1. **Gating (E1b) and dirty tracking (F2), otherwise as macOS.** A hardware
   control mints HARDWARE_APPLY on the tap/choice, calls
   `bridge.beginOutputEdit()` and applies at once
   (`SettingsSaveCoordinator.applyHardware`). The Save bar reads
   `bridge.outputConfigDirty` only (never `hardwareUnsaved`); Save writes the
   global draft, then `saveOutputConfig` and the CS save with
   SAVE_OUTPUT_CONFIG / CS_SAVE tokens minted on the Save tap; Revert drops the
   global draft, reverts the CS preview and calls `bridge.revertOutputConfig`
   with a HARDWARE_APPLY token minted on the Revert tap. Where one user action
   implies several calls (Reset Pins, the S/PDIF instance count, ADAT enable +
   pin) they run in dependency order (Windows `TopoSort`) and stop at the
   first refusal. Generic success lines where macOS has none of its own;
   failures use macOS's wording. The fire-and-forget input-rate / I2S
   clock-mode setters report nothing (no outcome to report; a suspend variant
   returning the status would be a BRIDGE addition).
2. **Capabilities** come from `bridge.hardwareCaps`; there is no flag for
   input-source switching, so the Inputs page is hidden on STM32 only
   (contract request: an `inputSourceSupported` / LG Sound Sync flag).
3. **BCK candidates** also require the LRCLK neighbour (BCK + 1) to be a
   wirable GPIO (Windows `IsAudioCapable`, Linux `pairFree`); macOS would
   offer GPIO 22 (LRCLK on board-reserved 23) and let the firmware refuse.
4. **Overview click-through:** tapping a pin opens the page that sets it
   (Windows PinNavigation, without the scroll-and-flash). Control-surface
   pins are recognised by the bridge's owner label "Control Surface N"
   (contract request: a structured owner kind on `PinOwner`).
5. **MCK multiplier lock** reads the live rate from `fetchInputRate()` every
   2 s while the I2S page is open (macOS: `telemetry.sampleRateHz`).
6. **Page list on phones.** The macOS sidebar becomes a grouped list of
   pages (one page at a time; system back returns to the list); ≥ 840 dp
   shows sidebar + page. "Software Update" and "Networking" are not ported
   (PORT-MAP), nor the Advanced tab's "Onboarding (Developer)" section
   (onboarding is Phase 6). The save bar adds a tap-to-expand list of what
   is pending (Windows InfoBar).
7. **Control Surfaces** renders the controls, groups, macros and auxiliary
   outputs from the core's `cs_snapshot`, with rename/remove of a slot,
   macro Run and aux on/off (live previews or one click, D1; run in the
   app-scoped coordinator, E9). The full binding editor (component/function/
   target pickers, IR learn, display pages, group and macro editors) is not
   ported yet.
8. **App preferences** use the exact macOS `@AppStorage` key names in a
   Preferences DataStore (`AppSettingsStore`, to move to :ui-common per
   F3); the macOS one-time migrations of retired keys are not needed.
9. **Device identity** for aborting a Save/hardware sequence is the selected
   serial (macOS: the transport generation); the bridge's own revert uses
   its generation.

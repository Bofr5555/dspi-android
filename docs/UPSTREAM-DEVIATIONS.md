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
    BRIDGE-3: `inputSourceSupported` and `lgSoundSyncSupported` (separate
    flags, as on macOS) are true while the V32 image is usable — macOS sets
    both on every V21+ bulk parse. macOS then re-probes 0xE1
    (GET_INPUT_SOURCE) and clears `inputSourceSupported` on a STALL; the
    core has no such probe, and every RP2040/RP2350 firmware that writes V32
    answers it. The S/PDIF receiver count 4 holds on RP2040 too (firmware
    `SPDIF_RX_NUM_INPUTS 4` unconditionally; review S18).
15. **Status variants for the input rate and I2S clock mode (BRIDGE-3,
    SETTINGS S7).** macOS `setInputRate`/`setI2SClockMode` are plain SETs
    without a result, ordered by its serial queue. Here the fire-and-forget
    forms stay, and `setInputRateWithStatus`/`setI2sClockModeWithStatus`
    suspend until the FFI queue ran the transfer, so a following suspend call
    cannot overtake them. Both go through the pin-call path (HARDWARE_APPLY
    gate, retry blackout, `hardwareUnsaved`, publish). The wire has no status
    byte for 0xED/0x88: the result is SUCCESS (transfer done) or NO_REPLY,
    never a firmware refusal. The fire-and-forget forms now publish the
    state also after a failed transfer (the core already changed it), like
    every pin call.
16. **Pin owner kinds (BRIDGE-3, SETTINGS S15).** `pinOwners()` keeps Qt's
    labels, roles and order and adds `kind`/`index`, which map 1:1 to macOS
    `PinConsumer` (split where macOS labels differ: SUBWOOFER vs output slot,
    LRCLK vs BCK, slave clocks) plus the macOS `PinRole` (`kind.macRole`;
    Qt "other" = macOS "utility"). Which pins are claimed is still Qt's rule
    (e.g. MCK only while enabled, UART/I2C while enabled); SETTINGS'
    `pinAssignment` port stays the macOS authority for pickers.
17. **Device generation (BRIDGE-3, SETTINGS S16).** `deviceGeneration`
    is macOS `transport.generation`: bumped on main on every open (select /
    attach) and every close (detach / disconnect; a reconnect bumps it
    twice), before the new device's state is published. It never resets.
18. **`PresetStatus.LOCKED` (BRIDGE-3, HOME H12).** macOS
    `PRESET_STATUS_LOCKED` = 0xFE is app-side (DSPi Link hub device lock);
    the firmware (`config.h` PRESET_* 0x00–0x04) and the core have no such
    code. core-api maps 0xFE to `LOCKED` and carries macOS's wording
    (`DeviceLock.LOCKED_MESSAGE` "Another console is applying changes",
    `PresetStatus.failureText` = `presetFailureText(_:)`), but the bridge
    never returns it: Android has no hub and no device lock.
19. **`fetchInputRate` is the live pipeline rate (BRIDGE-3, SETTINGS S8).**
    The core reads 0xEE word 0 = firmware `audio_state.freq` (macOS
    `fetchSampleRate` reads the same value via status wValue 15), not the
    selected I2S rate. `FakeDspiCore` now models this: the selected rate
    only while the firmware is the rate authority (I2S or ADAT input in
    master clock mode), otherwise `externalRateHz` (USB host / S/PDIF /
    slave master; 48 kHz by default).
20. **Read-only on firmware mismatch (G1, owner decision 2026-11-01,
    `reviews/decisions-waveB-3.md`).** Deliberate deviation: macOS and Qt
    show the mismatch banner but still send every command, and with
    compat != OK the core skips the bulk read, so the UI shows defaults that a
    confirmed save would write to flash. `writesAllowed: StateFlow<Boolean>`
    = connected && compat == COMPAT_OK. While false, every write/command is
    refused on the main thread before any USB traffic: all setters
    (incl. `sendOnly` live drags and the preview `send*ToDevice` calls),
    matrix, limiter, hardware IO (pins, clocks, input source/rate),
    preset/flash ops (save/load/delete/copy/rename/startup, Commit, Revert,
    Factory Reset, Save Master Volume / Output Configuration),
    `revertOutputConfig`, DAC mute / UART / I2C, clear clips, input links,
    paste, on-graph band writes, and the controllers' writes (siggen
    start/stop/stopNow/identify and its live draft apply, control-surface
    apply/sendOnly/save/revert except the read ops `refresh`/`refreshStatus`,
    stats watermark reset, configuration and filter import). The member
    returns its usual failure value (NO_REPLY / ERR_WRITE / 255 / false; DAC
    mute and UART/I2C also emit their "applied" event as failed) and emits
    `CommandFailed(name, WritesNotAllowedException)` with "firmware
    mismatch: read-only" ("device not connected: read-only" when nothing is
    connected). Gated members validate the token (an invalid one still
    throws) without spending it. Allowed: `enterBootloader` (the
    firmware-update path), every read/fetch, RTA subscriptions and app-side
    state (graph visibility, `beginOutputEdit`, the siggen draft, the
    clipboard copy, `restoreInputLinks`). A second layer,
    `WriteGuardedCore`, wraps the core with an explicit write classification
    of every `DspiCoreApi` method and refuses any write that a member failed
    to stop (tests assert it never trips).

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
   for the macOS read-backs. As Commands.swift setOutputEnable /
   applyDirectRouting, graph visibility follows ENABLE and Direct 1:1 only
   in the graph's overview mode (:ui-common `AppState.isOverviewMode`, the
   main window's selection; `bridge.directRouting(rows, overview)`), and
   ENABLE only when the device accepted the change.
8. **Icons** ("power", speaker) are drawn on a Canvas (no icon library in
   the build; :ui-common `Glyphs.kt`); disabled crosspoints are greyed (macOS `.saturation(0)`) and dimmed.
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
   Android, so the bridge never returns `PresetStatus.LOCKED`; its wording is
   core-api's `PresetStatus.failureText` all the same (BRIDGE-3).
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
   UI. Wording, tokens and failure texts are therefore PRESETS' (failure
   texts: core-api `PresetStatus.failureText`, LOCKED included). Alerts are
   drawn by :ui-common's `ConsoleAlertHost`.
7. **Source picker** mints `HARDWARE_APPLY` from the menu choice and applies
   immediately (E1b), as macOS does.
8. **Firmware banner** is driven by the bridge's `compat` (too old / too new),
   not by a comparison with a bundled firmware (FIRMWARE owns
   `FirmwareVersion`); the expected version shown is `1.1.6-beta5`. Hidden
   for the process lifetime ("this launch").
9. **Not ported here:** `SpectrumBarStrip` above the channel pages and the
   dashboard (RTA, ANALYSIS); onboarding anchors (Phase 6). The PEQ list
   follows the graph's band selection (one `PeqGraphSelection` per channel
   page through `LocalPeqGraphSelection`, macOS `peqSelection`): rows carry
   the band colour, `PeqRowHighlight` tints selected / graph-hovered rows,
   values follow a graph drag (`PeqGraphSelection.live` = PeqLiveReadouts),
   mouse hover sets `listHovered`; a tap on the number selects the band
   alone (touch has no Cmd/Shift-click; a hardware keyboard's modifiers are
   not read there). The list does not scroll a newly selected row into view
   (`PeqRowScrollFollower`): it is part of the page's scroll, not its own
   list. `GraphResizeHandle` sits under every HOME graph (height =
   `graphHeight`). With the XO tab shown the graph does not edit
   (macOS `crossoverTabShown`).
10. **Capability gates** (`firmwareSupportsNotch`, …) are all true: the core
    only talks to 1.1.6-compatible firmware.
11. **Linked input pairs** are mirrored by the bridge (`targets()`), not at
    the call site; Clear PEQ calls `clearPeq` once.
12. **Preferences** `sidebarVolumeMode` and `dashboardCardsPerRow` are the
    shared AppSettings (:ui-common, F3), the keys Settings › General writes.
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
   failures use macOS's wording. Input rate and I2S clock mode use the
   bridge's status-returning setters (BRIDGE-3), so the next call of a
   sequence cannot overtake them; their only failure is a lost transfer
   (no status byte on the wire), shown as a failure line; success shows
   none, as macOS. A sequence (and Save) aborts when
   `bridge.deviceGeneration` changes (macOS `transport.generation`).
2. **Capabilities** come from `bridge.hardwareCaps`, including
   `inputSourceSupported` (BRIDGE-3) for the Inputs page.
3. **BCK candidates** also require the LRCLK neighbour (BCK + 1) to be a
   wirable GPIO (Windows `IsAudioCapable`, Linux `pairFree`); macOS would
   offer GPIO 22 (LRCLK on board-reserved 23) and let the firmware refuse.
4. **Overview click-through:** tapping a pin opens the page that sets it
   (Windows PinNavigation, without the scroll-and-flash). Control-surface
   pins are recognised by `PinOwner.kind` = `CONTROL_SURFACE` and its
   `index` (BRIDGE-3; macOS `PinConsumer.controlSurface`).
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
8. **App preferences** are :ui-common's one AppSettings store (F3): the
   exact macOS `@AppStorage` key names in a Preferences DataStore
   (`app_settings`), provided by :app as `LocalAppSettings` and read by
   GRAPH, HOME, ANALYSIS and TOOLS too. The macOS one-time migrations of
   retired keys are not needed; the store imports the packages' interim
   local files once (`dspi_home`, `analysis_settings`, `dspi_tools`; keys
   already in the DataStore win) and deletes them. The analyser's device
   options are pushed into `bridge.rta` by :app at start and on every
   change, not by this tab.
9. **Device identity** for aborting a Save/hardware sequence is
   `bridge.deviceGeneration` (macOS: the transport generation); the pending
   global draft is still kept per serial (macOS `noteSelectedDevice`).

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
   long-press (:ui-common `HelpTip`, Material `TooltipBox`); channel-chip
   names likewise. Rows are :ui-common's `ParameterRow` (CustomSlider:
   relative drag, no tap-to-jump, nothing sent on the grab). Cmd+scroll
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
   upgrade note below V32, which is never the normal path: HOME hides the
   `OutputLimiterCell` below V32 as macOS (`hardwareCaps.limiterSupported`
   = V32; TOOLS review #16).
6. **Icons.** SF Symbols are not available: headers have no symbol except
   the Tube Modeller (`TubeIcon`, ported) and the Output Limiter
   (`LimiterIcon`, ported). The "unsupported" note uses a Unicode warning sign.
7. **TubeIllustration.** Drawn with three Canvas layers instead of CALayers;
   SwiftUI `.blur` on the contact shadow, heater streak and filament glow is
   approximated with unblurred translucent strokes (no DrawScope blur below
   API 31). The audio bloom follows `BridgeStatus.peaks` (app ids 2..10 =
   outputs), as macOS follows the status peaks.
8. **Tube Basic/Advanced** is the shared AppSettings key
   `tubeModellerAdvanced` (macOS `@AppStorage`); the segmented switch sits
   under the header rather than in it.
9. **Subharmonic solo** is switched off when the view leaves composition
   (macOS: when the window closes); headroom is re-read whenever the subharm
   parameters change and on open; the sub meter polls at 10 Hz while the
   view is up, extended (V30) and the effect is on.
10. **Upmixer telemetry** is polled every 60 ms while the view is up (macOS
    ~16 Hz shared timer; Linux 100 ms).
11. **System back** inside an open tool closes the tool (`BackHandler`,
    activity-compose approved in decision E3), then leaves the Tools route.
12. **Limiter link groups and output-config dirty tracking are the bridge's**
    (contract TOOLS C/D, decision F2). The published state mirrors the
    firmware's ganging, so the view shows peers' values directly
    (`LimiterGang` is kept in ToolParameters.kt only as the tested oracle).
    Every committed limiter change (enable, threshold, release, link group,
    Copy to all outputs, Link all stereo pairs, Unlink all, Switch every
    limiter off) runs through `edit { bridge.beginOutputEdit(); … }` as
    macOS `OutputLimiterSettings.edit`; live drag values (`sendOnly`) do not,
    as macOS `sendLimiterParamToDevice`. No deviation remains here.
13. **Polling is lifecycle-aware.** Upmixer telemetry and the subharm meter
    poll only while the view is composed and the app is at least STARTED
    (macOS: while the window is open; the bridge's status poll likewise stops
    at `setUiVisible(false)`). The subharm headroom is re-read only after a
    committed change (keyed on `stateRevision`), never per drag tick.
14. **Tools list** lists the four diagnostics with their macOS menu titles
    (Signal Generator, Spectrum Analyser, "Stats for nerbs", Interrupt
    Monitor); each opens the ANALYSIS route, which picks the sub-view.


## FIRMWARE

Reference: DSPi-Console (macOS) v1.1.6-beta5 `FirmwareUpdateView.swift`,
`FirmwareInstallUI.swift`, `FirmwareInstaller.swift`, `FirmwareVersion.swift`,
`BootloaderLocator.swift` (b5fdeac); Linux `qt/src/FirmwareUpdater.cpp`
(4683a2c). The installer's state machine, timings (30 s verify, 16 KB chunks,
0.95 reboot threshold), wording and button flow are kept.

1. **Write path: PICOBOOT, drive as fallback.** macOS and Linux copy the UF2
   onto the boot ROM's drive. Android rarely mounts it, so the default path
   writes through the boot ROM's PICOBOOT vendor interface (RP2350 datasheet
   §5.6; sequencing as picotool `load`/`reboot`): INTERFACE_RESET,
   EXCLUSIVE_ACCESS(1), EXIT_XIP, (RP2350) GET_INFO UF2_TARGET_PARTITION for
   the image's family, then per 4 kB sector FLASH_ERASE + WRITE of the pages
   the UF2 has, then REBOOT (RP2040: pc 0, sp 0x20042000, 500 ms) / REBOOT2
   (RP2350: FLASH_UPDATE, 500 ms, p0 = flash start of the target). Pages the
   UF2 lacks in a touched sector stay erased, as with a drag-and-drop
   (picotool zero-fills them). The drive path (Linux copy loop: 16 KB chunks,
   fsync each, late error or vanished drive = the ROM rebooting) is the
   fallback, using a drive the user picks with the Storage Access Framework.
2. **USB permission.** PICOBOOT needs Android's USB permission for the board,
   so `install` passes through a new state `WaitingForPermission(chip)`
   (system dialog); denied → `PermissionDenied`; board gone meanwhile →
   `NoBoardFound`. Asked only once the user has committed, never on
   detection.
3. **No volume wait.** macOS waits up to 8 s for the drive to mount, then fails
   with `volumeNotMounted`. PICOBOOT has no volume; on the drive path the
   drive is chosen by the user, so `WaitingForVolume` waits for that choice
   with no timeout. `volumeWaitTimeout` is not ported.
4. **UF2 validated before anything is written** (macOS lets the ROM decide):
   magics, 256-byte payloads, one family, complete block numbering, page
   alignment, flash window per chip (RP2040 16 MB, RP2350 32 MB), family
   allowed for the board (RP2040: rp2040 or none; RP2350: rp2350-arm-s,
   rp2350-riscv, absolute). New errors `WrongBoardFamily`, `InvalidImage`,
   `WrongVolume`, `PermissionDenied` (macOS-style sentences). The RP2350-E10
   absolute block (picotool `check_abs_block`) is skipped on the PICOBOOT
   path (it would erase a sector at the end of flash); the drive path copies
   the file unchanged.
5. **Owner requirements: local .uf2 first class, confirmation, no nagging.**
   The screen offers "Official release <version>" (bundled) or "A .uf2 file on
   this phone" side by side. A local file is read only up to 8 MB (DSPi UF2s
   are ~0.6 MB). A local image carries no expected version: the install is
   verified when the device comes back, whatever it reports, and the success
   card then says "The device is back, running firmware X." (no
   "confirmed"). The macOS one-click "Update Firmware" opens a confirmation
   (owner requirement; the gated ENTER_BOOTLOADER token is minted in its OK)
   that shows the running version and what will be installed, and, for the
   official release, warns that it replaces a custom build (e.g. the 1.20 V
   core-voltage build) and may bring back instability on marginal parts. It is
   shown also when the board is already in BOOTSEL. This dialog is the one
   E1 exception, approved by the coordinator. "Enter bootloader mode without
   installing" acts on the tap with no alert, as macOS does (E1): the tap
   mints the token. Nothing installs or prompts on its own.
6. **Lifetime (E9).** macOS gives each window open a fresh installer. Here
   the session (installer, locator, chosen file and drive, commitment, the
   open confirmation) lives in the route's ViewModel, so a rotation keeps
   everything, including an armed "Looking for the board". Device work runs
   on the session's app-scoped coroutine scope: an install in progress
   (permission dialog, write, verify) outlives leaving the screen and is shown
   again on return; leaving never cancels a write.
7. **Device lock.** macOS takes the DSPi Link device lock before the reboot
   (`deviceLocked`); there is no Link hub on Android, so that error is not
   ported.
8. **Export Configuration...** navigates to PRESETS (which owns configuration
   export) instead of opening a save panel. Follow-up for PRESETS/coordinator:
   a route argument that opens the export directly; today the user lands on
   the preset list.
9. **Wording** "Click"/"your computer"/"macOS" → "Tap"/"your phone"/"Android".
   The writing card's note says the board "restarts itself and disappears"
   on the PICOBOOT path (no drive to disappear). SF Symbols are text glyphs.
10. **Expected version.** `FirmwareVersion.expected` is the tracked Console
    release (`CONSOLE_VERSION = "1.1.6-beta5"`), not the Android app's version.
11. **Shared locator.** `FirmwareUpdateView` takes an optional
    `bootloaderLocator`; :app passes its one shared `AndroidBootloaderLocator`
    (the default, for previews/tests, is the view's own). The screen starts
    it on open and stops it when the visit ends, as macOS polls only while a
    flashing UI is open.

## ANALYSIS

1. **One screen, four pages.** The macOS Tools-menu windows (Signal
   Generator, Spectrum Analyser, System Statistics — menu item "Stats for
   nerbs" —, Interrupt Monitor) are internal sub-routes of `AnalysisScreen`,
   titled as the macOS windows: tabs on a phone, a side list on a tablet
   (≥ 840 dp). Only the page on screen polls or subscribes.
2. **The Signal Generator stops when its page leaves the screen** (tab
   change, leaving Analysis, the activity stopping: app in background, a call,
   another app in front), with the normal fade (`SIGGEN_CTL_STOP`); not on a
   configuration change (`isChangingConfigurations`). If a host recreates the
   page without flagging a configuration change (some split-screen resizes),
   that counts as leaving. macOS keeps playing after the window closes.
   Starting stays one explicit tap on Start (ungated, D1); the start sequence
   runs in a process scope (E9), not the composition. Default draft = macOS
   (1 kHz sine, outputs 1–2, −20 dBFS). No Space-bar shortcut; "Click" became
   "Tap" in the outputs caption.
3. **Spectrum Analyser chooses its channels itself.** macOS mirrors the main
   window's current page (selection set with the graph's gear) and only hides
   channels "in this window". Android has no main window beside it, so the
   page shows the dashboard's selection (macOS keys `rtaDashboardSource` /
   `rtaDashboardOtherSide`, same storage format) and edits it with an
   Inputs/Outputs switch and channel chips (one tap at a time, the other
   side remembered, fallback to the first enabled output as macOS
   `dashboardRtaSelection`). Curves | Bars | Both starts from
   `rtaDashboardShowGraph` / `rtaDashboardShowBars` as macOS `startFromPage`.
4. **Spectrum drawing** is Compose Canvas, not Metal/AGSL: bars as
   RtaBandsView (gradient, peak-hold caps, 12 dB grid, 1-2-5 labels decades
   first), curves as the Linux client draws this core's snapshot
   (Catmull-Rom through populated bands, bins crossfading in above the bass
   bank). No glow and no 30 px fade-in at the curve's left end. On phones
   (< 600 dp) bar cells use one column (platform: width).
5. **Analyser preferences** are the shared AppSettings (:ui-common, F3;
   macOS keys and defaults), so the dashboard selection
   (`rtaDashboardSource` / `rtaDashboardOtherSide`) is shared with the
   graph's gear panel. The transform size, averaging and peak decay are
   pushed into `bridge.rta` by :app once, app-wide, at start and on every
   change (the bridge sends them with every request), not when a page
   starts watching (macOS `RtaWatch` pushes there because its engine has no
   memory of them).
6. **"Following another console" / Take control** is not shown: the core and
   bridge expose no RTA ownership state (no hub sharing on Android).
7. **System Statistics**: all macOS sections, including S/PDIF INPUT (state,
   source, rate, counts, parity, FIFO, RX pin, channel status while locked,
   DEBUG rows) from the bridge's `fetchSpdifRxStatus` /
   `fetchSpdifChannelStatus` / `fetchActiveSpdifRxPin`, shown when the
   receiver answers (macOS `inputSourceSupported`). LG Sound Sync, ADAT bulk
   output (RP2350 only) and I2S slave come from `fetchLgStatus`,
   `fetchAdatOutStatus`, `fetchInputLock(0)`; all every 2 s while shown.
   Buffer fills use `fillPct` (the core has no sample-granular
   `fillCentiPct`). One column on phones, three on tablets.
8. **Number formatting** follows Swift/C `%.Nf` (exact value, ties to even:
   31.25 °C reads 31.2), not Java's ties-up `String.format`.
9. **Interrupt Monitor** is read-only and scrolls to the newest line on every
   append unless paused, as macOS. It adds the Linux client's Copy button
   (platform: a lazy list cannot be selected as one block); lines are the
   core's decoded text. A stop requested on leaving is fire-and-forget; a
   failure surfaces only as the bridge's `CommandFailed` event.


## GRAPH

Reference: macOS `GraphView.swift`, `GraphSpectrumOverlay.swift`,
`PeqGraph*.swift`, `PeqBandHUD.swift` @ b5fdeac; the editor logic is the
Windows `DSPiConsole.Core/GraphEditing` port (v1.1.6-beta4) with its tests.

1. **Touch.** The mouse/keyboard gestures are mapped onto touch as in
   PORT-MAP "Touch gestures": long-press for right-click, long-press on empty
   graph adds **Add Band…** to the graph menu (macOS: Cmd-click), pinch or
   two-finger vertical drag for the wheel's Q, pinch over empty graph zooms
   the dB range (macOS: scroll over the left-edge labels only). Bigger hit
   targets for a finger (24 dp dots, 14 dp curve). No touch equivalent for
   Cmd/Shift-click selection or the Shift/Option/Control drag modifiers
   (hardware keyboard only). Editor additions for touch: per-instance hit
   radius (set by `PeqTouchGestures.down`, restored to 10/6 by any mouse
   event) and `abandonPress()`.
2. **Chip text entry** opens a dialog on a tap (macOS: in-place field on a
   double-click), so the phone keyboard has room; it seeds the bare number as
   macOS does (Freq short form, Gain `%.2f`, Width `%.3f`), Return/Done
   applies, Tab/Shift-Tab apply and move to the next adjustable field, and
   unreadable text keeps it open. Chip values take a vertical drag (Shift
   ×0.12) and the mouse wheel (`hudScroll`; Cmd/Ctrl or a running graph
   gesture forwards to the graph) as on macOS. The gear is always shown
   (macOS: on hover). Android reports no scroll phase, so a mouse wheel never
   "begins" a gesture (macOS mouse wheels have none either; trackpads do).
3. **Rendering.** Compose Canvas instead of Metal/Win2D: band lobes are a
   vertical gradient per band instead of the per-pixel `pow(t, 1.5)` fade.
   Curve glows use the macOS strokes, alphas and blur radii (single curve
   0.3 / lw·4 / 6 pt and 0.6 / lw·2 / 3 pt; overlapping 0.07 / lw·5 / 8 pt
   and 0.07 / lw·2.5 / 5 pt; the edited curve as a single curve; spectrum
   edge 0.35 / 2 pt / 4 pt) through RenderEffect blur on API 31+; below 31
   (no RenderEffect) they fall back to unblurred fainter strokes. Curve
   animation is a tween of the Graphing animation speed (macOS: spring).
   Axis labels at the plot edges are kept fully inside the plot (macOS lets
   the outermost ones half-clip) so they stay legible on a phone.
   Grid lines are 1 dp (macOS 1 pt). Curves are not clamped to the plot (a
   clamp bent visible steep segments of an unwrapped phase trace).
4. **Spectrum overlay.** Bands + bins blend, bin smoothing, peak contour,
   the left-edge fade and the time smoothing between device frames
   (`RtaBarSmoother`: rise τ·0.4 / fall τ, peak rise instant, identity snap;
   τ = `rtaFallTau(channelRefreshInterval, 0.35)` when `rtaSmoothingOn`) are
   ported; the core's `refreshIntervalS` is used when it reports one. The
   band picture is sampled per column with the macOS Hermite
   (`sampleBands`), band slots without a level read the floor, and each
   start-group fades in its own layer, as macOS. The "Following another
   console" row, `hiddenChannels` and the `active` pause are not needed (no
   Link hub, no analyser window sharing the view).
5. **Not offered in the gear panel:** RTA Bars (the bar strip belongs with
   ANALYSIS's bar renderer) and Pop Out Graph (the full-screen route is
   `GraphScreen`, reached through app navigation).
6. **GraphScreen** (pop-out) is a full-screen route. With "Follow Channel
   Selection" (`popoutGraphFollowsSelection`, Graph Setup › CURVES in the
   pop-out and Settings › Graphing) it edits the main window's selected
   channel (:ui-common `AppState.activeEqChannel`: the channel route or the
   tablet sidebar; overview = none); off, a channel menu picks it and every
   curve is drawn active. The non-following pop-out shares the main
   visibility (macOS keeps its own `popoutVisibility`).
7. **Level offset.** The edited curve and the dots include the bridge's
   `channelGainOffset`, which is the output gain for outputs (macOS) and also
   the input preamp for inputs (bridge contract, BRIDGE §2).
8. **Filter types and bypass** are not gated by firmware feature flags: the
   core only talks V32 firmware, which has every PEQ type and per-band bypass
   (macOS checks `firmwareSupports*`). The Linkwitz Transform is shown but
   not created or dragged on the graph, as upstream.
9. **Preferences** are :ui-common's shared AppSettings (`LocalAppSettings`,
   F3; macOS keys; the Swift property names `rtaDashboardSelectionKey` /
   `rtaDashboardOtherSideKey` are stored as "rtaDashboardSource" /
   "rtaDashboardOtherSide"). The band selection is shared with HOME's band
   list via `LocalPeqGraphSelection` (`PeqGraphSelection.live` replaces the
   macOS `PeqLiveReadouts`). Outputs past the eight paired ones (PDM aside)
   draw in the accent colour, as macOS `ChannelPalette.output` (the
   bridge's `channelColor` wraps; review F9).
10. **GraphView(channels, editable).** With `editable` and one channel, that
    channel is the page's selected channel and the visible set is drawn too
    (others dashed), as on a macOS channel page; without `editable`, a
    non-empty `channels` draws just those.
11. **Live writes** go through the editor's own 1/30 s device timer (as
    Windows/macOS) and then the bridge `Throttle(30)` the package rules ask
    for. At the editor's cadence the throttle passes each value on at once
    (its window has closed by the next send), so it adds no latency in
    practice; it only merges a burst.

## CONSOLIDATION (decisions E6, F3)

What the consolidation package changed across packages; each package's own
section above is updated too.

1. **One preference store.** :ui-common `AppSettings` (every macOS
   `AppSettings` `@AppStorage` key, plus `settingsSelectedTab` and
   `tubeModellerAdvanced`) over one Preferences DataStore (`app_settings`),
   provided by :app as `LocalAppSettings`. On the first start the interim
   per-package files (`dspi_home`, `analysis_settings`, `dspi_tools`) fill
   the keys the DataStore lacks and are deleted (marker key
   `dspiLegacyPreferencesImported`, not a macOS key). GRAPH's former
   in-memory settings had nothing to import.
2. **Analyser options** (FFT order, averaging, peak decay) are pushed into
   the current bridge's `rta` once, app-wide (:app `bindRtaOptions`), at
   start and on change of the preferences, the analyser caps or the bridge
   (demo enter/exit); macOS pushes from the Spectrum tab and on every
   `RtaWatch` start. The device's order range is used when it reports a
   usable one, else the protocol's.
3. **Main-window selection.** macOS keeps `activeEqChannel` /
   `isOverviewMode` on the one view model; here :ui-common `AppState`
   (provided by :app as `LocalAppState`) is written by HOME (channel route,
   tablet sidebar; null on the phone's home page) and read by GraphScreen
   (Follow Channel Selection) and MATRIX (overview-only visibility).
   Curve visibility itself stays the bridge's.
4. **Shared widgets.** One implementation each in :ui-common: the NSAlert
   model + host (`se.bofr.dspi.ui.alert`), JSON (`se.bofr.dspi.ui.json`),
   compact gain/delay fields, glyphs, `MacColors`, `WhileVisible` /
   `rememberWhileVisible`, `ParameterRow`/`ValueField`, `SwitchRow`,
   `SegmentedPicker`, `SliderRow`, `Caption`, `HelpTip`, `SectionHeader`,
   `PullDownMenu`, `ToolHeader`, `ChannelChips`. Visible effects: the tool
   windows' sliders are CustomSlider (relative drag, no tap-to-jump, F4)
   with :ui-common's type sizes; switch rows toggle on a tap anywhere on
   the row (macOS toggles on its label too); ANALYSIS' and SETTINGS'
   segmented choices are Material segmented buttons (macOS
   `.pickerStyle(.segmented)`); ANALYSIS' value fields are tap-to-type like
   every other field. HOME's graphs are no longer fixed at 220/260 dp: their
   height is `AppSettings.graphHeight` (default 250, 200–350) with a
   `GraphResizeHandle` under each, as macOS.
5. **Not merged: GRAPH `PeqDspMath` and fake-core `DspMath`.** One pure-JVM
   home for both needs a new module in `settings.gradle.kts` (coordinator)
   and an edit of :fake-core (BRIDGE); left as two copies.

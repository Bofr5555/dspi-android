# Deliberate deviations from the upstream clients

Each package records here what it changed on purpose relative to the
reference it ports (PACKAGES.md), and why. One section per package.

## Gating policy (coordinator, contract)

Decisions D1–D3/D5 of `reviews/decisions-wave-0A.md`. Guiding rule from the
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
2. **HARDWARE_APPLY** (D2): output pins/types, I2S BCK pin, clock pin mode
   and clock mode, MCK, ADAT/S/PDIF/I2S input and output pins and enables,
   input source and input rate rebuild the audio pipeline. macOS applies
   them only from the Settings Save bar; here the save bar mints one
   reusable token and applies the whole draft with it. Live UI never calls
   them. (Qt calls them directly from its pages.)
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

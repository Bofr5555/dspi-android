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
@ `4683a2c`.

1. **Threading.** Qt calls the core on the GUI thread. Here every core call
   runs on one serial FFI dispatcher and results are published on the main
   dispatcher (PLAN §3.4/§5.4). Getters read the last published `DspState`
   snapshot instead of the core live; `sendOnly` calls therefore do not
   refresh getters until the commit, except the "preview" setters
   (`setInputPreamp(sendOnly)`, `sendOutputGainToDevice`), which refresh the
   snapshot silently and emit `PreviewChanged` like Qt's `previewChanged`.
   Read-modify-write setters (`setFilter`, `setCrossover`, …) read the band
   from the core on the FFI thread so queued edits compose.
2. **Curves.** `magnitudeCurve` includes the channel's gain offset (contract);
   Qt leaves that to `BodePlotItem`. Curves are recomputed eagerly on the FFI
   thread for the channels a change touched (Qt: lazily on draw).
   `magnitudesRevision` is bumped whenever a curve or a gain offset changed.
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
   PLAN §5.3) and while disconnected; Qt's timer always runs.
5. **DangerousOps** (no Qt equivalent; PLAN §4, DESIGN §4.5): every
   flash-writing and write-as-read operation (presets, save/revert/factory
   reset, save master volume / output config, bootloader, clear clips, all
   pin/IO setters that use IN requests, DAC mute test, buffer-stats reset,
   siggen start/identify, CS save/revert/learn/macro, configuration import)
   needs a single-use `ConfirmationToken` (10 s) from
   `bridge.dangerousOps.confirm(op)`. Without one the call does nothing,
   returns its failure value and emits `CommandFailed(DangerousOpNotConfirmed)`.
   Not gated: siggen stop, CS learn/macro cancel (safety), and the poll's own
   clip read-then-clear (Qt behaviour).
6. **Retry** (no Qt equivalent; DESIGN §4.5 rule 4): idempotent GETs issued
   within 2 s after a flash write are retried up to 4× at 150 ms on a
   transfer error. SETs are never retried.
7. **Errors.** Qt ignores the core's bool for fire-and-forget setters; here a
   failure is also reported as `BridgeEvent.CommandFailed`.
8. **StatsController.info** carries the STAT_* counters only (contract); the
   receiver/LG/ADAT/I2S-slave readings Qt folds into `info` are available
   through the bridge's `fetch*` calls. Buffer history for the traces is the
   UI's job (collect `buffers`).
9. **ConfigFiles** works on text, not file URLs (SAF lives in the UI);
   `inspectConfiguration` adds `crossPlatform` as Qt does, not the localised
   `saved` string.
10. **Channel descriptors** follow Qt (`"IN1"`, `"OUT3"`); the contract KDoc
    shows `"IN 1"`.

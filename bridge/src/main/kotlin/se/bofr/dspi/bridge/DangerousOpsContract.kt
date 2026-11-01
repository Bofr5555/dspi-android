package se.bofr.dspi.bridge

/*
 * CONTRACT (coordinator-owned): the confirmation gate for dangerous operations
 * (PLAN-NATIVE-ANDROID §4 "Write-as-read safety", DESIGN §4.5, coordinator
 * decisions D1/D5 of review wave 0+A, E1/E1b of wave B lot 1; E1b supersedes
 * D2). Package BRIDGE implements
 * [DangerousOps]; UI packages mint tokens; nothing else.
 *
 * Gating policy (D1, E1b) — stay as close to the official clients as possible:
 * - GATED: the flash writes the official clients treat as "save" (0x51, 0x52,
 *   0x53, 0x90, 0x92, 0x9D, 0xD6, copy preset), ENTER_BOOTLOADER (0xF0),
 *   CONFIG_IMPORT (bulk apply 0xA3/0xA1), and the pipeline-rebuilding
 *   hardware calls under [DangerousOp.HARDWARE_APPLY]. As on macOS those apply
 *   LIVE to RAM: the token is minted by the explicit user interaction with the
 *   hardware control (picker / toggle / menu choice, incl. HOME's input-source
 *   menu) and the call is made at once; the Settings Save bar flashes them with
 *   SAVE_OUTPUT_CONFIG (independent mode) and its Revert re-applies the
 *   baseline with a HARDWARE_APPLY token minted by the Revert tap.
 * - Who mints (E1): where macOS shows a confirmation alert (Commit Parameters,
 *   Revert to Saved, Factory Reset, Clear slot / Clear All, Import Device
 *   Configuration, Enter Bootloader / firmware install) the alert's confirm
 *   handler, with the macOS wording; where macOS acts on the explicit tap
 *   (Save preset, Copy to…, Save Master Volume, Save Output Configuration, CS
 *   save, Save bar Save, hardware controls) the tap itself. Never implicitly
 *   (sliders, startup, timers, notification actions).
 * - NOT GATED (one click in the official clients): load preset, revert to
 *   saved (loadParams), clear clips, DAC mute test, buffer-stats reset, siggen
 *   start/identify, CS revert/IR learn/macro fire. The only gate on preset
 *   load is the official Unsaved Changes flow (dirty -> Save/Discard/Cancel),
 *   owned by the UI and by the USB notification's `canCyclePreset`.
 * - NOT GATED, documented flash writes (D3; the official clients don't
 *   confirm them): preset rename 0x94, startup 0x96, output-config mode 0x98,
 *   master-volume mode, DAC HW mute config, UART/I2C persist. The bridge arms
 *   its retry blackout after them.
 *
 * Gated members take a [ConfirmationToken] parameter, so a gated call can never
 * be made — or silently do nothing — without one.
 */

/**
 * The gated operations. [opcodes] are the vendor requests the core issues for
 * the operation (documentation and tests; the gate is per operation).
 */
enum class DangerousOp(val opcodes: List<Int>, val writesFlash: Boolean) {
    /** [DspiBridge.saveParams] — 0x51. */
    SAVE_PARAMS(listOf(0x51), true),

    /** [DspiBridge.saveOutputConfig] — 0x52. */
    SAVE_OUTPUT_CONFIG(listOf(0x52), true),

    /** [DspiBridge.factoryReset] — 0x53. */
    FACTORY_RESET(listOf(0x53), true),

    /** [DspiBridge.savePreset] — 0x90. */
    SAVE_PRESET(listOf(0x90), true),

    /** [DspiBridge.copyPreset] — saves to the destination, then re-saves the source (0x90). */
    COPY_PRESET(listOf(0x90), true),

    /** [DspiBridge.deletePreset] — 0x92. */
    DELETE_PRESET(listOf(0x92), true),

    /** [DspiBridge.saveMasterVolume] — 0xD6. */
    SAVE_MASTER_VOLUME(listOf(0xD6), true),

    /** [CsController.save] — control surfaces `{op: "save"}`, 0x9D. */
    CS_SAVE(listOf(0x9D), true),

    /** [ConfigFiles.importConfiguration] — one bulk write (0xA3, applied on the last chunk, 0xA1) plus pin writes. */
    CONFIG_IMPORT(listOf(0xA3, 0xA1, 0x7C, 0xC0, 0xC2, 0xC6, 0xC8, 0xFE), true),

    /** [DspiBridge.enterBootloader] — 0xF0; the device reboots into BOOTSEL. */
    ENTER_BOOTLOADER(listOf(0xF0), true),

    /**
     * E1b (supersedes D2): every pipeline-rebuilding hardware call (output
     * pins/types, I2S BCK/clock pin mode and clock mode, MCK, ADAT/S/PDIF/I2S
     * input and output pins/enables, input source, input rate), applied live.
     * Minted by the explicit interaction with one hardware control (a control
     * that issues several calls, e.g. "S/PDIF inputs" count, reuses it), or by
     * the Save bar's Revert tap for [DspiBridge.revertOutputConfig].
     */
    HARDWARE_APPLY(
        listOf(
            0x7C, 0xC0, 0xC2, 0xFE, 0xC4, 0xC6, 0xC8, 0xCA, 0xCC, 0xE4, 0xE9, 0xF1, 0xF3,
            0x68, 0x6A, 0x6C, 0xE0, 0xED, 0x88
        ),
        false
    );

    /** A single token covers several calls (until revoked or idle for [DangerousOps.ttlMs]). */
    val reusable: Boolean get() = this == HARDWARE_APPLY
}

/**
 * Proof that the user confirmed [op]. Only tokens returned by
 * [DangerousOps.confirm] of the same bridge are valid; equality is identity.
 * Single use, except [DangerousOp.reusable] ops. An invalid token always
 * throws [DangerousOpNotConfirmed]; a valid one is spent only when the gated
 * call is made while a device is connected (a disconnected call returns its
 * usual failure value and the token stays usable).
 */
class ConfirmationToken internal constructor(val op: DangerousOp, val id: Long) {
    override fun toString() = "ConfirmationToken($op #$id)"
}

/**
 * A gated member was called with a token that is not valid for it: minted for
 * another op, already spent, revoked, expired, or from another bridge. A
 * programming error: thrown (never swallowed) before anything is sent to the
 * device.
 */
class DangerousOpNotConfirmed(val op: DangerousOp, reason: String) : IllegalStateException("$op not confirmed: $reason")

/**
 * The bridge's token issuer ([DspiBridge.dangerousOps]).
 *
 * ```
 * // the confirmation dialog's OK handler (macOS wording), main thread:
 * val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
 * scope.launch { bridge.savePreset(token, slot) }
 * ```
 * Main thread only.
 */
interface DangerousOps {
    /** Lifetime of an unused token (reusable tokens: since their last use). */
    val ttlMs: Long

    /** The user confirmed [op]: a token valid for one call (reusable ops: a sequence) within [ttlMs]. */
    fun confirm(op: DangerousOp): ConfirmationToken

    /** Drop a token (dialog dismissed, Save sequence finished, view gone). Idempotent. */
    fun revoke(token: ConfirmationToken)

    /** Whether [token] would be accepted now. */
    fun isValid(token: ConfirmationToken): Boolean
}

package se.bofr.dspi.bridge

import se.bofr.dspi.core.FlashStatus
import se.bofr.dspi.core.PinConfigStatus
import se.bofr.dspi.core.PresetStatus

/**
 * Operations that write flash, reboot the device, or are "write-as-read"
 * (an IN request the firmware acts on), per PLAN-NATIVE-ANDROID §4 and
 * DESIGN.md §4.5 (incl. the undocumented opcodes 0x68/0x6A/0x6C, 0x8F,
 * 0x9D, 0x9E, 0xA6, 0xCA/0xCC, 0xE9, 0xF3, 0xFE, 0x25). [opcodes] are the
 * vendor requests the core issues for the operation.
 *
 * Every one of them needs a [ConfirmationToken] from [DangerousOps.confirm]:
 * the UI mints one in the handler of the user's explicit confirmation (the
 * macOS-worded dialog for [writesFlash] operations; the button press itself
 * for the others), so none can be reached from a slider or a probe.
 *
 * Not gated, deliberately: stopping the signal generator and cancelling IR
 * learning / a macro (safety actions), and the bridge's own clip
 * read-then-clear in the status poll (DSPiBridge.cpp pollStatus).
 */
enum class DangerousOp(val opcodes: List<Int>, val writesFlash: Boolean) {
    SAVE_PARAMS(listOf(0x51), true),
    SAVE_OUTPUT_CONFIG(listOf(0x52), true),
    FACTORY_RESET(listOf(0x53), true),
    SAVE_PRESET(listOf(0x90), true),

    /** Saves the live state to the destination, then re-saves the source. */
    COPY_PRESET(listOf(0x90), true),
    DELETE_PRESET(listOf(0x92), true),
    SAVE_MASTER_VOLUME(listOf(0xD6), true),

    /** Control surfaces `{op: "save"}`. */
    CS_SAVE(listOf(0x9D), true),

    /** `.dspipreset` import: one bulk write (0xA3, applied on the last chunk) plus pin writes. */
    CONFIG_IMPORT(listOf(0xA3, 0xA1, 0x7C, 0xC0, 0xC2, 0xC6, 0xC8, 0xFE), true),
    ENTER_BOOTLOADER(listOf(0xF0), true),

    LOAD_PRESET(listOf(0x91), false),

    /** Revert to saved (reloads the active preset). */
    LOAD_PARAMS(listOf(0x91), false),
    CLEAR_CLIPS(listOf(0x83), false),
    OUTPUT_PIN(listOf(0x7C), false),
    OUTPUT_TYPE(listOf(0xC0), false),
    I2S_BCK_PIN(listOf(0xC2), false),
    I2S_CLOCK_PIN_MODE(listOf(0xFE), false),
    MCK_ENABLE(listOf(0xC4), false),
    MCK_PIN(listOf(0xC6), false),
    MCK_MULTIPLIER(listOf(0xC8), false),
    ADAT_OUT_ENABLE(listOf(0xCA), false),
    ADAT_OUT_PIN(listOf(0xCC), false),
    SPDIF_RX_PIN(listOf(0xE4), false),
    SPDIF_INPUT_ENABLE(listOf(0xE9), false),
    I2S_RX_PIN(listOf(0xF1), false),
    I2S_INPUT_CHANNELS(listOf(0xF3), false),
    ADAT_INPUT_ENABLE(listOf(0x68), false),
    ADAT_INPUT_PIN(listOf(0x6A), false),
    ADAT_INPUT_CLOCK_MODE(listOf(0x6C), false),
    TEST_DAC_MUTE(listOf(0xEC), false),

    /** System Statistics "Reset" (watermarks). */
    RESET_BUFFER_STATS(listOf(0xB1), false),

    /** Signal generator start, and Identify (audible). */
    SIGGEN_START(listOf(0xA6), false),

    /** Control surfaces `{op: "revert"}`. */
    CS_REVERT(listOf(0x9E), false),

    /** Control surfaces `{op: "learnArm"}`. */
    CS_IR_LEARN(listOf(0x8F), false),

    /** Control surfaces `{op: "macroFire"}`. */
    CS_MACRO_FIRE(listOf(0x25), false)
}

/** Proof that the user confirmed [op]; single use, valid [DangerousOps.ttlMs]. */
class ConfirmationToken internal constructor(val op: DangerousOp, internal val id: Long, internal val issuedAtMs: Long) {
    override fun toString() = "ConfirmationToken($op #$id)"
}

/** A gated operation was called without a valid [ConfirmationToken]; reported as [BridgeEvent.CommandFailed]. */
class DangerousOpNotConfirmed(val op: DangerousOp) : IllegalStateException("$op needs a confirmation token (DangerousOps.confirm)")

/** Implemented by [DspiBridgeImpl]; reach it through [DspiBridge.dangerousOps]. */
interface DangerousOpsHost {
    val dangerousOps: DangerousOps
}

/** The bridge's confirmation gate (see [DangerousOp]). */
val DspiBridge.dangerousOps: DangerousOps
    get() = (this as? DangerousOpsHost)?.dangerousOps ?: error("${this::class.simpleName} has no DangerousOps")

/**
 * The `DangerousOps` facade (PLAN §4 "Write-as-read safety").
 *
 * ```
 * // in the confirmation dialog's OK handler (main thread):
 * val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
 * val status = bridge.dangerousOps.savePreset(token, slot)
 * ```
 * [confirm] arms a single-use token for [ttlMs]. The typed calls below run
 * the operation with that token. The plain [DspiBridge] members of the same
 * operations (contract) consume an armed token too, and without one they
 * do nothing, return their failure value and emit
 * [BridgeEvent.CommandFailed] with [DangerousOpNotConfirmed].
 *
 * Main thread only.
 */
class DangerousOps internal constructor(
    private val bridge: DspiBridgeImpl,
    private val clock: () -> Long,
    val ttlMs: Long = DEFAULT_TTL_MS
) {
    private val armed = ArrayList<ConfirmationToken>()
    private var nextId = 1L

    /** The user confirmed [op]: a token for one call within [ttlMs]. */
    fun confirm(op: DangerousOp): ConfirmationToken = ConfirmationToken(op, nextId++, clock()).also { armed += it }

    /** Drop an unused token (dialog dismissed after confirming, view gone). */
    fun revoke(token: ConfirmationToken) {
        armed.remove(token)
    }

    /** Whether an unused, unexpired token for [op] is waiting. */
    fun isArmed(op: DangerousOp): Boolean {
        expire()
        return armed.any { it.op == op }
    }

    private fun expire() {
        val now = clock()
        armed.removeAll { now - it.issuedAtMs > ttlMs }
    }

    /** Take the token for [op] (the preferred one, else the oldest). */
    internal fun consume(op: DangerousOp): Boolean {
        expire()
        val t = preferred?.takeIf { it.op == op && it in armed } ?: armed.firstOrNull { it.op == op }
        preferred = null
        return t != null && armed.remove(t)
    }

    private var preferred: ConfirmationToken? = null

    /** Run [block] (a gated bridge call) with [token]; a wrong or stale token is dropped first, so the call refuses. */
    private inline fun <T> with(token: ConfirmationToken, op: DangerousOp, block: () -> T): T {
        expire()
        if (token.op != op) armed.remove(token)
        preferred = token.takeIf { it.op == op && it in armed }
        if (preferred == null) armed.remove(token)
        return block()
    }

    // ── Typed facade (one per gated operation) ──

    suspend fun saveParams(token: ConfirmationToken): FlashStatus = with(token, DangerousOp.SAVE_PARAMS) { bridge.saveParams() }

    suspend fun loadParams(token: ConfirmationToken): FlashStatus = with(token, DangerousOp.LOAD_PARAMS) { bridge.loadParams() }

    suspend fun factoryReset(token: ConfirmationToken): FlashStatus = with(token, DangerousOp.FACTORY_RESET) { bridge.factoryReset() }

    suspend fun saveOutputConfig(token: ConfirmationToken): PresetStatus = with(token, DangerousOp.SAVE_OUTPUT_CONFIG) {
        bridge.saveOutputConfig()
    }

    suspend fun saveMasterVolume(token: ConfirmationToken): PresetStatus = with(token, DangerousOp.SAVE_MASTER_VOLUME) {
        bridge.saveMasterVolume()
    }

    suspend fun savePreset(token: ConfirmationToken, slot: Int): PresetStatus = with(token, DangerousOp.SAVE_PRESET) {
        bridge.savePreset(slot)
    }

    suspend fun loadPreset(token: ConfirmationToken, slot: Int): PresetStatus = with(token, DangerousOp.LOAD_PRESET) {
        bridge.loadPreset(slot)
    }

    suspend fun deletePreset(token: ConfirmationToken, slot: Int): PresetStatus = with(token, DangerousOp.DELETE_PRESET) {
        bridge.deletePreset(slot)
    }

    suspend fun copyPreset(token: ConfirmationToken, fromSlot: Int, toSlot: Int): PresetStatus =
        with(token, DangerousOp.COPY_PRESET) { bridge.copyPreset(fromSlot, toSlot) }

    fun enterBootloader(token: ConfirmationToken) = with(token, DangerousOp.ENTER_BOOTLOADER) { bridge.enterBootloader() }

    fun clearClips(token: ConfirmationToken) = with(token, DangerousOp.CLEAR_CLIPS) { bridge.clearClips() }

    suspend fun setOutputPin(token: ConfirmationToken, output: Int, pin: Int): PinConfigStatus =
        with(token, DangerousOp.OUTPUT_PIN) { bridge.setOutputPin(output, pin) }

    suspend fun setOutputType(token: ConfirmationToken, slot: Int, kind: Int): PinConfigStatus =
        with(token, DangerousOp.OUTPUT_TYPE) { bridge.setOutputType(slot, kind) }

    suspend fun setI2sBckPin(token: ConfirmationToken, role: Int, pin: Int): PinConfigStatus =
        with(token, DangerousOp.I2S_BCK_PIN) { bridge.setI2sBckPin(role, pin) }

    suspend fun setI2sClockPinMode(token: ConfirmationToken, mode: Int): PinConfigStatus =
        with(token, DangerousOp.I2S_CLOCK_PIN_MODE) { bridge.setI2sClockPinMode(mode) }

    suspend fun setMckEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus =
        with(token, DangerousOp.MCK_ENABLE) { bridge.setMckEnabled(enabled) }

    suspend fun setMckPin(token: ConfirmationToken, pin: Int): PinConfigStatus = with(token, DangerousOp.MCK_PIN) { bridge.setMckPin(pin) }

    suspend fun setMckMultiplier(token: ConfirmationToken, mult: Int): PinConfigStatus =
        with(token, DangerousOp.MCK_MULTIPLIER) { bridge.setMckMultiplier(mult) }

    suspend fun setAdatOutEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus =
        with(token, DangerousOp.ADAT_OUT_ENABLE) { bridge.setAdatOutEnabled(enabled) }

    suspend fun setAdatOutPin(token: ConfirmationToken, pin: Int): PinConfigStatus =
        with(token, DangerousOp.ADAT_OUT_PIN) { bridge.setAdatOutPin(pin) }

    suspend fun setSpdifRxPin(token: ConfirmationToken, index: Int, pin: Int): PinConfigStatus =
        with(token, DangerousOp.SPDIF_RX_PIN) { bridge.setSpdifRxPin(index, pin) }

    suspend fun setSpdifInputEnabled(token: ConfirmationToken, index: Int, enabled: Boolean): PinConfigStatus =
        with(token, DangerousOp.SPDIF_INPUT_ENABLE) { bridge.setSpdifInputEnabled(index, enabled) }

    suspend fun setI2sRxPin(token: ConfirmationToken, pair: Int, pin: Int): PinConfigStatus =
        with(token, DangerousOp.I2S_RX_PIN) { bridge.setI2sRxPin(pair, pin) }

    suspend fun setI2sInputChannels(token: ConfirmationToken, channels: Int): PinConfigStatus =
        with(token, DangerousOp.I2S_INPUT_CHANNELS) { bridge.setI2sInputChannels(channels) }

    suspend fun setAdatInputEnabled(token: ConfirmationToken, enabled: Boolean): PinConfigStatus =
        with(token, DangerousOp.ADAT_INPUT_ENABLE) { bridge.setAdatInputEnabled(enabled) }

    suspend fun setAdatInputPin(token: ConfirmationToken, pin: Int): PinConfigStatus =
        with(token, DangerousOp.ADAT_INPUT_PIN) { bridge.setAdatInputPin(pin) }

    suspend fun setAdatInputClockMode(token: ConfirmationToken, mode: Int): PinConfigStatus =
        with(token, DangerousOp.ADAT_INPUT_CLOCK_MODE) { bridge.setAdatInputClockMode(mode) }

    suspend fun testDacMute(token: ConfirmationToken): Int = with(token, DangerousOp.TEST_DAC_MUTE) { bridge.testDacMute() }

    fun resetWatermarks(token: ConfirmationToken) = with(token, DangerousOp.RESET_BUFFER_STATS) { bridge.stats.resetWatermarks() }

    suspend fun siggenStart(token: ConfirmationToken): Boolean = with(token, DangerousOp.SIGGEN_START) { bridge.siggen.start() }

    fun siggenIdentify(token: ConfirmationToken, output: Int) = with(token, DangerousOp.SIGGEN_START) { bridge.siggen.identify(output) }

    suspend fun csSave(token: ConfirmationToken): Int = with(token, DangerousOp.CS_SAVE) { bridge.cs.save() }

    suspend fun csRevert(token: ConfirmationToken): Int = with(token, DangerousOp.CS_REVERT) { bridge.cs.revert() }

    /** A gated control-surface op (`save`, `revert`, `learnArm`, `macroFire`); [token] must match [csOpOf]. */
    suspend fun csApply(token: ConfirmationToken, opJson: String): String = with(token, token.op) { bridge.cs.apply(opJson) }

    suspend fun importConfiguration(token: ConfirmationToken, text: String, volumes: Boolean, hardware: Boolean): String =
        with(token, DangerousOp.CONFIG_IMPORT) { bridge.configFiles.importConfiguration(text, volumes, hardware) }

    companion object {
        const val DEFAULT_TTL_MS = 10_000L

        /** The gate a control-surface operation needs, or null when it is not gated. */
        fun csOpOf(opJson: String): DangerousOp? = when (Json.parseObject(opJson)?.str("op")) {
            "save" -> DangerousOp.CS_SAVE
            "revert" -> DangerousOp.CS_REVERT
            "learnArm" -> DangerousOp.CS_IR_LEARN
            "macroFire" -> DangerousOp.CS_MACRO_FIRE
            else -> null
        }
    }
}

// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsSaveCoordinator`
// (1:1: globalDraft/globalUserEdited, outputConfigDirty/outputBaseline/
// beginOutputEdit, csDirty/csBusy/csReloadToken, save(), revert()). Gating per
// coordinator decision E1b: a hardware control mints HARDWARE_APPLY on the
// user's interaction and applies at once ([applyHardware]); Save mints
// SAVE_OUTPUT_CONFIG / CS_SAVE; Revert mints HARDWARE_APPLY from the Revert tap.
package se.bofr.dspi.feature.settings

import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.ConfirmationToken
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.feature.settings.GlobalSettingsDraft.Companion.OUTPUT_CONFIG_MODE_INDEPENDENT

/**
 * Owner of pending (unsaved) Settings changes for one bridge, so the save bar
 * survives moving between pages and leaving Settings. Categories as on macOS:
 *
 * - **B, global parameters** ([globalDraft]): staged until Save (ungated
 *   directory/config writes, D3).
 * - **A, output config** (independent mode only): pin/type/clock/RX and
 *   output-limiter edits apply LIVE; "dirty" means not yet flashed. Save calls
 *   `saveOutputConfig()`; Revert re-applies the captured [outputBaseline].
 * - **C, control surfaces**: live previews the device marks dirty.
 *
 * Dirtiness is gated on actual user edits ([beginOutputEdit]), so fresh device
 * data never looks unsaved. [outputConfigDirty] is kept here until the
 * bridge's `outputConfigDirty` hook (pkg/contract2) lands; it is only read
 * through [outputDirty] and written through [beginOutputEdit] /
 * [markOutputClean], so switching to the hook is local. Limiter edits made in
 * TOOLS (which can't call [beginOutputEdit]) show up through the bridge's
 * `hardwareUnsaved` meanwhile. Main thread only.
 */
class SettingsSaveCoordinator(
    val bridge: DspiBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    // ── Category B: global parameters ──
    private val storedDraft = MutableStateFlow(GlobalSettingsDraft.from(bridge.state.value))
    private val _globalUserEdited = MutableStateFlow(false)
    val globalUserEdited: StateFlow<Boolean> = _globalUserEdited.asStateFlow()

    // ── Category A: output config (live edits, flash on save) ──
    private val _outputConfigDirty = MutableStateFlow(false)

    /** The user edited the output config since the last clean point (macOS `outputConfigDirty`). */
    val outputConfigDirty: StateFlow<Boolean> = _outputConfigDirty.asStateFlow()

    /** Pre-edit config Revert restores (macOS `outputBaseline`). */
    var outputBaseline: OutputConfigSnapshot = OutputConfigSnapshot.from(bridge.state.value, bridge.platformName)
        private set

    val hardwareChanges = HardwareChanges(bridge)

    // ── Category C: control surfaces ──
    private var csOpsInFlight = 0
    private val _csBusy = MutableStateFlow(false)
    val csBusy: StateFlow<Boolean> = _csBusy.asStateFlow()
    private val _csReloadToken = MutableStateFlow(0)
    val csReloadToken: StateFlow<Int> = _csReloadToken.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _lastResult = MutableStateFlow<SaveResult?>(null)

    /** What the last Save could not do (shown in the bar). */
    val lastResult: StateFlow<SaveResult?> = _lastResult.asStateFlow()

    private val _hardwareStatus = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** Outcome of the last hardware control change (macOS `statusMessage` / `statusIsError`). */
    val hardwareStatus: StateFlow<Pair<String, Boolean>?> = _hardwareStatus.asStateFlow()

    private var lastDeviceSerial: String? = bridge.selectedSerial.ifEmpty { null }

    init {
        // Keep the baseline at the device's clean config, so limiter edits from
        // TOOLS (reported by hardwareUnsaved) still have one to revert to.
        scope.launch {
            bridge.state.collect { s ->
                if (!_outputConfigDirty.value && !bridge.hardwareUnsaved.value) outputBaseline = deviceSnapshot(s)
            }
        }
    }

    // ── Dirty state ──

    /** The Global page's values: the device's until the user edits. */
    fun globalDraft(state: DspState = bridge.state.value): GlobalSettingsDraft =
        if (_globalUserEdited.value) storedDraft.value else GlobalSettingsDraft.from(state)

    fun globalDirty(state: DspState = bridge.state.value): Boolean =
        _globalUserEdited.value && storedDraft.value != GlobalSettingsDraft.from(state)

    /** macOS `outputDirty`: user edits not yet flashed, independent mode only. */
    fun outputDirty(state: DspState = bridge.state.value): Boolean =
        (_outputConfigDirty.value || bridge.hardwareUnsaved.value) && state.outputConfigMode == OUTPUT_CONFIG_MODE_INDEPENDENT

    fun csDirty(): Boolean = bridge.cs.supported.value && bridge.cs.unsaved.value

    fun hasPendingChanges(state: DspState = bridge.state.value): Boolean = globalDirty(state) || outputDirty(state) || csDirty()

    /** Only the control-surfaces preview is pending. */
    fun csOnly(state: DspState = bridge.state.value): Boolean = csDirty() && !globalDirty(state) && !outputDirty(state)

    fun deviceSnapshot(state: DspState = bridge.state.value): OutputConfigSnapshot = OutputConfigSnapshot.from(state, bridge.platformName)

    /** The pin authority over the device's config (macOS `vm.pinAssignment`). */
    fun pinContext(state: DspState = bridge.state.value): PinContext =
        PinContext.from(deviceSnapshot(state), state, if (bridge.connected) bridge.pinOwners() else emptyList())

    // ── Output-config editing ──

    /**
     * Call at the start of any output-config edit. On the first edit since the
     * last clean point (independent mode only), captures the pre-edit committed
     * config as the revert baseline.
     */
    fun beginOutputEdit(state: DspState = bridge.state.value) {
        if (state.outputConfigMode != OUTPUT_CONFIG_MODE_INDEPENDENT) return
        if (!_outputConfigDirty.value) {
            outputBaseline = deviceSnapshot(state)
            _outputConfigDirty.value = true
        }
    }

    private fun markOutputClean() {
        _outputConfigDirty.value = false
    }

    /**
     * A hardware control was changed by the user: mint HARDWARE_APPLY now (the
     * interaction is the confirmation, E1b), mark the edit, and apply
     * [changes] at once — several only when one action implies several calls,
     * in dependency order, stopping at the first refusal. Main thread.
     */
    fun applyHardware(changes: List<HardwareChange>): Job? {
        val todo = HardwareChangeOrder.topoSort(changes.filter { it.oldDisplay != it.newDisplay })
        if (todo.isEmpty()) return null
        val token = bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        beginOutputEdit()
        val serial = bridge.selectedSerial
        return scope.launch {
            try {
                for (c in todo) {
                    if (bridge.selectedSerial != serial) break
                    val st = try {
                        c.apply(token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        -1
                    }
                    if (st != 0) {
                        _hardwareStatus.value = (c.statusText?.invoke(st) ?: "Failed to set ${c.fieldLabel}") to true
                        return@launch
                    }
                }
                val one = todo.singleOrNull()
                _hardwareStatus.value =
                    (if (one != null) "${one.fieldLabel} set to ${one.newDisplay}" else "${todo.size} changes applied") to false
            } finally {
                bridge.dangerousOps.revoke(token)
            }
        }
    }

    fun applyHardware(change: HardwareChange): Job? = applyHardware(listOf(change))

    // ── Global draft editing ──

    /** Edit a global field; marks the draft user-edited (macOS `draftBinding`). */
    fun editGlobal(transform: (GlobalSettingsDraft) -> GlobalSettingsDraft) {
        storedDraft.value = transform(globalDraft())
        _globalUserEdited.value = true
        _lastResult.value = null
    }

    /** One line per pending change, for the save bar's list. */
    fun pendingSummary(state: DspState = bridge.state.value): List<String> {
        val out = ArrayList<String>()
        if (globalDirty(state)) {
            val d = storedDraft.value
            val s = GlobalSettingsDraft.from(state)
            if (d.presetStartupMode != s.presetStartupMode) {
                out += "Startup preset: ${startupName(s.presetStartupMode)} → ${startupName(d.presetStartupMode)}"
            }
            if (d.presetStartupMode == 0 && d.presetDefaultSlot != s.presetDefaultSlot) {
                out += "Default preset: ${s.presetDefaultSlot + 1} → ${d.presetDefaultSlot + 1}"
            }
            if (d.presetMasterVolumeMode != s.presetMasterVolumeMode) {
                out += "Master volume: ${modeName(s.presetMasterVolumeMode)} → ${modeName(d.presetMasterVolumeMode)}"
            }
            if (d.presetOutputConfigMode != s.presetOutputConfigMode) {
                out += "Hardware configuration: ${modeName(s.presetOutputConfigMode)} → ${modeName(d.presetOutputConfigMode)}"
            }
            if (d.dacHwMuteConfig != s.dacHwMuteConfig) out += "External mute control"
        }
        if (outputDirty(state)) out += "Input and output configuration (live now, not yet saved to flash)"
        if (csDirty()) out += "Control surfaces (live now, not yet saved)"
        return out
    }

    // ── Save / Revert ──

    fun onSave(): Job = scope.launch { save() }

    /** The bar's Revert: mints HARDWARE_APPLY from the tap when the output config is restored. */
    fun onRevert(): Job {
        val token = if (outputDirty()) bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY) else null
        return scope.launch { revert(token) }
    }

    /** macOS `save()`: global writes, then `saveOutputConfig()`, then the CS save. */
    suspend fun save(): SaveResult? {
        val state = bridge.state.value
        val doGlobal = globalDirty(state)
        val doOutput = outputDirty(state)
        val doCs = csDirty()
        if (!doGlobal && !doOutput && !doCs) return null
        val serial = bridge.selectedSerial
        fun stillCurrent() = bridge.selectedSerial == serial
        val messages = ArrayList<String>()
        _saving.value = true
        if (doCs) beginCsOperation()
        try {
            val pending = storedDraft.value
            val device = GlobalSettingsDraft.from(state)
            if (doGlobal) {
                if (pending.presetStartupMode != device.presetStartupMode || pending.presetDefaultSlot != device.presetDefaultSlot) {
                    bridge.setPresetStartup(pending.presetStartupMode, pending.presetDefaultSlot)
                }
                if (pending.presetMasterVolumeMode !=
                    device.presetMasterVolumeMode
                ) {
                    bridge.setMasterVolumeMode(pending.presetMasterVolumeMode)
                }
                if (pending.presetOutputConfigMode !=
                    device.presetOutputConfigMode
                ) {
                    bridge.setOutputConfigMode(pending.presetOutputConfigMode)
                }
                if (pending.dacHwMuteConfig != device.dacHwMuteConfig) {
                    val m = pending.dacHwMuteConfig
                    bridge.setDacMute(m.enabled, m.activeLow, m.pin, m.holdMs, m.releaseMs)
                }
            }
            if (doOutput && stillCurrent()) {
                val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG)
                val st = try {
                    bridge.saveOutputConfig(token)
                } finally {
                    bridge.dangerousOps.revoke(token)
                }
                if (st != PresetStatus.OK) messages += "Output configuration could not be saved to flash"
            }
            if (doCs && stillCurrent()) {
                val token = bridge.dangerousOps.confirm(DangerousOp.CS_SAVE)
                val st = try {
                    bridge.cs.save(token)
                } finally {
                    bridge.dangerousOps.revoke(token)
                }
                if (st != 0) messages += "Control surfaces: ${bridge.cs.statusMessage(st)}"
            }
            if (stillCurrent()) {
                _globalUserEdited.value = false
                markOutputClean()
            }
            return SaveResult(messages).also { _lastResult.value = it }
        } finally {
            if (doCs) endCsOperation()
            _saving.value = false
        }
    }

    /**
     * macOS `revert()`: drop the global draft, revert the CS preview, and
     * best-effort restore the live output config to [outputBaseline] in the
     * macOS order, with [token] (HARDWARE_APPLY, minted by the Revert tap).
     */
    suspend fun revert(token: ConfirmationToken?) {
        val state = bridge.state.value
        val doOutput = outputDirty(state)
        if (globalDirty(state)) _globalUserEdited.value = false
        _lastResult.value = null
        try {
            if (csDirty()) revertControlSurfaces()
            if (doOutput && token != null) restoreOutputConfig(outputBaseline, token)
        } finally {
            if (token != null) bridge.dangerousOps.revoke(token)
        }
    }

    private suspend fun restoreOutputConfig(base: OutputConfigSnapshot, t: ConfirmationToken) {
        val serial = bridge.selectedSerial
        fun stillCurrent() = bridge.selectedSerial == serial
        fun cur() = deviceSnapshot()
        if (!stillCurrent()) return
        var c = cur()
        for (slot in 0 until minOf(c.numOutputSlots, base.outputSlotTypes.size)) {
            if (c.outputSlotTypes.getOrNull(slot) != base.outputSlotTypes[slot]) bridge.setOutputType(t, slot, base.outputSlotTypes[slot])
        }
        c = cur()
        for (i in 0 until minOf(c.numPinOutputs, c.outputPins.size, base.outputPins.size)) {
            if (c.outputPins[i] != base.outputPins[i]) bridge.setOutputPin(t, i, base.outputPins[i])
        }
        if (!stillCurrent()) return
        c = cur()
        if (c.i2sBckPin != base.i2sBckPin) bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_MASTER, base.i2sBckPin)
        if (c.mckEnabled != base.mckEnabled) bridge.setMckEnabled(t, base.mckEnabled)
        if (c.mckPin != base.mckPin) bridge.setMckPin(t, base.mckPin)
        if (c.mckMultiplier != base.mckMultiplier) bridge.setMckMultiplier(t, base.mckMultiplier)
        if (c.spdifRxPins.getOrNull(0) != base.spdifRxPins.getOrNull(0)) bridge.setSpdifRxPin(t, 0, base.spdifRxPins[0])
        if (!stillCurrent()) return
        // Optional S/PDIF 2..4: disable before repinning, repin, then enable
        if (c.multiSpdifSupported) {
            for (idx in 1 until minOf(c.spdifInputCount, base.spdifRxPins.size)) {
                c = cur()
                val now = c.spdifInputEnabled(idx)
                val was = base.spdifInputEnabled(idx)
                if (now && !was) bridge.setSpdifInputEnabled(t, idx, false)
                if (c.spdifRxPins.getOrNull(idx) != base.spdifRxPins[idx]) bridge.setSpdifRxPin(t, idx, base.spdifRxPins[idx])
                if (!now && was) bridge.setSpdifInputEnabled(t, idx, true)
            }
        }
        if (!stillCurrent()) return
        // I2S input: a lowered count first, a raised count after the pair pins
        c = cur()
        val raising = base.i2sInputChannels > c.i2sInputChannels
        if (!raising && c.i2sInputChannels != base.i2sInputChannels) bridge.setI2sInputChannels(t, base.i2sInputChannels)
        for (pair in 0 until minOf(c.i2sRxPins.size, base.i2sRxPins.size)) {
            if (c.i2sRxPins[pair] != base.i2sRxPins[pair]) bridge.setI2sRxPin(t, pair, base.i2sRxPins[pair])
        }
        if (raising) bridge.setI2sInputChannels(t, base.i2sInputChannels)
        if (c.i2sInputRate != base.i2sInputRate) bridge.setInputRate(t, base.i2sInputRate)
        if (c.i2sClockMode != base.i2sClockMode) bridge.setI2sClockMode(t, base.i2sClockMode)
        // Clock-pin mode: the slave pair first, so re-entering SPLIT finds it valid
        if (c.i2sClockPinModeSupported) {
            if (c.i2sBckPinSlave != base.i2sBckPinSlave) {
                bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_SLAVE, base.i2sBckPinSlave)
            }
            if (c.i2sClockPinMode != base.i2sClockPinMode) bridge.setI2sClockPinMode(t, base.i2sClockPinMode)
        }
        if (!stillCurrent()) return
        // ADAT: the data pin first, then the enable state
        if (c.adatSupported) {
            if (c.adatPin != base.adatPin) bridge.setAdatOutPin(t, base.adatPin)
            if (c.adatEnabled != base.adatEnabled) bridge.setAdatOutEnabled(t, base.adatEnabled)
        }
        if (!stillCurrent()) return
        // Output limiters last (ungated setters)
        c = cur()
        for (k in 0 until minOf(c.limiterEnabled.size, base.limiterEnabled.size)) {
            if (c.limiterThresholdDb.getOrNull(k) != base.limiterThresholdDb.getOrNull(k)) {
                bridge.setLimiterThreshold(k, base.limiterThresholdDb[k])
            }
            if (c.limiterReleaseMs.getOrNull(k) != base.limiterReleaseMs.getOrNull(k)) bridge.setLimiterRelease(k, base.limiterReleaseMs[k])
            if (c.limiterLinkGroup.getOrNull(k) !=
                base.limiterLinkGroup.getOrNull(k)
            ) {
                bridge.setLimiterLinkGroup(k, base.limiterLinkGroup[k])
            }
            if (c.limiterEnabled[k] != base.limiterEnabled[k]) bridge.setLimiterEnabled(k, base.limiterEnabled[k])
        }
        markOutputClean()
    }

    private suspend fun revertControlSurfaces() {
        beginCsOperation()
        try {
            bridge.cs.revert()
        } finally {
            endCsOperation()
            _csReloadToken.value += 1
        }
    }

    /** Claim the shared control-surfaces status channel (macOS `beginCsOperation`). */
    fun beginCsOperation() {
        csOpsInFlight += 1
        _csBusy.value = true
    }

    fun endCsOperation() {
        csOpsInFlight = maxOf(0, csOpsInFlight - 1)
        _csBusy.value = csOpsInFlight > 0
    }

    /** Pending state belongs to one device; a re-plug of the same unit keeps it (macOS `noteSelectedDevice`). */
    fun noteSelectedDevice(serial: String) {
        if (serial.isEmpty()) return
        val last = lastDeviceSerial
        lastDeviceSerial = serial
        if (last != null && last != serial) resetForNewDevice()
    }

    private fun resetForNewDevice() {
        _globalUserEdited.value = false
        markOutputClean()
        outputBaseline = deviceSnapshot()
        _lastResult.value = null
        _hardwareStatus.value = null
    }

    companion object {
        private val instances: MutableMap<DspiBridge, SettingsSaveCoordinator> = Collections.synchronizedMap(WeakHashMap())

        /** App-lifetime coordinator of [bridge] (macOS `SettingsSaveCoordinator.shared`). */
        fun of(bridge: DspiBridge): SettingsSaveCoordinator = instances.getOrPut(bridge) { SettingsSaveCoordinator(bridge) }

        private fun modeName(m: Int) = if (m == 0) "Independent" else "With Preset"

        private fun startupName(m: Int) = if (m == 0) "Specified Default" else "Last Used"
    }
}

/** What the last Save could not do, one sentence each (empty = all saved). */
data class SaveResult(val failures: List<String>) {
    val ok: Boolean get() = failures.isEmpty()
}

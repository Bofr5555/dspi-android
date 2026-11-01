// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsSaveCoordinator`.
// The output-config half (outputConfigDirty, outputBaseline, beginOutputEdit,
// the revert sequence) lives in the bridge (decision F2): this class keeps the
// global draft, the control-surfaces operation gate, the save/revert
// sequences and the hardware controls' apply (E1b gating, E9 scope).
package se.bofr.dspi.feature.settings

import java.lang.ref.WeakReference
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
import se.bofr.dspi.bridge.DangerousOpNotConfirmed
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.PresetStatus

/**
 * Owner of pending (unsaved) Settings changes for one bridge, so the save bar
 * survives moving between pages and leaving Settings. Categories as on macOS:
 *
 * - **B, global parameters** ([globalDraft]): staged until Save (ungated
 *   directory/config writes, D3).
 * - **A, output config**: edits apply LIVE; the bridge tracks
 *   `outputConfigDirty` (independent mode) and the baseline. Save calls
 *   `saveOutputConfig`, Revert `revertOutputConfig`.
 * - **C, control surfaces**: live previews the device marks dirty.
 *
 * Gating (E1b): a hardware control mints HARDWARE_APPLY on the user's
 * interaction ([applyHardware]); the Save tap mints SAVE_OUTPUT_CONFIG and
 * CS_SAVE ([onSave]); the Revert tap mints HARDWARE_APPLY ([onRevert]).
 * Every device sequence runs in this app-scoped coordinator, never in a
 * composable's scope (E9). Main thread only.
 */
class SettingsSaveCoordinator(
    val bridge: DspiBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    // ── Category B: global parameters ──
    private val storedDraft = MutableStateFlow(GlobalSettingsDraft.from(bridge.state.value))
    private val _globalUserEdited = MutableStateFlow(false)
    val globalUserEdited: StateFlow<Boolean> = _globalUserEdited.asStateFlow()

    val hardwareChanges = HardwareChanges(bridge)

    // ── Category C: control surfaces ──
    private var csOpsInFlight = 0
    private val _csBusy = MutableStateFlow(false)

    /** A control-surfaces operation is in flight (they share one device status channel). */
    val csBusy: StateFlow<Boolean> = _csBusy.asStateFlow()
    private val _csReloadToken = MutableStateFlow(0)
    val csReloadToken: StateFlow<Int> = _csReloadToken.asStateFlow()
    private val _csStatus = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** Outcome of the last Control Surfaces page operation (null = fine). */
    val csStatus: StateFlow<Pair<String, Boolean>?> = _csStatus.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()
    private val _lastResult = MutableStateFlow<SaveResult?>(null)

    /** What the last Save/Revert could not do (shown in the bar). */
    val lastResult: StateFlow<SaveResult?> = _lastResult.asStateFlow()

    private val _hardwareStatus = MutableStateFlow<Pair<String, Boolean>?>(null)

    /** Outcome of the last hardware control change (macOS `statusMessage` / `statusIsError`). */
    val hardwareStatus: StateFlow<Pair<String, Boolean>?> = _hardwareStatus.asStateFlow()

    private var lastDeviceSerial: String? = bridge.selectedSerial.ifEmpty { null }

    // ── Dirty state ──

    /** The Global page's values: the device's until the user edits. */
    fun globalDraft(state: DspState = bridge.state.value): GlobalSettingsDraft =
        if (_globalUserEdited.value) storedDraft.value else GlobalSettingsDraft.from(state)

    fun globalDirty(state: DspState = bridge.state.value): Boolean =
        _globalUserEdited.value && storedDraft.value != GlobalSettingsDraft.from(state)

    /** macOS `outputDirty`: the bridge's `outputConfigDirty` only (never `hardwareUnsaved`, review S1). */
    fun outputDirty(): Boolean = bridge.outputConfigDirty.value

    fun csDirty(): Boolean = bridge.cs.supported.value && bridge.cs.unsaved.value

    fun hasPendingChanges(state: DspState = bridge.state.value): Boolean = globalDirty(state) || outputDirty() || csDirty()

    /** Only the control-surfaces preview is pending. */
    fun csOnly(state: DspState = bridge.state.value): Boolean = csDirty() && !globalDirty(state) && !outputDirty()

    fun deviceSnapshot(state: DspState = bridge.state.value): OutputConfigSnapshot =
        OutputConfigSnapshot.from(state, bridge.platformName, bridge.hardwareCaps)

    /** The pin authority over the device's config (macOS `vm.pinAssignment`). */
    fun pinContext(state: DspState = bridge.state.value): PinContext =
        PinContext.from(deviceSnapshot(state), state, if (bridge.connected) bridge.pinOwners() else emptyList())

    // ── Hardware controls ──

    /**
     * A hardware control was changed by the user: mint HARDWARE_APPLY now (the
     * interaction is the confirmation, E1b), `bridge.beginOutputEdit()`, and
     * apply [changes] at once — several only when one action implies several
     * calls, in dependency order, stopping at the first refusal and when the
     * device changes. [successText] replaces the generic success line
     * (macOS's own where it has one). Main thread.
     */
    fun applyHardware(changes: List<HardwareChange>, successText: String? = null): Job? {
        val todo = HardwareChangeOrder.topoSort(changes.filter { it.oldDisplay != it.newDisplay })
        if (todo.isEmpty()) return null
        val token = bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        bridge.beginOutputEdit()
        // macOS transport.generation: a re-plug, even of the same unit, aborts the sequence
        val generation = bridge.deviceGeneration.value
        return scope.launch {
            try {
                for (c in todo) {
                    if (bridge.deviceGeneration.value != generation) return@launch
                    val st = try {
                        c.apply(token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: DangerousOpNotConfirmed) {
                        // A programming error: never turned into a status line (review S6)
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
                val text =
                    successText ?: one?.successText
                        ?: if (one != null) "${one.fieldLabel} set to ${one.newDisplay}" else "${todo.size} changes applied"
                _hardwareStatus.value = if (text.isEmpty()) null else text to false
            } finally {
                bridge.dangerousOps.revoke(token)
            }
        }
    }

    fun applyHardware(change: HardwareChange): Job? = applyHardware(listOf(change))

    // ── Global draft editing ──

    /** Edit a global field; marks the draft user-edited (macOS `draftBinding`). Works offline. */
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
            if (d.presetStartupMode == GlobalSettingsDraft.STARTUP_SPECIFIED && d.presetDefaultSlot != s.presetDefaultSlot) {
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
        if (outputDirty()) out += "Input and output configuration (live now, not yet saved to flash)"
        if (csDirty()) out += "Control surfaces (live now, not yet saved)"
        return out
    }

    // ── Save / Revert ──

    /** The bar's Save: mints SAVE_OUTPUT_CONFIG / CS_SAVE on the tap (only what is pending). */
    fun onSave(): Job {
        val outputToken = if (outputDirty()) bridge.dangerousOps.confirm(DangerousOp.SAVE_OUTPUT_CONFIG) else null
        val csToken = if (csDirty()) bridge.dangerousOps.confirm(DangerousOp.CS_SAVE) else null
        return scope.launch { save(outputToken, csToken) }
    }

    /** The bar's Revert: mints HARDWARE_APPLY on the tap when the output config is restored. */
    fun onRevert(): Job {
        val token = if (outputDirty()) bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY) else null
        return scope.launch { revert(token) }
    }

    /**
     * macOS `save()`: global writes, then `saveOutputConfig()` with [outputToken],
     * then the CS save with [csToken]. Every token is revoked when done, used or not.
     */
    suspend fun save(outputToken: ConfirmationToken?, csToken: ConfirmationToken?): SaveResult? {
        try {
            val state = bridge.state.value
            val doGlobal = globalDirty(state)
            val doOutput = outputToken != null && outputDirty()
            val doCs = csToken != null && csDirty()
            if (!doGlobal && !doOutput && !doCs) return null
            // macOS stillCurrent(): the transport generation, bumped on every open and close
            val generation = bridge.deviceGeneration.value
            fun stillCurrent() = bridge.deviceGeneration.value == generation
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
                    if (pending.presetMasterVolumeMode != device.presetMasterVolumeMode) {
                        bridge.setMasterVolumeMode(pending.presetMasterVolumeMode)
                    }
                    if (pending.presetOutputConfigMode != device.presetOutputConfigMode) {
                        bridge.setOutputConfigMode(pending.presetOutputConfigMode)
                    }
                    if (pending.dacHwMuteConfig != device.dacHwMuteConfig) {
                        val m = pending.dacHwMuteConfig
                        bridge.setDacMute(m.enabled, m.activeLow, m.pin, m.holdMs, m.releaseMs)
                    }
                }
                if (doOutput && stillCurrent()) {
                    if (bridge.saveOutputConfig(outputToken!!) != PresetStatus.OK) {
                        messages += "Output configuration could not be saved to flash"
                    }
                }
                if (doCs && stillCurrent()) {
                    val st = bridge.cs.save(csToken!!)
                    if (st != 0) messages += "Control surfaces: ${bridge.cs.statusMessage(st)}"
                }
                if (stillCurrent()) _globalUserEdited.value = false
                return SaveResult(messages).also { _lastResult.value = it }
            } finally {
                if (doCs) endCsOperation()
                _saving.value = false
            }
        } finally {
            outputToken?.let { bridge.dangerousOps.revoke(it) }
            csToken?.let { bridge.dangerousOps.revoke(it) }
        }
    }

    /**
     * macOS `revert()`: drop the global draft, revert the CS preview, and
     * restore the live output config with the bridge's `revertOutputConfig`
     * using [token] (HARDWARE_APPLY, minted by the Revert tap).
     */
    suspend fun revert(token: ConfirmationToken?) {
        try {
            if (globalDirty()) _globalUserEdited.value = false
            _lastResult.value = null
            if (csDirty()) revertControlSurfaces()
            if (token != null && outputDirty()) bridge.revertOutputConfig(token)
        } finally {
            token?.let { bridge.dangerousOps.revoke(it) }
        }
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

    /**
     * One Control Surfaces page operation (`cs.apply` JSON: rename, remove,
     * macro fire, aux switch), serialised on the shared status channel and run
     * in this app-scoped coordinator (E9).
     */
    fun csOp(opJson: String): Job {
        beginCsOperation()
        _csStatus.value = null
        return scope.launch {
            try {
                val r = CsJson.parseObject(bridge.cs.apply(opJson))
                if (r?.bool("ok") != true) {
                    _csStatus.value = (r?.str("error") ?: bridge.cs.statusMessage(r?.int("status") ?: 0xFF)) to true
                }
            } finally {
                endCsOperation()
            }
        }
    }

    /**
     * The selection moved: the global draft belongs to the old device and is
     * dropped (the bridge clears its own output-config flag); a re-plug of the
     * same unit keeps it (macOS `noteSelectedDevice`).
     */
    fun noteSelectedDevice(serial: String) {
        if (serial.isEmpty()) return
        val last = lastDeviceSerial
        lastDeviceSerial = serial
        if (last != null && last != serial) {
            _globalUserEdited.value = false
            _lastResult.value = null
            _hardwareStatus.value = null
            _csStatus.value = null
        }
    }

    companion object {
        /** One app bridge: a single slot, so a replaced bridge (tests) is not kept alive (review S17). */
        private var cached: Pair<WeakReference<DspiBridge>, SettingsSaveCoordinator>? = null

        /** App-lifetime coordinator of [bridge] (macOS `SettingsSaveCoordinator.shared`). */
        @Synchronized
        fun of(bridge: DspiBridge): SettingsSaveCoordinator {
            cached?.let { (ref, c) -> if (ref.get() === bridge) return c }
            return SettingsSaveCoordinator(bridge).also { cached = WeakReference(bridge) to it }
        }

        private fun modeName(m: Int) = if (m == 0) "Independent" else "With Preset"

        private fun startupName(m: Int) = if (m == GlobalSettingsDraft.STARTUP_SPECIFIED) "Specified Default" else "Last Used"
    }
}

/** What the last Save could not do, one sentence each (empty = all saved). */
data class SaveResult(val failures: List<String>) {
    val ok: Boolean get() = failures.isEmpty()
}

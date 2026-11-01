// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsSaveCoordinator`,
// with the hardware category staged in Windows' PendingChangeTracker (D2:
// pipeline-rebuilding calls run only from the save bar's Save, with one
// HARDWARE_APPLY token).
package se.bofr.dspi.feature.settings

import java.util.Collections
import java.util.WeakHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.PresetStatus

/**
 * Owner of pending (unsaved) Settings changes for one bridge, so the save bar
 * survives moving between pages and leaving Settings. Three categories, one
 * Save and one Revert:
 *
 * - **Global parameters** ([globalDraft]): staged; Save writes the ones that
 *   differ (ungated directory/config writes, D3) — startup, master-volume
 *   mode, output-config mode, DAC mute.
 * - **Hardware** ([hardware]): pins, types, clocks and inputs staged as
 *   [PendingChange]s; Save mints one reusable HARDWARE_APPLY token and applies
 *   them in dependency order. Then, in independent mode, Save Output
 *   Configuration (SAVE_OUTPUT_CONFIG) keeps them — and any limiter edits the
 *   bridge reports as `hardwareUnsaved` — across a restart.
 * - **Control surfaces**: live previews the device marks dirty; Save is
 *   `cs.save` (CS_SAVE), Revert `cs.revert`.
 *
 * Tokens are minted by the Save sequence itself on the main thread — the
 * user's Save click is the confirmation, as on macOS (no extra dialog) — each
 * immediately before its use and revoked afterwards. Main thread only.
 */
class SettingsSaveCoordinator(
    val bridge: DspiBridge,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {
    // ── Category B: global parameters ──
    private val storedDraft = MutableStateFlow(GlobalSettingsDraft.from(bridge.state.value))
    private val _globalUserEdited = MutableStateFlow(false)
    val globalUserEdited: StateFlow<Boolean> = _globalUserEdited.asStateFlow()

    // ── Category A: hardware ──
    val hardware = PendingChangeTracker()
    val hardwareChanges = HardwareChanges(bridge)

    // ── Category C: control surfaces ──
    private var csOpsInFlight = 0
    private val _csBusy = MutableStateFlow(false)

    /** A control-surfaces operation is in flight (they share one status channel). */
    val csBusy: StateFlow<Boolean> = _csBusy.asStateFlow()
    private val _csReloadToken = MutableStateFlow(0)

    /** Bumped after a CS revert lands, so the page re-seeds its drafts. */
    val csReloadToken: StateFlow<Int> = _csReloadToken.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _lastResult = MutableStateFlow<SaveResult?>(null)

    /** Outcome of the last Save (failures stay staged for a retry, as on Windows). */
    val lastResult: StateFlow<SaveResult?> = _lastResult.asStateFlow()

    private var lastDeviceSerial: String? = bridge.selectedSerial.ifEmpty { null }

    // ── Dirty state ──

    /** The draft the Global page shows: the device's values until the user edits. */
    fun globalDraft(state: DspState = bridge.state.value): GlobalSettingsDraft =
        if (_globalUserEdited.value) storedDraft.value else GlobalSettingsDraft.from(state)

    fun globalDirty(state: DspState = bridge.state.value): Boolean =
        _globalUserEdited.value && storedDraft.value != GlobalSettingsDraft.from(state)

    /** Live output-config edits not yet flashed (independent mode only). */
    fun outputDirty(state: DspState = bridge.state.value): Boolean =
        bridge.hardwareUnsaved.value && state.outputConfigMode == GlobalSettingsDraft.OUTPUT_CONFIG_MODE_INDEPENDENT

    fun csDirty(): Boolean = bridge.cs.supported.value && bridge.cs.unsaved.value

    fun hasPendingChanges(state: DspState = bridge.state.value): Boolean =
        globalDirty(state) || hardware.count > 0 || outputDirty(state) || csDirty()

    /** Only the control-surfaces preview is pending. */
    fun csOnly(state: DspState = bridge.state.value): Boolean =
        csDirty() && !globalDirty(state) && hardware.count == 0 && !outputDirty(state)

    /** Something Revert can undo (live hardware edits made elsewhere can't be). */
    fun canRevert(state: DspState = bridge.state.value): Boolean = globalDirty(state) || hardware.count > 0 || csDirty()

    // ── Hardware draft ──

    /** The device's hardware configuration. */
    fun deviceSnapshot(state: DspState = bridge.state.value): OutputConfigSnapshot = OutputConfigSnapshot.from(state, bridge.platformName)

    /** The configuration the pages show: device + every staged change. */
    fun hardwareDraft(state: DspState = bridge.state.value, pending: List<PendingChange> = hardware.pending.value): OutputConfigSnapshot =
        pending.fold(deviceSnapshot(state)) { c, change -> change.patch(c) }

    /** The pin authority over the draft (macOS `vm.pinAssignment`). */
    fun pinContext(state: DspState = bridge.state.value, pending: List<PendingChange> = hardware.pending.value): PinContext =
        PinContext.from(hardwareDraft(state, pending), state, if (bridge.connected) bridge.pinOwners() else emptyList())

    fun stage(change: PendingChange) {
        _lastResult.value = null
        hardware.stage(change)
    }

    fun stage(changes: List<PendingChange>) = changes.forEach { stage(it) }

    // ── Global draft editing ──

    /** Edit a global field; marks the draft user-edited (macOS `draftBinding`). */
    fun editGlobal(transform: (GlobalSettingsDraft) -> GlobalSettingsDraft) {
        val base = globalDraft()
        storedDraft.value = transform(base)
        _globalUserEdited.value = true
        _lastResult.value = null
    }

    // ── Pending list (save bar) ──

    /** One line per pending change, for the save bar's list (Windows InfoBar expander). */
    fun pendingSummary(state: DspState = bridge.state.value): List<String> {
        val out = ArrayList<String>()
        if (globalDirty(state)) {
            val d = storedDraft.value
            val s = GlobalSettingsDraft.from(state)
            if (d.presetStartupMode !=
                s.presetStartupMode
            ) {
                out += "Startup preset: ${startupName(s.presetStartupMode)} → ${startupName(d.presetStartupMode)}"
            }
            if (d.presetStartupMode == 0 &&
                d.presetDefaultSlot != s.presetDefaultSlot
            ) {
                out += "Default preset: ${s.presetDefaultSlot + 1} → ${d.presetDefaultSlot + 1}"
            }
            if (d.presetMasterVolumeMode !=
                s.presetMasterVolumeMode
            ) {
                out +=
                    "Master volume: ${modeName(s.presetMasterVolumeMode)} → ${modeName(d.presetMasterVolumeMode)}"
            }
            if (d.presetOutputConfigMode !=
                s.presetOutputConfigMode
            ) {
                out +=
                    "Hardware configuration: ${modeName(s.presetOutputConfigMode)} → ${modeName(d.presetOutputConfigMode)}"
            }
            if (d.dacHwMuteConfig != s.dacHwMuteConfig) out += "External mute control"
        }
        hardware.pending.value.forEach { out += "${it.fieldLabel}: ${it.oldDisplay} → ${it.newDisplay}" }
        if (outputDirty(state)) out += "Output configuration changed on the device (not yet saved to flash)"
        if (csDirty()) out += "Control surfaces (live now, not yet saved)"
        return out
    }

    // ── Save / Revert ──

    /** The save bar's Save. */
    fun onSave(): Job = scope.launch { save() }

    /** The save bar's Revert. */
    fun onRevert(): Job = scope.launch { revert() }

    /**
     * Global writes, then the hardware batch (HARDWARE_APPLY), then Save Output
     * Configuration in independent mode (SAVE_OUTPUT_CONFIG), then the control
     * surfaces (CS_SAVE) — macOS order, hardware apply inserted where macOS's
     * live edits would already have landed. Stops if the device changes.
     */
    suspend fun save(): SaveResult? {
        val state = bridge.state.value
        val doGlobal = globalDirty(state)
        val doHardware = hardware.count > 0
        val doCs = csDirty()
        if (!doGlobal && !doHardware && !outputDirty(state) && !doCs) return null
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
            val finalMode = if (doGlobal) pending.presetOutputConfigMode else device.presetOutputConfigMode
            var applied = 0
            if (doHardware && stillCurrent()) {
                val token = bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
                try {
                    val report = hardware.applyAll(token)
                    applied = report.applied
                    report.failures.forEach { (c, why) -> messages += "${c.fieldLabel}: $why" }
                } finally {
                    bridge.dangerousOps.revoke(token)
                }
            }
            val doOutput = finalMode == GlobalSettingsDraft.OUTPUT_CONFIG_MODE_INDEPENDENT &&
                (applied > 0 || bridge.hardwareUnsaved.value)
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
            if (stillCurrent()) _globalUserEdited.value = false
            return SaveResult(messages).also { _lastResult.value = it }
        } finally {
            if (doCs) endCsOperation()
            _saving.value = false
        }
    }

    suspend fun revert() {
        val state = bridge.state.value
        if (globalDirty(state) || _globalUserEdited.value) _globalUserEdited.value = false
        hardware.discardAll()
        _lastResult.value = null
        if (csDirty()) {
            beginCsOperation()
            try {
                bridge.cs.revert()
            } finally {
                endCsOperation()
                _csReloadToken.value += 1
            }
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
     * The selection moved: pending edits belong to the old device and are
     * discarded; a re-plug of the same unit keeps them (macOS `noteSelectedDevice`).
     */
    fun noteSelectedDevice(serial: String) {
        if (serial.isEmpty()) return
        val last = lastDeviceSerial
        lastDeviceSerial = serial
        if (last != null && last != serial) resetForNewDevice()
    }

    private fun resetForNewDevice() {
        _globalUserEdited.value = false
        hardware.discardAll()
        _lastResult.value = null
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

// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SettingsSaveCoordinator`.
// The output-config half (outputConfigDirty, outputBaseline, beginOutputEdit,
// the revert sequence) lives in the bridge (decision F2): this class keeps the
// global draft, the control-surfaces operation gate, the save/revert
// sequences and the hardware controls' apply (E1b gating, E9 scope).
package se.bofr.dspi.feature.settings

import android.util.Log
import androidx.compose.runtime.mutableStateOf
import java.lang.ref.WeakReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.bofr.dspi.bridge.ConfirmationToken
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DangerousOpNotConfirmed
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspState
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.ui.json.Json
import se.bofr.dspi.ui.json.bool
import se.bofr.dspi.ui.json.int
import se.bofr.dspi.ui.json.str

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
    // Snapshot state, not a StateFlow: globalDraft()/globalDirty()/pendingSummary() are read during
    // composition (Global tab, save bar, sidebar dot) and must recompose on EVERY edit, not only the
    // first one that flips globalUserEdited (reviews/stale-state-audit.md S2).
    private val storedDraft = mutableStateOf(GlobalSettingsDraft.from(bridge.state.value))
    private val _globalUserEdited = MutableStateFlow(false)
    val globalUserEdited: StateFlow<Boolean> = _globalUserEdited.asStateFlow()

    // The same flag as snapshot state, for the composition-time readers below (S2).
    private val userEdited = mutableStateOf(false)

    private fun setUserEdited(on: Boolean) {
        userEdited.value = on
        _globalUserEdited.value = on
    }

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
        if (userEdited.value) storedDraft.value else GlobalSettingsDraft.from(state)

    fun globalDirty(state: DspState = bridge.state.value): Boolean =
        userEdited.value && storedDraft.value != GlobalSettingsDraft.from(state)

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
        if (changes.none { it.oldDisplay != it.newDisplay }) return null
        return launchHardware(successText) { changes }
    }

    /**
     * As [applyHardware], but [build] runs once every earlier hardware sequence
     * has finished, against the device's LATEST config and pins — not the
     * snapshot the page was composed with — so a second pick made before the
     * first one landed diffs against what the first one did
     * (reviews/stale-state-audit.md S3: S/PDIF 1 -> 3 -> 2 must end on 2).
     *
     * Unlike [applyHardware], it cannot know before [build] runs whether
     * anything differs, so it always mints a HARDWARE_APPLY token at the tap
     * (E1b) and launches a job; a no-op pick takes the lock, makes no device
     * call and revokes the token. [DspiBridge.beginOutputEdit] likewise runs
     * inside the job, after the earlier sequences, and only when the diff is
     * non-empty, so `outputConfigDirty` flips a little later than with the
     * list form (review-stalestate #4). A token that idled out while queued
     * ends the sequence with [CONFIRMATION_TIMED_OUT] instead of a crash.
     * There is deliberately no per-call timeout: the macOS client has none
     * (its USB transfers carry their own), and an expiry mid-sequence is
     * reported the same way.
     */
    fun applyHardwareLatest(successText: String? = null, build: (OutputConfigSnapshot, PinContext) -> List<HardwareChange>): Job =
        launchHardware(successText) {
            val state = bridge.state.value
            build(deviceSnapshot(state), pinContext(state))
        }

    /** Hardware sequences run one at a time, in tap order. */
    private val hardwareLock = Mutex()

    private fun launchHardware(successText: String?, build: () -> List<HardwareChange>): Job {
        // E1b: minted by the tap; the sequence may then wait behind earlier ones (hardwareLock)
        val token = bridge.dangerousOps.confirm(DangerousOp.HARDWARE_APPLY)
        // macOS transport.generation: a re-plug, even of the same unit, aborts the sequence
        val generation = bridge.deviceGeneration.value
        return scope.launch(hardwareGuard) {
            try {
                hardwareLock.withLock {
                    // The token idles out after DangerousOps.ttlMs; a long queue of earlier sequences
                    // can outlast it. Not an error: ask for the pick again (review-stalestate #1).
                    if (!bridge.dangerousOps.isValid(token)) {
                        _hardwareStatus.value = CONFIRMATION_TIMED_OUT to true
                        return@launch
                    }
                    val todo = HardwareChangeOrder.topoSort(build().filter { it.oldDisplay != it.newDisplay })
                    if (todo.isEmpty()) return@launch
                    bridge.beginOutputEdit()
                    for (c in todo) {
                        if (bridge.deviceGeneration.value != generation) return@launch
                        val st = try {
                            c.apply(token)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: DangerousOpNotConfirmed) {
                            // Never a plain "Failed to set" row (review S6), and never a crash of the
                            // launched job (review-stalestate #1): an expiry mid-sequence asks again,
                            // anything else is a programming error, logged and shown as such.
                            _hardwareStatus.value = notConfirmed(c, token, e) to true
                            return@launch
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
                }
            } finally {
                bridge.dangerousOps.revoke(token)
            }
        }
    }

    /**
     * Last line of defence for a hardware job (in production scopes, whose
     * SupervisorJob lets a child's handler run): log and show, never crash.
     */
    private val hardwareGuard = CoroutineExceptionHandler { _, e ->
        logError("hardware change failed", e)
        _hardwareStatus.value = "Hardware change failed: ${e.message ?: e::class.simpleName}" to true
    }

    private fun notConfirmed(c: HardwareChange, token: ConfirmationToken, e: DangerousOpNotConfirmed): String {
        if (!bridge.dangerousOps.isValid(token)) return CONFIRMATION_TIMED_OUT
        logError("${c.fieldLabel}: hardware change refused by the confirmation gate", e)
        return "${c.fieldLabel} not applied: confirmation refused (internal error)"
    }

    fun applyHardware(change: HardwareChange): Job? = applyHardware(listOf(change))

    // ── Global draft editing ──

    /** Edit a global field; marks the draft user-edited (macOS `draftBinding`). Works offline. */
    fun editGlobal(transform: (GlobalSettingsDraft) -> GlobalSettingsDraft) {
        storedDraft.value = transform(globalDraft())
        setUserEdited(true)
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
                if (stillCurrent()) setUserEdited(false)
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
            if (globalDirty()) setUserEdited(false)
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
                val r = Json.parseObject(bridge.cs.apply(opJson))
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
            setUserEdited(false)
            _lastResult.value = null
            _hardwareStatus.value = null
            _csStatus.value = null
        }
    }

    companion object {
        const val CONFIRMATION_TIMED_OUT = "Not applied: the confirmation timed out. Pick again."

        private fun logError(msg: String, e: Throwable) {
            runCatching { Log.e("DspiSettings", msg, e) }
        }

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

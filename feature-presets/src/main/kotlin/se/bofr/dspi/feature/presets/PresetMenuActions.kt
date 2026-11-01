// Port of DSPi-Console (macOS) `DSPi Console/ContentView.swift` @ v1.1.6-beta5:
// the preset picker's selection closure (Unsaved Changes flow, "Save Failed" /
// "Load Failed"), `saveActivePreset`, `copyActivePreset(to:)`,
// `showPresetClearConfirmation`, `showPresetClearAllConfirmation`, the Rename
// sheet and "Set as Default" (`PresetContextMenuItems`), following
// `unsaved_changes_spec.md`. Linux `qt/qml/components/PresetMenu.qml` and
// `main.qml` withUnsaved/switchPreset for how a GUI drives this core.
//
// Gating (decision E1): load is ungated and guarded by the Unsaved Changes
// flow only. Save / Copy / Clear are GATED; the token is minted by the user
// action macOS uses: the Save / Copy-to tap (no extra alert, as macOS), the
// Unsaved Changes handler, or the Clear alerts' confirm handler.
// Decision E9: device sequences run via launchDeviceSequence on the
// controller's (ViewModel) scope, so a configuration change can't stop them
// between flash writes.
package se.bofr.dspi.feature.presets

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.ConfirmationToken
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.ui.alert.AlertButton
import se.bofr.dspi.ui.alert.AlertButtonRole
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.AlertStyle
import se.bofr.dspi.ui.alert.AlertTextField
import se.bofr.dspi.ui.alert.ConsoleAlert

class PresetMenuActions(private val bridge: DspiBridge, private val scope: CoroutineScope, private val host: AlertHost) {
    /** ContentView `presetSwitchInFlight`: the picker ignores input while a switch runs. */
    private val _presetSwitchInFlight = MutableStateFlow(false)
    val presetSwitchInFlight: StateFlow<Boolean> = _presetSwitchInFlight.asStateFlow()

    /** `vm.isDeviceConnected && vm.hasUnsavedChanges`. */
    val hasUnsavedChanges: Boolean get() = bridge.connected && bridge.presetDirty.value

    fun presetLabel(slot: Int) = presetLabel(slot, bridge.isPresetOccupied(slot), bridge.presetName(slot))

    /** Saving an unnamed slot gives it "Preset N", so the picker stops showing it as "Empty". */
    private fun ensureName(slot: Int) {
        if (bridge.presetName(slot).isEmpty()) bridge.setPresetName(slot, defaultPresetName(slot))
    }

    /**
     * Runs the Unsaved Changes flow before an action that would lose the live
     * changes: [proceed] gets null when there is nothing unsaved, else SAVE or
     * DISCARD; CANCEL runs [onCancel]. The diff is fetched from the core.
     */
    fun withUnsavedChanges(onCancel: () -> Unit = {}, proceed: (UnsavedChangesAction?) -> Unit) {
        if (!hasUnsavedChanges) {
            proceed(null)
            return
        }
        scope.launch {
            val diff = PresetDiff(bridge.presetChanges())
            host.show(
                PresetAlerts.unsavedChangesAlert(diff) { action ->
                    if (action == UnsavedChangesAction.CANCEL) onCancel() else proceed(action)
                }
            )
        }
    }

    // ═══ Preset picker selection ═════════════════════════════════════════

    private fun showLoadFailed(status: PresetStatus) {
        host.showWarning(
            "Load Failed",
            if (status == PresetStatus.CRC) "Preset data is corrupted." else "Failed to load preset (error ${presetStatusCode(status)})."
        )
    }

    /** The picker's `selection` setter: switch to [slot], asking about unsaved changes first. */
    fun selectPreset(slot: Int) {
        val previousSlot = bridge.activePresetSlot
        if (slot == previousSlot) return
        if (_presetSwitchInFlight.value) return
        if (!bridge.connected) return
        _presetSwitchInFlight.value = true
        withUnsavedChanges(onCancel = { _presetSwitchInFlight.value = false }) { action ->
            // Save: the token is minted here, in the Unsaved Changes alert's Save handler.
            val token = if (action == UnsavedChangesAction.SAVE) bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET) else null
            scope.launchDeviceSequence {
                try {
                    if (token != null) {
                        ensureName(previousSlot)
                        val saveStatus = bridge.savePreset(token, previousSlot)
                        if (saveStatus != PresetStatus.OK) {
                            host.showWarning(
                                "Save Failed",
                                "Failed to save preset (${saveStatus.failureText}). Preset switch aborted."
                            )
                            return@launchDeviceSequence
                        }
                    }
                    val status = bridge.loadPreset(slot)
                    if (status != PresetStatus.OK) showLoadFailed(status)
                } finally {
                    _presetSwitchInFlight.value = false
                }
            }
        }
    }

    // ═══ Context menu (PresetContextMenuItems) ═══════════════════════════

    /**
     * "Save": as macOS, the menu tap itself saves (decision E1: the explicit tap
     * mints SAVE_PRESET); only a failure is reported.
     */
    fun saveActivePreset() {
        if (!bridge.connected) return
        val slot = bridge.activePresetSlot
        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
        scope.launchDeviceSequence {
            ensureName(slot)
            val status = bridge.savePreset(token, slot)
            if (status != PresetStatus.OK) host.showWarning("Save Failed", "Failed to save preset (${status.failureText}).")
        }
    }

    /** "Rename…": the Rename Preset sheet (Name field, Cancel / Rename). */
    fun renameActivePreset() {
        val slot = bridge.activePresetSlot
        host.show(
            ConsoleAlert(
                "Rename Preset",
                "",
                AlertStyle.INFORMATIONAL,
                listOf(
                    AlertButton("Rename", AlertButtonRole.DEFAULT) { r ->
                        // Flash write (directory), ungated (D3)
                        bridge.setPresetName(slot, r.text.trim().take(PRESET_NAME_MAX_LENGTH))
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                ),
                textField = AlertTextField(placeholder = "Name", initialText = bridge.presetName(slot))
            )
        )
    }

    /** Whether "Set as Default" is unavailable (the slot already is the startup default). */
    val isActiveDefault: Boolean
        get() = bridge.presetStartupMode == PRESET_STARTUP_SPECIFIED && bridge.presetDefaultSlot == bridge.activePresetSlot

    /** "Set as Default": startup mode Specified Default, this slot. Flash write, ungated (D3). */
    fun setActiveAsDefault() {
        if (isActiveDefault) return
        bridge.setPresetStartup(PRESET_STARTUP_SPECIFIED, bridge.activePresetSlot)
    }

    /**
     * "Copy to…": copies the live settings into [destinationSlot], keeping the
     * source active (copyPreset re-saves the source). As macOS: when dirty, the
     * Unsaved Changes alert first (Save saves the source, Discard reloads it);
     * then the copy. Decision E1: COPY_PRESET (and SAVE_PRESET on Save) is
     * minted by the destination tap, or by the Unsaved Changes handler.
     */
    fun copyActivePreset(destinationSlot: Int) {
        val sourceSlot = bridge.activePresetSlot
        if (destinationSlot == sourceSlot || !bridge.connected) return
        withUnsavedChanges { action ->
            val saveToken = if (action == UnsavedChangesAction.SAVE) bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET) else null
            runCopy(sourceSlot, destinationSlot, action, bridge.dangerousOps.confirm(DangerousOp.COPY_PRESET), saveToken)
        }
    }

    private fun runCopy(
        sourceSlot: Int,
        destinationSlot: Int,
        preOp: UnsavedChangesAction?,
        copyToken: ConfirmationToken,
        saveToken: ConfirmationToken? = null
    ) {
        scope.launchDeviceSequence {
            // Pre-op: align the live state with the source slot.
            when (preOp) {
                UnsavedChangesAction.SAVE -> {
                    ensureName(sourceSlot)
                    if (saveToken != null) bridge.savePreset(saveToken, sourceSlot)
                }
                UnsavedChangesAction.DISCARD -> bridge.loadPreset(sourceSlot)
                else -> Unit
            }
            // The destination keeps its name, or becomes "Preset N".
            ensureName(destinationSlot)
            val status = bridge.copyPreset(copyToken, sourceSlot, destinationSlot)
            if (status != PresetStatus.OK) host.showWarning("Copy Failed", "Failed to copy preset (${status.failureText}).")
        }
    }

    /** "Clear "N: name"…": erase the slot; the active slot is reloaded to apply factory defaults. */
    fun clearPreset(slot: Int) {
        host.show(
            ConsoleAlert(
                "Clear Preset?",
                "Clear \"${presetLabel(slot)}\" and restore factory defaults? This cannot be undone.",
                AlertStyle.CRITICAL,
                listOf(
                    AlertButton("Clear", AlertButtonRole.DESTRUCTIVE) {
                        val token = bridge.dangerousOps.confirm(DangerousOp.DELETE_PRESET)
                        scope.launchDeviceSequence {
                            val status = bridge.deletePreset(token, slot)
                            if (status == PresetStatus.OK) {
                                if (bridge.activePresetSlot == slot) bridge.loadPreset(slot)
                            } else {
                                host.showWarning("Clear Failed", "Failed to clear preset (error ${presetStatusCode(status)}).")
                            }
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }

    /** "Clear All Slots…": erase every occupied slot, then reload the active one. */
    fun clearAllPresets() {
        host.show(
            ConsoleAlert(
                "Clear All Presets?",
                "This will erase all preset data and names, restoring every slot to factory defaults. This cannot be undone.",
                AlertStyle.CRITICAL,
                listOf(
                    AlertButton("Clear All", AlertButtonRole.DESTRUCTIVE) {
                        // One confirmation covers every slot: one single-use token per delete, minted here.
                        val toDelete = (0 until PRESET_SLOT_COUNT).filter { bridge.isPresetOccupied(it) }
                        val tokens = toDelete.associateWith { bridge.dangerousOps.confirm(DangerousOp.DELETE_PRESET) }
                        scope.launchDeviceSequence {
                            for (slot in toDelete) bridge.deletePreset(tokens.getValue(slot), slot)
                            bridge.loadPreset(bridge.activePresetSlot)
                        }
                    },
                    AlertButton("Cancel", AlertButtonRole.CANCEL)
                )
            )
        )
    }
}

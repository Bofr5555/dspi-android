// Port of DSPi-Console-b5:DSPi Console/ContentView.swift @ b5fdeac: the
// sidebar Preset picker (selection set closure with the Unsaved Changes
// flow), saveActivePreset, copyActivePreset, showPresetClearConfirmation,
// showPresetClearAllConfirmation, the Rename Preset sheet and
// PresetContextMenuItems; PresetSnapshot.swift PresetAlerts wording.
// Gated flash writes (D1) mint their token in the confirming dialog's button.
package se.bofr.dspi.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DangerousOp
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.PresetStatus
import se.bofr.dspi.ui.AlertButton
import se.bofr.dspi.ui.DspiAlertDialog
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.PopUpButton

/** What a pending Unsaved Changes alert continues with. */
sealed interface PresetIntent {
    data class Switch(val slot: Int) : PresetIntent

    data class CopyTo(val dest: Int) : PresetIntent
}

/** The dialog the preset flows are showing. */
sealed interface PresetDialog {
    data class Unsaved(val intent: PresetIntent, val changes: List<String>) : PresetDialog

    data class ConfirmClear(val slot: Int) : PresetDialog

    data object ConfirmClearAll : PresetDialog

    data class Rename(val slot: Int, val initial: String) : PresetDialog

    data class Failure(val title: String, val message: String) : PresetDialog
}

/**
 * The preset flows of the sidebar picker and its context menu, as macOS
 * runs them (ContentView.swift). Device calls run in [scope]; every gated
 * call takes a token minted in the dialog button that confirmed it.
 */
@Stable
class PresetFlows(private val bridge: DspiBridge, private val scope: CoroutineScope) {
    var dialog by mutableStateOf<PresetDialog?>(null)
        private set

    /** `presetSwitchInFlight`. */
    var switchInFlight by mutableStateOf(false)
        private set

    private val active get() = bridge.activePresetSlot

    fun label(slot: Int) = HomeModel.presetLabel(slot, bridge.isPresetOccupied(slot), bridge.presetName(slot))

    private fun fail(title: String, message: String) {
        dialog = PresetDialog.Failure(title, message)
    }

    fun dismiss() {
        if (dialog is PresetDialog.Unsaved) switchInFlight = false
        dialog = null
    }

    /** Name an unnamed slot "Preset N" before saving into it (macOS does it before every save). */
    private fun nameIfEmpty(slot: Int) {
        if (bridge.presetName(slot).isEmpty()) bridge.setPresetName(slot, "Preset ${slot + 1}")
    }

    // ── Switching (the picker's selection setter) ──

    fun switchTo(slot: Int) {
        val previous = active
        if (slot == previous || switchInFlight) return
        switchInFlight = true
        if (bridge.connected && bridge.presetDirty.value) {
            scope.launch { dialog = PresetDialog.Unsaved(PresetIntent.Switch(slot), bridge.presetChanges()) }
        } else {
            scope.launch { load(slot) }
        }
    }

    private suspend fun load(slot: Int) {
        val status = bridge.loadPreset(slot)
        if (status != PresetStatus.OK) fail("Load Failed", HomeModel.loadFailedMessage(status))
        switchInFlight = false
    }

    /** Unsaved Changes -> Save (mints SAVE_PRESET, plus COPY_PRESET for a copy). */
    fun unsavedSave(intent: PresetIntent) {
        val saveToken = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
        val copyToken = if (intent is PresetIntent.CopyTo) bridge.dangerousOps.confirm(DangerousOp.COPY_PRESET) else null
        val source = active
        dialog = null
        scope.launch {
            nameIfEmpty(source)
            val saveStatus = bridge.savePreset(saveToken, source)
            when (intent) {
                is PresetIntent.Switch -> {
                    if (saveStatus != PresetStatus.OK) {
                        fail("Save Failed", "Failed to save preset (${HomeModel.presetFailureText(saveStatus)}). Preset switch aborted.")
                        switchInFlight = false
                    } else {
                        load(intent.slot)
                    }
                }
                is PresetIntent.CopyTo -> copy(copyToken!!, source, intent.dest)
            }
        }
    }

    /** Unsaved Changes -> Discard. */
    fun unsavedDiscard(intent: PresetIntent) {
        val copyToken = if (intent is PresetIntent.CopyTo) bridge.dangerousOps.confirm(DangerousOp.COPY_PRESET) else null
        val source = active
        dialog = null
        scope.launch {
            when (intent) {
                is PresetIntent.Switch -> load(intent.slot)
                is PresetIntent.CopyTo -> {
                    bridge.loadPreset(source)
                    copy(copyToken!!, source, intent.dest)
                }
            }
        }
    }

    // ── Context menu ──

    /** "Save": macOS saves on the menu tap itself (E1), so the tap mints the token. */
    fun save() {
        val slot = active
        val token = bridge.dangerousOps.confirm(DangerousOp.SAVE_PRESET)
        dialog = null
        scope.launch {
            nameIfEmpty(slot)
            val status = bridge.savePreset(token, slot)
            if (status != PresetStatus.OK) fail("Save Failed", "Failed to save preset (${HomeModel.presetFailureText(status)}).")
        }
    }

    fun requestRename() {
        dialog = PresetDialog.Rename(active, bridge.presetName(active))
    }

    fun rename(slot: Int, name: String) {
        dialog = null
        bridge.setPresetName(slot, name.trim())
    }

    /** "Set as Default": startup mode 0 (specified) with this slot. Ungated (D3). */
    fun setDefault() {
        bridge.setPresetStartup(0, active)
    }

    /** "Copy to…": the Unsaved Changes alert when dirty, otherwise straight away. */
    fun requestCopy(dest: Int) {
        if (dest == active) return
        if (bridge.connected && bridge.presetDirty.value) {
            scope.launch { dialog = PresetDialog.Unsaved(PresetIntent.CopyTo(dest), bridge.presetChanges()) }
        } else {
            // No alert on macOS: the menu tap mints the token (E1).
            val token = bridge.dangerousOps.confirm(DangerousOp.COPY_PRESET)
            val source = active
            scope.launch { copy(token, source, dest) }
        }
    }

    private suspend fun copy(token: se.bofr.dspi.bridge.ConfirmationToken, source: Int, dest: Int) {
        // The destination keeps its identity; only an unnamed one gets a default name.
        nameIfEmpty(dest)
        val status = bridge.copyPreset(token, source, dest)
        if (status != PresetStatus.OK) fail("Copy Failed", "Failed to copy preset (${HomeModel.presetFailureText(status)}).")
    }

    fun requestClear() {
        dialog = PresetDialog.ConfirmClear(active)
    }

    fun confirmClear(slot: Int) {
        val token = bridge.dangerousOps.confirm(DangerousOp.DELETE_PRESET)
        dialog = null
        scope.launch {
            val status = bridge.deletePreset(token, slot)
            if (status == PresetStatus.OK) {
                // A cleared active slot is reloaded to apply factory defaults.
                if (active == slot) bridge.loadPreset(slot)
            } else {
                fail("Clear Failed", "Failed to clear preset (error ${status.code}).")
            }
        }
    }

    fun requestClearAll() {
        dialog = PresetDialog.ConfirmClearAll
    }

    fun confirmClearAll() {
        val slots = (0 until DspiConstants.MAX_PRESETS).filter { bridge.isPresetOccupied(it) }
        val tokens = slots.map { it to bridge.dangerousOps.confirm(DangerousOp.DELETE_PRESET) }
        dialog = null
        scope.launch {
            for ((slot, token) in tokens) bridge.deletePreset(token, slot)
            bridge.loadPreset(active)
        }
    }
}

@Composable
fun rememberPresetFlows(bridge: DspiBridge): PresetFlows {
    val scope = rememberCoroutineScope()
    return remember(bridge) { PresetFlows(bridge, scope) }
}

/**
 * The sidebar "Preset" row: picker (active slot marked "*" while dirty),
 * long-press or ⋯ for the context menu (macOS right-click).
 */
@Composable
fun PresetPickerRow(bridge: DspiBridge, flows: PresetFlows, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val dirty = bridge.observeDirty()
    val connected = bridge.observeStatus { it.connected }
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Preset", style = DspiType.caption2, color = c.secondaryLabel)
        Spacer(Modifier.weight(1f))
        PopUpButton(
            items = (0 until DspiConstants.MAX_PRESETS).toList(),
            selection = s.activePresetSlot,
            titleForItem = { slot ->
                HomeModel.presetPickerTitle(slot, bridge.isPresetOccupied(slot), bridge.presetName(slot), s.activePresetSlot, dirty)
            },
            onSelect = flows::switchTo,
            enabled = connected && !flows.switchInFlight,
            onLongClick = { menuOpen = true }
        )
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = connected, modifier = Modifier.size(36.dp)) {
                Icon(DspiIcons.more, contentDescription = "Preset actions", tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
            }
            PresetContextMenuItems(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                activeSlot = s.activePresetSlot,
                slotLabels = (0 until DspiConstants.MAX_PRESETS).map(flows::label),
                isCurrentOccupied = bridge.isPresetOccupied(s.activePresetSlot),
                anySlotOccupied = s.presetOccupied != 0,
                isStartupSpecified = s.presetStartupMode == 0,
                defaultSlot = s.presetDefaultSlot,
                isDeviceConnected = connected,
                onSave = flows::save,
                onRename = flows::requestRename,
                onSetDefault = flows::setDefault,
                onCopyTo = flows::requestCopy,
                onClearActive = flows::requestClear,
                onClearAll = flows::requestClearAll
            )
        }
    }
    PresetDialogs(flows)
}

/** The preset context menu (macOS `PresetContextMenuItems`); "Copy to…" opens its slot list in place. */
@Composable
fun PresetContextMenuItems(
    expanded: Boolean,
    onDismiss: () -> Unit,
    activeSlot: Int,
    slotLabels: List<String>,
    isCurrentOccupied: Boolean,
    anySlotOccupied: Boolean,
    isStartupSpecified: Boolean,
    defaultSlot: Int,
    isDeviceConnected: Boolean,
    onSave: () -> Unit,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onCopyTo: (Int) -> Unit,
    onClearActive: () -> Unit,
    onClearAll: () -> Unit
) {
    val c = DspiTheme.colors
    var copyOpen by remember { mutableStateOf(false) }
    fun run(action: () -> Unit) {
        copyOpen = false
        onDismiss()
        action()
    }
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = {
            copyOpen = false
            onDismiss()
        }
    ) {
        if (copyOpen) {
            DropdownMenuItem(text = { Text("‹ Copy to…", color = c.secondaryLabel) }, onClick = { copyOpen = false })
            HorizontalDivider()
            for (dest in slotLabels.indices) {
                if (dest != activeSlot) DropdownMenuItem(text = { Text(slotLabels[dest]) }, onClick = { run { onCopyTo(dest) } })
            }
        } else {
            DropdownMenuItem(text = { Text("Save") }, onClick = { run(onSave) }, enabled = isDeviceConnected)
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { run(onRename) }, enabled = isDeviceConnected)
            DropdownMenuItem(
                text = { Text("Set as Default") },
                onClick = { run(onSetDefault) },
                enabled = isDeviceConnected && !(isStartupSpecified && defaultSlot == activeSlot)
            )
            DropdownMenuItem(
                text = { Text("Copy to…") },
                trailingIcon = { Text("▸", color = c.secondaryLabel) },
                onClick = { copyOpen = true },
                enabled = isDeviceConnected
            )
            if (isCurrentOccupied) {
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Clear \"${slotLabels[activeSlot]}\"…", color = c.red) },
                    onClick = { run(onClearActive) },
                    enabled = isDeviceConnected
                )
            }
            if (anySlotOccupied) {
                HorizontalDivider()
                DropdownMenuItem(text = {
                    Text("Clear All Slots…", color = c.red)
                }, onClick = { run(onClearAll) }, enabled = isDeviceConnected)
            }
        }
    }
}

/** The alerts and the rename sheet of the preset flows, macOS wording. */
@Composable
fun PresetDialogs(flows: PresetFlows) {
    when (val d = flows.dialog) {
        null -> Unit
        is PresetDialog.Unsaved ->
            DspiAlertDialog(
                title = "Unsaved Changes",
                message = HomeModel.unsavedChangesMessage(d.changes),
                buttons =
                listOf(
                    AlertButton("Save") { flows.unsavedSave(d.intent) },
                    AlertButton("Discard", AlertButton.Role.Destructive) { flows.unsavedDiscard(d.intent) },
                    AlertButton("Cancel", AlertButton.Role.Cancel, flows::dismiss)
                ),
                onDismiss = flows::dismiss
            )
        is PresetDialog.ConfirmClear ->
            DspiAlertDialog(
                title = "Clear Preset?",
                message = "Clear \"${flows.label(d.slot)}\" and restore factory defaults? This cannot be undone.",
                buttons =
                listOf(
                    AlertButton("Clear", AlertButton.Role.Destructive) { flows.confirmClear(d.slot) },
                    AlertButton("Cancel", AlertButton.Role.Cancel, flows::dismiss)
                ),
                onDismiss = flows::dismiss
            )
        PresetDialog.ConfirmClearAll ->
            DspiAlertDialog(
                title = "Clear All Presets?",
                message = "This will erase all preset data and names, restoring every slot to factory defaults. This cannot be undone.",
                buttons =
                listOf(
                    AlertButton("Clear All", AlertButton.Role.Destructive) { flows.confirmClearAll() },
                    AlertButton("Cancel", AlertButton.Role.Cancel, flows::dismiss)
                ),
                onDismiss = flows::dismiss
            )
        is PresetDialog.Rename -> RenamePresetSheet(d.initial, onCancel = flows::dismiss, onRename = { flows.rename(d.slot, it) })
        is PresetDialog.Failure ->
            DspiAlertDialog(
                title = d.title,
                message = d.message,
                buttons = listOf(AlertButton("OK", action = flows::dismiss)),
                onDismiss = flows::dismiss
            )
    }
}

/** The "Rename Preset" sheet: a Name field, Cancel / Rename. */
@Composable
private fun RenamePresetSheet(initial: String, onCancel: () -> Unit, onRename: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Rename Preset", style = DspiType.headline) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") })
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { onRename(text) }) { Text("Rename", fontWeight = FontWeight.SemiBold) } }
    )
}

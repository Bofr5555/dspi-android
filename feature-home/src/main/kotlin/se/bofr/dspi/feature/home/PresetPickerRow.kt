// The sidebar Preset row of DSPi-Console-b5:DSPi Console/ContentView.swift
// @ b5fdeac (picker + PresetContextMenuItems). Coordinator F1: the flows
// behind it (Unsaved Changes, Save/Copy/Clear with their tokens, Rename,
// Set as Default, failure alerts) are PRESETS' `PresetMenuActions`; HOME
// keeps only this UI. E9: the controller and its alert slot live in
// [HomeViewModel], so a rotation neither cancels a sequence nor loses an alert.
package se.bofr.dspi.feature.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.feature.presets.PRESET_SLOT_COUNT
import se.bofr.dspi.feature.presets.PresetMenuActions
import se.bofr.dspi.feature.presets.presetPickerTitle
import se.bofr.dspi.ui.DspiIcons
import se.bofr.dspi.ui.DspiTheme
import se.bofr.dspi.ui.DspiType
import se.bofr.dspi.ui.PopUpButton
import se.bofr.dspi.ui.alert.AlertHost
import se.bofr.dspi.ui.alert.ConsoleAlertHost
import se.bofr.dspi.ui.writesAllowed

/** The home screen's controllers that must outlive a configuration change (E9). */
class HomeViewModel(bridge: DspiBridge) : ViewModel() {
    /** The alert slot of the preset flows (NSAlert.runModal). */
    val host = AlertHost()

    /** PRESETS' preset picker / context-menu controller (F1). */
    val presetMenu = PresetMenuActions(bridge, viewModelScope, host)

    companion object {
        fun factory(bridge: DspiBridge): ViewModelProvider.Factory = viewModelFactory { initializer { HomeViewModel(bridge) } }
    }
}

/**
 * The sidebar "Preset" row: picker (the active slot marked "*" while dirty),
 * long-press or ⋯ for the context menu (macOS right-click).
 */
@Composable
fun PresetPickerRow(bridge: DspiBridge, vm: HomeViewModel, modifier: Modifier = Modifier) {
    val c = DspiTheme.colors
    val s = bridge.observeState()
    val dirty = bridge.observeDirty()
    val connected = bridge.observeStatus { it.connected }
    val actions = vm.presetMenu
    val inFlight by actions.presetSwitchInFlight.collectAsStateWithLifecycle()
    var menuOpen by remember { mutableStateOf(false) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Preset", style = DspiType.caption2, color = c.secondaryLabel)
        Spacer(Modifier.weight(1f))
        PopUpButton(
            items = (0 until PRESET_SLOT_COUNT).toList(),
            selection = s.activePresetSlot,
            titleForItem = { slot ->
                presetPickerTitle(slot, bridge.isPresetOccupied(slot), bridge.presetName(slot), slot == s.activePresetSlot, dirty)
            },
            onSelect = actions::selectPreset,
            enabled = connected && !inFlight,
            onLongClick = { if (connected) menuOpen = true }
        )
        Box {
            IconButton(onClick = { menuOpen = true }, enabled = connected, modifier = Modifier.size(36.dp)) {
                Icon(DspiIcons.more, contentDescription = "Preset actions", tint = c.secondaryLabel, modifier = Modifier.size(18.dp))
            }
            PresetContextMenuItems(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                activeSlot = s.activePresetSlot,
                slotLabels = (0 until PRESET_SLOT_COUNT).map(actions::presetLabel),
                isCurrentOccupied = bridge.isPresetOccupied(s.activePresetSlot),
                anySlotOccupied = s.presetOccupied != 0,
                isActiveDefault = actions.isActiveDefault,
                // G1: every preset action writes
                isDeviceConnected = connected && writesAllowed(),
                onSave = actions::saveActivePreset,
                onRename = actions::renameActivePreset,
                onSetDefault = actions::setActiveAsDefault,
                onCopyTo = actions::copyActivePreset,
                onClearActive = { actions.clearPreset(bridge.activePresetSlot) },
                onClearAll = actions::clearAllPresets
            )
        }
    }
    ConsoleAlertHost(vm.host)
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
    isActiveDefault: Boolean,
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
                if (dest != activeSlot) {
                    DropdownMenuItem(text = { Text(slotLabels[dest]) }, onClick = { run { onCopyTo(dest) } }, enabled = isDeviceConnected)
                }
            }
        } else {
            DropdownMenuItem(text = { Text("Save") }, onClick = { run(onSave) }, enabled = isDeviceConnected)
            DropdownMenuItem(text = { Text("Rename…") }, onClick = { run(onRename) }, enabled = isDeviceConnected)
            DropdownMenuItem(
                text = { Text("Set as Default") },
                onClick = { run(onSetDefault) },
                enabled = isDeviceConnected && !isActiveDefault
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

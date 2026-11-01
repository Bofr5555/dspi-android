// Port (render + simple edits) of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift
// `ControlSurfacesSettingsTab` (sections .controls / .groups / .macros / .aux),
// over the core's cs_snapshot model (CsController). Edits are live previews
// the device marks dirty; the save bar's Save persists them (CS_SAVE) and
// Revert discards them (cs.revert, ungated D1). The full binding editor
// (component/function/target pickers, IR learn, display pages, group and
// macro editors) is not ported yet.
package se.bofr.dspi.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge

/** Which Control page (macOS `ControlSurfacesSettingsTab.Section`). */
enum class CsSection { CONTROLS, GROUPS, MACROS, AUX }

@Composable
fun ControlSurfacesSettingsTab(section: CsSection, bridge: DspiBridge, coordinator: SettingsSaveCoordinator, cs: CsModel) {
    val connected = bridge.status.collectAsState().value.connected
    val loaded by bridge.cs.loaded.collectAsState()
    val busy by coordinator.csBusy.collectAsState()
    val scope = rememberCoroutineScope()
    var renaming by remember { mutableStateOf<Int?>(null) }
    var status by remember { mutableStateOf<Pair<String, Boolean>?>(null) }

    /** Run one deferred operation, serialised on the shared status channel. */
    fun op(json: String) {
        coordinator.beginCsOperation()
        scope.launch {
            try {
                val r = CsJson.parseObject(bridge.cs.apply(json))
                val ok = r?.bool("ok") == true
                status = if (ok) null else (r?.str("error") ?: bridge.cs.statusMessage(r?.int("status") ?: 0xFF)) to true
            } finally {
                coordinator.endCsOperation()
            }
        }
    }

    SettingsForm {
        when {
            !connected -> {
                item { SettingsSection(null) { Caption("No Device Connected. Control surfaces live on the device.") } }
                return@SettingsForm
            }
            !loaded -> {
                item { SettingsSection(null) { Caption("Reading control-surface capabilities from the device...") } }
                return@SettingsForm
            }
            !cs.supported -> {
                item { SettingsSection(null) { Caption("The connected firmware has no control surfaces.") } }
                return@SettingsForm
            }
        }
        if (status != null) item { StatusLine(status?.first, status?.second ?: false) }
        when (section) {
            CsSection.CONTROLS -> {
                val slots = cs.bindings.withIndex().filter { it.value.configured && !CsModel.isAuxType(it.value.type) }
                item {
                    SettingsSection("Controls") {
                        if (slots.isEmpty()) Caption("No Controls Configured")
                        slots.forEach { (slot, b) ->
                            val name = cs.slotName(slot).ifEmpty { CsModel.typeName(b.type) }
                            SettingRow(
                                name,
                                "${CsModel.typeName(
                                    b.type
                                )} · ${CsModel.nounName(b.noun, b.type)} · ${CsModel.actionName(b.action, b.noun)} · ${gpioText(b)}",
                                onClick = { renaming = slot }
                            ) { SlotPill(cs, slot) }
                        }
                    }
                }
                item {
                    Caption(
                        "Buttons and switches wire between the GPIO and GND (internal pull-up); pots use an ADC pin (GPIO 26, 27, or 28) " +
                            "between " +
                            "3V3 and GND; encoders use two GPIOs with the common wired to GND. LEDs drive active-high by default. An IR " +
                            "receiver " +
                            "module's OUT pin connects to any GPIO, and its remote buttons are learned by pressing them at the " +
                            "device.\n\nThis wiring " +
                            "is a board-level setting: it is stored on the device, survives preset changes, and survives a factory " +
                            "reset. Applied " +
                            "changes work at once; Save keeps them across a restart." +
                            if (cs.capsVersion != 0) "\n\nControl-surface capability version ${cs.capsVersion}." else ""
                    )
                }
            }
            CsSection.GROUPS -> {
                val groups = cs.groups.withIndex().filter { it.value.configured }
                item {
                    SettingsSection("Channel Groups") {
                        if (groups.isEmpty()) Caption("No Channel Groups Configured")
                        groups.forEach { (g, grp) ->
                            val kind = when (grp.targetKind) {
                                1 -> "Inputs"
                                2 -> "Outputs"
                                else -> "All Channels"
                            }
                            val users = cs.bindings.count { it.configured && (it.flags and 0x20) != 0 && it.target == g }
                            SettingRow(
                                cs.groupName(g),
                                "$kind · ${java.lang.Long.bitCount(
                                    grp.memberMask
                                )} channels · Used by $users control${if (users == 1) "" else "s"}"
                            )
                        }
                    }
                }
                item {
                    Caption(
                        "A group is a named set of channels one control can drive as a unit: mute a zone, trim a stereo pair, light an " +
                            "LED when any " +
                            "member clips. Turn on \"Address a Group\" in a control, or in a macro step, to use one.\n\nRelative moves " +
                            "(an encoder, a " +
                            "button) step every member from its own value, so the balance between them survives. A knob moves the " +
                            "group's average and " +
                            "keeps those offsets unless \"Match Members Exactly\" is on.\n\nGroups are stored on the device alongside " +
                            "the controls and " +
                            "share their Save and Revert."
                    )
                }
            }
            CsSection.MACROS -> {
                val macros = cs.macros.withIndex().filter { it.value.configured }
                item {
                    SettingsSection("Macros") {
                        if (macros.isEmpty()) Caption("No Macros Configured")
                        macros.forEach { (i, m) ->
                            val running = cs.macroRunning == i
                            SettingRow(
                                cs.macroName(i),
                                "${m.steps.size} step${if (m.steps.size == 1) "" else "s"}" + if (running) " · Running" else ""
                            ) {
                                // Firing a macro is one click (D1), never a flash write
                                OutlinedButton(onClick = { op(CsModel.macroFireOp(i)) }, enabled = !busy) { Text("Run") }
                            }
                        }
                    }
                }
                item {
                    Caption(
                        "A macro runs a short sequence of changes from a single press: select an input and load a preset, switch " +
                            "monitors, mute " +
                            "after a delay. Bind a button, switch, or remote key to \"Macro\" to fire one.\n\nEach step can wait before " +
                            "it runs, and can " +
                            "address a channel group. One macro runs at a time - firing another cancels the first at its current " +
                            "step.\n\nMacros are " +
                            "stored on the device alongside the controls and share their Save and Revert."
                    )
                }
            }
            CsSection.AUX -> {
                val outs = cs.bindings.withIndex().filter { CsModel.isAuxType(it.value.type) }
                item {
                    SettingsSection("Auxiliary Outputs") {
                        if (outs.isEmpty()) Caption("No Auxiliary Outputs Configured")
                        outs.forEach { (slot, b) ->
                            val on = cs.auxState.getOrElse(slot) { 0 } != 0
                            SettingRow(cs.auxName(slot), "${CsModel.typeName(b.type)} · GPIO ${b.gpio0}", onClick = { renaming = slot }) {
                                // Switching an output is instant and never writes to flash
                                Switch(checked = on, onCheckedChange = { op(CsModel.auxStateOp(slot, it)) }, enabled = !busy)
                            }
                        }
                    }
                }
                item {
                    Caption(
                        "An on/off output follows its switch. A dimmable output follows its switch and its level, so one button and one " +
                            "knob can " +
                            "share a lamp. Turn on \"Active-Low Output\" for the relay and opto-isolator boards that switch when the pin " +
                            "goes low.\n\n" +
                            "A GPIO is a 3.3 V pin good for a few milliamps. Anything real needs a MOSFET, a transistor with a flyback " +
                            "diode, or an " +
                            "opto-isolated relay module in between, and a dimmed load should have its own supply so its switching noise " +
                            "stays out of " +
                            "the DAC.\n\nSwitching an output is instant and never writes to flash. The pin, name and power-on behaviour " +
                            "are stored on " +
                            "the device alongside the controls and share their Save and Revert."
                    )
                }
            }
        }
    }

    renaming?.let { slot ->
        var text by remember(slot) { mutableStateOf(cs.slotName(slot)) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Slot ${slot + 1}") },
            text = {
                OutlinedTextField(value = text, onValueChange = {
                    if (it.length <=
                        31
                    ) {
                        text = it
                    }
                }, label = { Text("Name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    op(CsModel.nameOp(slot, text.trim()))
                    renaming = null
                }, enabled = !busy) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    op(CsModel.clearBindingOp(slot))
                    renaming = null
                }, enabled = !busy) { Text("Remove", color = Color(0xFFFF453A)) }
            }
        )
    }
}

private fun gpioText(b: CsBinding): String = if (b.gpio1 != 0xFF && b.gpio1 != b.gpio0) "GPIO ${b.gpio0}/${b.gpio1}" else "GPIO ${b.gpio0}"

@Composable
private fun SlotPill(cs: CsModel, slot: Int) {
    if (cs.isSlotActive(slot)) Pill("Active", OkGreen) else Pill("Inactive", WarningOrange)
}

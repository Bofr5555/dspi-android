// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `HardwareSettingsTab`
// (sections .outputs / .i2s / .spdif = the Outputs, I2S Configuration and
// Inputs pages). D2: edits are staged (PendingChangeTracker) and applied by the
// save bar with one HARDWARE_APPLY token instead of being sent from the
// pickers; the pages show the staged draft, and a pin a staged edit would
// collide on is reported here before Save (pinConflicts).
package se.bofr.dspi.feature.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.I2S_CLOCK_PIN_MODE_SPLIT
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.I2S_CLOCK_PIN_MODE_UNIFIED
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.OUTPUT_TYPE_I2S
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.OUTPUT_TYPE_SPDIF
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.PIN_UNSET

/** Which hardware page (macOS `HardwareSettingsTab.Page`). */
enum class HardwarePage { OUTPUTS, I2S, SPDIF }

@Composable
fun HardwareSettingsTab(page: HardwarePage, bridge: DspiBridge, coordinator: SettingsSaveCoordinator, onOpen: (SettingsCategory) -> Unit) {
    val state by bridge.state.collectAsState()
    val pending by coordinator.hardware.pending.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    val device = coordinator.deviceSnapshot(state)
    val draft = coordinator.hardwareDraft(state, pending)
    val pins = coordinator.pinContext(state, pending)
    val edits = coordinator.hardwareChanges
    val keys = pending.associateBy { it.key }
    fun was(key: String): String? = keys[key]?.let { "Unsaved — was ${it.oldDisplay}" }

    var confirmClockMode by remember { mutableStateOf<Int?>(null) }

    SettingsForm {
        if (!connected) {
            item { SettingsSection(null) { Caption("No Device Connected. Hardware settings live on the device.") } }
        }
        val conflicts = pins.pinConflicts()
        if (conflicts.isNotEmpty() && pending.isNotEmpty()) {
            item {
                SettingsSection(
                    "Pin Conflicts",
                    footer = "Saving would be refused for these pins. Move one of the features first.",
                    footerColor = WarningOrange
                ) {
                    conflicts.forEach { c ->
                        SettingRow(
                            "GPIO ${c.pin}",
                            c.claimants.joinToString(" · ") {
                                it.label
                            },
                            detailColor = WarningOrange,
                            onClick = { onOpen(c.claimants.last().page) }
                        )
                    }
                }
            }
        }
        when (page) {
            HardwarePage.OUTPUTS -> outputsPage(device, draft, pins, edits, coordinator, connected, ::was)
            HardwarePage.I2S -> if (!draft.isStm32) i2sPage(device, draft, pins, edits, coordinator, connected, ::was)
            HardwarePage.SPDIF -> inputsPage(bridge, device, draft, pins, edits, coordinator, connected, ::was) { confirmClockMode = it }
        }
    }

    confirmClockMode?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmClockMode = null },
            title = { Text("Change I2S clock mode?") },
            text = {
                Text(
                    "One or more I2S outputs are active. Switching between Master and Slave modes may cause sustained loud noises to be " +
                        "emitted by the connected I2S DAC if wiring has not been adjusted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    coordinator.stage(edits.i2sClockMode(device, mode))
                    confirmClockMode = null
                }) { Text("Change Clock Mode", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { confirmClockMode = null }) { Text("Cancel") } }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.outputsPage(
    device: OutputConfigSnapshot,
    draft: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean,
    was: (String) -> String?
) {
    item {
        SettingsSection("Slots") {
            for (slot in 0 until draft.numOutputSlots) {
                val type = draft.outputSlotTypes.getOrElse(slot) { 0 }
                val pin = draft.outputPins.getOrElse(slot) { PIN_UNSET }
                val isDefault = type == OUTPUT_TYPE_SPDIF && pin == OutputConfigSnapshot.DEFAULT_DATA_PINS.getOrNull(slot)
                SettingRow(
                    "Output ${slot + 1}" + if (isDefault) "  · Default" else "",
                    was("outputs.slot$slot.type") ?: if (type == OUTPUT_TYPE_I2S) "I2S" else "S/PDIF",
                    enabled = connected
                )
                ChoiceRow(
                    "Type",
                    null,
                    listOf(OUTPUT_TYPE_SPDIF to "S/PDIF", OUTPUT_TYPE_I2S to "I2S").filter {
                        it.first == OUTPUT_TYPE_SPDIF ||
                            i2sAllowedFor(draft, slot)
                    },
                    type,
                    enabled = connected
                ) { coordinator.stage(edits.outputType(device, slot, it)) }
                PinRow(
                    "GPIO",
                    was("outputs.out$slot.pin"),
                    pin,
                    VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.Output(slot)) == null },
                    enabled = connected
                ) { coordinator.stage(edits.outputPin(device, slot, it)) }
                RowDivider()
            }
            val pdm = draft.pdmIndex
            PinRow(
                "Subwoofer",
                was("outputs.out$pdm.pin") ?: "PDM",
                draft.outputPins.getOrElse(pdm) { PIN_UNSET },
                VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.Output(pdm)) == null },
                enabled = connected
            ) { coordinator.stage(edits.outputPin(device, pdm, it)) }
        }
    }
    if (draft.adatSupported) {
        item {
            SettingsSection("Bulk Output") {
                SwitchRow(
                    "Enable ADAT",
                    was("outputs.adat.enable")
                        ?: "Stream all 8 output channels as one optical ADAT lightpipe (44.1/48 kHz, 24-bit). Runs alongside the " +
                        "existing outputs; drive a TOSLINK transmitter from the data pin.",
                    draft.adatEnabled,
                    enabled = connected
                ) { coordinator.stage(edits.adatOutEnabled(device, draft, it)) }
                PinRow(
                    "Serial Data",
                    was("outputs.adat.pin")
                        ?: "GPIO driving the ADAT optical output. Default GPIO ${OutputConfigSnapshot.ADAT_PIN_DEFAULT}.",
                    draft.adatPin,
                    (listOf(OutputConfigSnapshot.ADAT_PIN_DEFAULT) + VALID_PINS).distinct().sorted().filter {
                        pins.pinInUseBy(it, PinConsumer.AdatOut) ==
                            null
                    },
                    enabled = connected
                ) { coordinator.stage(edits.adatOutPin(device, draft, it)) }
            }
        }
    }
    item {
        SettingsSection(null) {
            SettingRow("Reset Pins", "Sets every output pin back to its default. Output types are unchanged.", enabled = connected) {
                OutlinedButton(onClick = { coordinator.stage(edits.resetPins(device)) }, enabled = connected) { Text("Reset") }
            }
        }
    }
}

/** STM32 slot dependencies (macOS `i2sAllowedFor`); RP slots have none. */
internal fun i2sAllowedFor(c: OutputConfigSnapshot, slot: Int): Boolean {
    if (!c.isStm32) return true
    return when (slot) {
        1, 2 -> c.outputSlotTypes.getOrElse(0) { 0 } == OUTPUT_TYPE_I2S
        3 -> c.outputSlotTypes.getOrElse(2) { 0 } == OUTPUT_TYPE_I2S
        else -> true
    }
}

/**
 * BCK candidates: the pin and its LRCLK neighbour both free (macOS), and the
 * neighbour wirable (Windows `IsAudioCapable`, Linux `pairFree`).
 */
internal fun bckCandidates(pins: PinContext, consumer: PinConsumer, current: Int): List<Int> = VALID_PINS.filter { p ->
    p == current || ((p + 1) in VALID_PINS && pins.pinInUseBy(p, consumer) == null && pins.pinInUseBy(p + 1, consumer) == null)
}

private fun androidx.compose.foundation.lazy.LazyListScope.i2sPage(
    device: OutputConfigSnapshot,
    draft: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean,
    was: (String) -> String?
) {
    item {
        SettingsSection(
            "I2S Clock",
            footer = if (draft.anySlotIsI2S) "Set every output to S/PDIF (Outputs) before moving the bit clock pins." else null
        ) {
            PinRow(
                "BCK Pin",
                was("i2s.bck") ?: "LRCK: GPIO ${draft.i2sBckPin + 1} (BCK + 1)",
                draft.i2sBckPin,
                bckCandidates(pins, PinConsumer.I2sBck, draft.i2sBckPin),
                enabled = connected && !draft.anySlotIsI2S
            ) { coordinator.stage(edits.i2sBckPin(device, it)) }
            if (draft.i2sClockPinModeSupported) {
                SegmentedRow(
                    "Clock Pins",
                    was("i2s.clockPinMode")
                        ?: "Unified: Master and Slave modes share pins.\nSplit: Separate pins for Master and Slave modes.",
                    listOf(I2S_CLOCK_PIN_MODE_UNIFIED to "Unified", I2S_CLOCK_PIN_MODE_SPLIT to "Split"),
                    draft.i2sClockPinMode,
                    enabled = connected
                ) { coordinator.stage(edits.i2sClockPinMode(device, draft, it)) }
                PinRow(
                    "Slave BCK Pin",
                    was("i2s.bckSlave") ?: if (draft.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT) {
                        "LRCK: GPIO ${draft.i2sBckPinSlave + 1} (BCK + 1)"
                    } else {
                        "Stored but inactive while clock pins are shared."
                    },
                    draft.i2sBckPinSlave,
                    bckCandidates(pins, PinConsumer.I2sBckSlave, draft.i2sBckPinSlave),
                    enabled = connected && draft.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT
                ) { coordinator.stage(edits.i2sBckPinSlave(device, it)) }
            }
            SwitchRow(
                "Master Clock (MCK)",
                was("i2s.mck.enable") ?: if (draft.i2sSlaveActive) {
                    "Forced off in I2S slave mode; a local MCK would be asynchronous to the external clocks. Kept and restored when " +
                        "leaving slave mode."
                } else {
                    "Clock reference for external DACs"
                },
                draft.mckEnabled,
                enabled = connected && !draft.i2sSlaveActive
            ) { coordinator.stage(edits.mckEnabled(device, it)) }
            PinRow(
                "MCK Pin",
                was("i2s.mck.pin") ?: if (draft.mckEnabled) "Turn MCK off to change its pin." else null,
                draft.mckPin,
                draft.mckValidPins.filter { pins.pinInUseBy(it, PinConsumer.Mck) == null },
                enabled = connected && !draft.mckEnabled && !draft.i2sSlaveActive
            ) { coordinator.stage(edits.mckPin(device, draft, it)) }
            val locked = draft.i2sInputRate >= 2
            ChoiceRow(
                "MCK Multiplier",
                was("i2s.mck.multiplier") ?: if (locked) "Locked to 128x at 96.0 kHz" else null,
                listOf(0 to "128x", 1 to "256x"),
                draft.mckMultiplier,
                enabled = connected && !locked && !draft.i2sSlaveActive
            ) { coordinator.stage(edits.mckMultiplier(device, it)) }
            if (draft.i2sInputSupported) {
                ChoiceRow(
                    "Input Sample Rate",
                    was("i2s.inputRate") ?: if (draft.i2sSlaveActive) {
                        "Rate used in master mode. In slave mode the external master sets the rate."
                    } else {
                        "Rate for I2S input; DSPi drives the clocks, so the source must follow."
                    },
                    OutputConfigSnapshot.INPUT_RATE_LABELS.mapIndexed { i, l -> i to l },
                    draft.i2sInputRate,
                    enabled = connected && !draft.i2sSlaveActive
                ) { coordinator.stage(edits.inputRate(device, it)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inputsPage(
    bridge: DspiBridge,
    device: OutputConfigSnapshot,
    draft: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean,
    was: (String) -> String?,
    askClockMode: (Int) -> Unit
) {
    if (draft.inputSourceSupported) {
        item {
            SettingsSection("S/PDIF Input") {
                val count = draft.spdifEnabledCount
                if (draft.multiSpdifSupported) {
                    SegmentedRow(
                        "Instances",
                        "$count selectable input${if (count == 1) "" else "s"} sharing one receiver",
                        (1..draft.spdifInputCount).map { it to "$it" },
                        count,
                        enabled = connected
                    ) { coordinator.stage(edits.spdifInputCount(device, it)) }
                }
                val shown = if (draft.multiSpdifSupported) count else 1
                for (idx in 0 until shown) {
                    PinRow(
                        if (shown > 1) "S/PDIF ${idx + 1}" else "S/PDIF RX",
                        was("inputs.spdif$idx.pin") ?: "GPIO pin for S/PDIF input ${idx + 1} (TOSLINK RX module or comparator).",
                        draft.spdifRxPins.getOrElse(idx) { PIN_UNSET },
                        VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.SpdifRx(idx)) == null },
                        enabled = connected
                    ) { coordinator.stage(edits.spdifRxPin(device, draft, idx, it)) }
                }
                // Not a pipeline rebuild: live and per preset, as on macOS
                val lg = bridge.state.collectAsState().value.lgSoundSyncEnabled
                SwitchRow(
                    "LG Sound Sync",
                    "Decode the LG TV's TOSLINK volume + mute signaling and apply it as the host volume - TV remote becomes the volume " +
                        "control. Per-preset; saved with the active preset.",
                    lg,
                    enabled = connected
                ) { bridge.setLgSoundSync(it) }
            }
        }
    }
    if (draft.i2sInputSupported) {
        item {
            SettingsSection("I2S Input", footer = "The bit clock pins and the master-mode sample rate are set in I2S Configuration.") {
                if (draft.i2sClockModeSupported) {
                    SegmentedRow(
                        "Clock Mode",
                        was("inputs.i2s.clockMode")
                            ?: "Master: DSPi drives BCK/LRCLK. Slave: an external master drives the clocks and the rate is auto-detected.",
                        listOf(0 to "Master", 1 to "Slave"),
                        draft.i2sClockMode,
                        enabled = connected
                    ) { mode ->
                        // Warn before switching while an output drives an I2S DAC
                        if (draft.anySlotIsI2S) askClockMode(mode) else coordinator.stage(edits.i2sClockMode(device, mode))
                    }
                    if (device.i2sSlaveActive) LockStatusRow(bridge, which = 0, adat = false)
                }
                if (draft.i2sMaxPairs > 1) {
                    val pairs = draft.i2sActivePairs
                    SegmentedRow(
                        "Channels",
                        was("inputs.i2s.channels") ?: "$pairs stereo pair${if (pairs == 1) "" else "s"} of 24-bit audio, sample-aligned",
                        (1..draft.i2sMaxPairs).map { it * 2 to "${it * 2}" },
                        pairs * 2,
                        enabled = connected
                    ) { coordinator.stage(edits.i2sInputChannels(device, it)) }
                } else {
                    SettingRow("Channels", "One stereo pair of 24-bit audio") { Text("2") }
                }
                for (pair in 0 until draft.i2sActivePairs) {
                    PinRow(
                        if (draft.i2sActivePairs > 1) "Serial Data ${pair + 1}" else "Serial Data",
                        was("inputs.i2s.pair$pair.pin") ?: "GPIO data pin for input channels ${pair * 2 + 1}-${pair * 2 + 2}",
                        draft.i2sRxPins.getOrElse(pair) { PIN_UNSET },
                        VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.I2sRx(pair)) == null },
                        enabled = connected
                    ) { coordinator.stage(edits.i2sRxPin(device, pair, it)) }
                }
                if (draft.i2sActivePairs < draft.i2sMaxPairs) {
                    Caption("Unused pairs keep their pins parked; they reserve nothing until the channel count is raised.")
                }
            }
        }
    }
    if (draft.adatInputSupported) {
        item {
            SettingsSection(
                "ADAT Input",
                footer =
                "Pairs with an outboard ADC (for example a Behringer ADA8200) to turn DSPi into an analog-in processor. In master mode, " +
                    "wire DSPi's ADAT output to the box's ADAT input and the box's ADAT output back to the data pin here. The 8 channels " +
                    "arrive " +
                    "as input channels 1-8 with full per-input EQ and metering. Save a preset to keep this wiring."
            ) {
                SwitchRow(
                    "Enable ADAT Input",
                    was("inputs.adat.enable")
                        ?: "Receive 8 channels of 24-bit audio (44.1/48 kHz) from one TOSLINK optical input into input channels 1-8. " +
                        "Assign a data pin below, then select ADAT as the input source.",
                    draft.adatInputEnabled,
                    enabled = connected && (draft.adatInputPin != PIN_UNSET || draft.adatInputEnabled)
                ) { coordinator.stage(edits.adatInputEnabled(device, it)) }
                PinRow(
                    "Serial Data",
                    was("inputs.adat.pin")
                        ?: "GPIO receiving the ADAT optical input. No default - assign a spare pin (it may match the ADAT output pin for " +
                        "a loopback self-test).",
                    draft.adatInputPin,
                    VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.AdatIn) == null },
                    enabled = connected,
                    unsetText = if (draft.adatInputEnabled) null else "Not set"
                ) { coordinator.stage(edits.adatInputPin(device, draft, it)) }
                SegmentedRow(
                    "Clock Mode",
                    was("inputs.adat.clockMode")
                        ?: "Master: DSPi owns the sample rate (set in I2S Configuration) and the source syncs to the ADAT output. Slave: " +
                        "an external master owns the clock and the rate is auto-detected.",
                    listOf(0 to "Master", 1 to "Slave"),
                    draft.adatInputClockMode,
                    enabled = connected
                ) { coordinator.stage(edits.adatInputClockMode(device, it)) }
                if (draft.adatInputEnabled && draft.adatInputClockMode == 0 && !draft.adatEnabled) {
                    SettingRow(
                        "Clock is free-running",
                        "Master mode uses the ADAT output to clock the outboard device but the ADAT output is off. The source runs on " +
                            "its own " +
                            "clock, asynchronous to DSPi, so you will hear periodic disturbances. Enable ADAT Output, or switch to Slave " +
                            "mode above.",
                        titleColor = WarningOrange
                    ) {
                        OutlinedButton(onClick = { coordinator.stage(edits.adatOutEnabled(device, draft, true)) }, enabled = connected) {
                            Text("Enable ADAT Output")
                        }
                    }
                }
                if (device.inputSource == 3) LockStatusRow(bridge, which = 1, adat = true)
            }
        }
    }
}

/** Live lock indicator for a clocked input while it plays (polled at 1 Hz, as Linux). */
@Composable
private fun LockStatusRow(bridge: DspiBridge, which: Int, adat: Boolean) {
    var lock by remember { mutableStateOf<InputLockStatus?>(null) }
    LaunchedEffect(which) {
        while (true) {
            lock = bridge.fetchInputLock(which)
            delay(1000)
        }
    }
    val st = lock
    val names = if (adat) {
        listOf(
            "Inactive",
            "Acquiring",
            "Syncing",
            "Locked",
            "Relocking"
        )
    } else {
        listOf("Inactive", "Acquiring", "Relocking", "Locked")
    }
    val text = names.getOrElse(st?.state ?: 0) { "Inactive" }
    val detail = when {
        st != null && st.state == 3 && st.detectedRate > 0 -> "Locked to external clock at ${"%.1f".format(st.detectedRate / 1000.0)} kHz."
        st != null && st.measuredHz > 0 -> "Waiting for external clock (measured ${"%.1f".format(st.measuredHz / 1000.0)} kHz)."
        else -> "Waiting for external clock."
    }
    SettingRow("Lock Status", detail) {
        Pill(text, if (st?.state == 3) OkGreen else WarningOrange)
    }
}

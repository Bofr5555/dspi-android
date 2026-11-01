// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `HardwareSettingsTab`
// (sections .outputs / .i2s / .spdif = the Outputs, I2S Configuration and
// Inputs pages). As on macOS (E1b), a picker/toggle applies its change to the
// device at once, with a HARDWARE_APPLY token minted by that interaction
// (SettingsSaveCoordinator.applyHardware, which also calls beginOutputEdit);
// the save bar flashes the result in independent mode.
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
import java.util.Locale
import kotlinx.coroutines.delay
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.InputLockStatus
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.I2S_CLOCK_PIN_MODE_SPLIT
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.I2S_CLOCK_PIN_MODE_UNIFIED
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.OUTPUT_TYPE_I2S
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.OUTPUT_TYPE_SPDIF
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.PIN_UNSET
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.writeEnabled

/** Which hardware page (macOS `HardwareSettingsTab.Page`). */
enum class HardwarePage { OUTPUTS, I2S, SPDIF }

@Composable
fun HardwareSettingsTab(page: HardwarePage, bridge: DspiBridge, coordinator: SettingsSaveCoordinator, onOpen: (SettingsCategory) -> Unit) {
    val state by bridge.state.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    val device = coordinator.deviceSnapshot(state)
    val pins = coordinator.pinContext(state)
    val status by coordinator.hardwareStatus.collectAsState()
    val edits = coordinator.hardwareChanges

    var confirmClockMode by remember { mutableStateOf<Int?>(null) }
    var sampleRateHz by remember { mutableStateOf(0) }
    if (page == HardwarePage.I2S) {
        LaunchedEffect(connected) {
            while (connected) {
                sampleRateHz = bridge.fetchInputRate()
                delay(2000)
            }
        }
    }

    SettingsForm {
        if (!connected) {
            item { SettingsSection(null) { FormCaption("No Device Connected. Hardware settings live on the device.") } }
        }
        when (page) {
            HardwarePage.OUTPUTS -> outputsPage(device, pins, edits, coordinator, connected)
            HardwarePage.I2S -> if (!device.isStm32) i2sPage(sampleRateHz, device, pins, edits, coordinator, connected)
            HardwarePage.SPDIF -> inputsPage(bridge, device, pins, edits, coordinator, connected) { confirmClockMode = it }
        }
        item { StatusLine(status?.first, status?.second ?: false) }
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
                    coordinator.applyHardware(edits.i2sClockMode(device, mode))
                    confirmClockMode = null
                }) { Text("Change Clock Mode", color = Color(0xFFFF453A)) }
            },
            dismissButton = { TextButton(onClick = { confirmClockMode = null }) { Text("Cancel") } }
        )
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.outputsPage(
    device: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean
) {
    item {
        SettingsSection("Slots") {
            for (slot in 0 until device.numOutputSlots) {
                val type = device.outputSlotTypes.getOrElse(slot) { 0 }
                val pin = device.outputPins.getOrElse(slot) { PIN_UNSET }
                val isDefault = type == OUTPUT_TYPE_SPDIF && pin == OutputConfigSnapshot.DEFAULT_DATA_PINS.getOrNull(slot)
                SettingRow(
                    "Output ${slot + 1}" + if (isDefault) "  · Default" else "",
                    if (type == OUTPUT_TYPE_I2S) "I2S" else "S/PDIF",
                    enabled = writeEnabled(connected)
                )
                ChoiceRow(
                    "Type",
                    null,
                    listOf(OUTPUT_TYPE_SPDIF to "S/PDIF", OUTPUT_TYPE_I2S to "I2S").filter {
                        it.first == OUTPUT_TYPE_SPDIF ||
                            i2sAllowedFor(device, slot)
                    },
                    type,
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.outputType(device, slot, it)) }
                // STM32 has no assignable pins (macOS hides the picker)
                if (!device.isStm32) {
                    PinRow(
                        "GPIO",
                        null,
                        pin,
                        VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.Output(slot)) == null },
                        enabled = writeEnabled(connected)
                    ) { coordinator.applyHardware(edits.outputPin(device, slot, it, pins)) }
                }
                RowDivider()
            }
            val pdm = device.pdmIndex
            if (device.isStm32) {
                SettingRow("Subwoofer", "PDM")
            } else {
                PinRow(
                    "Subwoofer",
                    "PDM",
                    device.outputPins.getOrElse(pdm) { PIN_UNSET },
                    VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.Output(pdm)) == null },
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.outputPin(device, pdm, it, pins)) }
            }
        }
    }
    if (device.adatSupported) {
        item {
            SettingsSection("Bulk Output") {
                FormSwitchRow(
                    "Enable ADAT",
                    "Stream all 8 output channels as one optical ADAT lightpipe (44.1/48 kHz, 24-bit). Runs alongside the " +
                        "existing outputs; drive a TOSLINK transmitter from the data pin.",
                    device.adatEnabled,
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.adatOutEnabled(device, it)) }
                PinRow(
                    "Serial Data",
                    "GPIO driving the ADAT optical output. Default GPIO ${OutputConfigSnapshot.ADAT_PIN_DEFAULT}.",
                    device.adatPin,
                    (listOf(OutputConfigSnapshot.ADAT_PIN_DEFAULT) + VALID_PINS).distinct().sorted().filter {
                        pins.pinInUseBy(it, PinConsumer.AdatOut) ==
                            null
                    },
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.adatOutPin(device, it)) }
            }
        }
    }
    if (!device.isStm32) {
        item {
            SettingsSection(null) {
                SettingRow(
                    "Reset Pins",
                    "Sets every output pin back to its default. Output types are unchanged.",
                    enabled = writeEnabled(connected)
                ) {
                    OutlinedButton(
                        onClick = { coordinator.applyHardware(edits.resetPins(device), "All pins reset to defaults") },
                        enabled = writeEnabled(connected)
                    ) { Text("Reset") }
                }
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
    sampleRateHz: Int,
    device: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean
) {
    item {
        SettingsSection(
            "I2S Clock",
            footer = if (device.anySlotIsI2S) "Set every output to S/PDIF (Outputs) before moving the bit clock pins." else null
        ) {
            PinRow(
                "BCK Pin",
                "LRCK: GPIO ${device.i2sBckPin + 1} (BCK + 1)",
                device.i2sBckPin,
                bckCandidates(pins, PinConsumer.I2sBck, device.i2sBckPin),
                enabled = writeEnabled(connected) && !device.anySlotIsI2S
            ) { coordinator.applyHardware(edits.i2sBckPin(device, it)) }
            if (device.i2sClockPinModeSupported) {
                SegmentedRow(
                    "Clock Pins",
                    "Unified: Master and Slave modes share pins.\nSplit: Separate pins for Master and Slave modes.",
                    listOf(I2S_CLOCK_PIN_MODE_UNIFIED to "Unified", I2S_CLOCK_PIN_MODE_SPLIT to "Split"),
                    device.i2sClockPinMode,
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.i2sClockPinMode(device, it)) }
                PinRow(
                    "Slave BCK Pin",
                    if (device.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT) {
                        "LRCK: GPIO ${device.i2sBckPinSlave + 1} (BCK + 1)"
                    } else {
                        "Stored but inactive while clock pins are shared."
                    },
                    device.i2sBckPinSlave,
                    bckCandidates(pins, PinConsumer.I2sBckSlave, device.i2sBckPinSlave),
                    enabled = writeEnabled(connected) && device.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT
                ) { coordinator.applyHardware(edits.i2sBckPinSlave(device, it)) }
            }
            FormSwitchRow(
                "Master Clock (MCK)",
                if (device.i2sSlaveActive) {
                    "Forced off in I2S slave mode; a local MCK would be asynchronous to the external clocks. Kept and restored when " +
                        "leaving slave mode."
                } else {
                    "Clock reference for external DACs"
                },
                device.mckEnabled,
                enabled = writeEnabled(connected) && !device.i2sSlaveActive
            ) { coordinator.applyHardware(edits.mckEnabled(device, it)) }
            PinRow(
                "MCK Pin",
                if (device.mckEnabled) "Turn MCK off to change its pin." else null,
                device.mckPin,
                device.mckValidPins.filter { pins.pinInUseBy(it, PinConsumer.Mck) == null },
                enabled = writeEnabled(connected) && !device.mckEnabled && !device.i2sSlaveActive
            ) { coordinator.applyHardware(edits.mckPin(device, it)) }
            // macOS `mck256UnsupportedAtCurrentRate`: the live rate, not the configured one
            val locked = sampleRateHz >= 96000
            val rateLabel = if (sampleRateHz == 0) "unknown sample rate" else String.format(Locale.ROOT, "%.1f kHz", sampleRateHz / 1000.0)
            ChoiceRow(
                "MCK Multiplier",
                if (locked) "Locked to 128x at $rateLabel" else null,
                listOf(0 to "128x", 1 to "256x"),
                device.mckMultiplier,
                enabled = writeEnabled(connected) && !locked && !device.i2sSlaveActive
            ) { coordinator.applyHardware(edits.mckMultiplier(device, it)) }
            if (device.i2sInputSupported) {
                ChoiceRow(
                    "Input Sample Rate",
                    if (device.i2sSlaveActive) {
                        "Rate used in master mode. In slave mode the external master sets the rate."
                    } else {
                        "Rate for I2S input; DSPi drives the clocks, so the source must follow."
                    },
                    OutputConfigSnapshot.INPUT_RATE_LABELS.mapIndexed { i, l -> i to l },
                    device.i2sInputRate,
                    enabled = writeEnabled(connected) && !device.i2sSlaveActive
                ) { coordinator.applyHardware(edits.inputRate(device, it)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.inputsPage(
    bridge: DspiBridge,
    device: OutputConfigSnapshot,
    pins: PinContext,
    edits: HardwareChanges,
    coordinator: SettingsSaveCoordinator,
    connected: Boolean,
    askClockMode: (Int) -> Unit
) {
    if (device.inputSourceSupported) {
        item {
            SettingsSection("S/PDIF Input") {
                val count = device.spdifEnabledCount
                if (device.multiSpdifSupported) {
                    SegmentedRow(
                        "Instances",
                        "$count selectable input${if (count == 1) "" else "s"} sharing one receiver",
                        (1..device.spdifInputCount).map { it to "$it" },
                        count,
                        enabled = writeEnabled(connected)
                    ) { coordinator.applyHardware(edits.spdifInputCount(device, it)) }
                }
                val shown = if (device.multiSpdifSupported) count else 1
                for (idx in 0 until shown) {
                    PinRow(
                        if (shown > 1) "S/PDIF ${idx + 1}" else "S/PDIF RX",
                        "GPIO pin for S/PDIF input ${idx + 1} (TOSLINK RX module or comparator).",
                        device.spdifRxPins.getOrElse(idx) { PIN_UNSET },
                        VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.SpdifRx(idx)) == null },
                        enabled = writeEnabled(connected)
                    ) { coordinator.applyHardware(edits.spdifRxPin(device, idx, it, pins)) }
                }
                // Not a pipeline rebuild: live and per preset, as on macOS
                val lg = bridge.state.collectAsState().value.lgSoundSyncEnabled
                FormSwitchRow(
                    "LG Sound Sync",
                    "Decode the LG TV's TOSLINK volume + mute signaling and apply it as the host volume - TV remote becomes the volume " +
                        "control. Per-preset; saved with the active preset.",
                    lg,
                    enabled = writeEnabled(connected)
                ) { bridge.setLgSoundSync(it) }
            }
        }
    }
    if (device.i2sInputSupported) {
        item {
            SettingsSection("I2S Input", footer = "The bit clock pins and the master-mode sample rate are set in I2S Configuration.") {
                if (device.i2sClockModeSupported) {
                    SegmentedRow(
                        "Clock Mode",
                        "Master: DSPi drives BCK/LRCLK. Slave: an external master drives the clocks and the rate is auto-detected.",
                        listOf(0 to "Master", 1 to "Slave"),
                        device.i2sClockMode,
                        enabled = writeEnabled(connected)
                    ) { mode ->
                        // Warn before switching while an output drives an I2S DAC
                        if (device.anySlotIsI2S) askClockMode(mode) else coordinator.applyHardware(edits.i2sClockMode(device, mode))
                    }
                    if (device.i2sSlaveActive) LockStatusRow(bridge, which = 0, adat = false)
                }
                if (device.i2sMaxPairs > 1) {
                    val pairs = device.i2sActivePairs
                    SegmentedRow(
                        "Channels",
                        "$pairs stereo pair${if (pairs == 1) "" else "s"} of 24-bit audio, sample-aligned",
                        (1..device.i2sMaxPairs).map { it * 2 to "${it * 2}" },
                        pairs * 2,
                        enabled = writeEnabled(connected)
                    ) { coordinator.applyHardware(edits.i2sInputChannels(device, it)) }
                } else {
                    SettingRow("Channels", "One stereo pair of 24-bit audio") { Text("2") }
                }
                for (pair in 0 until device.i2sActivePairs) {
                    PinRow(
                        if (device.i2sActivePairs > 1) "Serial Data ${pair + 1}" else "Serial Data",
                        "GPIO data pin for input channels ${pair * 2 + 1}-${pair * 2 + 2}",
                        device.i2sRxPins.getOrElse(pair) { PIN_UNSET },
                        VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.I2sRx(pair)) == null },
                        enabled = writeEnabled(connected)
                    ) { coordinator.applyHardware(edits.i2sRxPin(device, pair, it)) }
                }
                if (device.i2sActivePairs < device.i2sMaxPairs) {
                    FormCaption("Unused pairs keep their pins parked; they reserve nothing until the channel count is raised.")
                }
            }
        }
    }
    if (device.adatInputSupported) {
        item {
            SettingsSection(
                "ADAT Input",
                footer =
                "Pairs with an outboard ADC (for example a Behringer ADA8200) to turn DSPi into an analog-in processor. In master mode, " +
                    "wire DSPi's ADAT output to the box's ADAT input and the box's ADAT output back to the data pin here. The 8 channels " +
                    "arrive " +
                    "as input channels 1-8 with full per-input EQ and metering. Save a preset to keep this wiring."
            ) {
                FormSwitchRow(
                    "Enable ADAT Input",
                    "Receive 8 channels of 24-bit audio (44.1/48 kHz) from one TOSLINK optical input into input channels 1-8. " +
                        "Assign a data pin below, then select ADAT as the input source.",
                    device.adatInputEnabled,
                    enabled = writeEnabled(connected) && (device.adatInputPin != PIN_UNSET || device.adatInputEnabled)
                ) { coordinator.applyHardware(edits.adatInputEnabled(device, it)) }
                PinRow(
                    "Serial Data",
                    "GPIO receiving the ADAT optical input. No default - assign a spare pin (it may match the ADAT output pin for " +
                        "a loopback self-test).",
                    device.adatInputPin,
                    VALID_PINS.filter { pins.pinInUseBy(it, PinConsumer.AdatIn) == null },
                    enabled = writeEnabled(connected),
                    unsetText = if (device.adatInputEnabled) null else "Not set"
                ) { coordinator.applyHardware(edits.adatInputPin(device, it)) }
                SegmentedRow(
                    "Clock Mode",
                    "Master: DSPi owns the sample rate (set in I2S Configuration) and the source syncs to the ADAT output. Slave: " +
                        "an external master owns the clock and the rate is auto-detected.",
                    listOf(0 to "Master", 1 to "Slave"),
                    device.adatInputClockMode,
                    enabled = writeEnabled(connected)
                ) { coordinator.applyHardware(edits.adatInputClockMode(device, it)) }
                if (device.adatInputEnabled && device.adatInputClockMode == 0 && !device.adatEnabled) {
                    SettingRow(
                        "Clock is free-running",
                        "Master mode uses the ADAT output to clock the outboard device but the ADAT output is off. The source runs on " +
                            "its own " +
                            "clock, asynchronous to DSPi, so you will hear periodic disturbances. Enable ADAT Output, or switch to Slave " +
                            "mode above.",
                        titleColor = MacColors.orange
                    ) {
                        OutlinedButton(onClick = {
                            coordinator.applyHardware(edits.adatOutEnabled(device, true))
                        }, enabled = writeEnabled(connected)) {
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
            if (bridge.status.value.connected) lock = bridge.fetchInputLock(which)
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
        st != null && st.state == 3 && st.detectedRate > 0 -> "Locked to external clock at ${String.format(
            Locale.ROOT,
            "%.1f",
            st.detectedRate / 1000.0
        )} kHz."
        st != null && st.measuredHz > 0 -> "Waiting for external clock (measured ${String.format(
            Locale.ROOT,
            "%.1f",
            st.measuredHz / 1000.0
        )} kHz)."
        else -> "Waiting for external clock."
    }
    SettingRow("Lock Status", detail) {
        Pill(text, if (st?.state == 3) MacColors.green else MacColors.orange)
    }
}

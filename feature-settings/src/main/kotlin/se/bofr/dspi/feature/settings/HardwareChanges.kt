// The hardware edits of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift
// `HardwareSettingsTab` (pickers' set closures and their PIN_CONFIG_* status
// texts) as staged PendingChanges (Windows Settings pages' Stage calls).
// Apply ordering follows macOS SettingsSaveCoordinator.revert(): types before
// pins, an enable is applied after its pin and a disable before it, a raised
// I2S channel count after the pair pins, the slave clock pair before the
// clock-pin mode, BCK moves after the output types.
package se.bofr.dspi.feature.settings

import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.I2S_CLOCK_PIN_MODE_SPLIT
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.OUTPUT_TYPE_I2S
import se.bofr.dspi.feature.settings.OutputConfigSnapshot.Companion.PIN_UNSET

/**
 * Builds the [PendingChange]s the hardware pages stage. [device] is the
 * device's current configuration (the "old" side), [draft] the configuration
 * with this edit's predecessors staged (dependencies are decided on it).
 * Every apply closure calls a GATED HARDWARE_APPLY member with the save bar's
 * token.
 */
class HardwareChanges(private val bridge: DspiBridge) {
    private fun gpio(p: Int) = if (p == PIN_UNSET) "Not set" else "GPIO $p"

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun typeName(t: Int) = if (t == OUTPUT_TYPE_I2S) "I2S" else "S/PDIF"

    fun outputName(c: OutputConfigSnapshot, index: Int) = if (index == c.pdmIndex) "Subwoofer" else "Output ${index + 1}"

    private fun slotTypeKeys(c: OutputConfigSnapshot) = (0 until c.numOutputSlots).map { "outputs.slot$it.type" }

    // ── Outputs ──

    fun outputType(device: OutputConfigSnapshot, slot: Int, kind: Int) = PendingChange(
        key = "outputs.slot$slot.type",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "Output ${slot + 1} type",
        oldDisplay = typeName(device.outputSlotTypes.getOrElse(slot) { 0 }),
        newDisplay = typeName(kind),
        apply = { t -> bridge.setOutputType(t, slot, kind).code },
        patch = { it.copy(outputSlotTypes = it.outputSlotTypes.with(slot, kind)) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Disable Output ${slot + 1} in the Matrix Mixer before changing its type"
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "The I2S clock pins are in use by another function"
                else -> "Failed to change Output ${slot + 1} to ${typeName(kind)}"
            }
        }
    )

    fun outputPin(device: OutputConfigSnapshot, index: Int, pin: Int): PendingChange {
        val name = outputName(device, index)
        return PendingChange(
            key = "outputs.out$index.pin",
            pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
            fieldLabel = "$name GPIO",
            oldDisplay = gpio(device.outputPins.getOrElse(index) { PIN_UNSET }),
            newDisplay = gpio(pin),
            apply = { t -> bridge.setOutputPin(t, index, pin).code },
            dependsOn = if (index < device.numOutputSlots) listOf("outputs.slot$index.type") else emptyList(),
            patch = { it.copy(outputPins = it.outputPins.with(index, pin)) },
            statusText = { st ->
                when (st) {
                    DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin is not available on this platform"
                    DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use by another output"
                    DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "Invalid output index"
                    DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE ->
                        if (index ==
                            device.pdmIndex
                        ) {
                            "PDM output must be disabled before changing its pin"
                        } else {
                            "Disable this output in the Matrix Mixer before changing its pin"
                        }
                    else -> "USB communication error"
                }
            }
        )
    }

    /** macOS "Reset Pins": every output back to its default pin. */
    fun resetPins(device: OutputConfigSnapshot): List<PendingChange> =
        (0 until device.numOutputSlots).map { outputPin(device, it, OutputConfigSnapshot.DEFAULT_DATA_PINS.getOrElse(it) { 6 + it }) } +
            outputPin(device, device.pdmIndex, OutputConfigSnapshot.DEFAULT_PDM_PIN)

    fun adatOutEnabled(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, enabled: Boolean) = PendingChange(
        key = "outputs.adat.enable",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "ADAT output",
        oldDisplay = onOff(device.adatEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setAdatOutEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("outputs.adat.pin") else emptyList(),
        patch = { it.copy(adatEnabled = enabled) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO ${draft.adatPin} is already in use"
                DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "ADAT output isn't supported on this device"
                else -> if (enabled) "Failed to enable ADAT output" else "Failed to disable ADAT output"
            }
        }
    )

    fun adatOutPin(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, pin: Int) = PendingChange(
        key = "outputs.adat.pin",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "ADAT data pin",
        oldDisplay = gpio(device.adatPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setAdatOutPin(t, pin).code },
        dependsOn = if (!draft.adatEnabled) listOf("outputs.adat.enable") else emptyList(),
        patch = { it.copy(adatPin = pin) },
        statusText = { st -> adatPinText(st, pin, "Failed to set ADAT pin") }
    )

    private fun adatPinText(st: Int, pin: Int, fallback: String) = when (st) {
        DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
        DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
        else -> fallback
    }

    // ── I2S configuration ──

    fun i2sBckPin(device: OutputConfigSnapshot, pin: Int) = PendingChange(
        key = "i2s.bck",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "BCK pin",
        oldDisplay = gpio(device.i2sBckPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_MASTER, pin).code },
        dependsOn = slotTypeKeys(device),
        patch = { it.copy(i2sBckPin = pin) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "All outputs must be S/PDIF before changing BCK pin"
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin or ${pin + 1} is already in use"
                else -> "Failed to set BCK pin"
            }
        }
    )

    fun i2sBckPinSlave(device: OutputConfigSnapshot, pin: Int) = PendingChange(
        key = "i2s.bckSlave",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Slave BCK pin",
        oldDisplay = gpio(device.i2sBckPinSlave),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_SLAVE, pin).code },
        patch = { it.copy(i2sBckPinSlave = pin) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin or ${pin + 1} is already in use"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Can't move the slave clock pins while an I2S output is active"
                DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
                else -> "Failed to set slave BCK pin"
            }
        }
    )

    fun i2sClockPinMode(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, mode: Int) = PendingChange(
        key = "i2s.clockPinMode",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Clock pins",
        oldDisplay = if (device.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT) "Split" else "Unified",
        newDisplay = if (mode == I2S_CLOCK_PIN_MODE_SPLIT) "Split" else "Unified",
        apply = { t -> bridge.setI2sClockPinMode(t, mode).code },
        dependsOn = listOf("i2s.bckSlave") + slotTypeKeys(device),
        patch = { it.copy(i2sClockPinMode = mode) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                    "Slave clock pair GPIO ${draft.i2sBckPinSlave}/${draft.i2sBckPinSlave + 1} conflicts with another function - move it " +
                        "first"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Switch I2S output slots to S/PDIF before changing clock pins"
                else -> "Failed to change clock pins"
            }
        }
    )

    fun mckEnabled(device: OutputConfigSnapshot, enabled: Boolean) = PendingChange(
        key = "i2s.mck.enable",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Master clock (MCK)",
        oldDisplay = onOff(device.mckEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setMckEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("i2s.mck.pin") else emptyList(),
        patch = { it.copy(mckEnabled = enabled) },
        statusText = { if (enabled) "Failed to enable master clock" else "Failed to disable master clock" }
    )

    fun mckPin(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, pin: Int) = PendingChange(
        key = "i2s.mck.pin",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "MCK pin",
        oldDisplay = gpio(device.mckPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setMckPin(t, pin).code },
        dependsOn = if (!draft.mckEnabled) listOf("i2s.mck.enable") else emptyList(),
        patch = { it.copy(mckPin = pin) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Disable MCK before changing its pin"
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
                else -> "Failed to set MCK pin"
            }
        }
    )

    fun mckMultiplier(device: OutputConfigSnapshot, mult: Int) = PendingChange(
        key = "i2s.mck.multiplier",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "MCK multiplier",
        oldDisplay = mckLabel(device.mckMultiplier),
        newDisplay = mckLabel(mult),
        apply = { t -> bridge.setMckMultiplier(t, mult).code },
        patch = { it.copy(mckMultiplier = mult) },
        statusText = { "Failed to set MCK multiplier" }
    )

    fun inputRate(device: OutputConfigSnapshot, index: Int) = PendingChange(
        key = "i2s.inputRate",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Input sample rate",
        oldDisplay = OutputConfigSnapshot.INPUT_RATE_LABELS.getOrElse(device.i2sInputRate) { "?" },
        newDisplay = OutputConfigSnapshot.INPUT_RATE_LABELS.getOrElse(index) { "?" },
        apply = { t ->
            bridge.setInputRate(t, index)
            0
        },
        patch = { it.copy(i2sInputRate = index) }
    )

    // ── Inputs ──

    fun i2sClockMode(device: OutputConfigSnapshot, mode: Int) = PendingChange(
        key = "inputs.i2s.clockMode",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "I2S clock mode",
        oldDisplay = masterSlave(device.i2sClockMode),
        newDisplay = masterSlave(mode),
        apply = { t ->
            bridge.setI2sClockMode(t, mode)
            0
        },
        patch = { it.copy(i2sClockMode = mode) }
    )

    fun spdifRxPin(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, index: Int, pin: Int): PendingChange {
        val label = if (draft.spdifEnabledCount > 1 || index > 0) "S/PDIF ${index + 1}" else "S/PDIF RX"
        return PendingChange(
            key = "inputs.spdif$index.pin",
            pageId = SettingsCategory.SPDIF_INPUT.id,
            fieldLabel = "$label pin",
            oldDisplay = gpio(device.spdifRxPins.getOrElse(index) { PIN_UNSET }),
            newDisplay = gpio(pin),
            apply = { t -> bridge.setSpdifRxPin(t, index, pin).code },
            dependsOn = if (index > 0 && !draft.spdifInputEnabled(index)) listOf("inputs.spdif$index.enable") else emptyList(),
            patch = { it.copy(spdifRxPins = it.spdifRxPins.with(index, pin)) },
            statusText = { st ->
                when (st) {
                    DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
                    DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
                    else -> "Failed to set $label pin"
                }
            }
        )
    }

    /**
     * The macOS "Instances" selector: enable inputs 2..n upwards, disable the
     * rest downwards (each enable after its predecessor and after its pin).
     */
    fun spdifInputCount(device: OutputConfigSnapshot, target: Int): List<PendingChange> {
        val out = ArrayList<PendingChange>()
        for (idx in 1 until device.spdifInputCount) {
            val enable = idx < target
            val deps = ArrayList<String>()
            if (enable) {
                deps += "inputs.spdif$idx.pin"
                if (idx > 1) deps += "inputs.spdif${idx - 1}.enable"
            } else if (idx + 1 < device.spdifInputCount) {
                deps += "inputs.spdif${idx + 1}.enable"
            }
            out += PendingChange(
                key = "inputs.spdif$idx.enable",
                pageId = SettingsCategory.SPDIF_INPUT.id,
                fieldLabel = "S/PDIF ${idx + 1}",
                oldDisplay = onOff(device.spdifInputEnabled(idx)),
                newDisplay = onOff(enable),
                apply = { t -> bridge.setSpdifInputEnabled(t, idx, enable).code },
                dependsOn = deps,
                patch = { c ->
                    val bit = 1 shl idx
                    c.copy(spdifInputsEnabled = if (enable) c.spdifInputsEnabled or bit else c.spdifInputsEnabled and bit.inv())
                },
                statusText = { st ->
                    when {
                        st == DspiConstants.PIN_CONFIG_PIN_IN_USE && enable -> "Can't enable S/PDIF ${idx + 1}: its pin is already in use"
                        st == DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                            "Switch the input source away from S/PDIF ${idx + 1} before " +
                                "reducing the input count"
                        else -> "Failed to ${if (enable) "enable" else "disable"} S/PDIF ${idx + 1}"
                    }
                }
            )
        }
        return out
    }

    fun i2sInputChannels(device: OutputConfigSnapshot, count: Int): PendingChange {
        val raising = count > device.i2sInputChannels
        return PendingChange(
            key = "inputs.i2s.channels",
            pageId = SettingsCategory.SPDIF_INPUT.id,
            fieldLabel = "I2S input channels",
            oldDisplay = "${device.i2sInputChannels}",
            newDisplay = "$count",
            apply = { t -> bridge.setI2sInputChannels(t, count).code },
            // A raise checks the newly active pairs' pins, so it goes last
            dependsOn = if (raising) (0 until 4).map { "inputs.i2s.pair$it.pin" } else emptyList(),
            patch = { it.copy(i2sInputChannels = count) },
            statusText = { st ->
                when (st) {
                    DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                        if (raising) {
                            "Can't switch to $count channels: a required data pin is already in use. Reassign the new pairs' pins, then " +
                                "try again."
                        } else {
                            "That pin is already in use"
                        }
                    DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "Multichannel I2S isn't supported on this device"
                    else -> "Failed to set channel count"
                }
            }
        )
    }

    fun i2sRxPin(device: OutputConfigSnapshot, pair: Int, pin: Int) = PendingChange(
        key = "inputs.i2s.pair$pair.pin",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "Serial Data ${pair + 1}",
        oldDisplay = gpio(device.i2sRxPins.getOrElse(pair) { PIN_UNSET }),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sRxPin(t, pair, pin).code },
        patch = { it.copy(i2sRxPins = it.i2sRxPins.with(pair, pin)) },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
                DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Can't change the bit clock while an I2S output is active"
                else -> "Failed to set Serial Data ${pair + 1}"
            }
        }
    )

    fun adatInputEnabled(device: OutputConfigSnapshot, enabled: Boolean) = PendingChange(
        key = "inputs.adat.enable",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input",
        oldDisplay = onOff(device.adatInputEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setAdatInputEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("inputs.adat.pin") else emptyList(),
        patch = { it.copy(adatInputEnabled = enabled) },
        statusText = { st ->
            when {
                st == DspiConstants.PIN_CONFIG_PIN_IN_USE && !enabled -> "Switch to another input source before disabling ADAT input"
                st == DspiConstants.PIN_CONFIG_PIN_IN_USE -> "That pin is already in use"
                st == DspiConstants.PIN_CONFIG_INVALID_PIN && enabled -> "Assign a valid data pin before enabling ADAT input"
                st == DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "ADAT input isn't supported on this device"
                else -> "Failed to set ADAT input ${if (enabled) "enabled" else "disabled"}"
            }
        }
    )

    fun adatInputPin(device: OutputConfigSnapshot, draft: OutputConfigSnapshot, pin: Int) = PendingChange(
        key = "inputs.adat.pin",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input pin",
        oldDisplay = gpio(device.adatInputPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setAdatInputPin(t, pin).code },
        dependsOn = if (!draft.adatInputEnabled) listOf("inputs.adat.enable") else emptyList(),
        patch = { it.copy(adatInputPin = pin) },
        statusText = { st -> adatPinText(st, pin, "Failed to set ADAT input pin") }
    )

    fun adatInputClockMode(device: OutputConfigSnapshot, mode: Int) = PendingChange(
        key = "inputs.adat.clockMode",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input clock mode",
        oldDisplay = masterSlave(device.adatInputClockMode),
        newDisplay = masterSlave(mode),
        apply = { t -> bridge.setAdatInputClockMode(t, mode).code },
        patch = { it.copy(adatInputClockMode = mode) },
        statusText = { "Failed to set ADAT input clock mode" }
    )

    companion object {
        fun mckLabel(m: Int) = if (m == 1) "256x" else "128x"

        fun masterSlave(m: Int) = if (m == 1) "Slave" else "Master"
    }
}

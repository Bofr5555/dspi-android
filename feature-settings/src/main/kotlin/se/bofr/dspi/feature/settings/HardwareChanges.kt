// The hardware edits of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift
// `HardwareSettingsTab` (pickers' set closures and their PIN_CONFIG_* status
// texts) as staged HardwareChanges (Windows Settings pages' Stage calls).
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
 * Builds the [HardwareChange]s a hardware control applies. [device] is the
 * device's current configuration (the "old" side, and what dependencies are
 * decided on). Every apply closure calls a GATED HARDWARE_APPLY member with
 * the token minted by the user's interaction with that control (E1b).
 */
class HardwareChanges(private val bridge: DspiBridge) {
    private fun gpio(p: Int) = if (p == PIN_UNSET) "Not set" else "GPIO $p"

    private fun onOff(b: Boolean) = if (b) "On" else "Off"

    private fun typeName(t: Int) = if (t == OUTPUT_TYPE_I2S) "I2S" else "S/PDIF"

    fun outputName(c: OutputConfigSnapshot, index: Int) = if (index == c.pdmIndex) "Subwoofer" else "Output ${index + 1}"

    private fun slotTypeKeys(c: OutputConfigSnapshot) = (0 until c.numOutputSlots).map { "outputs.slot$it.type" }

    // ── Outputs ──

    fun outputType(device: OutputConfigSnapshot, slot: Int, kind: Int) = HardwareChange(
        key = "outputs.slot$slot.type",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "Output ${slot + 1} type",
        oldDisplay = typeName(device.outputSlotTypes.getOrElse(slot) { 0 }),
        newDisplay = typeName(kind),
        apply = { t -> bridge.setOutputType(t, slot, kind).code },
        statusText = { "Failed to change Output ${slot + 1} type" },
        successText = "Output ${slot + 1} set to ${typeName(kind)}"
    )

    /** The output channel index of the PDM sub (for its enable), -1 if none. */
    private fun pdmOutputIndex(): Int = (0 until bridge.numOutputChannels).firstOrNull { bridge.isPdmOutput(it) } ?: -1

    /**
     * macOS `setPinForOutput` → `assignOutputPin`: when the firmware refuses
     * because the PDM sub is active, disable it, set the pin, re-enable it.
     * [pins] names the owner of a taken pin.
     */
    fun outputPin(device: OutputConfigSnapshot, index: Int, pin: Int, pins: PinContext? = null, failText: String? = null): HardwareChange {
        val name = outputName(device, index)
        return HardwareChange(
            key = "outputs.out$index.pin",
            pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
            fieldLabel = "$name GPIO",
            oldDisplay = gpio(device.outputPins.getOrElse(index) { PIN_UNSET }),
            newDisplay = gpio(pin),
            apply = { t ->
                var st = bridge.setOutputPin(t, index, pin).code
                val pdm = pdmOutputIndex()
                if (st == DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE && index == device.pdmIndex && pdm >= 0) {
                    bridge.setOutputEnable(pdm, false)
                    st = bridge.setOutputPin(t, index, pin).code
                    bridge.setOutputEnable(pdm, true)
                }
                st
            },
            dependsOn = if (index < device.numOutputSlots) listOf("outputs.slot$index.type") else emptyList(),
            statusText = { st ->
                failText ?: when (st) {
                    DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin is not available on this platform"
                    DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                        pins?.pinInUseBy(pin, PinConsumer.Output(index))?.let { "GPIO $pin is already assigned to $it" }
                            ?: "GPIO $pin is already in use by another output"
                    DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "Invalid output index"
                    DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "PDM output must be disabled before changing its pin"
                    else -> "USB communication error"
                }
            },
            successText = "$name reassigned to GPIO $pin"
        )
    }

    /** macOS "Reset Pins" (`resetToDefaults`): every output back to its default pin. */
    fun resetPins(device: OutputConfigSnapshot): List<HardwareChange> = (0 until device.numOutputSlots).map {
        outputPin(
            device,
            it,
            OutputConfigSnapshot.DEFAULT_DATA_PINS.getOrElse(it) {
                6 + it
            },
            failText = "Failed to reset Output ${it + 1}"
        )
    } + outputPin(device, device.pdmIndex, OutputConfigSnapshot.DEFAULT_PDM_PIN, failText = "Failed to reset Subwoofer")

    fun adatOutEnabled(device: OutputConfigSnapshot, enabled: Boolean) = HardwareChange(
        key = "outputs.adat.enable",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "ADAT output",
        oldDisplay = onOff(device.adatEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setAdatOutEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("outputs.adat.pin") else emptyList(),
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO ${device.adatPin} is already in use"
                DspiConstants.PIN_CONFIG_INVALID_OUTPUT -> "ADAT output isn't supported on this device"
                else -> if (enabled) "Failed to enable ADAT output" else "Failed to disable ADAT output"
            }
        },
        successText = "ADAT output ${if (enabled) "enabled" else "disabled"}"
    )

    fun adatOutPin(device: OutputConfigSnapshot, pin: Int) = HardwareChange(
        key = "outputs.adat.pin",
        pageId = SettingsCategory.OUTPUT_ASSIGNMENT.id,
        fieldLabel = "ADAT data pin",
        oldDisplay = gpio(device.adatPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setAdatOutPin(t, pin).code },
        dependsOn = if (device.adatEnabled) listOf("outputs.adat.enable") else emptyList(),
        statusText = { st -> adatPinText(st, pin, "Failed to set ADAT pin") },
        successText = "ADAT data pin set to GPIO $pin"
    )

    private fun adatPinText(st: Int, pin: Int, fallback: String) = when (st) {
        DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
        DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
        else -> fallback
    }

    // ── I2S configuration ──

    fun i2sBckPin(device: OutputConfigSnapshot, pin: Int) = HardwareChange(
        key = "i2s.bck",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "BCK pin",
        oldDisplay = gpio(device.i2sBckPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_MASTER, pin).code },
        dependsOn = slotTypeKeys(device),
        successText = "BCK pin set to GPIO $pin, LRCLK = GPIO ${pin + 1}",
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "All outputs must be S/PDIF before changing BCK pin"
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin or ${pin + 1} is already in use"
                else -> "Failed to set BCK pin"
            }
        }
    )

    fun i2sBckPinSlave(device: OutputConfigSnapshot, pin: Int) = HardwareChange(
        key = "i2s.bckSlave",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Slave BCK pin",
        oldDisplay = gpio(device.i2sBckPinSlave),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sBckPin(t, OutputConfigSnapshot.I2S_BCK_ROLE_SLAVE, pin).code },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin or ${pin + 1} is already in use"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Can't move the slave clock pins while an I2S output is active"
                DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
                else -> "Failed to set slave BCK pin"
            }
        }
    )

    fun i2sClockPinMode(device: OutputConfigSnapshot, mode: Int) = HardwareChange(
        key = "i2s.clockPinMode",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Clock pins",
        oldDisplay = if (device.i2sClockPinMode == I2S_CLOCK_PIN_MODE_SPLIT) "Split" else "Unified",
        newDisplay = if (mode == I2S_CLOCK_PIN_MODE_SPLIT) "Split" else "Unified",
        apply = { t -> bridge.setI2sClockPinMode(t, mode).code },
        dependsOn = listOf("i2s.bckSlave") + slotTypeKeys(device),
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                    "Slave clock pair GPIO ${device.i2sBckPinSlave}/${device.i2sBckPinSlave + 1} " +
                        "conflicts with another function - move it first"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Switch I2S output slots to S/PDIF before changing clock pins"
                else -> "Failed to change clock pins"
            }
        }
    )

    fun mckEnabled(device: OutputConfigSnapshot, enabled: Boolean) = HardwareChange(
        key = "i2s.mck.enable",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Master clock (MCK)",
        oldDisplay = onOff(device.mckEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setMckEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("i2s.mck.pin") else emptyList(),
        statusText = { if (enabled) "Failed to enable master clock" else "Failed to disable master clock" },
        successText = "Master clock ${if (enabled) "enabled" else "disabled"}"
    )

    fun mckPin(device: OutputConfigSnapshot, pin: Int) = HardwareChange(
        key = "i2s.mck.pin",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "MCK pin",
        oldDisplay = gpio(device.mckPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setMckPin(t, pin).code },
        dependsOn = if (device.mckEnabled) listOf("i2s.mck.enable") else emptyList(),
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Disable MCK before changing its pin"
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
                else -> "Failed to set MCK pin"
            }
        }
    )

    fun mckMultiplier(device: OutputConfigSnapshot, mult: Int) = HardwareChange(
        key = "i2s.mck.multiplier",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "MCK multiplier",
        oldDisplay = mckLabel(device.mckMultiplier),
        newDisplay = mckLabel(mult),
        apply = { t -> bridge.setMckMultiplier(t, mult).code },
        statusText = { "Failed to set MCK multiplier" },
        successText = "MCK multiplier set to ${mckLabel(mult)}"
    )

    fun inputRate(device: OutputConfigSnapshot, index: Int) = HardwareChange(
        key = "i2s.inputRate",
        pageId = SettingsCategory.I2S_CONFIG.id,
        fieldLabel = "Input sample rate",
        oldDisplay = OutputConfigSnapshot.INPUT_RATE_LABELS.getOrElse(device.i2sInputRate) { "?" },
        newDisplay = OutputConfigSnapshot.INPUT_RATE_LABELS.getOrElse(index) { "?" },
        apply = { t ->
            bridge.setInputRate(t, index)
            0
        },
        // Fire-and-forget on the bridge: no outcome to report (macOS shows none either)
        successText = ""
    )

    // ── Inputs ──

    fun i2sClockMode(device: OutputConfigSnapshot, mode: Int) = HardwareChange(
        key = "inputs.i2s.clockMode",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "I2S clock mode",
        oldDisplay = masterSlave(device.i2sClockMode),
        newDisplay = masterSlave(mode),
        apply = { t ->
            bridge.setI2sClockMode(t, mode)
            0
        },
        // Fire-and-forget on the bridge: no outcome to report (macOS shows none either)
        successText = ""
    )

    fun spdifRxPin(device: OutputConfigSnapshot, index: Int, pin: Int, pins: PinContext? = null): HardwareChange {
        val label = if (device.spdifEnabledCount > 1 || index > 0) "S/PDIF ${index + 1}" else "S/PDIF RX"
        return HardwareChange(
            key = "inputs.spdif$index.pin",
            pageId = SettingsCategory.SPDIF_INPUT.id,
            fieldLabel = "$label pin",
            oldDisplay = gpio(device.spdifRxPins.getOrElse(index) { PIN_UNSET }),
            newDisplay = gpio(pin),
            apply = { t -> bridge.setSpdifRxPin(t, index, pin).code },
            dependsOn = if (index > 0 && device.spdifInputEnabled(index)) listOf("inputs.spdif$index.enable") else emptyList(),
            statusText = { st ->
                when (st) {
                    DspiConstants.PIN_CONFIG_PIN_IN_USE ->
                        pins?.pinInUseBy(pin, PinConsumer.SpdifRx(index))?.let { "GPIO $pin is already assigned to $it" }
                            ?: "GPIO $pin is already in use"
                    DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin is not available on this platform"
                    else -> "Failed to set S/PDIF ${index + 1} RX pin"
                }
            },
            successText = "S/PDIF ${index + 1} RX pin set to GPIO $pin"
        )
    }

    /**
     * The macOS "Instances" selector: enable inputs 2..n upwards, disable the
     * rest downwards (each enable after its predecessor and after its pin).
     */
    fun spdifInputCount(device: OutputConfigSnapshot, target: Int): List<HardwareChange> {
        val out = ArrayList<HardwareChange>()
        for (idx in 1 until device.spdifInputCount) {
            val enable = idx < target
            val deps = ArrayList<String>()
            if (enable) {
                deps += "inputs.spdif$idx.pin"
                if (idx > 1) deps += "inputs.spdif${idx - 1}.enable"
            } else if (idx + 1 < device.spdifInputCount) {
                deps += "inputs.spdif${idx + 1}.enable"
            }
            out += HardwareChange(
                key = "inputs.spdif$idx.enable",
                pageId = SettingsCategory.SPDIF_INPUT.id,
                fieldLabel = "S/PDIF ${idx + 1}",
                oldDisplay = onOff(device.spdifInputEnabled(idx)),
                newDisplay = onOff(enable),
                apply = { t -> bridge.setSpdifInputEnabled(t, idx, enable).code },
                dependsOn = deps,
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

    fun i2sInputChannels(device: OutputConfigSnapshot, count: Int): HardwareChange {
        val raising = count > device.i2sInputChannels
        return HardwareChange(
            key = "inputs.i2s.channels",
            pageId = SettingsCategory.SPDIF_INPUT.id,
            fieldLabel = "I2S input channels",
            oldDisplay = "${device.i2sInputChannels}",
            newDisplay = "$count",
            apply = { t -> bridge.setI2sInputChannels(t, count).code },
            // A raise checks the newly active pairs' pins, so it goes last
            dependsOn = if (raising) (0 until 4).map { "inputs.i2s.pair$it.pin" } else emptyList(),
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

    fun i2sRxPin(device: OutputConfigSnapshot, pair: Int, pin: Int) = HardwareChange(
        key = "inputs.i2s.pair$pair.pin",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "Serial Data ${pair + 1}",
        oldDisplay = gpio(device.i2sRxPins.getOrElse(pair) { PIN_UNSET }),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setI2sRxPin(t, pair, pin).code },
        statusText = { st ->
            when (st) {
                DspiConstants.PIN_CONFIG_PIN_IN_USE -> "GPIO $pin is already in use"
                DspiConstants.PIN_CONFIG_INVALID_PIN -> "GPIO $pin isn't available on this device"
                DspiConstants.PIN_CONFIG_OUTPUT_ACTIVE -> "Can't change the bit clock while an I2S output is active"
                else -> "Failed to set Serial Data ${pair + 1}"
            }
        }
    )

    fun adatInputEnabled(device: OutputConfigSnapshot, enabled: Boolean) = HardwareChange(
        key = "inputs.adat.enable",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input",
        oldDisplay = onOff(device.adatInputEnabled),
        newDisplay = onOff(enabled),
        apply = { t -> bridge.setAdatInputEnabled(t, enabled).code },
        dependsOn = if (enabled) listOf("inputs.adat.pin") else emptyList(),
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

    fun adatInputPin(device: OutputConfigSnapshot, pin: Int) = HardwareChange(
        key = "inputs.adat.pin",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input pin",
        oldDisplay = gpio(device.adatInputPin),
        newDisplay = gpio(pin),
        apply = { t -> bridge.setAdatInputPin(t, pin).code },
        dependsOn = if (device.adatInputEnabled) listOf("inputs.adat.enable") else emptyList(),
        statusText = { st -> adatPinText(st, pin, "Failed to set ADAT input pin") }
    )

    fun adatInputClockMode(device: OutputConfigSnapshot, mode: Int) = HardwareChange(
        key = "inputs.adat.clockMode",
        pageId = SettingsCategory.SPDIF_INPUT.id,
        fieldLabel = "ADAT input clock mode",
        oldDisplay = masterSlave(device.adatInputClockMode),
        newDisplay = masterSlave(mode),
        apply = { t -> bridge.setAdatInputClockMode(t, mode).code },
        statusText = { "Failed to set ADAT input clock mode" }
    )

    companion object {
        fun mckLabel(m: Int) = if (m == 1) "256x" else "128x"

        fun masterSlave(m: Int) = if (m == 1) "Slave" else "Master"
    }
}

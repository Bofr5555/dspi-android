// Port of DSPi-Console-b5:DSPi Console/DSPViewModel.swift "GPIO Pin Conflict
// Resolution" (PinRole, PinAssignment, PinConsumer, pinAssignment,
// pinInUseBy, activePinAssignments) and of DSPi-Console-Windows
// Settings/PinNavigation.cs `PinConflict` + HardwarePins.cs page ids.
package se.bofr.dspi.feature.settings

import se.bofr.dspi.bridge.PinOwner
import se.bofr.dspi.core.DspState

/** What a claimed GPIO is doing; also the Overview's section order. */
enum class PinRole(val title: String, val tint: Long, val page: SettingsCategory) {
    OUTPUT("Outputs", 0xFF0278C7, SettingsCategory.OUTPUT_ASSIGNMENT),
    CLOCK("Clocks", 0xFFBA3822, SettingsCategory.I2S_CONFIG),
    INPUT("Inputs", 0xFF04856F, SettingsCategory.SPDIF_INPUT),
    CONTROL("Control", 0xFF9543A7, SettingsCategory.CONTROL_INTERFACES),
    UTILITY("Other", 0xFF807701, SettingsCategory.GLOBAL_PARAMS);

    companion object {
        /** The bridge's `PinOwner.role` strings. */
        fun ofBridge(role: String): PinRole = when (role) {
            "output" -> OUTPUT
            "clock" -> CLOCK
            "input" -> INPUT
            "control" -> CONTROL
            else -> UTILITY
        }
    }
}

/**
 * One GPIO and the feature holding it. [page] is where it is set (Windows
 * `PinAssignment.PageId`), so the Overview can navigate there.
 */
data class PinAssignment(val pin: Int, val label: String, val role: PinRole, val page: SettingsCategory = role.page)

/** Who asks, so a picker never sees its own pin as taken (macOS `PinConsumer`). */
sealed interface PinConsumer {
    /** Slot or PDM index into `outputPins`. */
    data class Output(val index: Int) : PinConsumer

    data object I2sBck : PinConsumer

    data object I2sBckSlave : PinConsumer

    data object Mck : PinConsumer

    data class SpdifRx(val index: Int) : PinConsumer

    data class I2sRx(val pair: Int) : PinConsumer

    data object DacMute : PinConsumer

    data object UartCtrl : PinConsumer

    data object I2cCtrl : PinConsumer

    data class ControlSurface(val slot: Int) : PinConsumer

    data object AdatOut : PinConsumer

    data object AdatIn : PinConsumer
}

/** DAC hardware mute config (macOS `DacHwMuteConfig`; directory sector, not per preset). */
data class DacHwMuteConfig(
    val enabled: Boolean = false,
    val activeLow: Boolean = true,
    val pin: Int = DAC_HW_MUTE_PIN_NONE,
    val holdMs: Int = 5,
    val releaseMs: Int = 0
) {
    companion object {
        const val DAC_HW_MUTE_PIN_NONE = 0xFF

        fun from(s: DspState) = DacHwMuteConfig(s.dacMuteEnabled, s.dacMuteActiveLow, s.dacMutePin, s.dacMuteHoldMs, s.dacMuteReleaseMs)
    }
}

/** A control-interface's pins and whether it is live (holds them). */
data class CtrlClaim(val live: Boolean, val pinA: Int, val pinB: Int)

/**
 * Everything [pinAssignment] decides over: the (draft) hardware config, the
 * device's DAC mute and control-interface state, and the live control-surface
 * bindings (from `DspiBridge.pinOwners()`, which reads the core's CS pin uses).
 */
data class PinContext(
    val config: OutputConfigSnapshot,
    val dacMute: DacHwMuteConfig = DacHwMuteConfig(),
    val uart: CtrlClaim = CtrlClaim(false, 0, 1),
    val i2c: CtrlClaim = CtrlClaim(false, 2, 3),
    /** (slot, pin) of each live control-surface binding pin. */
    val csPins: List<Pair<Int, Int>> = emptyList()
) {
    companion object {
        fun from(config: OutputConfigSnapshot, state: DspState, owners: List<PinOwner>): PinContext = PinContext(
            config = config,
            dacMute = DacHwMuteConfig.from(state),
            uart = CtrlClaim(state.ctrlStatus.uartLive, state.uart.txPin, state.uart.rxPin),
            i2c = CtrlClaim(state.ctrlStatus.i2cLive, state.i2c.sdaPin, state.i2c.sclPin),
            csPins = owners.mapNotNull { o ->
                CS_OWNER.matchEntire(o.owner)?.let { m -> (m.groupValues[1].toInt() - 1) to o.pin }
            }
        )

        private val CS_OWNER = Regex("Control Surface (\\d+)")
    }
}

/**
 * GPIOs offered to the user (macOS `HardwareSettingsTab.validPins`, firmware
 * `is_valid_gpio_pin`): 0–22 and 26–28.
 */
val VALID_PINS: List<Int> = (0..22).toList() + listOf(26, 27, 28)

/**
 * The claim on [pin], or null if free — the one authority every picker and
 * the Overview ask (macOS `pinAssignment(_:excluding:)`). Reservations follow
 * the firmware: optional S/PDIF inputs, ADAT, control interfaces and control
 * surfaces hold a pin only while enabled / live.
 */
fun PinContext.pinAssignment(pin: Int, excluding: PinConsumer? = null): PinAssignment? {
    val c = config
    for (slot in 0 until c.numOutputSlots) {
        if (excluding != PinConsumer.Output(slot) && c.outputPins.getOrNull(slot) == pin) {
            return PinAssignment(pin, "Output ${slot + 1}", PinRole.OUTPUT)
        }
    }
    val pdm = c.pdmIndex
    if (pdm < c.outputPins.size && pdm < c.numPinOutputs && excluding != PinConsumer.Output(pdm) && c.outputPins[pdm] == pin) {
        return PinAssignment(pin, "Subwoofer", PinRole.OUTPUT)
    }
    if (excluding != PinConsumer.I2sBck) {
        if (pin == c.i2sBckPin) return PinAssignment(pin, "I2S BCK", PinRole.CLOCK)
        if (pin == c.i2sBckPin + 1) return PinAssignment(pin, "I2S LRCLK", PinRole.CLOCK)
    }
    if (excluding != PinConsumer.I2sBckSlave && c.i2sClockPinMode == OutputConfigSnapshot.I2S_CLOCK_PIN_MODE_SPLIT) {
        if (pin == c.i2sBckPinSlave) return PinAssignment(pin, "I2S Slave BCK", PinRole.CLOCK)
        if (pin == c.i2sBckPinSlave + 1) return PinAssignment(pin, "I2S Slave LRCLK", PinRole.CLOCK)
    }
    if (excluding != PinConsumer.Mck && pin == c.mckPin) return PinAssignment(pin, "I2S MCK", PinRole.CLOCK)
    if (c.inputSourceSupported) {
        if (excluding != PinConsumer.SpdifRx(0) && pin == c.spdifRxPins.getOrNull(0)) {
            val label = if (c.multiSpdifSupported && c.anyOptionalSpdifEnabled) "S/PDIF 1 RX" else "S/PDIF RX"
            return PinAssignment(pin, label, PinRole.INPUT)
        }
        if (c.multiSpdifSupported) {
            for (idx in 1 until c.spdifInputCount) {
                if (!c.spdifInputEnabled(idx)) continue
                if (excluding != PinConsumer.SpdifRx(idx) && pin == c.spdifRxPins.getOrNull(idx)) {
                    return PinAssignment(pin, "S/PDIF ${idx + 1} RX", PinRole.INPUT)
                }
            }
        }
    }
    if (c.i2sInputSupported) {
        for (pair in 0 until minOf(c.i2sActivePairs, c.i2sRxPins.size)) {
            if (excluding != PinConsumer.I2sRx(pair) && c.i2sRxPins[pair] == pin) {
                return PinAssignment(pin, if (c.i2sMaxPairs > 1) "I2S RX ${pair + 1}" else "I2S RX", PinRole.INPUT)
            }
        }
    }
    if (excluding != PinConsumer.DacMute && dacMute.enabled && dacMute.pin != DacHwMuteConfig.DAC_HW_MUTE_PIN_NONE && pin == dacMute.pin) {
        return PinAssignment(pin, "DAC Mute", PinRole.UTILITY, SettingsCategory.GLOBAL_PARAMS)
    }
    // The ADAT input may share the output pin (loopback self-test): one-directional
    if (excluding != PinConsumer.AdatOut && excluding != PinConsumer.AdatIn && c.adatSupported && c.adatEnabled && pin == c.adatPin) {
        return PinAssignment(pin, "ADAT Output", PinRole.OUTPUT)
    }
    if (excluding != PinConsumer.AdatIn &&
        c.adatInputSupported &&
        c.adatInputEnabled &&
        c.adatInputPin != OutputConfigSnapshot.PIN_UNSET &&
        pin == c.adatInputPin
    ) {
        return PinAssignment(pin, "ADAT Input", PinRole.INPUT)
    }
    if (excluding != PinConsumer.UartCtrl && uart.live && (pin == uart.pinA || pin == uart.pinB)) {
        return PinAssignment(pin, "UART Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
    }
    if (excluding != PinConsumer.I2cCtrl && i2c.live && (pin == i2c.pinA || pin == i2c.pinB)) {
        return PinAssignment(pin, "I2C Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
    }
    for ((slot, p) in csPins) {
        if (excluding != PinConsumer.ControlSurface(slot) && p == pin) {
            return PinAssignment(pin, "Control Surface ${slot + 1}", PinRole.CONTROL, SettingsCategory.CONTROL_SURFACES)
        }
    }
    return null
}

/** The owner's name, or null if [pin] is free (macOS `pinInUseBy`). */
fun PinContext.pinInUseBy(pin: Int, excluding: PinConsumer? = null): String? = pinAssignment(pin, excluding)?.label

/** Every claimed GPIO among [pins], in pin order (macOS `activePinAssignments`). */
fun PinContext.activePinAssignments(pins: List<Int> = VALID_PINS): List<PinAssignment> = pins.mapNotNull { pinAssignment(it) }

/** A pin two features would hold at once, with every claimant. */
data class PinConflict(val pin: Int, val claimants: List<PinAssignment>)

/**
 * Pins claimed by more than one feature in this (draft) configuration — what
 * a Save would be refused for. The ADAT input on the ADAT output pin is the
 * firmware's deliberate loopback exception and not a conflict.
 */
fun PinContext.pinConflicts(): List<PinConflict> {
    val claims = HashMap<Int, MutableList<PinAssignment>>()
    fun add(pin: Int, label: String, role: PinRole, page: SettingsCategory = role.page) {
        if (pin == OutputConfigSnapshot.PIN_UNSET) return
        claims.getOrPut(pin) { ArrayList() } += PinAssignment(pin, label, role, page)
    }
    val c = config
    for (slot in 0 until c.numOutputSlots) c.outputPins.getOrNull(slot)?.let { add(it, "Output ${slot + 1}", PinRole.OUTPUT) }
    if (c.pdmIndex < c.outputPins.size && c.pdmIndex < c.numPinOutputs) add(c.outputPins[c.pdmIndex], "Subwoofer", PinRole.OUTPUT)
    add(c.i2sBckPin, "I2S BCK", PinRole.CLOCK)
    add(c.i2sBckPin + 1, "I2S LRCLK", PinRole.CLOCK)
    if (c.i2sClockPinMode == OutputConfigSnapshot.I2S_CLOCK_PIN_MODE_SPLIT) {
        add(c.i2sBckPinSlave, "I2S Slave BCK", PinRole.CLOCK)
        add(c.i2sBckPinSlave + 1, "I2S Slave LRCLK", PinRole.CLOCK)
    }
    // macOS holds the MCK pin whether or not MCK runs (pinAssignment)
    add(c.mckPin, "I2S MCK", PinRole.CLOCK)
    if (c.inputSourceSupported) {
        add(
            c.spdifRxPins.getOrElse(0) {
                OutputConfigSnapshot.PIN_UNSET
            },
            if (c.anyOptionalSpdifEnabled) "S/PDIF 1 RX" else "S/PDIF RX",
            PinRole.INPUT
        )
        for (idx in 1 until c.spdifInputCount) {
            if (c.spdifInputEnabled(idx)) {
                add(
                    c.spdifRxPins.getOrElse(idx) {
                        OutputConfigSnapshot.PIN_UNSET
                    },
                    "S/PDIF ${idx + 1} RX",
                    PinRole.INPUT
                )
            }
        }
    }
    if (c.i2sInputSupported) {
        for (pair in 0 until minOf(c.i2sActivePairs, c.i2sRxPins.size)) {
            add(c.i2sRxPins[pair], if (c.i2sMaxPairs > 1) "I2S RX ${pair + 1}" else "I2S RX", PinRole.INPUT)
        }
    }
    if (dacMute.enabled) add(dacMute.pin, "DAC Mute", PinRole.UTILITY, SettingsCategory.GLOBAL_PARAMS)
    val adatOut = c.adatSupported && c.adatEnabled
    if (adatOut) add(c.adatPin, "ADAT Output", PinRole.OUTPUT)
    val adatInShared = adatOut && c.adatInputPin == c.adatPin
    if (c.adatInputSupported && c.adatInputEnabled && !adatInShared) add(c.adatInputPin, "ADAT Input", PinRole.INPUT)
    if (uart.live) {
        add(uart.pinA, "UART Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
        add(uart.pinB, "UART Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
    }
    if (i2c.live) {
        add(i2c.pinA, "I2C Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
        add(i2c.pinB, "I2C Control", PinRole.CONTROL, SettingsCategory.CONTROL_INTERFACES)
    }
    for ((slot, p) in csPins) add(p, "Control Surface ${slot + 1}", PinRole.CONTROL, SettingsCategory.CONTROL_SURFACES)
    return claims.filterValues { it.size > 1 }.toSortedMap().map { (pin, list) -> PinConflict(pin, list) }
}

/**
 * Naming the thing in the way (Windows `PinConflict.FirstHeld`/`Describe`):
 * the first of [pins] something already holds, as a sentence, or null.
 */
fun PinContext.describeFirstHeld(pins: List<Int>, excluding: PinConsumer?, lead: String? = null): String? {
    for (p in pins) {
        val claim = pinAssignment(p, excluding) ?: continue
        val text = "GPIO ${claim.pin} is already used by ${claim.label}."
        return if (lead.isNullOrEmpty()) text else "$lead $text"
    }
    return null
}

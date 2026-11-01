// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `OutputConfigSnapshot`
// (+ the HardwareSettingsTab / DSPViewModel capability helpers it is read with).
package se.bofr.dspi.feature.settings

import se.bofr.dspi.bridge.HardwareCaps
import se.bofr.dspi.core.DspState

/**
 * The device-global hardware configuration (pins, output types, I2S clocks,
 * S/PDIF / I2S / ADAT inputs) as the Settings pages show it.
 *
 * What the hardware pages show and the pin authority decides over; the
 * revert baseline itself is the bridge's (`beginOutputEdit` /
 * `revertOutputConfig`, F2).
 *
 * Indices: [outputPins] has `numOutputSlots + 1` live entries (the last is the
 * PDM subwoofer); [spdifRxPins] 4 (input 1..4); [i2sRxPins] 4 pairs.
 * [i2sInputRate] and [mckMultiplier] are the core's indices (0 = 44.1 kHz /
 * 128x …), as the Linux client uses them.
 */
data class OutputConfigSnapshot(
    val platformName: String = "RP2350",
    val numPinOutputs: Int = 5,
    val outputPins: List<Int> = listOf(6, 7, 8, 9, 10),
    val outputSlotTypes: List<Int> = List(4) { 0 },
    val i2sBckPin: Int = 14,
    val mckEnabled: Boolean = false,
    val mckPin: Int = 13,
    val mckMultiplier: Int = 0,
    val spdifRxPins: List<Int> = listOf(5, 20, 21, 22),
    val spdifInputsEnabled: Int = 1,
    val i2sRxPins: List<Int> = listOf(1, 2, 3, 4),
    val i2sInputChannels: Int = 2,
    val i2sInputRate: Int = 1,
    val i2sClockMode: Int = 0,
    val i2sClockPinMode: Int = 0,
    val i2sBckPinSlave: Int = 16,
    val adatEnabled: Boolean = false,
    val adatPin: Int = ADAT_PIN_DEFAULT,
    val adatInputEnabled: Boolean = false,
    val adatInputPin: Int = PIN_UNSET,
    val adatInputClockMode: Int = 0,
    val inputSource: Int = 0,
    /** What the firmware supports (`DspiBridge.hardwareCaps`); defaults: an RP2350 on V32. */
    val caps: HardwareCaps = FULL_CAPS
) {
    val isRp2040: Boolean get() = platformName == "RP2040"
    val isRp2350: Boolean get() = platformName == "RP2350"
    val isStm32: Boolean get() = platformName == "STM32H723"

    /** S/PDIF / I2S output slots (the PDM subwoofer excluded). */
    val numOutputSlots: Int get() = (numPinOutputs - 1).coerceAtLeast(0)

    /** Index of the PDM subwoofer in [outputPins]. */
    val pdmIndex: Int get() = numOutputSlots

    val anySlotIsI2S: Boolean get() = (0 until numOutputSlots).any { outputSlotTypes.getOrElse(it) { 0 } == OUTPUT_TYPE_I2S }

    // ── Capabilities (macOS DSPViewModel flags; inferred from the platform, D-SETTINGS-3) ──
    /** No caps flag for it: STM32 has no input switching (macOS `inputSourceSupported`). */
    val inputSourceSupported: Boolean get() = !isStm32
    val multiSpdifSupported: Boolean get() = caps.multiSpdifSupported

    /** S/PDIF inputs the receiver can switch between. */
    val spdifInputCount: Int get() = if (caps.multiSpdifSupported) caps.spdifInputCount.coerceIn(1, 4) else 1
    val i2sInputSupported: Boolean get() = caps.i2sInputSupported
    val i2sMaxPairs: Int get() = if (caps.i2sMultichannelInput) 4 else 1
    val i2sClockModeSupported: Boolean get() = caps.i2sClockModeSupported
    val i2sClockPinModeSupported: Boolean get() = caps.i2sClockPinModeSupported
    val adatSupported: Boolean get() = caps.adatOutSupported
    val adatInputSupported: Boolean get() = caps.adatInputSupported

    /** Pairs whose data pins are live (only those reserve their pins). */
    val i2sActivePairs: Int get() = (i2sInputChannels / 2).coerceIn(1, 4).coerceAtMost(i2sMaxPairs)

    fun spdifInputEnabled(index: Int): Boolean = index == 0 || (spdifInputsEnabled shr index) and 1 == 1

    /** Inputs 1..N enabled (the count selector's value). */
    val spdifEnabledCount: Int get() {
        var n = 1
        for (k in 1 until 4) if (spdifInputEnabled(k)) n = k + 1
        return n
    }

    val anyOptionalSpdifEnabled: Boolean get() = (1 until 4).any { spdifInputEnabled(it) }

    /** I2S slave role is running (mode slave and source I2S). */
    val i2sSlaveActive: Boolean get() = i2sClockMode == I2S_CLOCK_MODE_SLAVE && inputSource == INPUT_SOURCE_I2S

    /** CLK_GPOUTn-capable pins (macOS `mckValidPins`). */
    val mckValidPins: List<Int> get() = when {
        isRp2040 -> listOf(21)
        anySlotIsI2S -> listOf(13, 21)
        else -> listOf(13, 15, 21)
    }

    companion object {
        /** Everything supported (previews, tests). */
        val FULL_CAPS = HardwareCaps(
            multiSpdifSupported = true,
            spdifInputCount = 4,
            i2sInputSupported = true,
            i2sMultichannelInput = true,
            i2sClockModeSupported = true,
            i2sClockPinModeSupported = true,
            adatOutSupported = true,
            adatInputSupported = true,
            limiterSupported = true
        )
        const val PIN_UNSET = 0xFF
        const val ADAT_PIN_DEFAULT = 12
        const val OUTPUT_TYPE_SPDIF = 0
        const val OUTPUT_TYPE_I2S = 1
        const val I2S_CLOCK_MODE_MASTER = 0
        const val I2S_CLOCK_MODE_SLAVE = 1
        const val I2S_CLOCK_PIN_MODE_UNIFIED = 0
        const val I2S_CLOCK_PIN_MODE_SPLIT = 1
        const val I2S_BCK_ROLE_MASTER = 0
        const val I2S_BCK_ROLE_SLAVE = 1
        const val INPUT_SOURCE_I2S = 2

        /** Default data pins of the output slots (macOS `defaultDataPins`) and the PDM pin. */
        val DEFAULT_DATA_PINS = listOf(6, 7, 8, 9)
        const val DEFAULT_PDM_PIN = 10

        /** I2S input rates by the core's index. */
        val INPUT_RATE_LABELS = listOf("44.1 kHz", "48 kHz", "96 kHz")

        fun from(s: DspState, platformName: String, caps: HardwareCaps): OutputConfigSnapshot = OutputConfigSnapshot(
            platformName = platformName,
            numPinOutputs = s.numPinOutputs,
            outputPins = s.outputPins,
            outputSlotTypes = s.outputTypes,
            i2sBckPin = s.i2sBckPin,
            mckEnabled = s.mckEnabled,
            mckPin = s.mckPin,
            mckMultiplier = s.mckMultiplier,
            spdifRxPins = s.spdifRxPins,
            spdifInputsEnabled = s.spdifInputsEnabled,
            i2sRxPins = s.i2sRxPins,
            i2sInputChannels = s.i2sInputChannels,
            i2sInputRate = s.i2sInputRate,
            i2sClockMode = s.i2sClockMode,
            i2sClockPinMode = s.i2sClockPinMode,
            i2sBckPinSlave = s.i2sBckPinSlave,
            adatEnabled = s.adatOutEnabled,
            adatPin = s.adatOutPin,
            adatInputEnabled = s.adatInputEnabled,
            adatInputPin = s.adatInputPin,
            adatInputClockMode = s.adatInputClockMode,
            inputSource = s.inputSource,
            caps = caps
        )
    }
}

internal fun List<Int>.with(index: Int, value: Int): List<Int> = if (index in indices) toMutableList().also { it[index] = value } else this

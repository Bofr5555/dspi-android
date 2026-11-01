// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `OutputConfigSnapshot`
// (+ the HardwareSettingsTab / DSPViewModel capability helpers it is read with).
package se.bofr.dspi.feature.settings

import se.bofr.dspi.core.DspState

/**
 * The device-global hardware configuration (pins, output types, I2S clocks,
 * S/PDIF / I2S / ADAT inputs) as the Settings pages show it.
 *
 * As on macOS, hardware edits apply live to the device's RAM; this snapshot
 * is the baseline [SettingsSaveCoordinator] captures at the first edit since
 * the last clean point and re-applies on Revert (limiters included).
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
    // Output limiters (macOS `limiterOutputs`), per output index
    val limiterEnabled: List<Boolean> = emptyList(),
    val limiterThresholdDb: List<Float> = emptyList(),
    val limiterReleaseMs: List<Float> = emptyList(),
    val limiterLinkGroup: List<Int> = emptyList()
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
    val inputSourceSupported: Boolean get() = !isStm32
    val multiSpdifSupported: Boolean get() = !isStm32

    /** S/PDIF inputs the receiver can switch between. */
    val spdifInputCount: Int get() = if (multiSpdifSupported) 4 else 1
    val i2sInputSupported: Boolean get() = !isStm32
    val i2sMaxPairs: Int get() = if (isRp2350) 4 else 1
    val i2sClockModeSupported: Boolean get() = !isStm32
    val i2sClockPinModeSupported: Boolean get() = !isStm32
    val adatSupported: Boolean get() = isRp2350
    val adatInputSupported: Boolean get() = isRp2350

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

        fun from(s: DspState, platformName: String): OutputConfigSnapshot = OutputConfigSnapshot(
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
            limiterEnabled = s.limiterEnabled,
            limiterThresholdDb = s.limiterThresholdDb,
            limiterReleaseMs = s.limiterReleaseMs,
            limiterLinkGroup = s.limiterLinkGroup
        )
    }
}

internal fun List<Int>.with(index: Int, value: Int): List<Int> = if (index in indices) toMutableList().also { it[index] = value } else this

// Port of DSPi-Console (macOS) `DSPi Console/MatrixMixerView.swift` (MatrixOutput,
// MatrixInput, PendingConflict) and the matrix helpers of `DSPViewModel.swift`
// (supports8chInput, effectiveInputChannels, upmixDerivesRows,
// matrixSourceRowCount, matrixRowShortName/FullName, outputEnableWouldConflict,
// pdmOutputIndex, eqWorkerRange) @ v1.1.6-beta5.
// Row-count fallback for USB (no CoreAudio on Android): Linux
// qt/qml/MatrixMixerWindow.qml `inputCount` @ 4683a2c.
package se.bofr.dspi.feature.matrix

import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants

/** One matrix column (macOS `MatrixOutput`). [color] is "#RRGGBB". */
data class MatrixOutput(
    val index: Int,
    /** The output's channel name (renamable, stored on the device). */
    val name: String,
    /** "OUT1" … "OUT9". */
    val descriptor: String,
    val color: String
)

/** One matrix source row (macOS `MatrixInput`). */
data class MatrixInput(
    val index: Int,
    /** Short row label (`matrixRowShortName`). */
    val name: String,
    /** Long label (`matrixRowFullName`); macOS shows it as the tooltip. */
    val fullName: String,
    val color: String
)

/** One crosspoint (macOS reads `vm.matrixRouting/Gain/Invert[row][col]`). */
data class MatrixRoute(val isConnected: Boolean, val gain: Float, val isInverted: Boolean)

/** Per-output controls under OUTPUT. */
data class MatrixOutputControls(
    val enabled: Boolean,
    val gainDB: Float,
    val delayMS: Float,
    val muted: Boolean,
    /** Enabling this output would collide with the other Core 1 user (orange). */
    val wouldConflict: Boolean
)

/** macOS `PendingConflict`: the alert shown before switching Core 1 between PDM and the EQ workers. */
data class PendingConflict(val output: Int, val isPDM: Boolean)

/** The macOS PDM-conflict alert, with its exact wording. */
data class ConflictAlert(val title: String, val message: String, val confirm: String)

/**
 * Everything MatrixMixerView draws, read from the bridge in one pass (the
 * macOS view reads the same values from `DSPViewModel`). Pure data, so the
 * reducers below are JVM-testable.
 */
data class MatrixMixerState(
    val platformName: String,
    val connected: Boolean,
    val is8ch: Boolean,
    val numMatrixInputs: Int,
    val visibleOutputs: List<MatrixOutput>,
    val matrixInputs: List<MatrixInput>,
    /** [row][output index]. */
    val routes: List<List<MatrixRoute>>,
    val outputs: List<MatrixOutputControls>,
    /** Input trim (preamp) per row, used in 8-channel mode. */
    val inputTrimDB: List<Float>,
    val pdmOutputIndex: Int,
    val eqWorkerRange: IntRange,
    val maxDelay: Float
) {
    fun route(row: Int, col: Int): MatrixRoute = routes.getOrNull(row)?.getOrNull(col) ?: MatrixRoute(false, 0f, false)

    fun output(idx: Int): MatrixOutputControls = outputs.getOrElse(idx) { MatrixOutputControls(false, 0f, 0f, false, false) }

    /** macOS `rowNeedsDivider(after:)`: stereo keeps one L/R separator; 8-channel groups pairs. */
    fun rowNeedsDivider(after: Int): Boolean = rowNeedsDivider(after, is8ch, numMatrixInputs)

    /**
     * macOS `requestOutputEnable`: what tapping ENABLE does. Disabling always
     * succeeds; enabling with a Core 1 conflict asks first.
     */
    fun requestOutputEnable(output: Int): EnableAction {
        val o = output(output)
        return when {
            o.enabled -> EnableAction.Set(false)
            o.wouldConflict -> EnableAction.Confirm(PendingConflict(output, isPDM = output == pdmOutputIndex))
            else -> EnableAction.Set(true)
        }
    }

    /** The macOS alert text for [conflict] (`.alert(item: $pendingConflict)`). */
    fun conflictAlert(conflict: PendingConflict): ConflictAlert = if (conflict.isPDM) {
        // 1-based for display
        val first = eqWorkerRange.first + 1
        val last = eqWorkerRange.last + 1
        ConflictAlert("Warning", "Outputs $first-$last will be disabled. Are you sure?", "Enable PDM")
    } else {
        ConflictAlert("Warning", "The PDM output will be disabled. Are you sure?", "Disable PDM")
    }

    companion object {
        /** Reads the bridge (main thread). [activeInputChannels] is `bridge.status.value.activeInputChannels`. */
        fun from(
            bridge: DspiBridge,
            activeInputChannels: Int = bridge.activeInputChannels,
            connected: Boolean = bridge.connected
        ): MatrixMixerState {
            val s = bridge.state.value
            val platform = bridge.platformName
            val numOut = bridge.numOutputChannels
            val numIn = bridge.numInputChannels
            val eff = effectiveInputChannels(platform, numIn, s.inputSource, s.i2sInputChannels, activeInputChannels)
            val upmix = bridge.upmixParams
            val derives = upmixDerivesRows(
                bridge.upmixSupported,
                upmix.getOrElse(DspiConstants.UPMIX_PARAM_ENABLED) { 0f } > 0f,
                eff
            )
            val surroundOff = upmix.getOrElse(DspiConstants.UPMIX_PARAM_SURROUND_MODE) { 0f }.toInt() == UPMIX_SURROUND_MODE_OFF
            val rows = matrixSourceRowCount(derives, surroundOff, eff)
            val outs = (0 until numOut).map { o ->
                val app = o + 2
                MatrixOutput(o, bridge.channelName(app), bridge.channelDescriptor(app).ifEmpty { "OUT${o + 1}" }, bridge.channelColor(app))
            }
            val enabled = (0 until numOut).map { bridge.outputEnabled(it) }
            val pdm = pdmOutputIndex(numOut)
            val eqRange = eqWorkerRange(platform)
            val inputs = (0 until maxOf(rows, 1)).map { i ->
                val inputName = inputChannelName(bridge, i)
                MatrixInput(
                    i,
                    matrixRowShortName(i, derives, inputName),
                    matrixRowFullName(i, derives, inputName),
                    inputColor(bridge, i)
                )
            }
            return MatrixMixerState(
                platformName = platform,
                connected = connected,
                is8ch = supports8chInput(platform, numIn),
                numMatrixInputs = eff,
                visibleOutputs = outs,
                matrixInputs = inputs,
                routes = inputs.map { r ->
                    (0 until numOut).map { o ->
                        MatrixRoute(bridge.matrixRouting(r.index, o), bridge.matrixGain(r.index, o), bridge.matrixInvert(r.index, o))
                    }
                },
                outputs = (0 until numOut).map { o ->
                    MatrixOutputControls(
                        enabled = enabled[o],
                        gainDB = bridge.outputGainDB(o),
                        delayMS = bridge.outputDelayMS(o),
                        muted = bridge.outputMuted(o),
                        wouldConflict = outputEnableWouldConflict(o, enabled, pdm, eqRange)
                    )
                },
                inputTrimDB = inputs.map { if (it.index < numIn) bridge.inputPreampDB(it.index) else 0f },
                pdmOutputIndex = pdm,
                eqWorkerRange = eqRange,
                maxDelay = maxDelayFor(platform)
            )
        }

        private fun inputChannelName(bridge: DspiBridge, row: Int): String {
            val app = bridge.inputAppId(row)
            val name = if (app >= 0) bridge.channelName(app) else ""
            return name.ifEmpty { "USB ${row + 1}" }
        }

        private fun inputColor(bridge: DspiBridge, row: Int): String {
            val app = bridge.inputAppId(row)
            return if (app >= 0) bridge.channelColor(app) else INPUT_PALETTE.getOrElse(row) { ACCENT }
        }
    }
}

/** Result of tapping ENABLE ([MatrixMixerState.requestOutputEnable]). */
sealed interface EnableAction {
    data class Set(val enabled: Boolean) : EnableAction

    data class Confirm(val conflict: PendingConflict) : EnableAction
}

const val BASE_MATRIX_INPUTS = 2
const val MAX_MATRIX_INPUTS = 8
const val UPMIX_SURROUND_MODE_OFF = 0
const val INPUT_SOURCE_USB = 0
const val INPUT_SOURCE_SPDIF = 1
const val INPUT_SOURCE_I2S = 2
const val INPUT_SOURCE_ADAT = 3
const val INPUT_SOURCE_SPDIF2 = 4
const val INPUT_SOURCE_SPDIF3 = 5
const val INPUT_SOURCE_SPDIF4 = 6

/** macOS `ChannelPalette.inputs` (fallback when the bridge has no app id for a row). */
internal val INPUT_PALETTE = listOf("#4A8FE3", "#F57373", "#73C78C", "#EDB34D", "#998CEB", "#E68CC7", "#66C7D1", "#CCB86B")
internal const val ACCENT = "#0A84FF"

/** macOS `supports8chInput` (the wire-version check is the bridge's compat gate). */
fun supports8chInput(platformName: String, numInputChannels: Int): Boolean =
    platformName == "RP2350" && numInputChannels == MAX_MATRIX_INPUTS

/** macOS `chOut1`: 2 on RP2040, 8 on RP2350. */
fun chOut1(platformName: String): Int = if (platformName == "RP2040") BASE_MATRIX_INPUTS else MAX_MATRIX_INPUTS

/**
 * macOS `effectiveInputChannels`. USB: macOS prefers the host-selected
 * CoreAudio format; Android has no equivalent, so the device-reported active
 * count is used (macOS's own fallback, and what Qt does).
 */
fun effectiveInputChannels(
    platformName: String,
    numInputChannels: Int,
    inputSource: Int,
    i2sInputChannels: Int,
    activeInputChannels: Int
): Int {
    val base =
        when (inputSource) {
            INPUT_SOURCE_SPDIF, INPUT_SOURCE_SPDIF2, INPUT_SOURCE_SPDIF3, INPUT_SOURCE_SPDIF4 -> BASE_MATRIX_INPUTS
            INPUT_SOURCE_I2S -> i2sInputChannels
            INPUT_SOURCE_ADAT -> 8
            else -> activeInputChannels
        }
    val top = minOf(chOut1(platformName), maxOf(numInputChannels, BASE_MATRIX_INPUTS))
    return maxOf(BASE_MATRIX_INPUTS, minOf(base, top))
}

/** macOS `upmixDerivesRows` ([upmixSupported] = `firmwareSupportsUpmixer`). */
fun upmixDerivesRows(upmixSupported: Boolean, upmixEnabled: Boolean, effectiveInputChannels: Int): Boolean =
    upmixSupported && upmixEnabled && effectiveInputChannels == BASE_MATRIX_INPUTS

/** macOS `matrixSourceRowCount`: 3 (L/R + C) with surround off, 5 (L/R + C + Ls + Rs) otherwise. */
fun matrixSourceRowCount(upmixDerivesRows: Boolean, surroundOff: Boolean, numMatrixInputs: Int): Int = when {
    !upmixDerivesRows -> numMatrixInputs
    surroundOff -> 3
    else -> 5
}

/** macOS `matrixRowShortName`. */
fun matrixRowShortName(row: Int, upmixDerivesRows: Boolean, inputChannelName: String): String {
    if (upmixDerivesRows) {
        when (row) {
            2 -> return "C"
            3 -> return "Ls"
            4 -> return "Rs"
        }
    }
    return inputChannelName
}

/** macOS `matrixRowFullName`. */
fun matrixRowFullName(row: Int, upmixDerivesRows: Boolean, inputChannelName: String): String {
    if (upmixDerivesRows) {
        when (row) {
            2 -> return "Upmix Centre"
            3 -> return "Upmix Left Surround"
            4 -> return "Upmix Right Surround"
        }
    }
    return inputChannelName
}

/** macOS `pdmOutputIndex` (matrix index 4 / 8). */
fun pdmOutputIndex(numOutputChannels: Int): Int = numOutputChannels - 1

/** macOS `eqWorkerRange`: outputs sharing Core 1 with the PDM sub. */
fun eqWorkerRange(platformName: String): IntRange = if (platformName == "RP2040") 2..3 else 2..7

/** macOS `CompactDelayField(maxDelay:)` in MatrixMixerView: 42 ms on RP2040, 85 ms otherwise. */
fun maxDelayFor(platformName: String): Float = if (platformName == "RP2040") 42f else 85f

/** macOS `outputEnableWouldConflict`. */
fun outputEnableWouldConflict(output: Int, outputEnabled: List<Boolean>, pdmOutputIndex: Int, eqWorkerRange: IntRange): Boolean {
    fun on(i: Int) = outputEnabled.getOrElse(i) { false }
    if (output == pdmOutputIndex) return eqWorkerRange.any { on(it) }
    if (output in eqWorkerRange) return on(pdmOutputIndex)
    return false
}

/** macOS `rowNeedsDivider(after:)`. */
fun rowNeedsDivider(after: Int, is8ch: Boolean, numMatrixInputs: Int): Boolean =
    if (is8ch) after % 2 == 1 && after < numMatrixInputs - 1 else after == 0

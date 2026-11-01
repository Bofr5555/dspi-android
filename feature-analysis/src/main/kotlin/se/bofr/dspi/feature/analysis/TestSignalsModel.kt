// Port of DSPi-Console-b5:DSPi Console/TestSignalsView.swift @ b5fdeac
// (type catalogue, start blocker, transport line, formatters) and
// Constants.swift (SIGGEN_* states, stop reasons, timing models, param
// semantics). UI-free so it is JVM-tested; TestSignalsView.kt draws it.
package se.bofr.dspi.feature.analysis

import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.SiggenCaps
import se.bofr.dspi.core.SiggenConfig
import se.bofr.dspi.core.SiggenParam
import se.bofr.dspi.core.SiggenStatus
import se.bofr.dspi.core.SiggenTypeInfo as SiggenTypeDesc

// Constants.swift (not in the core contract's DspiConstants)
const val SIGGEN_STATE_IDLE = 0
const val SIGGEN_STATE_FADE_IN = 1
const val SIGGEN_STATE_RUN = 2
const val SIGGEN_STATE_GAP = 3
const val SIGGEN_STATE_FADE_OUT = 4

const val SIGGEN_STOP_NONE = 0
const val SIGGEN_STOP_HOST = 1
const val SIGGEN_STOP_COMPLETED = 2
const val SIGGEN_STOP_PRESET = 3
const val SIGGEN_STOP_RECONFIG = 4

const val SIGGEN_TIMING_CONTINUOUS = 0
const val SIGGEN_TIMING_SWEEP = 1
const val SIGGEN_TIMING_PATTERN = 2

const val SIGGEN_PARAM_UNUSED = 0
const val SIGGEN_PARAM_FREQ_HZ = 1
const val SIGGEN_PARAM_MS = 2
const val SIGGEN_PARAM_CYCLES = 3
const val SIGGEN_PARAM_COUNT = 4
const val SIGGEN_PARAM_RATIO = 5
const val SIGGEN_PARAM_PATTERN = 6

const val SIGGEN_SINE = 0
const val SIGGEN_SQUARE = 1
const val SIGGEN_WHITE = 2
const val SIGGEN_PINK = 3
const val SIGGEN_SWEEP_LOG = 4
const val SIGGEN_SWEEP_LIN = 5
const val SIGGEN_SWEEP_STEP = 6
const val SIGGEN_IMPULSE = 7
const val SIGGEN_CLICKS_ALT = 8
const val SIGGEN_POLARITY = 9
const val SIGGEN_TONE_BURST = 10
const val SIGGEN_TONE_PAIR = 11
const val SIGGEN_MULTITONE = 12
const val SIGGEN_ISP = 13
const val SIGGEN_CHANNEL_ID = 14

const val SIGGEN_LEVEL_MIN_DB = -120f
const val SIGGEN_LEVEL_MAX_DB = 0f

/** The level slider's range (the field accepts the full -120..0). */
const val SIGGEN_SLIDER_MIN_DB = -80f

/** `activeChannel` while no output is walking. */
const val SIGGEN_CHANNEL_NONE = 0xFF

val SiggenStatus?.isRunning: Boolean get() = this != null && state != SIGGEN_STATE_IDLE

val SiggenParam.isUsed: Boolean get() = semantic != SIGGEN_PARAM_UNUSED

/**
 * Host-side per-type presentation info (Swift `SiggenTypeInfo`). Parameter
 * ranges and defaults come from the device caps when available; [fallback]
 * mirrors the spec table so the UI renders before the first caps fetch.
 */
data class SiggenTypePresentation(
    val id: Int,
    /** Short, under the glyph. */
    val tileLabel: String,
    /** Status line / transport. */
    val displayName: String,
    /** One-line description. */
    val blurb: String,
    /** p1..p4 field labels. */
    val paramLabels: List<String?>,
    val fallback: SiggenTypeDesc
)

private fun pd(semantic: Int, min: Float, max: Float, def: Float) = SiggenParam(semantic, min, max, def)

private fun fallbackDesc(id: Int, timing: Int, vararg params: SiggenParam) =
    SiggenTypeDesc(id, "", timing, params.toList() + List(4 - params.size) { pd(SIGGEN_PARAM_UNUSED, 0f, 0f, 0f) })

private fun freq(def: Float) = pd(SIGGEN_PARAM_FREQ_HZ, 1f, 30000f, def)

private fun ms(min: Float, max: Float, def: Float) = pd(SIGGEN_PARAM_MS, min, max, def)

private fun cycles(min: Float, max: Float, def: Float) = pd(SIGGEN_PARAM_CYCLES, min, max, def)

private fun info(id: Int, tile: String, name: String, blurb: String, labels: List<String?>, timing: Int, vararg params: SiggenParam) =
    SiggenTypePresentation(id, tile, name, blurb, labels + List(4 - labels.size) { null }, fallbackDesc(id, timing, *params))

/** Swift `siggenTypeInfos`, in tile order. */
val siggenTypeInfos: List<SiggenTypePresentation> =
    listOf(
        info(SIGGEN_SINE, "Sine", "Sine", "Pure tone, THD approx -139 dB", listOf("Frequency"), SIGGEN_TIMING_CONTINUOUS, freq(1000f)),
        info(
            SIGGEN_SQUARE,
            "Square",
            "Square wave",
            "Band-limited (polyBLEP) square",
            listOf("Frequency"),
            SIGGEN_TIMING_CONTINUOUS,
            freq(100f)
        ),
        info(SIGGEN_WHITE, "White", "White noise", "Uniform white noise", emptyList(), SIGGEN_TIMING_CONTINUOUS),
        info(SIGGEN_PINK, "Pink", "Pink noise", "-3 dB/oct, level-safe normalized", emptyList(), SIGGEN_TIMING_CONTINUOUS),
        info(
            SIGGEN_SWEEP_LOG,
            "Log Swp",
            "Log sweep",
            "Exponential sweep for room measurement",
            listOf("Start", "End"),
            SIGGEN_TIMING_SWEEP,
            freq(20f),
            freq(20000f)
        ),
        info(
            SIGGEN_SWEEP_LIN,
            "Lin Swp",
            "Linear sweep",
            "Linear frequency sweep",
            listOf("Start", "End"),
            SIGGEN_TIMING_SWEEP,
            freq(20f),
            freq(20000f)
        ),
        info(
            SIGGEN_SWEEP_STEP,
            "Step Swp",
            "Stepped sweep",
            "Discrete tones stepping up the band",
            listOf("Start", "End", "Steps/octave", "Dwell"),
            SIGGEN_TIMING_SWEEP,
            freq(20f),
            freq(20000f),
            pd(SIGGEN_PARAM_COUNT, 1f, 24f, 3f),
            ms(20f, 10000f, 250f)
        ),
        info(
            SIGGEN_IMPULSE,
            "Impulse",
            "Impulse",
            "Single-sample unit impulses",
            listOf("Period"),
            SIGGEN_TIMING_PATTERN,
            ms(10f, 60000f, 500f)
        ),
        info(
            SIGGEN_CLICKS_ALT,
            "Clicks",
            "Alternating clicks",
            "Clicks with alternating polarity",
            listOf("Period"),
            SIGGEN_TIMING_PATTERN,
            ms(10f, 60000f, 500f)
        ),
        info(
            SIGGEN_POLARITY,
            "Polarity",
            "Polarity pulse",
            "Positive half-sine lobe per period",
            listOf("Pulse width", "Period"),
            SIGGEN_TIMING_PATTERN,
            ms(1f, 100f, 5f),
            ms(10f, 60000f, 500f)
        ),
        info(
            SIGGEN_TONE_BURST,
            "Burst",
            "Tone burst",
            "Sine bursts with raised-cosine edges",
            listOf("Frequency", "On cycles", "Off cycles", "Edge cycles"),
            SIGGEN_TIMING_PATTERN,
            freq(1000f),
            cycles(1f, 1000f, 8f),
            cycles(0f, 1000f, 8f),
            cycles(0f, 100f, 2f)
        ),
        info(
            SIGGEN_TONE_PAIR,
            "2-Tone",
            "Tone pair",
            "IMD test pair (SMPTE / CCIF)",
            listOf("Tone 1", "Tone 2", "Ratio A1/A2"),
            SIGGEN_TIMING_CONTINUOUS,
            freq(60f),
            freq(7000f),
            pd(SIGGEN_PARAM_RATIO, 0.1f, 10f, 4f)
        ),
        info(
            SIGGEN_MULTITONE,
            "Multi",
            "Multitone",
            "Log-spaced tones, Schroeder phases",
            listOf("Tones", "Low", "High"),
            SIGGEN_TIMING_CONTINUOUS,
            pd(SIGGEN_PARAM_COUNT, 2f, 16f, 10f),
            freq(20f),
            freq(20000f)
        ),
        info(
            SIGGEN_ISP,
            "ISP",
            "ISP test",
            "Inter-sample-peak over patterns",
            listOf("Pattern"),
            SIGGEN_TIMING_CONTINUOUS,
            pd(SIGGEN_PARAM_PATTERN, 0f, 1f, 0f)
        ),
        info(
            SIGGEN_CHANNEL_ID,
            "Chan ID",
            "Channel ID",
            "Counted pentatonic blips per channel",
            listOf("Blip length"),
            SIGGEN_TIMING_PATTERN,
            ms(30f, 1000f, 120f)
        )
    )

/** Swift `typeInfo(_:)`: unknown ids show as Sine. */
fun typeInfo(id: Int): SiggenTypePresentation = siggenTypeInfos.firstOrNull { it.id == id } ?: siggenTypeInfos[0]

/** Swift `vm.siggenTypeDesc(for:) ?? info.fallback`: the device's descriptor when it gave one. */
fun siggenTypeDesc(caps: SiggenCaps?, id: Int): SiggenTypeDesc {
    if (caps != null && caps.supported && id < caps.typeCount) {
        val d = caps.types.getOrNull(id)
        if (d != null && d.id == id && d.params.size == 4) return d
    }
    return typeInfo(id).fallback
}

/** Swift `unitLabel(_:)`. */
fun siggenUnitLabel(semantic: Int): String = when (semantic) {
    SIGGEN_PARAM_FREQ_HZ -> "Hz"
    SIGGEN_PARAM_MS -> "ms"
    SIGGEN_PARAM_CYCLES -> "cyc"
    SIGGEN_PARAM_RATIO -> "×"
    else -> ""
}

/** Swift `setParamValue`: clamped to the descriptor (a zero max means unbounded); counts are whole. */
fun siggenClampParam(desc: SiggenParam, value: Float): Float {
    val whole = desc.semantic == SIGGEN_PARAM_COUNT || desc.semantic == SIGGEN_PARAM_CYCLES
    val v = if (whole) Math.round(value).toFloat() else value
    val lo = maxOf(v, desc.min)
    return if (desc.max == 0f) lo else minOf(lo, desc.max)
}

/** Decimal places the parameter's field shows (Swift `maxDecimals`). */
fun siggenParamDecimals(semantic: Int): Int = when (semantic) {
    SIGGEN_PARAM_COUNT, SIGGEN_PARAM_CYCLES -> 0
    SIGGEN_PARAM_RATIO -> 2
    else -> 1
}

/** What an output chip shows (Swift `outputChip`): off, on, or on and inverted. */
enum class SiggenOutputState { OFF, ON, INVERTED }

fun siggenOutputState(config: SiggenConfig, output: Int): SiggenOutputState {
    val bit = 1 shl output
    return when {
        config.channelMask and bit == 0 -> SiggenOutputState.OFF
        config.invertMask and bit == 0 -> SiggenOutputState.ON
        else -> SiggenOutputState.INVERTED
    }
}

/** Swift `walkEnabled`: Channel ID always walks. */
fun siggenWalkEnabled(config: SiggenConfig): Boolean =
    config.flags and DspiConstants.SIGGEN_FLAG_WALK != 0 || config.signalType == SIGGEN_CHANNEL_ID

/** Swift `startBlocker`: why Start is disabled, or null. */
fun siggenStartBlocker(connected: Boolean, supported: Boolean, config: SiggenConfig, desc: SiggenTypeDesc): String? = when {
    !connected -> "No device connected"
    !supported -> "Firmware has no signal generator"
    config.channelMask == 0 -> "Select at least one output"
    desc.timing == SIGGEN_TIMING_SWEEP && config.durationMs == 0L -> "Sweep length must be greater than 0"
    else -> null
}

/** Swift `stateLabelAndColor` (label half; the colour is chosen by the view). */
fun siggenStateLabel(state: Int): String = when (state) {
    SIGGEN_STATE_FADE_IN -> "Fading in"
    SIGGEN_STATE_RUN -> "Running"
    SIGGEN_STATE_GAP -> "Gap"
    SIGGEN_STATE_FADE_OUT -> "Fading out"
    else -> "Idle"
}

/** Swift `formatElapsed(_:)` (Signal Generator). */
fun siggenFormatElapsed(ms: Long): String {
    val s = ms / 1000.0
    if (s < 60) return cFixed(s, 1) + " s"
    return "${(s / 60).toInt()}:" + cFixed(s % 60, 1).padStart(4, '0')
}

/** Swift `formatFreq(_:)`. */
fun siggenFormatFreq(hz: Float): String = if (hz >= 1000) cFixed(hz / 1000.0, 2) + " kHz" else cFixed(hz.toDouble(), 0) + " Hz"

/** Swift `transportTitle`. */
fun siggenTransportTitle(status: SiggenStatus?, blocker: String?, connected: Boolean, supported: Boolean, config: SiggenConfig): String {
    if (status.isRunning) {
        val active = typeInfo(status!!.signalType)
        return "${siggenStateLabel(status.state)} · ${active.displayName}"
    }
    if (blocker != null && connected && supported) return blocker
    return "Ready · ${typeInfo(config.signalType).displayName}"
}

/** Swift `transportDetail`; [outputName] names output index `o`. */
fun siggenTransportDetail(status: SiggenStatus?, config: SiggenConfig, outputName: (Int) -> String): String {
    if (status != null && status.isRunning) {
        val parts = mutableListOf(siggenFormatElapsed(status.elapsedMs))
        if (status.currentFreq > 0) parts += siggenFormatFreq(status.currentFreq)
        if (status.cyclesDone > 0) parts += "cycle ${status.cyclesDone}"
        if (status.activeChannel != SIGGEN_CHANNEL_NONE) parts += outputName(status.activeChannel)
        parts += "edits apply live"
        return parts.joinToString(" · ")
    }
    return when (status?.stopReason ?: SIGGEN_STOP_NONE) {
        SIGGEN_STOP_COMPLETED -> "Finished after ${siggenFormatElapsed(status!!.elapsedMs)}"
        SIGGEN_STOP_HOST -> "Stopped"
        SIGGEN_STOP_PRESET -> "Stopped by preset load"
        else -> {
            val n = Integer.bitCount(config.channelMask)
            "$n output${if (n == 1) "" else "s"} · peak ${cFixed(config.levelDb.toDouble(), 1)} dBFS"
        }
    }
}

/**
 * `String(format: "%.Nf")` as Swift/C prints it: the double's exact value,
 * ties to even (Java's Formatter rounds ties up, so 31.25 would read 31.3).
 */
fun cFixed(value: Double, decimals: Int): String {
    if (!value.isFinite()) return value.toString()
    val s = java.math.BigDecimal(value).setScale(decimals, java.math.RoundingMode.HALF_EVEN).toPlainString()
    return if (s.startsWith("-") && s.trimStart('-').all { it == '0' || it == '.' }) s.substring(1) else s
}

/** Values in a field: up to [decimals] places, trailing zeros stripped (ValueField `stripTrailingZeros`). */
fun formatFieldValue(v: Float, decimals: Int): String {
    if (decimals <= 0) return Math.round(v).toString()
    var s = cFixed(v.toDouble(), decimals)
    if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
    return if (s == "-0") "0" else s
}

// Port of DSPi-Console:DSPi Console/TubeModellerView.swift @ v1.1.6-beta5
// (b5fdeac); core driving as Linux qt/qml/TubeModellerWindow.qml
// (setTubeParam(TUBE_PARAM_*, v); selecting a type makes the core copy the
// four character knobs, editing one of them drops the type to Custom — the
// core mirrors the firmware here, commands.rs `set_tube_param`). The glow
// follows the loudest routed output from the status peaks (bridge.status).
package se.bofr.dspi.feature.tools

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_ASYM_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_ASYM_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_BIAS_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_BIAS_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_DRIVE_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_DRIVE_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_HARDNESS_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_HARDNESS_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_MIX_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_MIX_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_RECT_SOLID_STATE
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_SAG_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_SAG_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_TRIM_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_TRIM_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_TYPE_CUSTOM
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_XFMR_DAMPING_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_XFMR_DAMPING_MIN
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_XFMR_RES_MAX
import se.bofr.dspi.feature.tools.TubeParameters.Companion.TUBE_XFMR_RES_MIN

// MARK: - Shaper Model

/**
 * `TubeShaper`: the firmware's static waveshaper (spec §1), in Double for the
 * graph: drive, bias, the two knees, the hardness blend, the rest-point offset,
 * mix and trim. Sag, the DC blocker and the output stage (memory) are left out.
 */
data class TubeShaper(
    val driveDB: Float,
    val biasPct: Float,
    val asymDB: Float,
    val hardnessPct: Float,
    val mixPct: Float,
    val trimDB: Float
) {
    val m: Double = 10.0.pow(driveDB / 20.0)
    val b: Double = biasPct / 200.0
    val ratioN: Double = 10.0.pow(-asymDB / 20.0)
    private val h = hardnessPct / 100.0
    private val c1 = 1.5 + 0.375 * h
    private val c3 = -0.5 - 0.75 * h
    private val c5 = 0.375 * h
    private val sP = 1 / (c1 * m)
    private val sN = 10.0.pow(asymDB / 20.0) / (c1 * m)
    private val dryW = 1 - mixPct / 100.0
    private val wetW = mixPct / 100.0 * 10.0.pow(trimDB / 20.0)
    private val v0 = shape(0.0)

    private fun shape(x: Double): Double {
        var t = m * x + b
        if (t < 0) t *= ratioN
        t = t.coerceIn(-1.0, 1.0)
        val t2 = t * t
        val p = t * (c1 + t2 * (c3 + t2 * c5))
        return p * (if (t >= 0) sP else sN)
    }

    /** The shaper's input after drive, bias and the negative-knee ratio; |t| >= 1 is clipped. */
    fun knee(x: Double): Double {
        val t = m * x + b
        return if (t < 0) t * ratioN else t
    }

    /** Wet path only, the rest-point offset removed. */
    fun wet(x: Double): Double = shape(x) - v0

    /** What leaves the module for input [x]. */
    fun output(x: Double): Double = dryW * x + wetW * wet(x)

    /**
     * 2nd and 3rd harmonic of a full-scale sine relative to the fundamental in
     * dB (-120 when absent): a 256-point DFT at three bins.
     */
    fun harmonics(): Pair<Double, Double> {
        val n = 256
        val re = DoubleArray(3)
        val im = DoubleArray(3)
        for (i in 0 until n) {
            val phase = 2 * PI * i / n
            val y = output(sin(phase))
            for (k in 0 until 3) {
                re[k] += y * cos((k + 1) * phase)
                im[k] += y * sin((k + 1) * phase)
            }
        }
        val mag = (0 until 3).map { hypot(re[it], im[it]) }
        fun rel(a: Double): Double = if (mag[0] <= 1e-12 || a <= mag[0] * 1e-6) -120.0 else 20 * log10(a / mag[0])
        return Pair(rel(mag[1]), rel(mag[2]))
    }

    /** Positive knee x (m x + b = 1). */
    val positiveKneeX: Double get() = (1 - b) / m

    /** Negative knee x ((m x + b) ratio_n = -1). */
    val negativeKneeX: Double get() = (-1 / ratioN - b) / m

    companion object {
        /** "AT FULL SCALE" value text: "none" at or below -100 dB. */
        fun harmonicText(db: Double): String = if (db <= -100) "none" else ToolFormat.fixed(db.toFloat(), 0) + " dB"
    }
}

private const val PREFS = "dspi_tools"
private const val KEY_ADVANCED = "tubeModellerAdvanced"

/**
 * TubeModellerView: Basic (showcase | TUBE shelf, Drive/Mix, OUTPUTS) or
 * Advanced (TRANSFER CURVE, STAGE, OUTPUTS | CHARACTER, OUTPUT STAGE).
 */
@Composable
fun TubeModellerView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val tube = TubeParameters.from(st)
    val outputCount = st.numOutputChannels
    val supported = ToolFirmware.supportsTube(st.formatVersion)
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    // Basic shows the tube and the two controls most people need; remembered across launches.
    var advanced by remember { mutableStateOf(runCatching { prefs.getBoolean(KEY_ADVANCED, false) }.getOrDefault(false)) }
    // What the transfer graph follows during a drag: the committed shaper with one parameter replaced.
    var liveShaper by remember { mutableStateOf<TubeShaper?>(null) }
    val base = tube.shaper()
    androidx.compose.runtime.LaunchedEffect(base) { liveShaper = null }

    fun setParam(idx: Int, v: Float) = bridge.setTubeParam(idx, v)

    @Composable
    fun paramRow(
        title: String,
        unit: String,
        value: Float,
        range: ClosedFloatingPointRange<Float>,
        maxDecimals: Int,
        idx: Int,
        help: String,
        ends: Pair<String, String>? = null,
        shaper: ((Float) -> TubeShaper)? = null
    ) = ParameterRow(
        title = title,
        unit = unit,
        value = value,
        range = range,
        maxDecimals = maxDecimals,
        ends = ends,
        isEnabled = connected,
        help = help,
        live = { v ->
            bridge.setTubeParam(idx, v.coerceIn(range), sendOnly = true)
            if (shaper != null) liveShaper = shaper(v)
        },
        set = { setParam(idx, it.coerceIn(range)) }
    )

    val outputs: @Composable () -> Unit = {
        SectionHeader("OUTPUTS") {
            ToolMenu(
                "Presets",
                connected,
                listOf(
                    "All outputs" to { setParam(DspiConstants.TUBE_PARAM_MASK, ToolMasks.allOutputs(outputCount).toFloat()) },
                    "Exclude sub" to { setParam(DspiConstants.TUBE_PARAM_MASK, ToolMasks.excludeSub(outputCount).toFloat()) },
                    "None" to { setParam(DspiConstants.TUBE_PARAM_MASK, 0f) }
                )
            )
        }
        HelpTip(
            "Tube runs before the crossover and the per-output EQ, where a real preamp sits: a sub output saturates the " +
                "full-band program and then low-passes the result."
        ) {
            ChannelChips(
                count = outputCount,
                isOn = { ToolMasks.isOn(tube.outputMask, it) },
                names = (0 until outputCount).map { bridge.channelName(2 + it) },
                enabled = connected,
                onToggle = { setParam(DspiConstants.TUBE_PARAM_MASK, ToolMasks.toggled(tube.outputMask, it).toFloat()) }
            )
        }
    }

    val drive: @Composable (String, Pair<String, String>?) -> Unit = { help, ends ->
        paramRow(
            "Drive", "dB", tube.driveDB, TUBE_DRIVE_MIN..TUBE_DRIVE_MAX, 1, DspiConstants.TUBE_PARAM_DRIVE, help, ends,
            { base.copy(driveDB = it) }
        )
    }
    val mix: @Composable (String, Pair<String, String>?) -> Unit = { help, ends ->
        paramRow(
            "Mix", "%", tube.mixPct, TUBE_MIX_MIN..TUBE_MIX_MAX, 0, DspiConstants.TUBE_PARAM_MIX, help, ends,
            { base.copy(mixPct = it) }
        )
    }

    ToolScaffold(header = {
        ToolHeader(
            title = "Tube Modeller",
            subtitle = "Valve-style harmonic colour, supply sag and a tube amplifier's output stage",
            isOn = tube.enabled,
            switchEnabled = connected && supported,
            onToggle = { setParam(DspiConstants.TUBE_PARAM_ENABLED, if (it) 1f else 0f) },
            icon = { TubeIcon(lit = tube.enabled, color = accentColor(), modifier = Modifier.size(27.dp)) }
        )
    }, modifier = modifier) {
        if (!supported) {
            UnsupportedNote("Requires firmware with wire format V31 or newer.", "Update the DSPi firmware to use the Tube Modeller.")
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                // The Basic | Advanced switch: only how many controls you see, never the sound.
                SegmentedPicker(
                    listOf("Basic", "Advanced"),
                    listOf(0, 1),
                    if (advanced) 1 else 0,
                    true,
                    { v ->
                        advanced = v == 1
                        runCatching { prefs.edit().putBoolean(KEY_ADVANCED, advanced).apply() }
                    },
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                )
                if (!advanced) {
                    ToolColumns(left = {
                        TubeShowcase(tube, outputCount, status.peaks, connected)
                    }, right = {
                        HelpTip(
                            "Loads the character of a real tube: its bias, asymmetry, knee hardness and sag. Drive, mix and " +
                                "everything in Advanced keep their values."
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                SectionLabel("TUBE")
                                TubeParameters.TUBE_GROUPS.forEach { (title, types) ->
                                    ShelfGroup(title, types, tube.type, connected) { setParam(DspiConstants.TUBE_PARAM_TYPE, it.toFloat()) }
                                }
                            }
                        }
                        HorizontalDivider()
                        drive(
                            "How hard the tube is driven. Drive moves the knee, not the level: at the -12 dB default the knee sits " +
                                "12 dB above full scale and the colour is subtle, the -30 dB floor is close to transparent, and the " +
                                "top of the range is overdrive.",
                            "Clean" to "Overdrive"
                        )
                        mix(
                            "Blends the tube with the untouched signal. Below 100% the original transients stay intact under the " +
                                "colour, which is the easiest way to use heavy drive subtly.",
                            "Dry" to "All tube"
                        )
                        HorizontalDivider()
                        outputs()
                    })
                } else {
                    ToolColumns(left = {
                        SectionHeader("TRANSFER CURVE") {
                            ToolMenu(
                                "Apply preset",
                                connected,
                                TubeParameters.startingPoints.map { p ->
                                    "${p.name} - ${p.detail}" to {
                                        setParam(DspiConstants.TUBE_PARAM_TYPE, p.tubeType.toFloat())
                                        setParam(DspiConstants.TUBE_PARAM_DRIVE, p.driveDB)
                                        setParam(DspiConstants.TUBE_PARAM_RECTIFIER, p.rectifier.toFloat())
                                        setParam(DspiConstants.TUBE_PARAM_DAMPING, p.damping)
                                        setParam(DspiConstants.TUBE_PARAM_RESONANCE, p.resHz)
                                        setParam(DspiConstants.TUBE_PARAM_XFMR, if (p.xfmr) 1f else 0f)
                                        setParam(DspiConstants.TUBE_PARAM_MIX, TUBE_MIX_MAX)
                                        setParam(DspiConstants.TUBE_PARAM_TRIM, 0f)
                                    }
                                }
                            )
                        }
                        val shown = liveShaper ?: base
                        GraphFrame(200.dp) { TubeTransferView(shown, tube.enabled) }
                        TubeHarmonicsReadout(shown)
                        HorizontalDivider()
                        SectionLabel("STAGE")
                        TubeTypeRow(tube, connected) { setParam(DspiConstants.TUBE_PARAM_TYPE, it.toFloat()) }
                        drive(
                            "Gain ahead of the shaper. At 0 dB a full-scale signal just reaches the knee, so this alone sets how " +
                                "hard the stage is driven. The shaper carries matching makeup gain, so clean material keeps its level " +
                                "at every drive; harmonics and sag rise with it.",
                            null
                        )
                        mix(
                            "Blend of the processed signal with the untouched input. The dry path is sample-aligned with the wet " +
                                "one, so blending never combs.",
                            null
                        )
                        paramRow(
                            "Output Trim",
                            "dB",
                            tube.trimDB,
                            TUBE_TRIM_MIN..TUBE_TRIM_MAX,
                            1,
                            DspiConstants.TUBE_PARAM_TRIM,
                            "Level of the processed signal only. A hard-driven, strongly asymmetric setting can push the wet path " +
                                "above full scale; watch the output clip indicators and bring it back here.",
                            shaper = { base.copy(trimDB = it) }
                        )
                        HorizontalDivider()
                        outputs()
                    }, right = {
                        SectionHeader("CHARACTER") {
                            Caption(tube.selectedRow?.let { "from ${it.name}" } ?: "custom")
                        }
                        paramRow(
                            "Bias",
                            "%",
                            tube.biasPct,
                            TUBE_BIAS_MIN..TUBE_BIAS_MAX,
                            0,
                            DspiConstants.TUBE_PARAM_BIAS,
                            "Shifts the operating point along the curve. Positive values give the classic warm second harmonic that " +
                                "grows with level; negative values give the same amount with the even products inverted, which only " +
                                "matters when mixed with the dry signal.",
                            shaper = { base.copy(biasPct = it) }
                        )
                        paramRow(
                            "Asymmetry",
                            "dB",
                            tube.asymDB,
                            TUBE_ASYM_MIN..TUBE_ASYM_MAX,
                            1,
                            DspiConstants.TUBE_PARAM_ASYM,
                            "How much later the negative half reaches its knee than the positive half. Adds even-order content at " +
                                "heavy drive. Zero is symmetric, as in a push-pull stage.",
                            shaper = { base.copy(asymDB = it) }
                        )
                        paramRow(
                            "Knee Hardness",
                            "%",
                            tube.hardnessPct,
                            TUBE_HARDNESS_MIN..TUBE_HARDNESS_MAX,
                            0,
                            DspiConstants.TUBE_PARAM_HARDNESS,
                            "Blends from a soft cubic knee (0%) to a harder quintic one (100%). Clean material stays at the same level " +
                                "at every setting; only how abruptly the stage runs out changes.",
                            shaper = { base.copy(hardnessPct = it) }
                        )
                        Box(Modifier.alpha(if (tube.rectifier == TUBE_RECT_SOLID_STATE) 0.5f else 1f)) {
                            paramRow(
                                "Sag",
                                "%",
                                tube.sagPct,
                                TUBE_SAG_MIN..TUBE_SAG_MAX,
                                0,
                                DspiConstants.TUBE_PARAM_SAG,
                                "Supply-sag compression: sustained heavy drive pulls the gain down slowly, then recovers. The rectifier " +
                                    "below scales the depth and sets the timing."
                            )
                        }
                        HelpTip(
                            "The power-supply rectifier sets how deep and how slow the sag is. Solid state switches sag off entirely; " +
                                "the valve rectifiers get progressively softer and slower from GZ34 to 5Y3."
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("Rectifier", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                SegmentedPicker(
                                    TubeParameters.RECTIFIER_SEGMENTS,
                                    listOf(0, 1, 2, 3),
                                    tube.rectifier,
                                    connected,
                                    { setParam(DspiConstants.TUBE_PARAM_RECTIFIER, it.toFloat()) }
                                )
                                Caption(tube.rectifierSummary)
                            }
                        }
                        HorizontalDivider()
                        SwitchRow(
                            title = "OUTPUT STAGE",
                            subtitle = "A valve amplifier's loose grip on the speaker.",
                            checked = tube.xfmrEnabled,
                            enabled = connected,
                            onChange = { setParam(DspiConstants.TUBE_PARAM_XFMR, if (it) 1f else 0f) },
                            help = "A tube amplifier's high source impedance lets the speaker's own impedance curve shape the " +
                                "response: a broad bump at the woofer resonance and a small lift at the top. Nothing here is " +
                                "nonlinear, and the firmware skips the whole stage while it is off."
                        )
                        // Hidden rather than shown doing nothing: the firmware skips the stage while off.
                        if (tube.xfmrEnabled) {
                            val (bell, top) = tube.xfmrLift
                            paramRow(
                                "Damping Factor",
                                "",
                                tube.xfmrDamping,
                                TUBE_XFMR_DAMPING_MIN..TUBE_XFMR_DAMPING_MAX,
                                1,
                                DspiConstants.TUBE_PARAM_DAMPING,
                                "The speaker's nominal impedance divided by the amplifier's source impedance. A single-ended triode " +
                                    "amplifier without feedback sits around 2 to 3; a push-pull pentode amplifier with feedback around " +
                                    "8 to 15. It sets the size of both the bell and the top lift.",
                                ends = "1 (loose)" to "20 (tight)"
                            )
                            Caption("+${ToolFormat.fixed(bell, 1)} dB at resonance, +${ToolFormat.fixed(top, 1)} dB at the top.")
                            paramRow(
                                "Speaker Resonance",
                                "Hz",
                                tube.xfmrResHz,
                                TUBE_XFMR_RES_MIN..TUBE_XFMR_RES_MAX,
                                0,
                                DspiConstants.TUBE_PARAM_RESONANCE,
                                "Where the loudspeaker resonates in its enclosure, which is where the bell sits. Q is fixed at 0.707, " +
                                    "so the bump is broad. 85 Hz suits a typical small to medium woofer; larger drivers sit lower.",
                                ends = "30 Hz" to "150 Hz"
                            )
                        }
                    })
                }
            }
        }
    }
}

/** Basic mode's showcase: the selected tube, glowing with the music on its outputs. */
@Composable
private fun TubeShowcase(tube: TubeParameters, outputCount: Int, peaks: List<Float>, connected: Boolean) {
    val shape = RoundedCornerShape(10.dp)
    val glow by androidx.compose.animation.core.animateFloatAsState(
        if (tube.enabled) 1f else 0f,
        androidx.compose.animation.core.tween(if (tube.enabled) 900 else 600),
        label = "pool"
    )
    Box(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), shape)
            .background(
                Brush.radialGradient(listOf(ToolColors.orange.copy(alpha = 0.10f * glow), Color.Transparent), radius = 500f),
                shape
            )
            .border(1.dp, Color.Gray.copy(alpha = 0.2f), shape)
            .padding(vertical = 16.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TubeIllustration(
                family = TubeFamily.of(tube.type),
                lit = tube.enabled,
                peaks = peaks,
                outputStart = 2,
                outputCount = outputCount,
                outputMask = tube.outputMask,
                active = connected,
                modifier = Modifier.width(168.dp).height(280.dp)
            )
            Text(tube.selectedRow?.name ?: "Custom", fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
            Text(tube.showcaseCaption, fontSize = 12.sp, color = secondaryColor(), textAlign = TextAlign.Center)
        }
    }
}

/** One shelf group: a 4-column grid of tube chips. */
@Composable
private fun ShelfGroup(title: String, types: IntRange, selected: Int, enabled: Boolean, select: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Caption(title)
        types.chunked(4).forEach { rowTypes ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowTypes.forEach { t ->
                    val row = TubeParameters.tubeTypeRow(t)
                    HelpTip(row?.let { "${it.name}: ${it.style}" } ?: TubeParameters.tubeTypeName(t), Modifier.weight(1f)) {
                        ToolChip(row?.shortName ?: TubeParameters.tubeTypeName(t), selected == t, enabled, Modifier.fillMaxWidth()) {
                            select(t)
                        }
                    }
                }
                repeat(4 - rowTypes.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** Advanced mode's Tube picker (Custom + the three groups) and its caption. */
@Composable
private fun TubeTypeRow(tube: TubeParameters, enabled: Boolean, select: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    HelpTip(
        "Loads the bias, asymmetry, knee hardness and sag of a real tube. Drive, mix, the rectifier and the output stage are " +
            "left alone. Editing any of the four character controls switches this to Custom."
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tube", fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                Box {
                    TextButton(onClick = { open = true }, enabled = enabled) { Text(TubeParameters.tubeTypeName(tube.type) + " ▾") }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text("Custom") }, onClick = {
                            open = false
                            select(TUBE_TYPE_CUSTOM)
                        })
                        TubeParameters.TUBE_GROUPS.forEach { (title, types) ->
                            HorizontalDivider()
                            Text(title, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 12.sp, color = secondaryColor())
                            types.forEach { t ->
                                DropdownMenuItem(text = { Text(TubeParameters.tubeTypeName(t)) }, onClick = {
                                    open = false
                                    select(t)
                                })
                            }
                        }
                    }
                }
            }
            Caption(tube.tubeTypeCaption)
        }
    }
}

/** `TubeHarmonicsReadout`: AT FULL SCALE 2nd / 3rd. */
@Composable
private fun TubeHarmonicsReadout(shaper: TubeShaper) {
    val (second, third) = remember(shaper) { shaper.harmonics() }
    HelpTip(
        "Level of the second and third harmonic relative to the fundamental, for a full-scale sine through the static curve after " +
            "mix and trim. Sag lowers the drive on sustained loud passages and the output stage adds its own low-frequency lift, so " +
            "the running figures sit somewhat lower."
    ) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SectionLabel("AT FULL SCALE")
            Spacer(Modifier.weight(1f))
            listOf("2nd" to second, "3rd" to third).forEach { (label, db) ->
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Caption(label)
                    Text(
                        TubeShaper.harmonicText(db),
                        Modifier.width(56.dp),
                        fontSize = 14.sp,
                        color = if (db > -100) MaterialTheme.colorScheme.onSurface else secondaryColor()
                    )
                }
            }
        }
    }
}

/** `TubeTransferView`: output against input for one full-scale swing. */
@Composable
internal fun TubeTransferView(shaper: TubeShaper, isEnabled: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val accent = accentColor()
    val secondary = secondaryColor()
    val primary = MaterialTheme.colorScheme.onSurface
    val yMax = 1.4
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        fun x(v: Double) = ((v + 1) / 2).toFloat() * w
        fun y(v: Double) = ((yMax - v.coerceIn(-yMax, yMax)) / (2 * yMax)).toFloat() * h
        val grid = Color.Gray.copy(alpha = 0.15f)
        listOf(-1.0, -0.5, 0.5, 1.0).forEach { drawLine(grid, Offset(x(it), 0f), Offset(x(it), h), 1f) }
        listOf(-0.5, 0.5).forEach { drawLine(grid, Offset(0f, y(it)), Offset(w, y(it)), 1f) }
        listOf(-1.0, 1.0).forEach {
            drawLine(
                ToolColors.red.copy(alpha = 0.3f),
                Offset(0f, y(it)),
                Offset(w, y(it)),
                1f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            )
        }
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(x(0.0), 0f), Offset(x(0.0), h), 1f)
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1f)
        if (isEnabled) {
            val xp = shaper.positiveKneeX
            val xn = shaper.negativeKneeX
            if (xp < 1) {
                val x0 = x(maxOf(xp, -1.0))
                drawRect(ToolColors.orange.copy(alpha = 0.10f), Offset(x0, 0f), Size(maxOf(0f, w - x0), h))
            }
            if (xn > -1) {
                val x1 = x(minOf(xn, 1.0))
                drawRect(ToolColors.orange.copy(alpha = 0.10f), Offset(0f, 0f), Size(maxOf(0f, x1), h))
            }
            drawLine(
                primary.copy(alpha = 0.25f),
                Offset(x(-1.0), y(-1.0)),
                Offset(x(1.0), y(1.0)),
                1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
            )
            val path = Path()
            val steps = 240
            for (i in 0..steps) {
                val xi = -1 + 2.0 * i / steps
                val px = x(xi)
                val py = y(shaper.output(xi))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            drawPath(path, accent, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            labelAt(measurer, "Disabled", Offset(w / 2, h / 2), secondary.copy(alpha = 0.5f), 13.sp, mono = false)
        }
        labelAt(measurer, "in", Offset(w - 10.dp.toPx(), y(0.0) + 8.dp.toPx()), secondary.copy(alpha = 0.6f), 9.sp, bold = true)
        labelAt(measurer, "out", Offset(x(0.0) + 12.dp.toPx(), 7.dp.toPx()), secondary.copy(alpha = 0.6f), 9.sp, bold = true)
        labelAt(measurer, "0 dBFS", Offset(22.dp.toPx(), y(1.0) - 7.dp.toPx()), ToolColors.red.copy(alpha = 0.5f), 9.sp)
    }
}

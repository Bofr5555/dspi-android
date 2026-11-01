// Port of DSPi-Console:DSPi Console/SubharmonicSynthView.swift @ v1.1.6-beta5
// (b5fdeac); core driving as Linux qt/qml/SubharmonicSynthWindow.qml:
// setSubharmParam(SUBHARM_PARAM_*, v), the headroom re-read after every change
// and on open, the 10 Hz sub meter while the view is up, and solo switched off
// when the view goes away (it is runtime-only and never saved).
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.MaskField
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_BOOST_HZ
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_BOOST_MAX
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_BOOST_Q
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_CEILING_MAX
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_DIVIDER_GAIN
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_LEVEL_MAX
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_LEVEL_MIN
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_SELECT_ALL
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_SELECT_PERCUSSIVE
import se.bofr.dspi.feature.tools.SubharmParameters.Companion.SUBHARM_SELECT_SUSTAINED
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.ChannelChips
import se.bofr.dspi.ui.HelpTip
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.ParameterRow
import se.bofr.dspi.ui.PullDownMenu
import se.bofr.dspi.ui.SectionHeader
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.SegmentedPicker
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.ToolHeader
import se.bofr.dspi.ui.ValueFormat
import se.bofr.dspi.ui.rememberWhileVisible
import se.bofr.dspi.ui.writeEnabled

/** `SubharmBandView`'s magnitudes: the 70 Hz bell and the steady-state sub levels. */
object SubharmBandMath {
    const val MIN_FREQ = 16.0
    const val MAX_FREQ = 250.0
    const val DB_MIN = -42.0
    const val DB_MAX = 18.0

    /** Analog peaking prototype: A = 10^(boost/40), peak gain A^2 (the firmware's `k (A^2 - 1) v1`). */
    fun bellDB(freq: Double, boostDB: Float): Double {
        if (boostDB <= 0f) return 0.0
        val a = 10.0.pow(boostDB / 40.0)
        val q = SUBHARM_BOOST_Q.toDouble()
        val w = freq / SUBHARM_BOOST_HZ
        val base = (1 - w * w) * (1 - w * w)
        val num = base + (a * w / q).pow(2)
        val den = base + (w / (a * q)).pow(2)
        return 10 * log10(num / den)
    }

    /** Band level + the divider's 0.849 gain + the bell at [freq]. */
    fun subDB(bandDB: Float, freq: Double, boostDB: Float): Double =
        bandDB + 20 * log10(SUBHARM_DIVIDER_GAIN.toDouble()) + bellDB(freq, boostDB)

    fun isOff(bandDB: Float): Boolean = bandDB <= SUBHARM_LEVEL_MIN
}

internal enum class SubharmGraphKey { LOW_DB, HIGH_DB, TOP_DB, BOOST_DB, CEILING_DB }

/** SubharmonicSynthView: BANDS + LEVELS | SELECTIVITY, SUB CEILING, LF BOOST, OUTPUTS. */
@Composable
fun SubharmonicSynthView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val subharm = SubharmParameters.from(st)
    val outputCount = st.numOutputChannels
    // Names are plain getters: read here, where `st` is observed, not inside the skippable column lambdas (audit T3).
    val outputNames = (0 until outputCount).map { bridge.channelName(2 + it) }
    val supported = ToolFirmware.supportsSubharm(st.formatVersion)
    val extended = ToolFirmware.supportsSubharmExtended(st.formatVersion)
    val graphLive =
        rememberGraphLive<SubharmGraphKey>(listOf(subharm.lowDB, subharm.highDB, subharm.topDB, subharm.boostDB, subharm.ceilingDB))

    // Worst-case gain, re-read after every change (and on open).
    // Keyed on the committed parameters only (stateRevision bumps on emitting
    // changes, never on a sendOnly drag tick), so a drag costs no extra reads.
    val revision by bridge.stateRevision.collectAsState()
    val committed = remember(revision) { bridge.state.value.subharm }
    var headroomDB by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(supported, connected, committed) {
        if (supported && connected) headroomDB = bridge.fetchSubharmHeadroom() ?: 0f
    }
    // The live sub meter, 10 Hz while something can be metered.
    var meter by remember { mutableStateOf(emptyList<Float>()) }
    val visible = rememberWhileVisible()
    LaunchedEffect(extended, connected, subharm.enabled, visible) {
        meter = emptyList()
        while (isActive && extended && connected && subharm.enabled && visible) {
            meter = bridge.fetchSubharmMeter()
            delay(100)
        }
        meter = emptyList()
    }
    // Solo mutes the program: never leave it on behind a closed view.
    val bridgeNow by rememberUpdatedState(bridge)
    DisposableEffect(Unit) {
        onDispose { if (bridgeNow.subharmSolo) bridgeNow.setSubharmSolo(false) }
    }

    fun setParam(id: Int, v: Float) = bridge.setSubharmParam(id, v)

    @Composable
    fun bandRow(title: String, source: String, value: Float, id: Int, key: SubharmGraphKey, help: String) = ParameterRow(
        title = title,
        subtitle = "Derived from $source",
        unit = "dB",
        value = value,
        range = SubharmParameters.LEVEL_RANGE,
        maxDecimals = 1,
        ends = "Off" to ValueFormat.signed(SUBHARM_LEVEL_MAX, 0) + " dB",
        displayOverride = if (value <= SUBHARM_LEVEL_MIN) "Off" else null,
        formatLive = { if (it <= SUBHARM_LEVEL_MIN) "Off" else null },
        isEnabled = connected,
        help = help,
        live = { v ->
            val c = v.coerceIn(SubharmParameters.LEVEL_RANGE)
            bridge.setSubharmParam(id, c, sendOnly = true)
            graphLive.set(key, c)
        },
        set = { setParam(id, it.coerceIn(SubharmParameters.LEVEL_RANGE)) }
    )

    ToolScaffold(header = {
        ToolHeader(
            title = "Subharmonic Synthesizer",
            subtitle = "Generates a subharmonic at half the frequency of the source's bass",
            isOn = subharm.enabled,
            switchEnabled = connected && supported,
            onToggle = { setParam(DspiConstants.SUBHARM_PARAM_ENABLED, if (it) 1f else 0f) },
            extras = { if (extended) SoloButton(subharm.solo, connected && subharm.enabled) { bridge.setSubharmSolo(!subharm.solo) } }
        )
    }, modifier = modifier) {
        if (!supported) {
            UnsupportedNote(
                "Requires firmware with wire format V29 or newer.",
                "Update the DSPi firmware to use the Subharmonic Synthesizer."
            )
        } else {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ToolColumns(left = {
                    SectionHeader("BANDS") {
                        PullDownMenu(
                            "Apply preset",
                            connected,
                            SubharmParameters.startingPoints.map { p ->
                                "${p.name} - ${p.detail}" to {
                                    setParam(DspiConstants.SUBHARM_PARAM_LOW, p.low)
                                    setParam(DspiConstants.SUBHARM_PARAM_HIGH, p.high)
                                    setParam(DspiConstants.SUBHARM_PARAM_BOOST, p.boost)
                                    if (extended) setParam(DspiConstants.SUBHARM_PARAM_TOP, SUBHARM_LEVEL_MIN)
                                }
                            }
                        )
                    }
                    GraphFrame(210.dp) {
                        SubharmBandView(
                            lowDB = graphLive[SubharmGraphKey.LOW_DB, subharm.lowDB],
                            highDB = graphLive[SubharmGraphKey.HIGH_DB, subharm.highDB],
                            topDB = graphLive[SubharmGraphKey.TOP_DB, if (extended) subharm.topDB else SUBHARM_LEVEL_MIN],
                            boostDB = graphLive[SubharmGraphKey.BOOST_DB, subharm.boostDB],
                            ceilingDB = graphLive[SubharmGraphKey.CEILING_DB, if (extended) subharm.ceilingDB else SUBHARM_CEILING_MAX],
                            isEnabled = subharm.enabled
                        )
                    }
                    HelpTip(
                        if (headroomDB > 0f) {
                            "This setting can add up to ${ToolFormat.fixed(headroomDB, 1)} dB. Lower the preamp on the inputs feeding " +
                                "the selected outputs by that much, or a loud passage will clip."
                        } else {
                            "This setting cannot push the signal past full scale."
                        }
                    ) {
                        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("HEADROOM COST")
                            Spacer(Modifier.weight(1f))
                            Text(
                                SubharmParameters.headroomText(headroomDB),
                                fontSize = 14.sp,
                                color = if (headroomDB > 0f) MacColors.orange else secondaryColor()
                            )
                        }
                    }
                    HorizontalDivider()
                    SectionLabel("LEVELS")
                    bandRow(
                        "24 - 36 Hz",
                        "48 - 72 Hz",
                        subharm.lowDB,
                        DspiConstants.SUBHARM_PARAM_LOW,
                        SubharmGraphKey.LOW_DB,
                        "Level of the sub synthesized from program content between 48 and 72 Hz. At 0 dB it comes out 1.4 dB below " +
                            "the bass that produced it, which is the divider's own gain."
                    )
                    HorizontalDivider()
                    bandRow(
                        "36 - 56 Hz",
                        "72 - 112 Hz",
                        subharm.highDB,
                        DspiConstants.SUBHARM_PARAM_HIGH,
                        SubharmGraphKey.HIGH_DB,
                        "Level of the sub synthesized from program content between 72 and 112 Hz. This band has its own divider, " +
                            "so a bass note here and a kick in the band below are tracked independently."
                    )
                    if (extended) {
                        HorizontalDivider()
                        bandRow(
                            "56 - 80 Hz",
                            "112 - 160 Hz",
                            subharm.topDB,
                            DspiConstants.SUBHARM_PARAM_TOP,
                            SubharmGraphKey.TOP_DB,
                            "Level of the sub synthesized from program content between 112 and 160 Hz. It ships off: this band " +
                                "reaches up into the range where a divided sub starts to compete with the program's own " +
                                "fundamentals. Turn it up for a subwoofer that cannot reach the lowest octave."
                        )
                    }
                }, right = {
                    if (extended) {
                        SectionLabel("SELECTIVITY")
                        HelpTip(SubharmParameters.selectivityHelp(subharm.selectMode)) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SegmentedPicker(
                                    listOf("All material", "Percussive", "Sustained"),
                                    listOf(SUBHARM_SELECT_ALL, SUBHARM_SELECT_PERCUSSIVE, SUBHARM_SELECT_SUSTAINED),
                                    subharm.selectMode,
                                    connected,
                                    { setParam(DspiConstants.SUBHARM_PARAM_SELECT, it.toFloat()) }
                                )
                                Caption(SubharmParameters.selectivitySummary(subharm.selectMode))
                            }
                        }
                        // Depth and hold are ignored in "all material", so hidden there.
                        if (subharm.selectivityActive) {
                            ParameterRow(
                                title = "Depth",
                                unit = "%",
                                value = subharm.selectDepthPct,
                                range = SubharmParameters.DEPTH_RANGE,
                                maxDecimals = 0,
                                isEnabled = connected,
                                help = "How far the material this mode does not favour is gated down. At 0% the selectivity is " +
                                    "inaudible whatever the mode is set to; 100% is full gating.",
                                live = {
                                    bridge.setSubharmParam(
                                        DspiConstants.SUBHARM_PARAM_DEPTH,
                                        it.coerceIn(SubharmParameters.DEPTH_RANGE),
                                        sendOnly = true
                                    )
                                },
                                set = { setParam(DspiConstants.SUBHARM_PARAM_DEPTH, it.coerceIn(SubharmParameters.DEPTH_RANGE)) }
                            )
                            ParameterRow(
                                title = "Hold",
                                unit = "ms",
                                value = subharm.selectHoldMs,
                                range = SubharmParameters.HOLD_RANGE,
                                maxDecimals = 0,
                                isEnabled = connected,
                                help = if (subharm.selectMode == SUBHARM_SELECT_PERCUSSIVE) {
                                    "The length of the sub burst after each attack."
                                } else {
                                    "How long a band must ring before its sub opens. Every note's first hold period has no sub, so " +
                                        "staccato bass lines get little."
                                },
                                live = {
                                    bridge.setSubharmParam(
                                        DspiConstants.SUBHARM_PARAM_HOLD,
                                        it.coerceIn(SubharmParameters.HOLD_RANGE),
                                        sendOnly = true
                                    )
                                },
                                set = { setParam(DspiConstants.SUBHARM_PARAM_HOLD, it.coerceIn(SubharmParameters.HOLD_RANGE)) }
                            )
                        }
                        HorizontalDivider()
                        SectionLabel("SUB CEILING")
                        ParameterRow(
                            title = "Threshold",
                            unit = "dB",
                            value = subharm.ceilingDB,
                            range = SubharmParameters.CEILING_RANGE,
                            maxDecimals = 0,
                            ends = "-40 dBFS" to "Off",
                            displayOverride = if (subharm.ceilingOff) "Off" else null,
                            formatLive = { if (it >= SUBHARM_CEILING_MAX) "Off" else null },
                            isEnabled = connected,
                            help = "A soft limit on the synthesized sub just before it is mixed back in, capping how far it can push " +
                                "a driver without touching the program signal. It is an absolute level, so a ceiling at full scale " +
                                "limits nothing and means the stage is off. With it on, the headroom cost is only the ceiling's " +
                                "worth. A loud onset overshoots it by a few dB for the first few milliseconds while the limiter's " +
                                "3 ms attack catches up.",
                            live = { v ->
                                val c = v.coerceIn(SubharmParameters.CEILING_RANGE)
                                bridge.setSubharmParam(DspiConstants.SUBHARM_PARAM_CEILING, c, sendOnly = true)
                                graphLive.set(SubharmGraphKey.CEILING_DB, c)
                            },
                            set = { setParam(DspiConstants.SUBHARM_PARAM_CEILING, it.coerceIn(SubharmParameters.CEILING_RANGE)) }
                        )
                        HorizontalDivider()
                    }
                    SectionLabel("LF BOOST")
                    ParameterRow(
                        title = "70 Hz bell",
                        unit = "dB",
                        value = subharm.boostDB,
                        range = SubharmParameters.BOOST_RANGE,
                        maxDecimals = 1,
                        ends = "Off" to ValueFormat.signed(SUBHARM_BOOST_MAX, 0) + " dB",
                        isEnabled = connected,
                        help = "A gentle bell at 70 Hz, Q 0.9, applied to the whole output after the subs are summed. It fills the " +
                            "gap between the synthesized sub and the program's own mid-bass. Meant to stay gentle, as on the dbx.",
                        live = { v ->
                            val c = v.coerceIn(SubharmParameters.BOOST_RANGE)
                            bridge.setSubharmParam(DspiConstants.SUBHARM_PARAM_BOOST, c, sendOnly = true)
                            graphLive.set(SubharmGraphKey.BOOST_DB, c)
                        },
                        set = { setParam(DspiConstants.SUBHARM_PARAM_BOOST, it.coerceIn(SubharmParameters.BOOST_RANGE)) }
                    )
                    HorizontalDivider()
                    SectionHeader("OUTPUTS") {
                        PullDownMenu(
                            "Presets",
                            connected,
                            listOf(
                                "Sub only (recommended)" to
                                    { setParam(DspiConstants.SUBHARM_PARAM_MASK, ToolMasks.subOnly(outputCount).toFloat()) },
                                "All outputs" to {
                                    setParam(DspiConstants.SUBHARM_PARAM_MASK, ToolMasks.allOutputs(outputCount).toFloat())
                                },
                                "None" to { setParam(DspiConstants.SUBHARM_PARAM_MASK, 0f) }
                            )
                        )
                    }
                    HelpTip(
                        "Select the outputs that can actually play 24 to 80 Hz. Subharm runs before the crossover, so a satellite " +
                            "with a highpass loses the sub again - mask it off and save the CPU instead."
                    ) {
                        val accent = accentColor()
                        ChannelChips(
                            count = outputCount,
                            isOn = { ToolMasks.isOn(subharm.outputMask, it) },
                            names = outputNames,
                            enabled = connected,
                            onToggle = {
                                bridge.toggleMaskBit(MaskField.SUBHARM_OUTPUTS, it, SubharmParameters.SUBHARM_DEFAULT_OUTPUT_MASK)
                            },
                            below = if (extended) {
                                { out, on ->
                                    LevelBar(
                                        if (on) {
                                            meter.getOrElse(out) {
                                                0f
                                            }
                                        } else {
                                            0f
                                        },
                                        accent,
                                        Modifier.alpha(if (on) 1f else 0.25f),
                                        3.dp
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                    if (extended) {
                        SwitchRow(
                            title = "Link output pairs",
                            subtitle = "One sub per pair, from its mono sum.",
                            checked = subharm.linkPairs,
                            enabled = connected,
                            onChange = { setParam(DspiConstants.SUBHARM_PARAM_LINK, if (it) 1f else 0f) },
                            help = "Synthesize one sub per output pair from its mono sum and feed it to both channels, as the dbx " +
                                "does. Bass is near-mono in most material, and two independent dividers can land on opposite " +
                                "polarities, which cancels a centred note's sub between the speakers.",
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                })
            }
        }
    }
}

/** The latching SOLO header button. */
@Composable
private fun SoloButton(solo: Boolean, enabled: Boolean, onClick: () -> Unit) {
    HelpTip(
        if (solo) {
            "The selected outputs are carrying the synthesized sub only - the program signal is muted on them. Closing this " +
                "window switches it off."
        } else {
            "Mute the program signal on the selected outputs so the synthesized sub can be heard or measured on its own. Never " +
                "saved to a preset."
        }
    ) {
        Box(
            Modifier
                .padding(end = 4.dp)
                .height(28.dp)
                .alpha(if (enabled) 1f else 0.4f)
                .background(if (solo) MacColors.orange else secondaryColor().copy(alpha = 0.12f), RoundedCornerShape(5.dp))
                .clickable(enabled = writeEnabled(enabled), onClick = onClick)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("SOLO", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (solo) Color.White else secondaryColor())
        }
    }
}

/**
 * `SubharmBandView`: the program bands the dividers listen to, the subs an
 * octave below, the LF boost bell and the sub ceiling, on a real dB scale.
 */
@Composable
internal fun SubharmBandView(
    lowDB: Float,
    highDB: Float,
    topDB: Float,
    boostDB: Float,
    ceilingDB: Float,
    isEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    val measurer = rememberTextMeasurer()
    val accent = accentColor()
    val secondary = secondaryColor()
    val primary = MaterialTheme.colorScheme.onSurface
    val bg = MaterialTheme.colorScheme.surface
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val axisStrip = 13.dp.toPx()
        val annotationStrip = 38.dp.toPx()
        val bottom = h - axisStrip
        fun x(f: Double) = ToolGraphMath.logX(f, SubharmBandMath.MIN_FREQ, SubharmBandMath.MAX_FREQ, w)
        fun y(db: Double): Float {
            val c = db.coerceIn(SubharmBandMath.DB_MIN, SubharmBandMath.DB_MAX)
            val frac = (c - SubharmBandMath.DB_MIN) / (SubharmBandMath.DB_MAX - SubharmBandMath.DB_MIN)
            return (bottom - frac * (bottom - annotationStrip)).toFloat()
        }
        val off = SubharmBandMath::isOff
        val allBandsOff = off(lowDB) && off(highDB) && off(topDB)

        // Grid
        val grid = Color.Gray.copy(alpha = 0.15f)
        listOf(20.0, 50.0, 100.0, 200.0).forEach { drawLine(grid, Offset(x(it), annotationStrip), Offset(x(it), bottom), 1f) }
        listOf(12.0, -12.0, -24.0, -36.0).forEach { drawLine(grid, Offset(0f, y(it)), Offset(w, y(it)), 1f) }
        drawLine(grid, Offset(0f, bottom), Offset(w, bottom), 1f)
        drawLine(Color.Gray.copy(alpha = 0.4f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1f)
        listOf(12, 0, -12, -24, -36).forEach { db ->
            labelAt(measurer, if (db > 0) "+$db" else "$db", Offset(12f, y(db.toDouble()) - 7f), secondary.copy(alpha = 0.5f), 8.sp)
        }

        if (isEnabled) {
            fun sourceBand(lo: Double, hi: Double, dimmed: Boolean) {
                val x0 = x(lo)
                val x1 = x(hi)
                drawRect(
                    primary.copy(alpha = if (dimmed) 0.04f else 0.10f),
                    Offset(x0, annotationStrip),
                    Size(
                        maxOf(0f, x1 - x0),
                        maxOf(
                            0f,
                            bottom - annotationStrip
                        )
                    )
                )
                val bracket = Path().apply {
                    moveTo(x0 + 1, 9.dp.toPx())
                    lineTo(x0 + 1, 5.dp.toPx())
                    lineTo(x1 - 1, 5.dp.toPx())
                    lineTo(x1 - 1, 9.dp.toPx())
                }
                drawPath(bracket, secondary.copy(alpha = if (dimmed) 0.25f else 0.5f), style = Stroke(1f))
                labelAt(
                    measurer,
                    "${lo.toInt()}-${hi.toInt()}",
                    Offset((x0 + x1) / 2, 5.dp.toPx()),
                    secondary.copy(alpha = if (dimmed) 0.4f else 0.85f),
                    8.sp,
                    bold = true,
                    background = bg.copy(alpha = 0.85f)
                )
            }
            sourceBand(48.0, 72.0, off(lowDB))
            sourceBand(72.0, 112.0, off(highDB))
            sourceBand(112.0, 160.0, off(topDB))

            if (boostDB > 0f) {
                val path = Path()
                val steps = 96
                for (i in 0..steps) {
                    val t = i.toDouble() / steps
                    val f = 10.0.pow(
                        log10(SubharmBandMath.MIN_FREQ) + t * (log10(SubharmBandMath.MAX_FREQ) - log10(SubharmBandMath.MIN_FREQ))
                    )
                    val px = (t * w).toFloat()
                    val py = y(SubharmBandMath.bellDB(f, boostDB))
                    if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(
                    path,
                    MacColors.green.copy(alpha = 0.8f),
                    style = Stroke(1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                labelAt(
                    measurer,
                    "LF boost",
                    Offset(
                        x(SUBHARM_BOOST_HZ.toDouble()),
                        maxOf(
                            annotationStrip + 5.dp.toPx(),
                            y(SubharmBandMath.bellDB(SUBHARM_BOOST_HZ.toDouble(), boostDB)) - 8.dp.toPx()
                        )
                    ),
                    MacColors.green.copy(alpha = 0.9f),
                    8.sp,
                    bold = true,
                    mono = false,
                    background = bg.copy(alpha = 0.85f)
                )
            }

            fun subBlock(lo: Double, hi: Double, level: Float, color: Color) {
                val x0 = x(lo)
                val x1 = x(hi)
                fun topAt(f: Double) = y(SubharmBandMath.subDB(level, f, boostDB))
                val shape = Path().apply {
                    moveTo(x0, bottom)
                    val steps = 16
                    for (i in 0..steps) {
                        val t = i.toDouble() / steps
                        val f = lo * (hi / lo).pow(t)
                        lineTo((x0 + t * (x1 - x0)).toFloat(), topAt(f))
                    }
                    lineTo(x1, bottom)
                    close()
                }
                drawPath(shape, color.copy(alpha = 0.4f))
                drawPath(shape, color.copy(alpha = 0.9f), style = Stroke(1f))
                val midTop = topAt(sqrt(lo * hi))
                val inside = bottom - midTop > 18.dp.toPx()
                labelAt(
                    measurer,
                    "${lo.toInt()}-${hi.toInt()}",
                    Offset((x0 + x1) / 2, if (inside) midTop + 8.dp.toPx() else midTop - 6.dp.toPx()),
                    if (inside) Color.White.copy(alpha = 0.9f) else primary.copy(alpha = 0.7f),
                    8.sp,
                    bold = true
                )
            }

            fun octaveArrow(from: Double, to: Double, yy: Float) {
                val x0 = x(from)
                val x1 = x(to)
                drawLine(
                    primary.copy(alpha = 0.3f),
                    Offset(x0, yy),
                    Offset(x1 + 3, yy),
                    1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f))
                )
                val head = Path().apply {
                    moveTo(x1 + 8, yy - 6)
                    lineTo(x1, yy)
                    lineTo(x1 + 8, yy + 6)
                }
                drawPath(head, primary.copy(alpha = 0.45f), style = Stroke(1f))
                labelAt(
                    measurer,
                    "÷2",
                    Offset((x0 + x1) / 2, yy),
                    secondary.copy(alpha = 0.8f),
                    7.sp,
                    bold = true,
                    background = bg.copy(alpha = 0.9f)
                )
            }

            if (!off(lowDB)) {
                subBlock(24.0, 36.0, lowDB, accent)
                octaveArrow(60.0, 30.0, 15.dp.toPx())
            }
            if (!off(highDB)) {
                subBlock(36.0, 56.0, highDB, MacColors.orange)
                octaveArrow(92.0, 46.0, 23.dp.toPx())
            }
            if (!off(topDB)) {
                subBlock(56.0, 80.0, topDB, MacColors.purple)
                octaveArrow(136.0, 68.0, 31.dp.toPx())
            }

            if (ceilingDB < SUBHARM_CEILING_MAX && !allBandsOff) {
                val cy = y(ceilingDB.toDouble())
                val x1 = x(80.0)
                drawLine(
                    MacColors.red.copy(alpha = 0.65f),
                    Offset(0f, cy),
                    Offset(x1, cy),
                    1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
                )
                labelAt(
                    measurer,
                    "ceiling",
                    Offset(x1 + 18.dp.toPx(), cy),
                    MacColors.red.copy(alpha = 0.8f),
                    8.sp,
                    bold = true,
                    mono = false,
                    background = bg.copy(alpha = 0.85f)
                )
            }
            if (allBandsOff) {
                labelAt(
                    measurer,
                    "All bands off",
                    Offset(w / 2, (annotationStrip + bottom) / 2),
                    secondary.copy(alpha = 0.6f),
                    12.sp,
                    mono = false
                )
            }
        } else {
            labelAt(measurer, "Disabled", Offset(w / 2, (annotationStrip + bottom) / 2), secondary.copy(alpha = 0.5f), 13.sp, mono = false)
        }
        listOf(20, 50, 100, 200).forEach { f ->
            labelAt(measurer, "$f", Offset(x(f.toDouble()), h - 6f), secondary.copy(alpha = 0.6f), 9.sp)
        }
    }
}

// Port of DSPi-Console:DSPi Console/LoudnessView.swift @ v1.1.6-beta5 (b5fdeac);
// core driving as Linux qt/qml/LoudnessWindow.qml. Window controller -> an
// internal sub-route of ToolsScreen.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.log10
import kotlin.math.pow
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.ChannelChips
import se.bofr.dspi.ui.ParameterRow
import se.bofr.dspi.ui.PullDownMenu
import se.bofr.dspi.ui.SectionHeader
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.ToolHeader

// MARK: - ISO 226 Data

/** `IsoData`: one ISO 226:2003 row. */
data class IsoData(val f: Float, val af: Float, val lu: Float, val tf: Float)

/** ISO 226:2003 equal-loudness computation, for visualization only. */
object LoudnessCurve {
    val isoTable =
        listOf(
            IsoData(20f, 0.532f, -31.6f, 78.5f),
            IsoData(25f, 0.506f, -27.2f, 68.7f),
            IsoData(31.5f, 0.480f, -23.0f, 59.5f),
            IsoData(40f, 0.455f, -19.1f, 51.1f),
            IsoData(50f, 0.432f, -15.9f, 44.0f),
            IsoData(63f, 0.409f, -13.0f, 37.5f),
            IsoData(80f, 0.387f, -10.3f, 31.5f),
            IsoData(100f, 0.367f, -8.1f, 26.5f),
            IsoData(125f, 0.349f, -6.2f, 22.1f),
            IsoData(160f, 0.330f, -4.5f, 17.9f),
            IsoData(200f, 0.315f, -3.1f, 14.4f),
            IsoData(250f, 0.301f, -2.0f, 11.4f),
            IsoData(315f, 0.288f, -1.1f, 8.6f),
            IsoData(400f, 0.276f, -0.4f, 6.2f),
            IsoData(500f, 0.267f, 0.0f, 4.4f),
            IsoData(630f, 0.259f, 0.3f, 3.0f),
            IsoData(800f, 0.253f, 0.5f, 2.2f),
            IsoData(1000f, 0.250f, 0.0f, 2.4f),
            IsoData(1250f, 0.246f, -2.7f, 3.5f),
            IsoData(1600f, 0.244f, -4.1f, 1.7f),
            IsoData(2000f, 0.243f, -1.0f, -1.3f),
            IsoData(2500f, 0.243f, 1.7f, -4.2f),
            IsoData(3150f, 0.243f, 2.5f, -6.0f),
            IsoData(4000f, 0.242f, 1.2f, -5.4f),
            IsoData(5000f, 0.242f, -2.1f, -1.5f),
            IsoData(6300f, 0.245f, -7.1f, 6.0f),
            IsoData(8000f, 0.254f, -11.2f, 12.6f),
            IsoData(10000f, 0.271f, -10.7f, 13.9f),
            IsoData(12500f, 0.301f, -3.1f, 12.3f),
            IsoData(16000f, 0.310f, -2.0f, 17.0f)
        )

    /** Fixed visualization volume: the curve is shown at -40 dB. */
    const val VISUALIZATION_VOLUME_DB = -40f

    fun iso226SPL(tf: Float, af: Float, lu: Float, phon: Float): Float {
        val b = 0.4f * 10f.pow((tf + lu) / 10f - 9f)
        val threshold = b.pow(af)
        var a = 4.47e-3f * (10f.pow(0.025f * phon) - 1.15f) + threshold
        if (a < 1e-10f) a = 1e-10f
        return (10f / af) * log10(a) - lu + 94f
    }

    fun loudnessCompensationDB(tf: Float, af: Float, lu: Float, refSPL: Float, effectivePhon: Float, intensity: Float): Float {
        if (effectivePhon >= refSPL) return 0f
        val splRef = iso226SPL(tf, af, lu, refSPL)
        val splEff = iso226SPL(tf, af, lu, effectivePhon)
        val flat = effectivePhon - refSPL
        val freq = splEff - splRef
        return (freq - flat) * (intensity / 100f)
    }

    /** `CompensationCurveView.computeCurve()`: (frequency, gain dB) per ISO row. */
    fun computeCurve(refSPL: Float, intensity: Float): List<Pair<Float, Float>> {
        val effectivePhon = (refSPL + VISUALIZATION_VOLUME_DB).coerceAtLeast(20f).coerceAtMost(refSPL)
        return isoTable.map { d -> d.f to loudnessCompensationDB(d.tf, d.af, d.lu, refSPL, effectivePhon, intensity) }
    }
}

internal enum class LoudnessGraphKey { REF_SPL, INTENSITY }

/** LoudnessView: header, COMPENSATION CURVE | PARAMETERS + OUTPUTS. */
@Composable
fun LoudnessView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val loudness = LoudnessParameters.from(st)
    val outputCount = st.numOutputChannels
    val showMask = ToolFirmware.supportsLoudnessMask(st.formatVersion)
    val graphLive = rememberGraphLive<LoudnessGraphKey>(loudness.refSPL to loudness.intensity)

    ToolScaffold(header = {
        ToolHeader(
            title = "Loudness Compensation",
            subtitle = "ISO 226:2003 Fletcher-Munson",
            isOn = st.loudnessEnabled,
            switchEnabled = connected,
            onToggle = { bridge.setLoudness(it) }
        )
    }, modifier = modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToolColumns(left = {
                SectionLabel("COMPENSATION CURVE")
                GraphFrame(200.dp) {
                    CompensationCurveView(
                        refSPL = graphLive[LoudnessGraphKey.REF_SPL, loudness.refSPL],
                        intensity = graphLive[LoudnessGraphKey.INTENSITY, loudness.intensity],
                        isEnabled = st.loudnessEnabled
                    )
                }
            }, right = {
                SectionLabel("PARAMETERS")
                ParameterRow(
                    title = "Reference SPL",
                    unit = "dB",
                    value = loudness.refSPL,
                    range = LoudnessParameters.REF_SPL_RANGE,
                    maxDecimals = 1,
                    isEnabled = connected,
                    caption = "SPL at 1 kHz when USB volume is 0 dB. Lower = more compensation per dB of volume reduction.",
                    live = { v ->
                        bridge.setLoudnessRef(v, sendOnly = true)
                        graphLive.set(LoudnessGraphKey.REF_SPL, v)
                    },
                    set = { bridge.setLoudnessRef(it) }
                )
                HorizontalDivider()
                ParameterRow(
                    title = "Intensity",
                    unit = "%",
                    value = loudness.intensity,
                    range = LoudnessParameters.INTENSITY_RANGE,
                    maxDecimals = 1,
                    isEnabled = connected,
                    caption = "Scales the ISO 226 compensation. 100% = standard curve. 0% = bypassed. >100% = exaggerated.",
                    live = { v ->
                        bridge.setLoudnessIntensity(v, sendOnly = true)
                        graphLive.set(LoudnessGraphKey.INTENSITY, v)
                    },
                    set = { bridge.setLoudnessIntensity(it) }
                )
                if (showMask) {
                    HorizontalDivider()
                    SectionHeader("OUTPUTS") {
                        PullDownMenu(
                            "Presets",
                            connected,
                            listOf(
                                "All outputs" to { bridge.setLoudnessOutputMask(LoudnessParameters.LOUDNESS_DEFAULT_OUTPUT_MASK) },
                                "Slot 1 only (Headphones)" to { bridge.setLoudnessOutputMask(LoudnessParameters.HEADPHONES_MASK) },
                                "None" to { bridge.setLoudnessOutputMask(0x0000) }
                            )
                        )
                    }
                    Caption(
                        "Compensate only the outputs feeding your low-level listening chain. Keep bass-managed pairs " +
                            "(mains + sub) together so the crossover stays coherent."
                    )
                    ChannelChips(
                        count = outputCount,
                        isOn = { ToolMasks.isOn(loudness.outputMask, it) },
                        names = (0 until outputCount).map { bridge.channelName(2 + it) },
                        enabled = connected,
                        onToggle = { bridge.setLoudnessOutputMask(ToolMasks.toggled(loudness.outputMask, it)) }
                    )
                }
            })
        }
    }
}

/** `CompensationCurveView`: the boost at -40 dB volume, on a dynamic dB axis. */
@Composable
internal fun CompensationCurveView(refSPL: Float, intensity: Float, isEnabled: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val accent = accentColor()
    val secondary = secondaryColor()
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val points = LoudnessCurve.computeCurve(refSPL, intensity)
        val (lo, hi) = ToolGraphMath.dynamicDomain(points.map { it.second.toDouble() })
        fun x(f: Double) = ToolGraphMath.logX(f, 20.0, 20000.0, w)
        fun y(g: Double) = ToolGraphMath.linY(g, lo, hi, h)
        val grid = Color.Gray.copy(alpha = 0.15f)
        val steps = ToolGraphMath.gridSteps(lo, hi)
        steps.forEach { drawLine(grid, Offset(0f, y(it)), Offset(w, y(it)), 1f) }
        listOf(100, 1000, 10000).forEach { f -> drawLine(grid, Offset(x(f.toDouble()), 0f), Offset(x(f.toDouble()), h), 1f) }
        listOf(100, 1000, 10000).forEach { f ->
            labelAt(measurer, ToolGraphMath.freqLabel(f), Offset(x(f.toDouble()), h - 6f), secondary.copy(alpha = 0.6f), 9.sp)
        }
        steps.forEach { labelAt(measurer, "${it.toInt()}", Offset(14f, y(it)), secondary.copy(alpha = 0.6f), 9.sp) }
        if (isEnabled) {
            val path = Path()
            points.forEachIndexed { i, (f, g) ->
                val p = Offset(x(f.toDouble()), y(g.toDouble()))
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            drawPath(path, accent, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Info badge
            val badge = Size(84.dp.toPx(), 16.dp.toPx())
            val corner = Offset(w - badge.width - 4.dp.toPx(), 4.dp.toPx())
            drawRoundRect(accent.copy(alpha = 0.8f), corner, badge, CornerRadius(4.dp.toPx()))
            labelAt(
                measurer,
                "Curve at -40dB",
                corner + Offset(badge.width / 2, badge.height / 2),
                Color.White,
                9.sp,
                bold = true,
                mono = false
            )
        } else {
            labelAt(measurer, "Disabled", Offset(w / 2, h / 2), secondary.copy(alpha = 0.5f), 13.sp, mono = false)
        }
    }
}

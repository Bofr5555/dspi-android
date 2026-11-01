// Port of DSPi-Console:DSPi Console/CrossfeedView.swift @ v1.1.6-beta5 (b5fdeac);
// core driving as Linux qt/qml/CrossfeedWindow.qml. Crossfeed runs on output
// PAIRS (outputs 2p / 2p+1) exactly as the firmware and macOS do: the mask is
// a pair mask (setCrossfeedOutputPairMask), the PDM sub is never crossfed.
package se.bofr.dspi.feature.tools

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.Caption
import se.bofr.dspi.ui.ChannelChips
import se.bofr.dspi.ui.MacColors
import se.bofr.dspi.ui.ParameterRow
import se.bofr.dspi.ui.PullDownMenu
import se.bofr.dspi.ui.SectionHeader
import se.bofr.dspi.ui.SectionLabel
import se.bofr.dspi.ui.SwitchRow
import se.bofr.dspi.ui.ToolHeader

/** `CrossfeedCurveView.computeCurves()`: the BS2B lowpass and its complement. */
object CrossfeedCurve {
    private const val SAMPLE_RATE = 48000.0

    /** (frequency, crossfeed dB, direct dB), 100 log-spaced points 20 Hz..20 kHz. */
    fun computeCurves(freq: Float, feed: Float, numPoints: Int = 100): List<Triple<Double, Double, Double>> {
        val levelRatio = 10.0.pow(feed / 20.0)
        val g = 1.0 / (1.0 + levelRatio)
        val lpX = exp(-2.0 * PI * freq / SAMPLE_RATE)
        val lpA0 = g * (1.0 - lpX)
        return (0 until numPoints).map { i ->
            val t = i.toDouble() / (numPoints - 1)
            val f = 20.0 * 1000.0.pow(t)
            val omega = 2.0 * PI * f / SAMPLE_RATE
            val realDen = 1.0 - lpX * cos(omega)
            val imagDen = lpX * sin(omega)
            val mag2 = realDen * realDen + imagDen * imagDen
            val lpReal = (lpA0 * realDen) / mag2
            val lpImag = (-lpA0 * imagDen) / mag2
            val lpDB = 20.0 * log10(maxOf(sqrt(lpReal * lpReal + lpImag * lpImag), 1e-10))
            val dReal = 1.0 - lpReal
            val dImag = -lpImag
            val directDB = 20.0 * log10(maxOf(sqrt(dReal * dReal + dImag * dImag), 1e-10))
            Triple(f, lpDB, directDB)
        }
    }
}

internal enum class CrossfeedGraphKey { FREQ, FEED }

/** CrossfeedView: FREQUENCY RESPONSE + PRESET | PARAMETERS, ITD, OUTPUT PAIRS. */
@Composable
fun CrossfeedView(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val st by bridge.state.collectAsState()
    val status by bridge.status.collectAsState()
    val connected = status.connected
    val crossfeed = CrossfeedParameters.from(st)
    val pairCount = ToolMasks.pairCount(st.numOutputChannels)
    val showPairMask = ToolFirmware.supportsCrossfeedMask(st.formatVersion)
    val graphLive = rememberGraphLive<CrossfeedGraphKey>(crossfeed.freq to crossfeed.feed)

    fun pairChannelName(p: Int, right: Boolean): String = bridge.channelName(2 + 2 * p + if (right) 1 else 0)

    ToolScaffold(header = {
        ToolHeader(
            title = "Crossfeed",
            subtitle = "BS2B Bauer Stereophonic-to-Binaural",
            isOn = st.crossfeedEnabled,
            switchEnabled = connected,
            onToggle = { bridge.setCrossfeed(it) }
        )
    }, modifier = modifier) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            ToolColumns(left = {
                SectionLabel("FREQUENCY RESPONSE")
                GraphFrame(180.dp) {
                    CrossfeedCurveView(
                        freq = graphLive[CrossfeedGraphKey.FREQ, crossfeed.freq],
                        feed = graphLive[CrossfeedGraphKey.FEED, crossfeed.feed],
                        isEnabled = st.crossfeedEnabled
                    )
                }
                HorizontalDivider()
                SectionLabel("PRESET")
                CrossfeedParameters.presets.forEachIndexed { i, p ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = connected) { bridge.setCrossfeedPreset(i) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = crossfeed.preset == i, onClick = { bridge.setCrossfeedPreset(i) }, enabled = connected)
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(p.name, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Caption(p.description)
                        }
                    }
                }
            }, right = {
                Column(Modifier.alpha(if (crossfeed.isCustom) 1f else 0.5f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    SectionLabel("PARAMETERS")
                    ParameterRow(
                        title = "Cutoff Frequency",
                        unit = "Hz",
                        value = crossfeed.freq,
                        range = CrossfeedParameters.FREQ_RANGE,
                        maxDecimals = 1,
                        isEnabled = connected,
                        caption = "Simulates head shadow lowpass cutoff. Lower = more bass crossfeed. Typical: 650-700 Hz.",
                        live = { v ->
                            val c = v.coerceIn(CrossfeedParameters.FREQ_RANGE)
                            bridge.setCrossfeedFreq(c, sendOnly = true)
                            graphLive.set(CrossfeedGraphKey.FREQ, c)
                        },
                        set = { v ->
                            bridge.setCrossfeedFreq(v.coerceIn(CrossfeedParameters.FREQ_RANGE))
                            if (!crossfeed.isCustom) bridge.setCrossfeedPreset(CrossfeedParameters.PRESET_CUSTOM)
                        }
                    )
                    HorizontalDivider()
                    ParameterRow(
                        title = "Feed Level",
                        unit = "dB",
                        value = crossfeed.feed,
                        range = CrossfeedParameters.FEED_RANGE,
                        maxDecimals = 1,
                        isEnabled = connected,
                        caption = "Crossfeed attenuation below direct signal. Higher = more crossfeed. Typical: 4.5-9.5 dB.",
                        live = { v ->
                            val c = v.coerceIn(CrossfeedParameters.FEED_RANGE)
                            bridge.setCrossfeedFeed(c, sendOnly = true)
                            graphLive.set(CrossfeedGraphKey.FEED, c)
                        },
                        set = { v ->
                            bridge.setCrossfeedFeed(v.coerceIn(CrossfeedParameters.FEED_RANGE))
                            if (!crossfeed.isCustom) bridge.setCrossfeedPreset(CrossfeedParameters.PRESET_CUSTOM)
                        }
                    )
                }
                HorizontalDivider()
                SwitchRow(
                    title = "Interaural Time Delay",
                    subtitle = "Simulates ~220 µs path difference via all-pass filter",
                    checked = crossfeed.itd,
                    enabled = connected,
                    onChange = { bridge.setCrossfeedITD(it) }
                )
                if (showPairMask) {
                    HorizontalDivider()
                    SectionHeader("OUTPUT PAIRS") {
                        PullDownMenu(
                            "Presets",
                            connected,
                            listOf(
                                "All pairs" to { bridge.setCrossfeedOutputPairMask(ToolMasks.allPairs(pairCount)) },
                                "Pair 1 only (Headphones)" to { bridge.setCrossfeedOutputPairMask(0x01) },
                                "None" to { bridge.setCrossfeedOutputPairMask(0x00) }
                            )
                        )
                    }
                    Caption(
                        "Crossfeed only the stereo output pairs feeding headphones. Speaker pairs stay bit-accurate. " +
                            "The mono sub is never crossfed."
                    )
                    ChannelChips(
                        count = pairCount,
                        isOn = { ToolMasks.isOn(crossfeed.outputMask, it) },
                        names = (0 until pairCount).map { "${pairChannelName(it, false)} / ${pairChannelName(it, true)}" },
                        enabled = connected,
                        onToggle = { bridge.setCrossfeedOutputPairMask(ToolMasks.toggled(crossfeed.outputMask, it)) }
                    )
                }
            })
        }
    }
}

/** `CrossfeedCurveView`: Direct (accent) and Crossfeed (orange) on a dynamic dB axis. */
@Composable
internal fun CrossfeedCurveView(freq: Float, feed: Float, isEnabled: Boolean, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val accent = accentColor()
    val secondary = secondaryColor()
    val bg = androidx.compose.material3.MaterialTheme.colorScheme.surface
    val onBg = androidx.compose.material3.MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val curves = CrossfeedCurve.computeCurves(freq, feed)
        val (lo, hi) = ToolGraphMath.dynamicDomain(curves.map { it.second } + curves.map { it.third })
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
            fun curve(sel: (Triple<Double, Double, Double>) -> Double, color: Color) {
                val path = Path()
                curves.forEachIndexed { i, c ->
                    val p = Offset(x(c.first), y(sel(c)))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            curve({ it.second }, MacColors.orange)
            curve({ it.third }, accent)
            // Legend
            val box = Size(78.dp.toPx(), 30.dp.toPx())
            val o = Offset(w - box.width - 4.dp.toPx(), 4.dp.toPx())
            drawRect(bg.copy(alpha = 0.85f), o, box)
            listOf("Direct" to accent, "Crossfeed" to MacColors.orange).forEachIndexed { i, (label, c) ->
                val cy = o.y + 8.dp.toPx() + i * 13.dp.toPx()
                drawLine(c, Offset(o.x + 6.dp.toPx(), cy), Offset(o.x + 16.dp.toPx(), cy), 2.dp.toPx())
                labelAt(measurer, label, Offset(o.x + 44.dp.toPx(), cy), onBg, 9.sp, bold = true, mono = false)
            }
        } else {
            labelAt(measurer, "Disabled", Offset(w / 2, h / 2), secondary.copy(alpha = 0.5f), 13.sp, mono = false)
        }
    }
}

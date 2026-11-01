// Port of DSPi-Console-b5:DSPi Console/GraphView.swift @ b5fdeac: BodePlotView
// (grid, labels, curves grouped by identical response, phase trace, spectrum
// behind, the editor overlay on top, the options gear), GraphResizeHandle and
// GraphLegend. BodeLineShape's animatable path is BodeLine here.
package se.bofr.dspi.feature.graph

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.math.BigDecimal
import java.math.MathContext
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import se.bofr.dspi.bridge.ChannelMap
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.LocalAppSettings

/** The graph's panel colour (the macOS card colour under a dark appearance). */
internal val GraphBackground = Color(0xFF242426)

/**
 * The response graph as an embeddable composable (macOS GraphView.swift
 * `BodePlotView`), used by :feature-home's channel page and dashboard as well
 * as by [GraphScreen]. This signature is a cross-package contract (HOME
 * consumes it): keep it stable.
 *
 * @param channels app channel ids to draw (empty = the visible set,
 *   [DspiBridge.channelVisible]). With [editable] and exactly one channel, that
 *   channel is the page's selected channel (macOS `activeEqChannel`): the
 *   visible set is drawn too, the selected channel solid with its band dots
 *   and the others dashed, as on a macOS channel page.
 * @param editable enables on-graph PEQ editing (PeqGraphEditor) for the
 *   single channel in [channels].
 *
 * Height: [AppSettings.graphHeight] unless [modifier] fixes one. Share the
 * band selection with a band list through [LocalPeqGraphSelection]; the
 * preferences come from [LocalAppSettings].
 */
@Composable
fun GraphView(bridge: DspiBridge, modifier: Modifier = Modifier, channels: Set<Int> = emptySet(), editable: Boolean = false) {
    BodePlotView(bridge, modifier, channels, editable)
}

/** One line on the graph: channels sharing one identical curve (macOS `CurveGroup`). */
data class CurveGroup(val id: List<Int>, val mags: List<Double>, val entries: List<ChannelEntry>)

data class ChannelEntry(val eqCh: Int, val isActive: Boolean)

/**
 * Visible channels grouped by identical magnitudes, drawn as one line each
 * (macOS `groupedChannels`). [edited] is left out: the editor overlay draws it.
 */
fun groupedChannels(
    visible: List<Int>,
    curve: (Int) -> List<Double>,
    activeEq: Int?,
    edited: Int?,
    followsSelection: Boolean = true
): List<CurveGroup> {
    val groups = LinkedHashMap<List<Double>, MutableList<ChannelEntry>>()
    for (eqCh in visible) {
        if (eqCh == edited) continue
        val mags = curve(eqCh).ifEmpty { List(DspiConstants.MAGNITUDE_POINTS) { 0.0 } }
        val isActive = !followsSelection || activeEq == null || eqCh == activeEq
        groups.getOrPut(mags) { mutableListOf() } += ChannelEntry(eqCh, isActive)
    }
    return groups.map { (mags, entries) -> CurveGroup(entries.map { it.eqCh }, mags, entries) }.sortedBy { it.id.firstOrNull() ?: 0 }
}

/** Gradient stops for overlapping channels (macOS `gradientStops(for:)`). */
fun gradientStops(colors: List<Color>): List<Pair<Float, Color>> {
    if (colors.isEmpty()) return emptyList()
    if (colors.size == 1) return listOf(0f to colors[0], 1f to colors[0])
    val stops = mutableListOf<Pair<Float, Color>>()
    val count = colors.size
    val transitionWidth = if (count == 2) {
        0.4
    } else if (count == 3) {
        0.25
    } else {
        0.15
    }
    val segmentWidth = 1.0 / count
    for ((index, color) in colors.withIndex()) {
        val centre = (index + 0.5) * segmentWidth
        val solidStart = max(0.0, centre - (segmentWidth - transitionWidth) / 2)
        val solidEnd = min(1.0, centre + (segmentWidth - transitionWidth) / 2)
        if (index == 0) stops += 0f to color
        stops += solidStart.toFloat() to color
        stops += solidEnd.toFloat() to color
        if (index == count - 1) stops += 1f to color
    }
    return stops.sortedBy { it.first }
}

/** The dB grid step for a span (macOS: 1, 3, 5 or 10 dB). */
fun dbGridStep(span: Double): Double = if (span <= 12) {
    1.0
} else if (span <= 30) {
    3.0
} else if (span <= 60) {
    5.0
} else {
    10.0
}

/** "+6", "-12", "+0", "+2.5" as macOS `String(format: "+%g")` (six significant digits, no trailing zeros). */
fun dbLabel(db: Double): String {
    val v = if (db == 0.0) "0" else BigDecimal(db).round(MathContext(6)).stripTrailingZeros().toPlainString()
    return if (db >= 0) "+$v" else v
}

/** macOS `Color.accentColor` (system blue): an output beyond the palette (macOS `eqCurveColor`). */
internal val AccentColour = Color(0xFF007AFF)

/** A channel's curve colour (macOS `eqCurveColor`), from the bridge's palette. */
internal fun channelColour(bridge: DspiBridge, ch: Int): Color {
    if (!bridge.isInputChannel(ch)) {
        // macOS: `MatrixOutput.all.indices.contains(outIdx) ? … : .accentColor` — no wrap-around.
        val out = bridge.rta.tapIndex(ch)
        if (out !in 0 until DspiConstants.MAX_OUTPUTS) return AccentColour
    }
    val hex = bridge.channelColor(ch).removePrefix("#")
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) } ?: ChannelPalette.color(ch)
}

/**
 * A curve's drawing state, kept across frames (macOS `BodeLineShape` +
 * `AnimatableVector`): the x of each data point for the current axis, the
 * from/to vectors of the running tween, and one reusable [Path]. A frame
 * rewinds the path and interpolates in place; nothing is allocated.
 */
internal class BodeLineCache {
    val path = Path()
    private var brush: Brush? = null
    private var brushKey: Pair<List<Color>, Float>? = null
    private var xs = FloatArray(0)
    private var xsFreqs: List<Double>? = null
    private var xsMin = Double.NaN
    private var xsMax = Double.NaN
    private var xsW = Float.NaN
    var from = DoubleArray(0)
        private set
    var to = DoubleArray(0)
        private set

    /** The gradient of an overlapping group, rebuilt only when its colours or the width change. */
    fun gradient(colors: List<Color>, w: Float): Brush {
        val key = colors to w
        if (brushKey != key || brush == null) {
            brushKey = key
            brush = Brush.horizontalGradient(*gradientStops(colors).toTypedArray(), startX = 0f, endX = w)
        }
        return brush!!
    }

    /** Starts a tween from the curve as drawn at [t] toward [mags]. */
    fun retarget(mags: List<Double>, t: Double) {
        val next = DoubleArray(mags.size) { mags[it] }
        if (from.size == to.size && to.size == next.size) {
            for (i in to.indices) from[i] = from[i] + (to[i] - from[i]) * t
        } else {
            from = next.copyOf()
        }
        to = next
    }

    /** Fills [path] with the curve at tween position [t] (macOS `BodeLineShape.path(in:)`). */
    fun build(freqs: List<Double>, t: Double, dbTop: Double, dbBottom: Double, minFreq: Double, maxFreq: Double, w: Float, h: Float): Path {
        path.rewind()
        val n = to.size
        if (n == 0) return path
        if (freqs !== xsFreqs || minFreq != xsMin || maxFreq != xsMax || w != xsW || xs.size != n) {
            xsFreqs = freqs
            xsMin = minFreq
            xsMax = maxFreq
            xsW = w
            xs = FloatArray(n)
            val logMin = log10(minFreq)
            val span = log10(maxFreq) - logMin
            for (i in 0 until n) {
                // The data grid is 10 Hz-20 kHz, 201 log-spaced points.
                val f = freqs.getOrNull(i) ?: (10.0 * 2000.0.pow(i.toDouble() / max(n - 1, 1)))
                xs[i] = ((log10(f) - logMin) / span * w).toFloat()
            }
        }
        val dbSpan = dbTop - dbBottom
        val tween = from.size == n && t < 1
        for (i in 0 until n) {
            val v = if (tween) from[i] + (to[i] - from[i]) * t else to[i]
            // No clamp (a clamp bends the visible part of a steep segment);
            // only a non-finite value is pinned far off the plot.
            val y = if (v.isFinite()) {
                (h - (v - dbBottom) / dbSpan * h).toFloat()
            } else if (v > 0) {
                -1e5f
            } else {
                1e5f
            }
            if (i == 0) path.moveTo(xs[i], y) else path.lineTo(xs[i], y)
        }
        return path
    }
}

/** The phase trace's colour (macOS `Color(white: 0.93)`). */
private val PHASE_COLOUR = Color(0xFFEDEDED)

/** Glow alphas without a blur (below API 31), shared with the edited curve (PeqGraphRenderer). */
internal const val GLOW_FALLBACK_WIDE = 0.12f
internal const val GLOW_FALLBACK_NARROW = 0.25f

/**
 * One animated line (macOS `BodeLineShape` with `.animation(.spring)`): the
 * curve glides to a new shape over the Graphing animation speed.
 */
@Composable
private fun BodeLine(
    mags: List<Double>,
    freqs: List<Double>,
    settings: AppSettings,
    colors: List<Color>,
    active: Boolean,
    minFreq: Double,
    maxFreq: Double,
    dbTop: Double,
    dbBottom: Double,
    phase: Boolean = false
) {
    val cache = remember { BodeLineCache().also { it.retarget(mags, 1.0) } }
    var target by remember { mutableStateOf(mags) }
    val progress = remember { Animatable(1f) }
    LaunchedEffect(mags) {
        if (mags == target) return@LaunchedEffect
        cache.retarget(mags, progress.value.toDouble())
        target = mags
        progress.snapTo(0f)
        progress.animateTo(1f, tween((settings.graphAnimationSpeed * 1000).roundToInt(), easing = FastOutSlowInEasing))
    }
    val lw = settings.graphLineWidth.toFloat()
    val strokes =
        remember(lw, active, phase) {
            val dash = if (active) null else PathEffect.dashPathEffect(floatArrayOf(6f, 4f))
            if (phase) {
                // Dotted, light grey (macOS dash [0.1, lw * 3] with round caps).
                listOf(Stroke(lw * 0.9f, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, lw * 3))))
            } else if (colors.size == 1) {
                listOf(Stroke(lw * 4), Stroke(lw * 2), Stroke(lw, pathEffect = dash))
            } else {
                listOf(Stroke(lw * 5), Stroke(lw * 2.5f), Stroke(lw * 1.25f, pathEffect = dash))
            }
        }
    val glow = settings.showGraphGlow && active && !phase
    // macOS: the glow strokes are blurred (single 0.3 / lw·4 / blur 6 and
    // 0.6 / lw·2 / blur 3; overlapping 0.07 / lw·5 / blur 8 and 0.07 / lw·2.5 /
    // blur 5). With RenderEffect (API 31+) each is its own blurred layer;
    // below it they are plain fainter strokes.
    if (glow && blurAvailable) {
        val single = colors.size == 1
        for (layer in 0..1) {
            val radius = if (single) (if (layer == 0) 6f else 3f) else (if (layer == 0) 8f else 5f)
            val alpha = if (single) (if (layer == 0) 0.3f else 0.6f) else 0.07f
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = BlurEffect(radius.dp.toPx(), radius.dp.toPx(), TileMode.Decal)
                }
            ) {
                val d = density
                val w = size.width / d
                val path = cache.build(freqs, progress.value.toDouble(), dbTop, dbBottom, minFreq, maxFreq, w, size.height / d)
                scale(d, d, pivot = Offset.Zero) {
                    if (single) {
                        drawPath(
                            path,
                            colors[0],
                            alpha = alpha,
                            style = strokes[layer]
                        )
                    } else {
                        drawPath(path, cache.gradient(colors, w), alpha = alpha, style = strokes[layer])
                    }
                }
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val w = size.width / d
        val h = size.height / d
        val path = cache.build(freqs, progress.value.toDouble(), dbTop, dbBottom, minFreq, maxFreq, w, h)
        scale(d, d, pivot = Offset.Zero) {
            if (phase) {
                drawPath(path, PHASE_COLOUR, style = strokes[0])
                return@scale
            }
            val fallback = glow && !blurAvailable
            if (colors.size == 1) {
                val c = colors[0]
                if (fallback) {
                    drawPath(path, c, alpha = GLOW_FALLBACK_WIDE, style = strokes[0])
                    drawPath(path, c, alpha = GLOW_FALLBACK_NARROW, style = strokes[1])
                }
                drawPath(path, c, style = strokes[2])
            } else {
                val b = cache.gradient(colors, w)
                if (fallback) {
                    drawPath(path, b, alpha = 0.07f, style = strokes[0])
                    drawPath(path, b, alpha = 0.07f, style = strokes[1])
                }
                drawPath(path, b, style = strokes[2])
            }
        }
    }
}

/** The graph (macOS `BodePlotView`). */
@Composable
internal fun BodePlotView(bridge: DspiBridge, modifier: Modifier, channels: Set<Int>, editable: Boolean, isPopOut: Boolean = false) {
    val store = LocalAppSettings.current
    val settings by store.settings.collectAsStateWithLifecycle()
    val stateRev by bridge.stateRevision.collectAsStateWithLifecycle()
    val magRev by bridge.magnitudesRevision.collectAsStateWithLifecycle()
    val connectedFlow = remember(bridge) { bridge.status.map { it.connected }.distinctUntilChanged() }
    val connected by connectedFlow.collectAsStateWithLifecycle(bridge.connected)
    val sharedSelection = LocalPeqGraphSelection.current
    val ownSelection = remember { PeqGraphSelection() }
    val selection = sharedSelection ?: ownSelection

    val minFreq = settings.graphMinFreq
    val maxFreq = settings.graphMaxFreq
    val dbTop = settings.dbTop
    val dbBottom = settings.dbBottom

    val activeEq: Int? = if (editable && channels.size == 1) channels.first() else null
    val editedChannel: Int? =
        remember(stateRev, magRev, activeEq, connected) {
            activeEq?.takeIf { connected && bridge.channelExists(it) && bridge.channelVisible(it) }
        }

    val visible: List<Int> =
        remember(stateRev, magRev, channels, activeEq) {
            if (channels.isEmpty() || activeEq != null) {
                (0 until ChannelMap.APP_CHANNEL_COUNT).filter { bridge.channelExists(it) && bridge.channelVisible(it) }
            } else {
                channels.filter { bridge.channelExists(it) }.sorted()
            }
        }
    // No curves without a device: the grid and axes stay.
    val groups =
        remember(stateRev, magRev, visible, editedChannel, connected, isPopOut, settings.popoutGraphFollowsSelection) {
            // macOS: a pop-out that does not follow the selection draws every curve as active
            val followsSelection = !isPopOut || settings.popoutGraphFollowsSelection
            if (!connected) {
                emptyList()
            } else {
                groupedChannels(visible, {
                    bridge.magnitudeCurve(it)
                }, activeEq, editedChannel, followsSelection)
            }
        }
    val freqs = remember(bridge) { bridge.curveFrequencies }
    val phases =
        remember(stateRev, magRev, activeEq, settings.phaseUnwrapped, settings.showPhase, connected) {
            val ch = activeEq
            if (settings.showPhase &&
                connected &&
                ch != null &&
                bridge.channelVisible(ch)
            ) {
                bridge.phaseCurve(ch, settings.phaseUnwrapped)
            } else {
                null
            }
        }
    val editorConfig =
        remember(stateRev, magRev, editedChannel, settings) {
            editorConfigFor(bridge, editedChannel, settings)
        }
    val curvesAlpha by animateFloatAsState(if (connected) 1f else 0f, tween(300), label = "device")
    var optionsOpen by remember { mutableStateOf(false) }
    val onDashboard = activeEq == null
    // Parsed once per state change, not per group per recomposition.
    val colours = remember(stateRev) { List(ChannelMap.APP_CHANNEL_COUNT) { channelColour(bridge, it) } }
    val caps by bridge.rta.caps.collectAsStateWithLifecycle()
    val spectrum = rememberSpectrumSelection(bridge, store, activeEq)
    // The page's channels, from their explicit inputs (settings, analyser caps, device state).
    val spectrumSelection = remember(spectrum, spectrum.pageKey, settings, caps, stateRev) { spectrum.value }

    Box(
        modifier
            .fillMaxWidth()
            .height(settings.graphHeight.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(GraphBackground)
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
    ) {
        GraphGrid(settings, minFreq, maxFreq, dbTop, dbBottom)
        if (connected && settings.rtaShowsGraph(onDashboard) && !spectrumSelection.isEmpty) {
            GraphSpectrumOverlay(bridge, spectrumSelection, minFreq, maxFreq, settings, Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().alpha(curvesAlpha)) {
            for (group in groups) {
                key(group.id) {
                    BodeLine(
                        group.mags,
                        freqs,
                        settings,
                        group.entries.map { colours[it.eqCh] },
                        group.entries.any { it.isActive },
                        minFreq,
                        maxFreq,
                        dbTop,
                        dbBottom
                    )
                }
            }
            phases?.let {
                BodeLine(
                    it, freqs, settings, emptyList(), true, minFreq, maxFreq, settings.phaseTop, -settings.phaseTop, phase = true
                )
            }
        }
        GraphLabels(settings, minFreq, maxFreq, dbTop, dbBottom)
        // Band editing, and the edited channel's curve. Also owns pinch zoom of the dB range.
        PeqGraphEditorView(
            bridge = bridge,
            config = editorConfig,
            selection = selection,
            background = GraphBackground,
            onZoom = { ratio ->
                store.update {
                    it.copy(graphDBRange = (it.graphDBRange / ratio).coerceIn(AppSettings.DB_RANGE_MIN, AppSettings.DB_RANGE_MAX))
                }
            },
            onZoomRange = { delta ->
                store.update {
                    it.copy(graphDBRange = (it.graphDBRange + delta).coerceIn(AppSettings.DB_RANGE_MIN, AppSettings.DB_RANGE_MAX))
                }
            }
        )
        // The gear at the top right (macOS: shown on hover; touch has none, so always).
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .size(28.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable { optionsOpen = true },
            contentAlignment = Alignment.Center
        ) { Text("⚙", color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp) }
    }
    if (optionsOpen) {
        GraphOptions(
            bridge = bridge,
            onDashboard = onDashboard,
            spectrum = spectrum,
            onDismiss = { optionsOpen = false },
            inPopOutWindow = isPopOut
        )
    }
}

/** What the editor is told about the graph (macOS `BodePlotView.editorConfig`). */
internal fun editorConfigFor(bridge: DspiBridge, ch: Int?, settings: AppSettings): PeqEditorConfig {
    val base =
        PeqEditorConfig(
            minFreq = settings.graphMinFreq,
            maxFreq = settings.graphMaxFreq,
            dbTop = settings.dbTop,
            dbBottom = settings.dbBottom
        )
    if (ch == null) return base
    val bands = bridge.channelBands(ch)
    val c = channelColour(bridge, ch)
    return base.copy(
        channel = ch,
        bands = bands?.peq ?: emptyList(),
        statics = bands?.xover ?: emptyList(),
        offsetDb = bridge.channelGainOffset(ch),
        // Master EQ bypass flattens the inputs' curves (macOS: ch < BASE_MATRIX_INPUTS).
        flat = bridge.bypass && bridge.isInputChannel(ch),
        curveColor = PeqRgb(c.red, c.green, c.blue),
        lineWidth = settings.graphLineWidth.toFloat(),
        glow = settings.showGraphGlow,
        availableTypes = availableFilterTypes(),
        bypassSupported = true,
        showFrequencyReadout = settings.showFrequencyReadout,
        showLevelReadout = settings.showLevelReadout
    )
}

/**
 * The PEQ types the graph creates and offers (macOS
 * `availableFilterTypes(includeLinkwitz: false)`). The V32 firmware the core
 * talks to supports every PEQ type and per-band bypass, so none of the macOS
 * `firmwareSupports*` checks can fail here.
 */
internal fun availableFilterTypes(): Set<Int> =
    FilterTypes.PEQ_TYPES.filter { it != DspiConstants.FILTER_FLAT && !FilterTypes.isLinkwitzTransform(it) }.toSet()

/** macOS `majorFreqs` (GraphView.swift l.269). */
private val MAJOR_FREQS = doubleArrayOf(100.0, 1000.0, 10000.0)

/** macOS `minorFreqs` less the majors (l.270-273). */
private val MINOR_FREQS =
    doubleArrayOf(
        20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0, 200.0, 300.0, 400.0, 500.0, 600.0, 700.0, 800.0, 900.0,
        2000.0, 3000.0, 4000.0, 5000.0, 6000.0, 7000.0, 8000.0, 9000.0, 20000.0
    )

/** macOS `labelFreqs` (l.410-413). */
private val LABEL_FREQS = doubleArrayOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)
private val LABEL_TEXTS = arrayOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")

@Composable
private fun GraphGrid(settings: AppSettings, minF: Double, maxF: Double, dTop: Double, dBot: Double) {
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val w = size.width / d
        val h = size.height / d
        val grid = settings.graphGridOpacity

        // The user's grid opacity scales each line's own strength.
        fun gridColor(alpha: Double) = Color.White.copy(alpha = min(alpha * grid, 1.0).toFloat())

        fun xPos(f: Double) = ((log10(f) - log10(minF)) / (log10(maxF) - log10(minF)) * w).toFloat()

        fun yPos(db: Double) = (h - (db - dBot) / (dTop - dBot) * h).toFloat()
        scale(d, d, pivot = Offset.Zero) {
            if (settings.showFrequencyGrid) {
                for (f in MAJOR_FREQS) if (f in minF..maxF) drawLine(gridColor(0.15), Offset(xPos(f), 0f), Offset(xPos(f), h), 1f)
                for (f in MINOR_FREQS) if (f in minF..maxF) drawLine(gridColor(0.06), Offset(xPos(f), 0f), Offset(xPos(f), h), 1f)
            }
            if (settings.showDBGrid) {
                val step = dbGridStep(dTop - dBot)
                var db = ceil(dBot / step) * step
                while (db <= dTop) {
                    // 0 dB is drawn separately.
                    if (abs(db) > 0.01) drawLine(gridColor(0.1), Offset(0f, yPos(db)), Offset(w, yPos(db)), 1f)
                    db += step
                }
                if (dBot <= 0 && dTop >= 0) drawLine(gridColor(0.3), Offset(0f, yPos(0.0)), Offset(w, yPos(0.0)), 1f)
            }
        }
    }
}

@Composable
private fun GraphLabels(settings: AppSettings, minF: Double, maxF: Double, dTop: Double, dBot: Double) {
    // Up to ~25 labels at once (10 frequencies, up to 11 dB steps, 5 phase ticks).
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val style = TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.4f))
    val phaseStyle = style.copy(color = Color(0xFFEDEDED).copy(alpha = 0.55f))
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        fun xPos(f: Double) = ((log10(f) - log10(minF)) / (log10(maxF) - log10(minF)) * w).toFloat()

        fun yPos(db: Double, top: Double, bottom: Double) = (h - (db - bottom) / (top - bottom) * h).toFloat()
        val pad = 4.dp.toPx()
        if (settings.showFrequencyLabels) {
            for (i in LABEL_FREQS.indices) {
                val f = LABEL_FREQS[i]
                val text = LABEL_TEXTS[i]
                if (f < minF || f > maxF) continue
                val r = measurer.measure(text, style)
                val x = (xPos(f) - r.size.width / 2f).coerceIn(0f, max(w - r.size.width, 0f))
                drawText(r, topLeft = Offset(x, h - 2.dp.toPx() - r.size.height))
            }
        }
        if (settings.showDBLabels) {
            val step = dbGridStep(dTop - dBot)
            var db = ceil(dBot / step) * step
            while (db <= dTop) {
                val r = measurer.measure(dbLabel(db), style)
                val y = (yPos(db, dTop, dBot) - r.size.height / 2f).coerceIn(0f, max(h - r.size.height, 0f))
                drawText(r, topLeft = Offset(pad, y))
                db += step
            }
        }
        // Phase axis labels (right side), scaled with the vertical zoom.
        if (settings.showPhase) {
            val pTop = settings.phaseTop
            for (p in listOf(pTop, pTop / 2, 0.0, -pTop / 2, -pTop)) {
                val text = if (p == 0.0) "0°" else String.format(Locale.ROOT, "%+d°", p.roundToInt())
                val r = measurer.measure(text, phaseStyle)
                val y = (yPos(p, pTop, -pTop) - r.size.height / 2f).coerceIn(0f, max(h - r.size.height, 0f))
                drawText(r, topLeft = Offset(w - pad - r.size.width, y))
            }
        }
    }
}

/**
 * A vertical drag strip that resizes the graph, between 200 and 350 points
 * (macOS `GraphResizeHandle`), for HOME to put under the graph on a page.
 */
@Composable
fun GraphResizeHandle(modifier: Modifier = Modifier, height: Int = 20) {
    val store = LocalAppSettings.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .pointerInput(store) {
                detectVerticalDragGestures { change, dy ->
                    change.consume()
                    store.update {
                        it.copy(
                            graphHeight = (it.graphHeight + dy / density).coerceIn(
                                AppSettings.GRAPH_HEIGHT_MIN,
                                AppSettings.GRAPH_HEIGHT_MAX
                            )
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(Modifier.size(36.dp, 4.dp).clip(RoundedCornerShape(2.dp)).background(Color.Gray.copy(alpha = 0.5f)))
    }
}

/**
 * Pills for the active inputs and enabled outputs; a tap shows or hides that
 * channel's curve (macOS `GraphLegend`, the pop-out window's legend).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GraphLegend(bridge: DspiBridge, modifier: Modifier = Modifier) {
    val stateRev by bridge.stateRevision.collectAsStateWithLifecycle()
    val magRev by bridge.magnitudesRevision.collectAsStateWithLifecycle()
    val connectedFlow = remember(bridge) { bridge.status.map { it.connected }.distinctUntilChanged() }
    val connected by connectedFlow.collectAsStateWithLifecycle(bridge.connected)
    // Faded rather than removed without a device (a pure crossfade, 0.3 s), and inert while faded.
    val fade by animateFloatAsState(if (connected) 1f else 0f, tween(300), label = "legend")
    val pills =
        remember(stateRev, magRev) {
            val inputs = (0 until bridge.liveInputCount()).map { bridge.inputAppId(it) }
            val outputs =
                (0 until bridge.numOutputChannels)
                    .filter { bridge.outputEnabled(it) }
                    .map { bridge.rta.appChannel(DspiConstants.RTA_TAP_OUTPUT, it) }
            (inputs + outputs).filter { bridge.channelExists(it) }
        }
    FlowRow(
        modifier.padding(top = 6.dp, bottom = 2.dp).alpha(fade),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (ch in pills) {
            val isVisible = remember(magRev, ch) { bridge.channelVisible(ch) }
            val color = channelColour(bridge, ch)
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(if (isVisible) color.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.1f))
                    .border(1.dp, if (isVisible) color.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                    .clickable(enabled = connected) { bridge.setChannelVisible(ch, !isVisible) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (isVisible) color else Color.Gray.copy(alpha = 0.5f)))
                Text(
                    bridge.channelDescriptor(ch),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isVisible) Color.White else Color.Gray,
                    modifier = Modifier.padding(start = 6.dp).widthIn(min = 30.dp)
                )
            }
        }
    }
}

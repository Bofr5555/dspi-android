// Port of DSPi-Console-b5:DSPi Console/SpectrumAnalyserView.swift @ b5fdeac
// (SpectrumAnalyserView: header, Curves | Bars | Both, bar cells in columns,
// notices, RtaStatusBar; RtaBandsView / RtaBandGrid / RtaBinGrid drawing)
// with the Metal renderers (RtaMetalBars/RtaMetalCurves/RtaRenderCache)
// replaced by Compose Canvas drawing, following
// DSPi-Console-Linux:qt/src/RtaViews.cpp @ 4683a2c (SpectrumBarsItem,
// SpectrumCurveItem) and qt/qml/SpectrumAnalyserWindow.qml for how this core's
// snapshot is drawn. Draw paths reuse cached brushes, paths, text layouts and
// arrays: nothing is allocated per display frame.
// Deviations (docs/UPSTREAM-DEVIATIONS.md, ANALYSIS): the page chooses its own
// side and channels (no main window to mirror); no glow / fade-in on curves.
package se.bofr.dspi.feature.analysis

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.LongState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot
import se.bofr.dspi.ui.ChannelPalette
import se.bofr.dspi.ui.settings.AppSettingsStore
import se.bofr.dspi.ui.settings.LocalAppSettings

/** Subscription ids for the bridge's RtaController (views share one analyser). */
private val rtaSubscriptionIds = AtomicInteger(0x5A_0000)

/** "#RRGGBB" from the bridge's channel palette. */
internal fun parseHexColor(hex: String): Color? {
    val h = hex.removePrefix("#")
    val v = h.toLongOrNull(16) ?: return null
    return when (h.length) {
        6 -> Color(0xFF000000 or v)
        8 -> Color(v)
        else -> null
    }
}

/** What the analyser page shows, apart from the frames themselves. */
data class SpectrumUiState(
    val connected: Boolean,
    val caps: RtaCapsInfo?,
    val selection: RtaChannelSelection,
    /** Channels that can be analysed at the selection's tap now. */
    val available: List<Int>,
    val mode: RtaDisplayMode,
    val prefs: RtaPreferences
) {
    val supported: Boolean get() = connected && caps?.supported == true
}

/**
 * The Spectrum Analyser page. Subscribes to the device's analyser while it is
 * on screen (released when it leaves; the core stops the analyser shortly
 * after the last view goes) and draws the bridge's snapshots.
 */
@Composable
fun SpectrumAnalyserView(bridge: DspiBridge, modifier: Modifier = Modifier, store: AppSettingsStore = LocalAppSettings.current) {
    val rta = bridge.rta
    val bridgeStatus by bridge.status.collectAsState()

    @Suppress("UNUSED_VARIABLE")
    val revision by bridge.stateRevision.collectAsState() // enables, names, live inputs
    val caps by rta.caps.collectAsState()

    // The macOS preferences and the dashboard's stored selection (the shared AppSettings)
    val settings by store.settings.collectAsState()
    val prefs = remember(settings) { settings.rtaPreferences() }
    val selectionKey = settings.rtaDashboardSelectionKey
    val otherSideKey = settings.rtaDashboardOtherSideKey
    // Swift startFromPage(): each time the page opens it draws what the dashboard draws
    var chosenMode by rememberSaveable { mutableStateOf<RtaDisplayMode?>(null) }
    val mode = chosenMode ?: rtaInitialMode(prefs.dashboardShowGraph, prefs.dashboardShowBars)
    fun storeSelection(active: RtaChannelSelection, other: RtaChannelSelection?) {
        store.update { it.copy(rtaDashboardSelectionKey = active.storageKey, rtaDashboardOtherSideKey = other?.storageKey ?: "") }
    }

    val selection = RtaChannelSelection.resolve(RtaChannelSelection.fromStorageKey(selectionKey)) { rta.availableChannels(it) }
    val state =
        SpectrumUiState(
            connected = bridgeStatus.connected,
            caps = caps,
            selection = selection,
            available = rta.availableChannels(selection.tap),
            mode = mode,
            prefs = prefs
        )

    // Watch while on screen (Swift RtaWatch): the newest request picks the tap
    val subscriptionId = remember { rtaSubscriptionIds.incrementAndGet() }
    var visible by remember { mutableStateOf(false) }
    WhileVisible(key = rta, onShow = { visible = true }, onHide = { visible = false })
    val wantsBins = mode != RtaDisplayMode.BARS && selection.channels.size == 1
    val watching = visible && state.supported && !selection.isEmpty
    // The device-side options (fft order, averaging, peak decay) are pushed by :app, app-wide
    LaunchedEffect(watching, selection.tap, selection.mask, wantsBins) {
        if (watching) {
            rta.update(subscriptionId, selection.tap, selection.mask, wantsBins)
        } else {
            rta.release(subscriptionId)
        }
    }
    DisposableEffect(rta) { onDispose { rta.release(subscriptionId) } }

    // Frames: adopt each snapshot, then glide at the display rate until settled
    val render = remember { RtaRenderState() }
    val tick = remember { mutableLongStateOf(0L) }
    val channels = remember(selection) { selection.channels.toIntArray() }
    LaunchedEffect(render, visible, selection, mode, prefs) {
        if (!visible) return@LaunchedEffect
        val stepper: (Long) -> Boolean = render::step
        rta.snapshot.collectLatest { snap ->
            val tau = rtaFallTau(rtaChannelRefreshInterval(snap, prefs.fftOrder), prefs.smoothing)
            render.take(snap, rta.caps.value, selection.tap, channels, mode != RtaDisplayMode.BARS, tau)
            tick.longValue++
            while (tau > 0) {
                val moving = withFrameNanos(stepper)
                tick.longValue++
                if (!moving) break
            }
        }
    }

    SpectrumAnalyserContent(
        state = state,
        render = render,
        tick = tick,
        snapshot = rta.snapshot,
        channelName = { tap, ch -> bridge.channelName(rta.appChannel(tap, ch)).ifEmpty { "Ch ${ch + 1}" } },
        channelColor = { tap, ch ->
            val app = rta.appChannel(tap, ch)
            parseHexColor(bridge.channelColor(app)) ?: ChannelPalette.color(app)
        },
        onSide = { tap ->
            // Swift switchRtaSide: the stored form, so a channel disabled for now is still remembered
            val active = RtaChannelSelection.fromStorageKey(selectionKey) ?: selection
            val (next, remembered) = RtaChannelSelection.switchingSides(active, RtaChannelSelection.fromStorageKey(otherSideKey), tap)
            storeSelection(next, remembered)
        },
        onToggle = { ch -> storeSelection(selection.toggling(selection.tap, ch), RtaChannelSelection.fromStorageKey(otherSideKey)) },
        onMode = { chosenMode = it },
        modifier = modifier
    )
}

@Composable
fun SpectrumAnalyserContent(
    state: SpectrumUiState,
    render: RtaRenderState,
    tick: LongState,
    snapshot: StateFlow<RtaSnapshot?>,
    channelName: (tap: Int, ch: Int) -> String,
    channelColor: (tap: Int, ch: Int) -> Color,
    onSide: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onMode: (RtaDisplayMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxSize()) {
        if (!state.supported) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Notice(
                    "Spectrum analyser unavailable",
                    if (state.connected) {
                        "The connected firmware does not provide a compatible analyser. Update the firmware to use it."
                    } else {
                        "Connect a DSPi to use the analyser."
                    }
                )
            }
            return@Column
        }
        val sel = state.selection
        SpectrumHeader(state, channelName, channelColor, onSide, onToggle, onMode)
        HorizontalDivider()
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.20f))
                .padding(12.dp)
        ) {
            when {
                state.available.isEmpty() && sel.isEmpty ->
                    Notice(if (sel.tap == DspiConstants.RTA_TAP_INPUT) "No active inputs." else "No enabled outputs.", null)
                sel.isEmpty -> Notice("No channels selected", "Choose the channels to analyse above.")
                else -> {
                    val colors = remember(sel, channelColor) { sel.channels.map { channelColor(sel.tap, it) } }
                    val scale = remember(state.prefs) { RtaScale(state.prefs.floorDb, state.prefs.ceilingDb) }
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.mode != RtaDisplayMode.BARS) {
                            RtaCurvesPlot(render, colors, scale, state.prefs, tick, Modifier.weight(1f).fillMaxWidth())
                        }
                        if (state.mode != RtaDisplayMode.CURVES) {
                            RtaBarsGrid(state, render, colors, scale, tick, channelName, Modifier.weight(1f).fillMaxWidth())
                        }
                    }
                }
            }
        }
        HorizontalDivider()
        RtaStatusBar(state, snapshot, showsBars = !sel.isEmpty && state.mode != RtaDisplayMode.CURVES)
    }
}

// ── Header ──

@Composable
private fun SpectrumHeader(
    state: SpectrumUiState,
    channelName: (Int, Int) -> String,
    channelColor: (Int, Int) -> Color,
    onSide: (Int) -> Unit,
    onToggle: (Int) -> Unit,
    onMode: (RtaDisplayMode) -> Unit
) {
    val sel = state.selection
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SegmentedPicker(
                options = listOf(DspiConstants.RTA_TAP_INPUT, DspiConstants.RTA_TAP_OUTPUT),
                selected = sel.tap,
                label = { if (it == DspiConstants.RTA_TAP_INPUT) "Inputs" else "Outputs" },
                onSelect = onSide
            )
            Spacer(Modifier.weight(1f))
            SegmentedPicker(RtaDisplayMode.entries, state.mode, { it.label }, onMode)
        }
        // The channels at this side: tap to analyse or drop one (the curves' legend too)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (ch in state.available.union(sel.channels).sorted()) {
                val on = ch in sel.channels
                val color = channelColor(sel.tap, ch)
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (on) color.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { onToggle(ch) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Dot(color.copy(alpha = if (on) 1f else 0.25f), 7.dp)
                    Text(
                        channelName(sel.tap, ch),
                        fontSize = 12.sp,
                        color = if (on) MaterialTheme.colorScheme.onSurface else secondaryText().copy(alpha = 0.5f),
                        textDecoration = if (on) null else TextDecoration.LineThrough,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

// ── Bars ──

@Composable
private fun RtaBarsGrid(
    state: SpectrumUiState,
    render: RtaRenderState,
    colors: List<Color>,
    scale: RtaScale,
    tick: LongState,
    channelName: (Int, Int) -> String,
    modifier: Modifier
) {
    val sel = state.selection
    BoxWithConstraints(modifier) {
        // A phone is too narrow for two readable cells side by side
        val preferred = if (maxWidth < 600.dp) 1 else state.prefs.barColumns
        val columns = rtaBarColumns(sel.channels.size, preferred)
        val rows = sel.channels.indices.chunked(columns)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            for (row in rows) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (slot in row) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Dot(colors[slot], 6.dp)
                                Text(
                                    channelName(sel.tap, sel.channels[slot]),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = secondaryText(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            RtaBandsCanvas(
                                render,
                                slot,
                                colors[slot],
                                scale,
                                state.prefs.showPeakHold,
                                tick,
                                Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private val axisStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp)

/** One channel's third-octave bars (RtaBandsView + RtaBandGrid with labels). */
@Composable
private fun RtaBandsCanvas(
    render: RtaRenderState,
    slot: Int,
    color: Color,
    scale: RtaScale,
    showPeakHold: Boolean,
    tick: LongState,
    modifier: Modifier
) {
    val measurer = rememberTextMeasurer()
    val secondary = secondaryText()
    Spacer(
        modifier.drawWithCache {
            val labelRow = 12.dp.toPx()
            val plotH = max(0f, size.height - labelRow)
            val w = size.width
            val barBrush = Brush.verticalGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.45f)), 0f, plotH)
            val capColor = color.copy(alpha = 0.9f)
            val grid = scale.gridLines()
            val gridY = FloatArray(grid.size) { plotH - plotH * scale.norm(grid[it].toDouble()).toFloat() }
            val gridStyle = axisStyle.copy(color = secondary.copy(alpha = 0.6f))
            val gridLabels = Array(grid.size) { measurer.measure(grid[it].toString(), gridStyle) }
            val shown = IntArray(DspiConstants.RTA_MAX_BANDS)
            val labelStyle = axisStyle.copy(color = secondary)
            val charWidth = measurer.measure("0", labelStyle).size.width.toFloat()
            // Frequency labels change with the band layout only
            var labelKey = Long.MIN_VALUE
            var labels: List<Pair<TextLayoutResult, Float>> = emptyList()
            onDrawBehind {
                tick.longValue
                drawGrid(grid, gridY, gridLabels, secondary, w)
                if (plotH < 2f) return@onDrawBehind
                val nb = render.bandCount(slot)
                val n = render.shownBands(nb, shown)
                if (n == 0) return@onDrawBehind
                val caps = render.caps
                val key = (render.populated * 31 + nb) * 31 + (caps?.centres?.hashCode() ?: 0)
                if (key != labelKey && caps != null) {
                    labelKey = key
                    val placed = rtaPlaceBandLabels(caps.centres, shown.copyOf(n), w, charWidth)
                    labels = placed.map { measurer.measure(it.text, labelStyle) to it.x }
                }
                val levels = render.levels(slot)
                val slotW = w / n
                val gap = (slotW * 0.18f).coerceIn(0.5f, 2f)
                val barW = max(1f, slotW - gap)
                val radius = CornerRadius(min(1.5f * density, barW / 3))
                for (i in 0 until n) {
                    val b = shown[i]
                    val x = i * slotW + gap / 2
                    val lv = scale.norm(levels[b].toDouble()).toFloat()
                    if (lv >= 0.001f) {
                        val y = plotH * (1 - lv)
                        drawRoundRect(barBrush, Offset(x, y), Size(barW, plotH - y), radius)
                    }
                    if (showPeakHold) {
                        val pn = scale.norm(levels[nb + b].toDouble()).toFloat()
                        if (pn >= 0.001f) drawRect(capColor, Offset(x, max(0f, plotH * (1 - pn) - 1)), Size(barW, 1.5f * density))
                    }
                }
                for (i in labels.indices) {
                    val (layout, x) = labels[i]
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, plotH + 1))
                }
            }
        }
    )
}

/** A line every 12 dB, 0 dBFS stronger, with its number at the right edge. */
private fun DrawScope.drawGrid(grid: IntArray, gridY: FloatArray, labels: Array<TextLayoutResult>, secondary: Color, w: Float) {
    for (i in grid.indices) {
        val zero = grid[i] == 0
        drawLine(
            secondary.copy(alpha = if (zero) 0.35f else 0.12f),
            Offset(0f, gridY[i]),
            Offset(w, gridY[i]),
            strokeWidth = if (zero) 1f * density else 0.5f * density
        )
        val l = labels[i]
        drawText(l, topLeft = Offset(w - l.size.width - 2 * density, gridY[i] - l.size.height))
    }
}

// ── Curves ──

/** The filled spectrum over a log-frequency dB grid (RtaBinGrid + the graph's spectrum overlay). */
@Composable
private fun RtaCurvesPlot(
    render: RtaRenderState,
    colors: List<Color>,
    scale: RtaScale,
    prefs: RtaPreferences,
    tick: LongState,
    modifier: Modifier
) {
    val measurer = rememberTextMeasurer()
    val secondary = secondaryText()
    Spacer(
        modifier.drawWithCache {
            val labelRow = 12.dp.toPx()
            val bottom = max(0f, size.height - labelRow)
            val w = size.width
            val grid = scale.gridLines()
            val gridY = FloatArray(grid.size) { bottom - bottom * scale.norm(grid[it].toDouble()).toFloat() }
            val gridStyle = axisStyle.copy(color = secondary.copy(alpha = 0.6f))
            val gridLabels = Array(grid.size) { measurer.measure(grid[it].toString(), gridStyle) }
            val minHz = prefs.graphMinFreq
            val maxHz = prefs.graphMaxFreq
            val lo = log10(minHz)
            val hi = log10(maxHz)
            val marks = rtaLabelledCentres.filter { it in minHz..maxHz }
            val markX = FloatArray(marks.size) { ((log10(marks[it]) - lo) / (hi - lo) * w).toFloat() }
            val markLabels = Array(marks.size) { measurer.measure(rtaShortHz(marks[it]), axisStyle.copy(color = secondary)) }
            val fills =
                Array(colors.size) {
                    Brush.verticalGradient(listOf(colors[it].copy(alpha = 0.34f), colors[it].copy(alpha = 0.02f)), 0f, bottom)
                }
            val lineColors = Array(colors.size) { colors[it].copy(alpha = 0.55f) }
            val peakColors = Array(colors.size) { colors[it].copy(alpha = 0.35f) }
            val line = Path()
            val fill = Path()
            val peak = Path()
            val cols = RtaCurveColumns()
            val stroke = Stroke(width = 1f * density, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val binsBuf = FloatArray(DspiConstants.RTA_MAX_BINS)
            onDrawBehind {
                tick.longValue
                drawGrid(grid, gridY, gridLabels, secondary, w)
                for (i in marks.indices) {
                    drawLine(secondary.copy(alpha = 0.10f), Offset(markX[i], 0f), Offset(markX[i], bottom), strokeWidth = 0.5f * density)
                    val l = markLabels[i]
                    val x = (markX[i] - l.size.width / 2f).coerceIn(0f, max(0f, w - l.size.width))
                    drawText(l, topLeft = Offset(x, bottom + 1))
                }
                val caps = render.caps ?: return@onDrawBehind
                if (bottom < 4f || w < 4f) return@onDrawBehind
                val channels = render.channels
                val nBins = render.bins.count
                for (k in 0 until nBins) binsBuf[k] = render.bins[k]
                for (i in channels.indices) {
                    if (i >= colors.size) break
                    val nb = render.bandCount(i)
                    if (nb == 0) continue
                    val levels = render.levels(i)
                    val withBins = channels.size == 1 && nBins > 0
                    cols.compute(
                        levels, nb, false, render.populated, caps, w, minHz, maxHz,
                        if (withBins) binsBuf else null, if (withBins) nBins else 0, render.binsRate
                    )
                    buildPaths(cols, scale, bottom, line, fill)
                    drawPath(fill, fills[i])
                    drawPath(line, lineColors[i], style = stroke)
                    if (prefs.showPeakHold) {
                        cols.compute(levels, nb, true, render.populated, caps, w, minHz, maxHz, null, 0, 0)
                        buildPaths(cols, scale, bottom, peak, null)
                        drawPath(peak, peakColors[i], style = stroke)
                    }
                }
            }
        }
    )
}

/** Runs of non-NaN columns become the line and (closed to the bottom) the fill. */
private fun buildPaths(cols: RtaCurveColumns, scale: RtaScale, bottom: Float, line: Path, fill: Path?) {
    line.reset()
    fill?.reset()
    val v = cols.columns
    val n = cols.count
    var c = 0
    while (c < n) {
        while (c < n && v[c].isNaN()) c++
        if (c >= n) break
        val start = c
        val x0 = c * RtaCurveColumns.COLUMN_STEP
        val y0 = bottom - bottom * scale.norm(v[c].toDouble()).toFloat()
        line.moveTo(x0, y0)
        fill?.moveTo(x0, bottom)
        fill?.lineTo(x0, y0)
        var lastX = x0
        c++
        while (c < n && !v[c].isNaN()) {
            val x = c * RtaCurveColumns.COLUMN_STEP
            val y = bottom - bottom * scale.norm(v[c].toDouble()).toFloat()
            line.lineTo(x, y)
            fill?.lineTo(x, y)
            lastX = x
            c++
        }
        if (fill != null && c > start) {
            fill.lineTo(lastX, bottom)
            fill.close()
        }
    }
}

// ── Status ──

/** Swift `RtaStatusBar`: running, refresh, frames/s, transform, loads, and the one hint that applies. */
@Composable
private fun RtaStatusBar(state: SpectrumUiState, snapshot: StateFlow<RtaSnapshot?>, showsBars: Boolean) {
    // The only reader of the per-frame snapshot outside the canvases: its changes recompose this line alone
    val s = snapshot.collectAsState().value
    val running = s?.running == true
    val caps = state.caps
    val lowest =
        if (showsBars && running && caps != null && (s?.fftOrder ?: state.prefs.fftOrder) < caps.orderMax) {
            rtaLowestMeasurableCentreHz(s, caps)
        } else {
            null
        }
    val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = secondaryText())
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(if (running) MacColors.green else secondaryText().copy(alpha = 0.5f), 6.dp)
            Text(if (running) "Running" else "Idle", style = style)
        }
        Text(rtaRefreshDescription(s, state.prefs.fftOrder), style = style)
        if (s != null) {
            if (s.framesPerS > 0) Text("${s.framesPerS} frames/s", style = style)
            if (s.lastFrameUs > 0) Text("transform ${s.lastFrameUs} µs", style = style)
            if (s.busyUsPerS > 0) Text(rtaMainLoopDescription(s.busyUsPerS), style = style)
            if (s.bassBusyUsPerS > 0) Text(rtaBassLoadDescription(s.bassBusyUsPerS), style = style)
        }
        when {
            s?.configRejected == true -> Text("Device refused this configuration", style = style.copy(color = MacColors.orange))
            lowest != null ->
                Text(
                    "shaded bands below ${rtaShortHz(lowest)} Hz need a larger transform size in Settings",
                    style = style.copy(color = MacColors.orange)
                )
            caps != null && caps.dynamicRangeDb > 0 -> Text("${caps.dynamicRangeDb}/${caps.bassDynamicRangeDb} dB range", style = style)
        }
    }
}

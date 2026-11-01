// Port of DSPi-Console-b5:DSPi Console/GraphSpectrumOverlay.swift @ b5fdeac:
// the live spectrum drawn inside the response graph, behind the curves, from
// the analyser's snapshots (bridge `rta` controller; macOS RtaEngine). The
// spectrum gets its own vertical mapping (the analyser's floor at the bottom
// of the plot, its ceiling at the top) and shares only the frequency axis.
// One channel: the bands in the bass and the FFT bins above them; several:
// band curves only. Compose Canvas instead of Metal; the per-frame time
// smoothing between device frames (RtaBarSmoother) is not ported yet
// (docs/UPSTREAM-DEVIATIONS.md GRAPH).
package se.bofr.dspi.feature.graph

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaSnapshot

/** The analyser's level range on the plot (macOS `RtaScale`). */
data class RtaScale(val floorDB: Double, val ceilingDB: Double) {
    fun norm(db: Double): Double {
        if (ceilingDB <= floorDB) return 0.0
        return min(1.0, max(0.0, (db - floorDB) / (ceilingDB - floorDB)))
    }
}

/** Width each FFT bin is averaged over (macOS `rtaBinSmoothingOctaves`). */
const val RTA_BIN_SMOOTHING_OCTAVES = 1.0 / 6.0

/**
 * Each bin's level averaged in power over [octaves] centred on it (macOS
 * `rtaSmoothBins`). Where that window is narrower than a bin, the bin is left alone.
 */
fun rtaSmoothBins(levelsDB: List<Double>, octaves: Double): List<Double> {
    val n = levelsDB.size
    if (octaves <= 0 || n <= 2) return levelsDB
    val prefix = DoubleArray(n + 1)
    for (k in 0 until n) prefix[k + 1] = prefix[k] + 10.0.pow(levelsDB[k] / 10)
    val half = 2.0.pow(octaves / 2)
    val out = levelsDB.toMutableList()
    for (k in 1 until n) {
        val lo = max(1, ceil(k / half).toInt())
        val hi = min(n - 1, floor(k * half).toInt())
        if (hi <= lo) continue
        val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        out[k] = 10 * log10(max(mean, 1e-30))
    }
    return out
}

/**
 * Turns the analyser's frames into points on the graph's log frequency axis
 * (macOS `RtaCurveBuilder`), in plot coordinates (dp, y down).
 */
class RtaCurveBuilder(
    private val minFreq: Double,
    private val maxFreq: Double,
    private val width: Double,
    private val height: Double,
    private val scale: RtaScale
) {
    fun x(hz: Double): Double {
        if (minFreq <= 0 || maxFreq <= minFreq) return 0.0
        val lo = log10(minFreq)
        return (log10(max(hz, 1.0)) - lo) / (log10(maxFreq) - lo) * width
    }

    fun y(db: Double): Double = height - height * scale.norm(db)

    /**
     * The third-octave picture as points. Bands below [firstBand] hold no FFT
     * bin at the current size and are left out; one band either side of the
     * visible range is kept, so the curve runs off the plot edges.
     */
    fun bandPoints(centres: List<Int>, levels: List<Float>, nBands: Int, firstBand: Int): List<Offset> {
        if (centres.isEmpty()) return emptyList()
        val slots = min(if (nBands > 0) nBands else centres.size, DspiConstants.RTA_MAX_BANDS)
        val points = mutableListOf<Offset>()
        var below: Offset? = null
        for (i in max(firstBand, 0) until min(slots, min(centres.size, levels.size))) {
            val hz = centres[i].toDouble()
            val point = Offset(x(hz).toFloat(), y(levels[i].toDouble()).toFloat())
            if (hz < minFreq) {
                below = point
                continue
            }
            below?.let { points += it }
            below = null
            points += point
            if (hz > maxFreq) break
        }
        return points
    }

    /**
     * The bins, smoothed across frequency, as one point per column, taking the
     * loudest bin that lands in each; columns between two bins are interpolated.
     */
    fun binPoints(binsDb: List<Float>, count: Int, sampleRateHz: Long): List<Offset> {
        if (count <= 1 || sampleRateHz <= 0 || minFreq <= 0 || maxFreq <= minFreq) return emptyList()
        val columns = max(width.roundToInt(), 2)
        val smoothed = rtaSmoothBins(binsDb.take(count).map { it.toDouble() }, RTA_BIN_SMOOTHING_OCTAVES)
        val target = DoubleArray(columns) { Double.NEGATIVE_INFINITY }
        for (k in 1 until min(count, smoothed.size)) {
            val hz = k.toDouble() * sampleRateHz / (2.0 * count)
            if (hz < minFreq || hz > maxFreq) continue
            val c = x(hz).roundToInt().coerceIn(0, columns - 1)
            target[c] = max(target[c], smoothed[k])
        }
        val first = target.indexOfFirst { it.isFinite() }
        val last = target.indexOfLast { it.isFinite() }
        if (first < 0 || last <= first) return emptyList()
        var previous = first
        for (c in first + 1..last) {
            if (!target[c].isFinite()) continue
            val gap = c - previous
            if (gap > 1) {
                val a = target[previous]
                val b = target[c]
                for (g in 1 until gap) target[previous + g] = a + (b - a) * g / gap
            }
            previous = c
        }
        return (first..last).map { Offset(it.toFloat(), y(target[it]).toFloat()) }
    }

    /**
     * One channel's picture from both products: the bands below the top of the
     * bass bank and the bins above it, crossfaded over one octave. One point
     * per column; where only one product has data it is used alone.
     */
    fun blend(bands: List<Offset>, bins: List<Offset>, centres: List<Int>, bassBands: Int): List<Offset> {
        if (bins.size <= 1) return bands
        val columns = max(width.roundToInt(), 2)
        val firstBinColumn = bins[0].x.roundToInt()
        var loX = Double.NEGATIVE_INFINITY
        var hiX = Double.NEGATIVE_INFINITY
        if (bassBands > 0 && bassBands <= centres.size) {
            loX = x(centres[bassBands - 1].toDouble())
            hiX = x(centres[min(bassBands + 2, centres.size - 1)].toDouble())
        }
        val out = ArrayList<Offset>(columns)
        for (c in 0 until columns) {
            val xc = c.toFloat()
            val a = sampleAt(bands, xc)
            val b = bins.getOrNull(c - firstBinColumn)?.y
            val y =
                when {
                    a != null && b != null -> {
                        val w = if (hiX > loX) ((xc - loX) / (hiX - loX)).coerceIn(0.0, 1.0).toFloat() else 1f
                        a + (b - a) * w
                    }
                    a != null -> a
                    b != null -> b
                    else -> continue
                }
            out += Offset(xc, y)
        }
        return out
    }

    /** Linear sample of a band polyline at x (macOS samples the Catmull-Rom curve). */
    private fun sampleAt(points: List<Offset>, x: Float): Float? {
        if (points.size < 2 || x < points.first().x || x > points.last().x) return null
        for (i in 0 until points.size - 1) {
            val p = points[i]
            val q = points[i + 1]
            if (x >= p.x && x <= q.x) {
                val t = if (q.x > p.x) (x - p.x) / (q.x - p.x) else 0f
                return p.y + (q.y - p.y) * t
            }
        }
        return null
    }
}

/** A Catmull-Rom curve through [points] as cubic Béziers, control points clamped into [0, height] (macOS `smoothPath`). */
fun smoothPath(points: List<Offset>, height: Float): Path {
    val path = Path()
    if (points.size < 2) return path

    fun clampY(y: Float) = min(max(y, 0f), height)
    path.moveTo(points[0].x, points[0].y)
    for (i in 0 until points.size - 1) {
        val p0 = if (i > 0) points[i - 1] else points[i]
        val p1 = points[i]
        val p2 = points[i + 1]
        val p3 = if (i + 2 < points.size) points[i + 2] else p2
        path.cubicTo(
            p1.x + (p2.x - p0.x) / 6,
            clampY(p1.y + (p2.y - p0.y) / 6),
            p2.x - (p3.x - p1.x) / 6,
            clampY(p2.y - (p3.y - p1.y) / 6),
            p2.x,
            p2.y
        )
    }
    return path
}

private fun polyline(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points[0].x, points[0].y)
    for (p in points.drop(1)) path.lineTo(p.x, p.y)
    return path
}

private val overlayIds = AtomicInteger(0x6A00)

/**
 * The live spectrum for [selection] (channels at its tap), drawn as a
 * translucent fill under the curves in each channel's colour. Registers with
 * the analyser while shown; the analyser stops when nothing shows it.
 */
@Composable
fun GraphSpectrumOverlay(
    bridge: DspiBridge,
    selection: RtaChannelSelection,
    minFreq: Double,
    maxFreq: Double,
    settings: GraphSettings,
    modifier: Modifier = Modifier
) {
    val rta = bridge.rta
    val id = remember { overlayIds.incrementAndGet() }
    DisposableEffect(id, selection) {
        rta.update(id, selection.tap, selection.mask, true)
        onDispose { rta.release(id) }
    }
    val caps by rta.caps.collectAsState()
    val snapshot by rta.snapshot.collectAsState()
    val c = caps ?: return
    if (!c.supported) return
    val snap = snapshot ?: return
    if (snap.tap != selection.tap) return
    val channels = selection.channels
    if (channels.isEmpty()) return
    // The fine curve needs the whole selection to be one channel.
    val wantsBins = channels.size == 1
    val colours = channels.map { rtaColour(bridge, selection.tap, it) }
    Canvas(modifier.graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)) {
        val dens = density
        val wDp = size.width / dens
        val hDp = size.height / dens
        if (wDp <= 4 || hDp <= 4) return@Canvas
        val builder =
            RtaCurveBuilder(minFreq, maxFreq, wDp.toDouble(), hDp.toDouble(), RtaScale(settings.rtaFloorDB, settings.rtaCeilingDB))
        val opacity = settings.rtaGraphOpacity.toFloat()
        var start = Float.POSITIVE_INFINITY
        scale(dens, dens, pivot = Offset.Zero) {
            for ((idx, ch) in channels.withIndex()) {
                val traces = traceFor(builder, c, snap, ch, wantsBins) ?: continue
                val colour = colours[idx]
                val (curve, dense, peak) = traces
                if (curve.size > 1) {
                    val path = if (dense) polyline(curve) else smoothPath(curve, hDp)
                    val fill = Path()
                    fill.addPath(path)
                    fill.lineTo(curve.last().x, hDp)
                    fill.lineTo(curve.first().x, hDp)
                    fill.close()
                    drawPath(
                        fill,
                        Brush.verticalGradient(listOf(colour.copy(alpha = 0.34f * opacity), colour.copy(alpha = 0.02f * opacity)), 0f, hDp)
                    )
                    if (settings.showGraphGlow) {
                        drawPath(
                            path,
                            colour.copy(alpha = 0.15f * opacity),
                            style = Stroke(4f, cap = StrokeCap.Round)
                        )
                    }
                    drawPath(path, colour.copy(alpha = 0.55f * opacity), style = Stroke(1f, join = StrokeJoin.Round))
                    start = min(start, curve.first().x)
                }
                if (settings.rtaShowPeakHold && peak.size > 1) {
                    drawPath(smoothPath(peak, hDp), colour.copy(alpha = 0.35f * opacity), style = Stroke(1f, cap = StrokeCap.Round))
                    start = min(start, peak.first().x)
                }
            }
            // Everything fades in across the 30 points right of where the data
            // starts, instead of standing up as a wall in the middle of the graph.
            if (start.isFinite() && start > 1f) {
                drawRect(Color.Transparent, Offset.Zero, androidx.compose.ui.geometry.Size(start, hDp), blendMode = BlendMode.Clear)
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = start, endX = start + 30f),
                    Offset(start, 0f),
                    androidx.compose.ui.geometry.Size(30f, hDp),
                    blendMode = BlendMode.DstIn
                )
            }
        }
    }
}

private fun traceFor(
    builder: RtaCurveBuilder,
    caps: RtaCapsInfo,
    snap: RtaSnapshot,
    ch: Int,
    wantsBins: Boolean
): Triple<List<Offset>, Boolean, List<Offset>>? {
    val populated = (snap.populated shr ch) and 1L == 1L
    val frame = snap.frames.getOrNull(ch)?.takeIf { populated }
    val bands = frame?.let { builder.bandPoints(caps.centres, it.avgDb, it.nBands, snap.firstBand) } ?: emptyList()
    var curve = bands
    var dense = false
    if (wantsBins && snap.binsChannel == ch && snap.binsCount > 1) {
        val bins = builder.binPoints(snap.binsDb, snap.binsCount, snap.binsSampleRate)
        curve = builder.blend(bands, bins, caps.centres, caps.bassBands)
        dense = bins.size > 1
    }
    val peak = frame?.let { builder.bandPoints(caps.centres, it.peakDb, it.nBands, snap.firstBand) } ?: emptyList()
    if (curve.size <= 1 && peak.size <= 1) return null
    return Triple(curve, dense, peak)
}

/** A channel's curve colour at an analyser tap (macOS `eqCurveColor` via `rtaEqChannel`). */
internal fun rtaColour(bridge: DspiBridge, tap: Int, channel: Int): Color = channelColour(bridge, bridge.rta.appChannel(tap, channel))

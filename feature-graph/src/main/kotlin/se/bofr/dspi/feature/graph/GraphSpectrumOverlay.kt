// Port of DSPi-Console-b5:DSPi Console/GraphSpectrumOverlay.swift @ b5fdeac
// (GraphSpectrumOverlay, RtaCurveBuilder, rtaSmoothBins, smoothPath,
// polyline), with RtaBarSmoother / rtaFallTau / channelRefreshInterval from
// SpectrumAnalyserView.swift l.110-200, 595-603 and the bin projection of
// RtaRenderCache.swift `projectBins`: the live spectrum drawn inside the
// response graph, behind the curves. The spectrum gets its own vertical
// mapping (the analyser's floor at the bottom, its ceiling at the top) and
// shares only the frequency axis. One channel: the bands in the bass and the
// FFT bins above them; several: band curves only.
//
// Compose Canvas instead of Metal. The snapshot is read in the draw phase
// only (an analyser frame redraws, never recomposes), and every buffer, path
// and brush lives in a per-view render cache keyed on the geometry, so a
// steady frame allocates nothing.
package se.bofr.dspi.feature.graph

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil
import kotlin.math.exp
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
import se.bofr.dspi.ui.settings.AppSettings

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
 * `rtaSmoothBins`), from the first n values of `src` into `out`. Where the window is
 * narrower than a bin the bin is left alone. [prefix] needs n + 1 entries.
 */
fun rtaSmoothBins(src: DoubleArray, n: Int, octaves: Double, prefix: DoubleArray, out: DoubleArray) {
    for (k in 0 until n) out[k] = src[k]
    if (octaves <= 0 || n <= 2) return
    prefix[0] = 0.0
    for (k in 0 until n) prefix[k + 1] = prefix[k] + 10.0.pow(src[k] / 10)
    val half = 2.0.pow(octaves / 2)
    for (k in 1 until n) {
        val lo = max(1, ceil(k / half).toInt())
        val hi = min(n - 1, floor(k * half).toInt())
        if (hi <= lo) continue
        val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        out[k] = 10 * log10(max(mean, 1e-30))
    }
}

/**
 * Glides the analyser display between device frames (macOS `RtaBarSmoother`):
 * one pole per value, stepped by real elapsed time, the fall slower than the
 * rise. A new identity (channel and count) snaps to the new picture instead
 * of sliding across from the old one. A tau of zero moves instantly.
 */
class RtaBarSmoother {
    private var values = DoubleArray(0)
    private var count = -1
    private var lastTime = Double.NaN
    private var identity = Int.MIN_VALUE

    /** Steps toward the first n values of `target`; returns the smoothed values (first [n] valid, owned by the smoother). */
    fun step(now: Double, target: DoubleArray, n: Int, identity: Int, riseTau: Double, fallTau: Double): DoubleArray {
        if (identity != this.identity || n != count) {
            this.identity = identity
            count = n
            if (values.size < n) values = DoubleArray(max(n, 64))
            for (i in 0 until n) values[i] = target[i]
            lastTime = now
            return values
        }
        val dt = if (lastTime.isNaN()) 0.0 else now - lastTime
        if (dt <= 0) return values
        lastTime = now
        val riseK = if (riseTau > 0) 1 - exp(-dt / riseTau) else 1.0
        val fallK = if (fallTau > 0) 1 - exp(-dt / fallTau) else 1.0
        for (i in 0 until n) {
            val t = target[i]
            val v = values[i]
            values[i] = v + (t - v) * (if (t > v) riseK else fallK)
        }
        return values
    }
}

/** macOS `rtaFallTau(refreshInterval:amount:)`: about one rotation interval, clamped to 35..400 ms; 0 = off. */
fun rtaFallTau(refreshInterval: Double, amount: Double): Double {
    if (amount <= 0) return 0.0
    return min(0.40, max(0.035, refreshInterval * amount))
}

/** macOS `channelRefreshInterval`: how long one channel waits between frames (the core's figure when it has one). */
fun rtaChannelRefreshInterval(snap: RtaSnapshot, fftOrder: Int): Double {
    if (snap.refreshIntervalS > 0) return snap.refreshIntervalS.toDouble()
    val live = max(snap.liveCount, 1)
    if (snap.framesPerS > 0) return live.toDouble() / snap.framesPerS
    val rate = if (snap.sampleRate > 0) snap.sampleRate.toDouble() else 48000.0
    return (1 shl fftOrder) / rate * live
}

/**
 * Turns the analyser's frames into plot coordinates (dp, y down) on the
 * graph's log frequency axis (macOS `RtaCurveBuilder`). The geometry-dependent
 * tables (band x positions, bin → column map) are rebuilt only when the
 * geometry, the centres or the bin layout change; every output goes into
 * caller-owned arrays.
 */
class RtaCurveBuilder {
    var minFreq = 20.0
        private set
    var maxFreq = 20000.0
        private set
    var width = 0.0
        private set
    var height = 0.0
        private set
    var scale = RtaScale(-90.0, 6.0)
        private set

    /** Pixel columns (dp) of the plot. */
    var columns = 2
        private set

    private var centres: List<Int> = emptyList()
    private var centreX = FloatArray(DspiConstants.RTA_MAX_BANDS)
    private var binCount = -1
    private var binRate = -1L
    private var binColumn = IntArray(DspiConstants.RTA_MAX_BINS)

    /** First and last filled column of the last [binColumns] call. */
    var binFirst = -1
        private set
    var binLast = -1
        private set

    /** Sets the geometry; true when it changed (callers drop what they cached on it). */
    fun configure(minFreq: Double, maxFreq: Double, width: Double, height: Double, scale: RtaScale): Boolean {
        if (minFreq == this.minFreq && maxFreq == this.maxFreq && width == this.width && height == this.height && scale == this.scale) {
            return false
        }
        this.minFreq = minFreq
        this.maxFreq = maxFreq
        this.width = width
        this.height = height
        this.scale = scale
        columns = max(width.roundToInt(), 2)
        centres = emptyList()
        binCount = -1
        return true
    }

    fun x(hz: Double): Double {
        if (minFreq <= 0 || maxFreq <= minFreq) return 0.0
        val lo = log10(minFreq)
        return (log10(max(hz, 1.0)) - lo) / (log10(maxFreq) - lo) * width
    }

    fun y(db: Double): Double = height - height * scale.norm(db)

    private fun ensureCentres(c: List<Int>) {
        if (c === centres || c == centres) return
        centres = c
        if (centreX.size < c.size) centreX = FloatArray(c.size)
        for (i in c.indices) centreX[i] = x(c[i].toDouble()).toFloat()
    }

    /**
     * The third-octave picture: `levels[i]` (dB) at band centre i, from
     * [firstBand] (bands below it hold no FFT bin at this size) up to the slot
     * count. One band either side of the visible range is kept so the curve
     * runs off the plot edges. Writes [outX]/[outY]; returns the count.
     */
    fun bandPoints(
        centres: List<Int>,
        levels: DoubleArray,
        nLevels: Int,
        nBands: Int,
        firstBand: Int,
        outX: FloatArray,
        outY: FloatArray
    ): Int {
        if (centres.isEmpty()) return 0
        ensureCentres(centres)
        val slots = min(if (nBands > 0) nBands else centres.size, DspiConstants.RTA_MAX_BANDS)
        var n = 0
        var belowX = Float.NaN
        var belowY = Float.NaN
        // A slot the frame carries no level for reads the floor (macOS fills
        // `levels` with `scale.floorDB` before copying the frame in).
        for (i in max(firstBand, 0) until min(slots, centres.size)) {
            val hz = centres[i].toDouble()
            val px = centreX[i]
            val py = y(if (i < nLevels) levels[i] else scale.floorDB).toFloat()
            if (hz < minFreq) {
                belowX = px
                belowY = py
                continue
            }
            if (!belowX.isNaN()) {
                outX[n] = belowX
                outY[n] = belowY
                n++
                belowX = Float.NaN
            }
            outX[n] = px
            outY[n] = py
            n++
            if (hz > maxFreq) break
        }
        return n
    }

    /**
     * The (already frequency-smoothed) bin levels as one dB value per column,
     * the loudest bin in each, columns between bins interpolated (macOS
     * `projectBins`). Fills `out` from binFirst to binLast; false when fewer than two
     * columns are filled.
     */
    fun binColumns(levels: DoubleArray, count: Int, sampleRateHz: Long, out: DoubleArray): Boolean {
        binFirst = -1
        binLast = -1
        if (count <= 1 || sampleRateHz <= 0 || minFreq <= 0 || maxFreq <= minFreq) return false
        if (count != binCount || sampleRateHz != binRate) {
            binCount = count
            binRate = sampleRateHz
            if (binColumn.size < count) binColumn = IntArray(count)
            for (k in 0 until count) {
                val hz = k.toDouble() * sampleRateHz / (2.0 * count)
                binColumn[k] = if (k == 0 || hz < minFreq || hz > maxFreq) -1 else x(hz).roundToInt().coerceIn(0, columns - 1)
            }
        }
        for (c in 0 until columns) out[c] = Double.NEGATIVE_INFINITY
        for (k in 1 until count) {
            val c = binColumn[k]
            if (c >= 0 && levels[k] > out[c]) out[c] = levels[k]
        }
        var first = -1
        var last = -1
        for (c in 0 until columns) {
            if (out[c].isFinite()) {
                if (first < 0) first = c
                last = c
            }
        }
        if (first < 0 || last <= first) return false
        var previous = first
        for (c in first + 1..last) {
            if (!out[c].isFinite()) continue
            val gap = c - previous
            if (gap > 1) {
                val a = out[previous]
                val b = out[c]
                for (g in 1 until gap) out[previous + g] = a + (b - a) * g / gap
            }
            previous = c
        }
        binFirst = first
        binLast = last
        return true
    }

    /** macOS `sampleBands`: Hermite between points seg and seg+1, slopes from the neighbours. */
    private fun hermiteAt(xs: FloatArray, ys: FloatArray, n: Int, seg: Int, x: Float): Float {
        val h = xs[seg + 1] - xs[seg]
        if (h <= 0f) return ys[seg]
        val t = (x - xs[seg]) / h
        val t2 = t * t
        val t3 = t2 * t
        val y =
            (2 * t3 - 3 * t2 + 1) * ys[seg] + (t3 - 2 * t2 + t) * h * slope(xs, ys, n, seg) +
                (-2 * t3 + 3 * t2) * ys[seg + 1] + (t3 - t2) * h * slope(xs, ys, n, seg + 1)
        return min(max(y, 0f), height.toFloat())
    }

    private fun slope(xs: FloatArray, ys: FloatArray, n: Int, i: Int): Float {
        val a = max(i - 1, 0)
        val b = min(i + 1, n - 1)
        return if (xs[b] > xs[a]) (ys[b] - ys[a]) / (xs[b] - xs[a]) else 0f
    }

    /**
     * One channel's picture from both products: the bands below the top of the
     * bass bank and the bins (`binDb` from binFirst to binLast, dB per column) above
     * it, crossfaded over one octave. Writes one y per column into [outY] (NaN
     * where neither has data); returns the number of columns filled.
     */
    fun blend(
        bandX: FloatArray,
        bandY: FloatArray,
        nBands: Int,
        binDb: DoubleArray,
        centres: List<Int>,
        bassBands: Int,
        outY: FloatArray
    ): Int {
        var loX = Double.NEGATIVE_INFINITY
        var hiX = Double.NEGATIVE_INFINITY
        if (bassBands > 0 && bassBands <= centres.size) {
            loX = x(centres[bassBands - 1].toDouble())
            hiX = x(centres[min(bassBands + 2, centres.size - 1)].toDouble())
        }
        var filled = 0
        var seg = 0
        for (c in 0 until columns) {
            val xc = c.toFloat()
            // The band curve sampled per column with the same cubic Hermite
            // (Catmull-Rom slopes) as macOS `RtaRenderCache.sampleBands`,
            // clamped into the plot.
            var a = Float.NaN
            if (nBands >= 2 && xc >= bandX[0] && xc <= bandX[nBands - 1]) {
                while (seg < nBands - 2 && xc > bandX[seg + 1]) seg++
                a = hermiteAt(bandX, bandY, nBands, seg, xc)
            }
            val b = if (binFirst >= 0 && c in binFirst..binLast) y(binDb[c]).toFloat() else Float.NaN
            val v =
                when {
                    !a.isNaN() && !b.isNaN() -> {
                        val w = if (hiX > loX) ((xc - loX) / (hiX - loX)).coerceIn(0.0, 1.0).toFloat() else 1f
                        a + (b - a) * w
                    }
                    !a.isNaN() -> a
                    else -> b
                }
            outY[c] = v
            if (!v.isNaN()) filled++
        }
        return filled
    }
}

/**
 * A Catmull-Rom curve through the points as cubic Béziers into [path]
 * (rewound), control points clamped into [0, height] (macOS `smoothPath`).
 */
fun smoothPath(path: Path, xs: FloatArray, ys: FloatArray, n: Int, height: Float) {
    path.rewind()
    if (n < 2) return

    fun clampY(y: Float) = min(max(y, 0f), height)
    path.moveTo(xs[0], ys[0])
    for (i in 0 until n - 1) {
        val i0 = if (i > 0) i - 1 else i
        val i3 = if (i + 2 < n) i + 2 else i + 1
        path.cubicTo(
            xs[i] + (xs[i + 1] - xs[i0]) / 6,
            clampY(ys[i] + (ys[i + 1] - ys[i0]) / 6),
            xs[i + 1] - (xs[i3] - xs[i]) / 6,
            clampY(ys[i + 1] - (ys[i3] - ys[i]) / 6),
            xs[i + 1],
            ys[i + 1]
        )
    }
}

/**
 * Straight segments through one y per column into [path] (rewound), gaps
 * (NaN) left open (macOS `polyline`). Returns the first filled column, -1 if none.
 */
fun columnPath(path: Path, ys: FloatArray, columns: Int): Int {
    path.rewind()
    var first = -1
    var open = false
    for (c in 0 until columns) {
        val y = ys[c]
        if (y.isNaN()) {
            open = false
            continue
        }
        if (first < 0) first = c
        if (open) path.lineTo(c.toFloat(), y) else path.moveTo(c.toFloat(), y)
        open = true
    }
    return first
}

/** One channel's buffers, smoothers and paths, kept across frames. */
private class ChannelTrace {
    val avg = RtaBarSmoother()
    val peak = RtaBarSmoother()
    val bins = RtaBarSmoother()
    val levels = DoubleArray(DspiConstants.RTA_MAX_BANDS)
    val bandX = FloatArray(DspiConstants.RTA_MAX_BANDS + 1)
    val bandY = FloatArray(DspiConstants.RTA_MAX_BANDS + 1)
    val peakX = FloatArray(DspiConstants.RTA_MAX_BANDS + 1)
    val peakY = FloatArray(DspiConstants.RTA_MAX_BANDS + 1)
    val curve = Path()
    val fill = Path()
    val peakPath = Path()
    var colour = Color.Unspecified
    var fillBrush: Brush? = null
    var fillOpacity = Float.NaN
    var fillHeight = Float.NaN
    var fadeBrush: Brush? = null
    var fadeStart = Float.NaN
    var start = Float.POSITIVE_INFINITY
    var hasCurve = false
    var hasPeak = false

    /** Which drawing pass last drew this trace (start-group bookkeeping without allocation). */
    var pass = 0
}

/** Everything a spectrum overlay draws with, keyed on its geometry. */
private class SpectrumRenderCache {
    val builder = RtaCurveBuilder()
    val traces = HashMap<Int, ChannelTrace>()

    /** This frame's traces in selection order (reused list). */
    val drawn = ArrayList<ChannelTrace>(DspiConstants.RTA_MAX_CHANNELS)
    val colours = ArrayList<Color>(DspiConstants.RTA_MAX_CHANNELS)
    var columnY = FloatArray(0)
    var binDb = DoubleArray(0)
    var binSpan = DoubleArray(0)
    val binSrc = DoubleArray(DspiConstants.RTA_MAX_BINS)
    val binSmooth = DoubleArray(DspiConstants.RTA_MAX_BINS)
    val prefix = DoubleArray(DspiConstants.RTA_MAX_BINS + 1)

    /** macOS blurred glow: a 2 pt stroke under a 4 pt blur (API 31+). */
    val blurGlowStroke = Stroke(2f, cap = StrokeCap.Round, join = StrokeJoin.Round)

    /** Below API 31 (no RenderEffect) the glow is a wider faint stroke. */
    val wideGlowStroke = Stroke(4f, cap = StrokeCap.Round)
    val edgeStroke = Stroke(1f, join = StrokeJoin.Round)
    val peakStroke = Stroke(1f, cap = StrokeCap.Round)
    val layerPaint = Paint()
    var layerRect = Rect.Zero
    var pass = 0

    fun trace(key: Int) = traces.getOrPut(key) { ChannelTrace() }

    fun reshape(columns: Int, wDp: Float, hDp: Float) {
        if (columnY.size < columns) columnY = FloatArray(columns)
        if (binDb.size < columns) binDb = DoubleArray(columns)
        if (binSpan.size < columns) binSpan = DoubleArray(columns)
        layerRect = Rect(0f, 0f, wDp, hDp)
        for (t in traces.values) {
            t.fillBrush = null
            t.fadeBrush = null
        }
    }
}

/** RenderEffect blur exists from API 31; below it the glow falls back to plain strokes. */
internal val blurAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

private val overlayIds = AtomicInteger(0x6A00)

/**
 * The live spectrum for [selection] (channels at its tap), drawn as a
 * translucent fill under the curves in each channel's colour. Registers with
 * the analyser while shown; the analyser stops when nothing shows it.
 *
 * Three stacked layers in macOS order: fills, the glow (blurred, API 31+),
 * then edges and peak contours. The first layer builds the frame's paths in
 * the shared cache; the others draw from it.
 */
@Composable
fun GraphSpectrumOverlay(
    bridge: DspiBridge,
    selection: RtaChannelSelection,
    minFreq: Double,
    maxFreq: Double,
    settings: AppSettings,
    modifier: Modifier = Modifier
) {
    val rta = bridge.rta
    val id = remember { overlayIds.incrementAndGet() }
    DisposableEffect(id, selection) {
        rta.update(id, selection.tap, selection.mask, true)
        onDispose { rta.release(id) }
    }
    val caps by rta.caps.collectAsStateWithLifecycle()
    // Read in the draw phase only: an analyser frame redraws the Canvas, nothing recomposes.
    val snapshot = rta.snapshot.collectAsStateWithLifecycle()
    val stateRev by bridge.stateRevision.collectAsStateWithLifecycle()
    val c = caps ?: return
    if (!c.supported) return
    val channels = selection.channels
    if (channels.isEmpty()) return
    val colours = remember(selection, stateRev) { channels.map { rtaColour(bridge, selection.tap, it) } }
    val cache = remember { SpectrumRenderCache() }
    // Interpolate at the display rate while Smoothing is on (macOS TimelineView(.animation)).
    val frameNanos = remember { mutableLongStateOf(0L) }
    val smoothing = settings.rtaSmoothing
    if (smoothing > 0) {
        LaunchedEffect(Unit) {
            while (true) withFrameNanos { frameNanos.longValue = it }
        }
    }
    val opacity = settings.rtaGraphOpacity.toFloat()
    Box(modifier) {
        // Layer 1: build the frame, draw the fills.
        Canvas(Modifier.fillMaxSize()) {
            frameNanos.longValue
            cache.drawn.clear()
            val snap = snapshot.value ?: return@Canvas
            if (snap.tap != selection.tap) return@Canvas
            val d = density
            val wDp = size.width / d
            val hDp = size.height / d
            if (wDp <= 4 || hDp <= 4) return@Canvas
            if (cache.builder.configure(
                    minFreq,
                    maxFreq,
                    wDp.toDouble(),
                    hDp.toDouble(),
                    RtaScale(settings.rtaFloorDB, settings.rtaCeilingDB)
                )
            ) {
                cache.reshape(cache.builder.columns, wDp, hDp)
            }
            val order = if (snap.fftOrder > 0) snap.fftOrder else 10
            val tau = if (smoothing > 0) rtaFallTau(rtaChannelRefreshInterval(snap, order), smoothing) else 0.0
            val now = System.nanoTime() / 1e9
            // The fine curve needs the whole selection to be one channel.
            val wantsBins = channels.size == 1
            cache.colours.clear()
            for (idx in channels.indices) {
                val t = cache.trace(channels[idx])
                buildTrace(cache, t, c, snap, channels[idx], wantsBins, tau, now, settings.rtaShowPeakHold, hDp)
                if (!t.hasCurve && !t.hasPeak) continue
                val colour = colours.getOrElse(idx) { Color.White }
                if (t.fillBrush == null || t.colour != colour || t.fillOpacity != opacity || t.fillHeight != hDp) {
                    t.colour = colour
                    t.fillOpacity = opacity
                    t.fillHeight = hDp
                    t.fillBrush =
                        Brush.verticalGradient(listOf(colour.copy(alpha = 0.34f * opacity), colour.copy(alpha = 0.02f * opacity)), 0f, hDp)
                }
                cache.drawn += t
            }
            scale(d, d, pivot = Offset.Zero) {
                drawByStartGroup(cache, hDp) { t -> if (t.hasCurve) drawPath(t.fill, t.fillBrush!!) }
            }
        }
        // Layer 2: the glow, blurred as one layer for every channel (macOS `soft.addFilter(.blur(radius: 4))`).
        if (settings.showGraphGlow && blurAvailable) {
            Canvas(
                Modifier.fillMaxSize().graphicsLayer {
                    renderEffect = BlurEffect(4.dp.toPx(), 4.dp.toPx(), TileMode.Decal)
                }
            ) {
                frameNanos.longValue
                snapshot.value
                scale(density, density, pivot = Offset.Zero) {
                    drawByStartGroup(cache, size.height / density) { t ->
                        if (t.hasCurve) drawPath(t.curve, t.colour, alpha = 0.35f * opacity, style = cache.blurGlowStroke)
                    }
                }
            }
        }
        // Layer 3: edges and peak contours (and the glow fallback below API 31).
        Canvas(Modifier.fillMaxSize()) {
            frameNanos.longValue
            snapshot.value
            val glowFallback = settings.showGraphGlow && !blurAvailable
            scale(density, density, pivot = Offset.Zero) {
                drawByStartGroup(cache, size.height / density) { t ->
                    if (t.hasCurve) {
                        if (glowFallback) drawPath(t.curve, t.colour, alpha = 0.15f * opacity, style = cache.wideGlowStroke)
                        drawPath(t.curve, t.colour, alpha = 0.55f * opacity, style = cache.edgeStroke)
                    }
                    // Peak hold rides on top as a thin contour.
                    if (t.hasPeak) drawPath(t.peakPath, t.colour, alpha = 0.35f * opacity, style = cache.peakStroke)
                }
            }
        }
    }
}

/**
 * Draws the frame's traces grouped by where their data starts (macOS `draw`:
 * `Dictionary(grouping:) { Int($0.start.rounded()) }`, keys ascending), each
 * group in its own layer that fades in across the 30 points right of its
 * start, instead of standing up as a wall in the middle of the graph. Data
 * that starts off the left edge has no wall to hide and is drawn directly.
 */
private inline fun DrawScope.drawByStartGroup(cache: SpectrumRenderCache, hDp: Float, draw: DrawScope.(ChannelTrace) -> Unit) {
    val traces = cache.drawn
    if (traces.isEmpty()) return
    val pass = ++cache.pass
    while (true) {
        // The lowest start key not drawn yet in this pass.
        var key = Int.MAX_VALUE
        for (t in traces) if (t.pass != pass) key = min(key, t.start.roundToInt())
        if (key == Int.MAX_VALUE) break
        var x0 = Float.POSITIVE_INFINITY
        var lead: ChannelTrace? = null
        for (t in traces) {
            if (t.pass != pass && t.start.roundToInt() == key && t.start < x0) {
                x0 = t.start
                lead = t
            }
        }
        val fade = x0 > 1f
        if (fade) drawIntoCanvas { it.saveLayer(cache.layerRect, cache.layerPaint) }
        for (t in traces) {
            if (t.pass == pass || t.start.roundToInt() != key) continue
            t.pass = pass
            draw(t)
        }
        if (fade) {
            val l = lead!!
            if (l.fadeBrush == null || l.fadeStart != x0) {
                l.fadeStart = x0
                l.fadeBrush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = x0, endX = x0 + 30f)
            }
            drawRect(Color.Transparent, Offset.Zero, Size(x0, hDp), blendMode = BlendMode.Clear)
            drawRect(l.fadeBrush!!, Offset(x0, 0f), Size(30f, hDp), blendMode = BlendMode.DstIn)
            drawIntoCanvas { it.restore() }
        }
    }
}

private fun buildTrace(
    cache: SpectrumRenderCache,
    t: ChannelTrace,
    caps: RtaCapsInfo,
    snap: RtaSnapshot,
    ch: Int,
    wantsBins: Boolean,
    tau: Double,
    now: Double,
    showPeak: Boolean,
    hDp: Float
) {
    val b = cache.builder
    t.hasCurve = false
    t.hasPeak = false
    t.start = Float.POSITIVE_INFINITY
    val populated = (snap.populated shr ch) and 1L == 1L
    val frame = if (populated) snap.frames.getOrNull(ch) else null
    var nBands = 0
    if (frame != null) {
        val n = min(frame.avgDb.size, t.levels.size)
        for (i in 0 until n) t.levels[i] = frame.avgDb[i].toDouble()
        val slots = if (frame.nBands > 0) frame.nBands else n
        val lv = if (tau > 0) t.avg.step(now, t.levels, n, (ch shl 8) or slots, tau * 0.4, tau) else t.levels
        nBands = b.bandPoints(caps.centres, lv, n, frame.nBands, snap.firstBand, t.bandX, t.bandY)
    }
    val cy = cache.columnY
    var dense = false
    if (wantsBins && snap.binsChannel == ch && snap.binsCount > 1) {
        val count = min(snap.binsCount, min(snap.binsDb.size, cache.binSrc.size))
        for (k in 0 until count) cache.binSrc[k] = snap.binsDb[k].toDouble()
        rtaSmoothBins(cache.binSrc, count, RTA_BIN_SMOOTHING_OCTAVES, cache.prefix, cache.binSmooth)
        if (b.binColumns(cache.binSmooth, count, snap.binsSampleRate, cache.binDb)) {
            if (tau > 0) {
                val span = b.binLast - b.binFirst + 1
                for (i in 0 until span) cache.binSpan[i] = cache.binDb[b.binFirst + i]
                val sm = t.bins.step(now, cache.binSpan, span, (ch shl 16) or span, tau * 0.4, tau)
                for (i in 0 until span) cache.binDb[b.binFirst + i] = sm[i]
            }
            dense = b.blend(t.bandX, t.bandY, nBands, cache.binDb, caps.centres, caps.bassBands, cy) > 1
        }
    }
    if (dense) {
        // The blended series is already one point per column: no smoothing.
        val first = columnPath(t.curve, cy, b.columns)
        var last = first
        for (c in 0 until b.columns) if (!cy[c].isNaN()) last = c
        t.fill.rewind()
        t.fill.addPath(t.curve)
        t.fill.lineTo(last.toFloat(), hDp)
        t.fill.lineTo(first.toFloat(), hDp)
        t.fill.close()
        t.hasCurve = first >= 0 && last > first
        t.start = first.toFloat()
    } else if (nBands > 1) {
        smoothPath(t.curve, t.bandX, t.bandY, nBands, hDp)
        t.fill.rewind()
        t.fill.addPath(t.curve)
        t.fill.lineTo(t.bandX[nBands - 1], hDp)
        t.fill.lineTo(t.bandX[0], hDp)
        t.fill.close()
        t.hasCurve = true
        t.start = t.bandX[0]
    }
    if (showPeak && frame != null) {
        val n = min(frame.peakDb.size, t.levels.size)
        for (i in 0 until n) t.levels[i] = frame.peakDb[i].toDouble()
        val slots = if (frame.nBands > 0) frame.nBands else n
        // A peak cap that eased upward would stop being a peak.
        val lv = if (tau > 0) t.peak.step(now, t.levels, n, (ch shl 8) or slots, 0.0, tau) else t.levels
        val np = b.bandPoints(caps.centres, lv, n, frame.nBands, snap.firstBand, t.peakX, t.peakY)
        if (np > 1) {
            smoothPath(t.peakPath, t.peakX, t.peakY, np, hDp)
            t.hasPeak = true
            t.start = min(t.start, t.peakX[0])
        }
    }
}

/** A channel's curve colour at an analyser tap (macOS `eqCurveColor` via `rtaEqChannel`). */
internal fun rtaColour(bridge: DspiBridge, tap: Int, channel: Int): Color = channelColour(bridge, bridge.rta.appChannel(tap, channel))

// Port of DSPi-Console-b5:DSPi Console/SpectrumAnalyser.swift and the
// drawing-independent half of SpectrumAnalyserView.swift @ b5fdeac (RtaScale,
// RtaBarSmoother, rtaFallTau, RtaChannelSelection, refresh description), with
// the curve geometry from DSPi-Console-Linux:qt/src/RtaViews.cpp @ 4683a2c
// (SpectrumCurveItem::columnLevels), which is how a client draws this core's
// snapshot. The device protocol, ownership and bin averaging/smoothing of
// SpectrumAnalyser.swift live in the core (rta.rs) and the bridge's
// RtaController. Everything here works in preallocated arrays: it runs every
// display frame.
package se.bofr.dspi.feature.analysis

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import se.bofr.dspi.core.DspiConstants
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.core.RtaChannelFrame
import se.bofr.dspi.core.RtaSnapshot

/**
 * The macOS analyser preferences this window reads (DSPi_ConsoleApp.swift
 * AppSettings, same defaults), projected from the shared store by
 * [rtaPreferences].
 */
data class RtaPreferences(
    val floorDb: Double = -90.0,
    val ceilingDb: Double = 6.0,
    val showPeakHold: Boolean = true,
    /** `rtaSmoothing`: 0.35 when `rtaSmoothingOn`, 0 when off. */
    val smoothing: Double = 0.35,
    val barColumns: Int = 2,
    val graphMinFreq: Double = 15.0,
    val graphMaxFreq: Double = 20000.0,
    val fftOrder: Int = 10,
    val avgMs: Int = 300,
    val peakDecayDbs: Int = 12,
    /** `rtaDashboardShowGraph` / `rtaDashboardShowBars`: the window starts drawing what the dashboard draws. */
    val dashboardShowGraph: Boolean = true,
    val dashboardShowBars: Boolean = false
)

/** Swift `startFromPage()`: both switches -> Both, bars only -> Bars, else Curves. */
fun rtaInitialMode(showGraph: Boolean, showBars: Boolean): RtaDisplayMode = when {
    showGraph && showBars -> RtaDisplayMode.BOTH
    showBars -> RtaDisplayMode.BARS
    else -> RtaDisplayMode.CURVES
}

/** Swift `SpectrumAnalyserView.DisplayMode`. */
enum class RtaDisplayMode(val label: String) {
    CURVES("Curves"),
    BARS("Bars"),
    BOTH("Both")
}

/** The vertical scale every analyser view shares (Swift `RtaScale`). */
data class RtaScale(val floorDb: Double, val ceilingDb: Double) {
    fun norm(db: Double): Double {
        if (ceilingDb <= floorDb) return 0.0
        return ((db - floorDb) / (ceilingDb - floorDb)).coerceIn(0.0, 1.0)
    }

    /** dB grid lines, every 12 dB below the ceiling (RtaBandGrid.drawGrid). */
    fun gridLines(): IntArray {
        val out = ArrayList<Int>()
        var db = floor(ceilingDb / 12) * 12
        while (db > floorDb) {
            out += db.toInt()
            db -= 12
        }
        return out.toIntArray()
    }
}

/** Frequencies worth labelling on a third-octave axis: the 1-2-5 sequence. */
val rtaLabelledCentres: DoubleArray = doubleArrayOf(10.0, 20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10000.0, 20000.0)

fun rtaShortHz(hz: Double): String = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else "${hz.roundToInt()}"

/** Interpolate at 60 fps independently of the device frame/poll cadence. */
const val RTA_FRAME_INTERVAL_S: Double = 1.0 / 60.0

/**
 * Swift `rtaFallTau(refreshInterval:amount:)`: the smoothing preference as a
 * fall time constant for a rotation interval; 0 means draw the device's
 * numbers directly.
 */
fun rtaFallTau(refreshInterval: Double, amount: Double): Double {
    if (amount <= 0) return 0.0
    return min(0.40, max(0.035, refreshInterval * amount))
}

/**
 * Swift `RtaBarSmoother`: one pole per band, stepped by real elapsed time,
 * falling slower than it rises. [identity] changing (channel, band count)
 * snaps to the new picture. Allocation-free after the first frame of a size.
 * Settles (and reports so) once every value is within 0.05 dB of its target
 * (Linux RtaSmoother), so the display loop can stop.
 */
class RtaBarSmoother(capacity: Int = 2 * DspiConstants.RTA_MAX_BANDS) {
    private var values = FloatArray(capacity)
    private var target = FloatArray(capacity)
    var count: Int = 0
        private set
    private var identity = Long.MIN_VALUE
    private var lastNanos = Long.MIN_VALUE
    private var settled = true

    /** Current (smoothed) value [i]. */
    operator fun get(i: Int): Float = values[i]

    val isSettled: Boolean get() = settled

    /**
     * New target values `src[0 until n]`. A new [identity] or size snaps;
     * otherwise the values glide from where they are.
     */
    fun setTarget(src: FloatArray, n: Int, identity: Long) {
        if (n > values.size) {
            values = FloatArray(n)
            target = FloatArray(n)
        }
        if (identity != this.identity || n != count) {
            this.identity = identity
            count = n
            System.arraycopy(src, 0, values, 0, n)
            System.arraycopy(src, 0, target, 0, n)
            settled = true
            lastNanos = Long.MIN_VALUE
            return
        }
        var same = true
        for (i in 0 until n) {
            target[i] = src[i]
            if (values[i] != src[i]) same = false
        }
        settled = same
    }

    /** Jump to the target (smoothing off). */
    fun snap() {
        System.arraycopy(target, 0, values, 0, count)
        settled = true
    }

    /**
     * Step toward the target at [nowNanos]. Values from [peakFrom] on are
     * peak-hold caps: they jump up and only their fall is interpolated. A
     * zero tau moves instantly in that direction. Returns true while moving.
     */
    fun step(nowNanos: Long, riseTau: Double, fallTau: Double, peakFrom: Int = Int.MAX_VALUE): Boolean {
        if (settled) {
            lastNanos = nowNanos
            return false
        }
        val dt = if (lastNanos == Long.MIN_VALUE) RTA_FRAME_INTERVAL_S else min(0.1, (nowNanos - lastNanos) / 1e9)
        lastNanos = nowNanos
        if (dt <= 0) return true
        val riseK = if (riseTau > 0) (1 - exp(-dt / riseTau)).toFloat() else 1f
        val fallK = if (fallTau > 0) (1 - exp(-dt / fallTau)).toFloat() else 1f
        var moving = false
        for (i in 0 until count) {
            val t = target[i]
            val v = values[i]
            if (v == t) continue
            val k = if (t > v) (if (i >= peakFrom) 1f else riseK) else fallK
            var nv = v + (t - v) * k
            if (abs(t - nv) < 0.05f) nv = t else moving = true
            values[i] = nv
        }
        settled = !moving
        return moving
    }
}

/** Swift `RtaChannelSelection`: channels at one tap, ascending. */
data class RtaChannelSelection(val tap: Int, val channels: List<Int>) {
    val isEmpty: Boolean get() = channels.isEmpty()
    val mask: Int get() = channels.fold(0) { m, c -> m or (1 shl c) }

    fun contains(tap: Int, channel: Int): Boolean = this.tap == tap && channel in channels

    fun accepts(tap: Int): Boolean = isEmpty || this.tap == tap

    /** One channel checked or unchecked; a channel at the other tap is ignored. */
    fun toggling(tap: Int, channel: Int): RtaChannelSelection {
        if (!accepts(tap)) return this
        val mine = this.tap == tap
        val next = if (channel in channels && mine) channels - channel else (if (mine) channels else emptyList()) + channel
        return of(tap, next)
    }

    /** Only the channels in [live], keeping the tap. */
    fun restricted(live: List<Int>): RtaChannelSelection = of(tap, channels.filter { it in live })

    /** "in:0,2" or "out:1"; "out:" is a deliberate choice of nothing. */
    val storageKey: String get() = (if (tap == DspiConstants.RTA_TAP_INPUT) "in:" else "out:") + channels.joinToString(",")

    companion object {
        fun of(tap: Int, channels: List<Int>) = RtaChannelSelection(tap, channels.distinct().sorted())

        /** Swift `init?(storageKey:)`. */
        fun fromStorageKey(key: String?): RtaChannelSelection? {
            if (key == null) return null
            val colon = key.indexOf(':')
            if (colon < 0) return null
            val tap =
                when (key.substring(0, colon)) {
                    "in" -> DspiConstants.RTA_TAP_INPUT
                    "out" -> DspiConstants.RTA_TAP_OUTPUT
                    else -> return null
                }
            val chans = ArrayList<Int>()
            for (item in key.substring(colon + 1).split(',')) {
                if (item.isEmpty()) continue
                val n = item.toIntOrNull() ?: return null
                if (n !in 0..15) return null
                chans += n
            }
            return of(tap, chans)
        }

        /**
         * Swift `dashboardRtaSelection`: the stored selection limited to live
         * channels; never chosen, or every chosen channel gone, falls back to
         * the first enabled output, then input 1. A stored empty selection stays empty.
         */
        fun resolve(stored: RtaChannelSelection?, available: (Int) -> List<Int>): RtaChannelSelection {
            if (stored != null) {
                if (stored.isEmpty) return stored
                val live = stored.restricted(available(stored.tap))
                if (!live.isEmpty) return live
            }
            val firstOut = available(DspiConstants.RTA_TAP_OUTPUT).firstOrNull()
            if (firstOut != null) return of(DspiConstants.RTA_TAP_OUTPUT, listOf(firstOut))
            return of(DspiConstants.RTA_TAP_INPUT, listOf(0))
        }

        val NONE = RtaChannelSelection(DspiConstants.RTA_TAP_OUTPUT, emptyList())

        /**
         * Moving to the other side: the side being left is remembered, the
         * side being entered comes back as it was left (empty if never used).
         */
        fun switchingSides(
            active: RtaChannelSelection,
            remembered: RtaChannelSelection?,
            tap: Int
        ): Pair<RtaChannelSelection, RtaChannelSelection?> {
            if (tap == active.tap) return active to remembered
            val restored = remembered?.takeIf { it.tap == tap } ?: of(tap, emptyList())
            return restored to active
        }
    }
}

/**
 * The latest frame for [channel] when the snapshot was taken at [tap]
 * (Swift `RtaEngine.frame(channel:tap:)`; Linux `frameFor`).
 */
fun rtaFrame(snapshot: RtaSnapshot?, tap: Int, channel: Int): RtaChannelFrame? {
    if (snapshot == null || snapshot.tap != tap) return null
    // The core packs frames[0 until count] in channel order
    val frames = snapshot.frames
    for (i in 0 until minOf(snapshot.count, frames.size)) {
        if (frames[i].channel == channel) return frames[i]
    }
    return null
}

/** Whether band [b] has a reading at this rate and transform size (core `populated`). */
fun rtaBandPopulated(snapshot: RtaSnapshot?, b: Int): Boolean = snapshot != null && b in 0..63 && (snapshot.populated shr b) and 1L == 1L

/**
 * Swift `channelRefreshInterval`: how long one channel waits between frames.
 * The core reports it; without frames it is the fill time times the channels.
 */
fun rtaChannelRefreshInterval(s: RtaSnapshot?, fftOrder: Int): Double {
    if (s == null) return (1 shl fftOrder) / 48000.0
    if (s.refreshIntervalS > 0f) return s.refreshIntervalS.toDouble()
    val n = max(s.liveCount, 1)
    if (s.framesPerS > 0) return n.toDouble() / s.framesPerS
    val rate = if (s.sampleRate > 0) s.sampleRate.toDouble() else 48000.0
    return (1 shl fftOrder) / rate * n
}

/** Swift `refreshDescription`. */
fun rtaRefreshDescription(s: RtaSnapshot?, fftOrder: Int): String {
    if (s == null || !s.running || s.liveCount <= 0) return "idle"
    val ms = (rtaChannelRefreshInterval(s, fftOrder) * 1000).roundToInt()
    val channels = if (s.liveCount == 1) "1 channel" else "${s.liveCount} channels"
    return "$channels, each refreshed every $ms ms"
}

/** Swift `RtaStatus.bassLoadDescription`; 65535 is saturation. */
fun rtaBassLoadDescription(bassBusyUsPerS: Int): String {
    val prefix = if (bassBusyUsPerS >= 0xFFFF) "≥" else ""
    return "bass $prefix" + cFixed(bassBusyUsPerS / 10000.0, 2) + "%"
}

/** `main loop x.x%`: 10,000 microseconds per second is one percent. */
fun rtaMainLoopDescription(busyUsPerS: Int): String = "main loop " + cFixed(busyUsPerS / 10000.0, 1) + "%"

/** Swift `lowestMeasurableCentreHz`: the centre of the lowest band with a reading, when some lower ones have none. */
fun rtaLowestMeasurableCentreHz(s: RtaSnapshot?, caps: RtaCapsInfo?): Double? {
    if (s == null || caps == null) return null
    val first = if (s.firstBand == 0xFF) DspiConstants.RTA_MAX_BANDS else s.firstBand
    if (first <= 0 || first >= caps.centres.size) return null
    return caps.centres[first].toDouble()
}

/** Channels in a selection that are shown in this window (Swift `hidden`). */
fun rtaVisibleChannels(selection: RtaChannelSelection, hidden: Set<Int>): List<Int> = selection.channels.filter { it !in hidden }

/** Swift `bars(...)`: columns used for [count] cells (1...4, never more than the cells). */
fun rtaBarColumns(count: Int, preferred: Int): Int = min(max(count, 1), min(max(preferred, 1), 4))

/** A label placed on an axis: its text and centre x. */
data class RtaAxisLabel(val text: String, val x: Float)

/**
 * RtaBandGrid.drawFrequencyLabels: labels for the bands [shown] (band indices
 * in slot order) at nominal 1-2-5 centres. Decades go first, then the 2s and
 * 5s wherever they clear what is placed and the plot's edges.
 */
fun rtaPlaceBandLabels(centres: List<Int>, shown: IntArray, width: Float, charWidth: Float, gap: Float = 4f): List<RtaAxisLabel> {
    if (shown.isEmpty() || centres.isEmpty()) return emptyList()
    val slot = width / shown.size
    data class Cand(val hz: Double, val x: Float, val text: String)
    val cands = ArrayList<Cand>()
    for ((pos, i) in shown.withIndex()) {
        if (i >= centres.size) continue
        val hz = centres[i].toDouble()
        val nominal = rtaLabelledCentres.firstOrNull { abs(hz - it) < it * 0.03 } ?: continue
        cands += Cand(nominal, (pos + 0.5f) * slot, rtaShortHz(hz))
    }
    val decades = setOf(10.0, 100.0, 1000.0, 10000.0)
    val ordered = cands.filter { it.hz in decades } + cands.filter { it.hz !in decades }
    val placed = ArrayList<ClosedFloatingPointRange<Float>>()
    val out = ArrayList<RtaAxisLabel>()
    for (c in ordered) {
        val half = c.text.length * charWidth / 2
        val lo = c.x - half
        val hi = c.x + half
        if (lo < 0 || hi > width) continue
        if (placed.any { lo < it.endInclusive + gap && hi > it.start - gap }) continue
        placed += lo..hi
        out += RtaAxisLabel(c.text, c.x)
    }
    return out
}

/**
 * The analyser curves (Linux SpectrumCurveItem::columnLevels): a Catmull-Rom
 * through the populated band points per pixel column, with the averaged and
 * smoothed FFT bins taking over above the bass bank (one-octave smoothstep
 * crossfade). Columns are [COLUMN_STEP] px apart; NaN = no data. One instance
 * per drawn curve; arrays are reused until the width changes.
 */
class RtaCurveColumns {
    var columns: FloatArray = FloatArray(0)
        private set
    var count: Int = 0
        private set
    private var binCols = FloatArray(0)
    private val px = FloatArray(DspiConstants.RTA_MAX_BANDS)
    private val py = FloatArray(DspiConstants.RTA_MAX_BANDS)

    fun xForFreq(f: Double, width: Float, minHz: Double, maxHz: Double): Float {
        val lo = log10(minHz)
        val hi = log10(maxHz)
        return ((log10(max(f, 1.0)) - lo) / (hi - lo) * width).toFloat()
    }

    /**
     * Fill [columns] for a plot [width] px wide over [minHz]..[maxHz].
     * [levels] holds `nb` averages then `nb` peaks; [peaks] picks the second
     * half. Bins are used only for averages (`bins` may be null).
     */
    fun compute(
        levels: RtaBarSmoother,
        nb: Int,
        peaks: Boolean,
        populated: Long,
        caps: RtaCapsInfo,
        width: Float,
        minHz: Double,
        maxHz: Double,
        bins: FloatArray?,
        nBins: Int,
        sampleRate: Int
    ) {
        val cols = ceil(width / COLUMN_STEP).toInt() + 1
        if (columns.size < cols) {
            columns = FloatArray(cols)
            binCols = FloatArray(cols)
        }
        count = cols
        java.util.Arrays.fill(columns, 0, cols, Float.NaN)
        if (nb == 0 || width <= 0f) return

        // Band points; one beyond each edge keeps the curve running off the plot
        var n = 0
        for (b in 0 until min(nb, caps.centres.size)) {
            if ((populated shr b) and 1L == 0L) continue
            px[n] = xForFreq(caps.centres[b].toDouble(), width, minHz, maxHz)
            py[n] = levels[if (peaks) nb + b else b]
            n++
        }
        var first = 0
        while (n - first > 2 && px[first + 1] < 0) first++
        while (n - first > 2 && px[n - 2] > width) n--

        if (n - first >= 2) {
            var seg = first
            for (c in 0 until cols) {
                val x = c * COLUMN_STEP
                if (x < px[first] || x > px[n - 1]) continue
                while (seg < n - 2 && x > px[seg + 1]) seg++
                val x1 = px[seg]
                val y1 = py[seg]
                val x2 = px[seg + 1]
                val y2 = py[seg + 1]
                val y0 = py[max(first, seg - 1)]
                val y3 = py[min(n - 1, seg + 2)]
                val t = (x - x1) / max(1e-6f, x2 - x1)
                val t2 = t * t
                val t3 = t2 * t
                columns[c] = 0.5f * (
                    (2 * y1) + (-y0 + y2) * t + (2 * y0 - 5 * y1 + 4 * y2 - y3) * t2 +
                        (-y0 + 3 * y1 - 3 * y2 + y3) * t3
                    )
            }
        }

        if (peaks || bins == null || nBins < 2 || sampleRate <= 0 || caps.bassBands < 1) return

        // Bins: the loudest in each column, gaps filled across
        java.util.Arrays.fill(binCols, 0, cols, Float.NaN)
        val df = sampleRate / (2.0 * nBins)
        for (k in 1 until nBins) {
            val x = xForFreq(k * df, width, minHz, maxHz)
            if (x < -COLUMN_STEP || x > width + COLUMN_STEP) continue
            val c = (x / COLUMN_STEP).roundToInt().coerceIn(0, cols - 1)
            if (binCols[c].isNaN() || bins[k] > binCols[c]) binCols[c] = bins[k]
        }
        var last = -1
        for (c in 0 until cols) {
            if (binCols[c].isNaN()) continue
            if (last >= 0 && c - last > 1) {
                for (j in last + 1 until c) binCols[j] = binCols[last] + (binCols[c] - binCols[last]) * (j - last) / (c - last).toFloat()
            }
            last = c
        }

        // Crossfade from the top of the bass bank to two bands above it
        val bb = caps.bassBands
        val fLo = caps.centres[max(0, bb - 1)].toDouble()
        val fHi = caps.centres[min(caps.centres.size - 1, bb + 2)].toDouble()
        if (fLo <= 0 || fHi <= fLo) return
        val lLo = log10(fLo)
        val lHi = log10(fHi)
        val lo = log10(minHz)
        val hi = log10(maxHz)
        for (c in 0 until cols) {
            if (binCols[c].isNaN()) continue
            val f = 10.0.pow(lo + (c * COLUMN_STEP / width) * (hi - lo))
            var w = ((log10(f) - lLo) / (lHi - lLo)).coerceIn(0.0, 1.0)
            w = w * w * (3 - 2 * w)
            columns[c] =
                if (columns[c].isNaN()) {
                    if (w > 0.999) binCols[c] else Float.NaN
                } else {
                    (columns[c] * (1 - w) + binCols[c] * w).toFloat()
                }
        }
    }

    companion object {
        const val COLUMN_STEP: Float = 2f
    }
}

/** Identity of a drawn series: tap, channel and size (Linux `identityOf`). */
fun rtaSeriesIdentity(tap: Int, channel: Int, count: Int, extra: Int = 0): Long {
    val high = ((tap.toLong() and 0xFF) shl 48) or ((channel.toLong() and 0xFF) shl 40)
    return high or ((count.toLong() and 0xFFFF) shl 24) or (extra.toLong() and 0xFFFFFF)
}

/**
 * Copy a frame's levels into [dst] as `n` averages then `n` peaks (Linux
 * `levelsOf`); returns the count written (0 for no frame).
 */
fun rtaLevelsOf(frame: RtaChannelFrame?, dst: FloatArray): Int {
    if (frame == null) return 0
    val n = min(frame.nBands, DspiConstants.RTA_MAX_BANDS)
    for (b in 0 until n) {
        dst[b] = frame.avgDb.getOrElse(b) { DspiConstants.RTA_FLOOR_DB }
        dst[n + b] = frame.peakDb.getOrElse(b) { DspiConstants.RTA_FLOOR_DB }
    }
    return 2 * n
}

/**
 * What the analyser window draws between device frames (the per-view state
 * of Swift RtaBandsView/RtaSmoothingState and Linux RtaViewBase): one
 * smoother per channel slot holding averages then peaks, and the bins of a
 * single-channel selection. [take] adopts a snapshot, [step] glides toward
 * it. Slots are allocated once per channel position; nothing is allocated
 * per display frame.
 */
class RtaRenderState {
    var tap: Int = DspiConstants.RTA_TAP_OUTPUT
        private set
    var channels: IntArray = IntArray(0)
        private set
    private var smoothers = Array(DspiConstants.RTA_MAX_CHANNELS) { RtaBarSmoother() }
    val bins = RtaBarSmoother(DspiConstants.RTA_MAX_BINS)
    var binsRate: Int = 0
        private set
    var populated: Long = 0
        private set
    var caps: RtaCapsInfo? = null
        private set
    private val scratch = FloatArray(2 * DspiConstants.RTA_MAX_BANDS)
    private val binScratch = FloatArray(DspiConstants.RTA_MAX_BINS)
    private var fallTau = 0.0

    /** Smoother for channel position [i] (averages `0 until n`, peaks `n until 2n`). */
    fun levels(i: Int): RtaBarSmoother = smoothers[i]

    /** Bands in channel position [i]'s frame (0 = no frame). */
    fun bandCount(i: Int): Int = smoothers[i].count / 2

    /**
     * Adopt [snapshot] for [channels] at [tap]. [wantsBins]: the curves are
     * drawn and one channel is shown. [fallTau] 0 snaps (smoothing off).
     */
    fun take(snapshot: RtaSnapshot?, caps: RtaCapsInfo?, tap: Int, channels: IntArray, wantsBins: Boolean, fallTau: Double) {
        this.tap = tap
        this.channels = channels
        this.caps = caps
        this.fallTau = fallTau
        val atTap = snapshot != null && snapshot.tap == tap
        populated = if (atTap) snapshot!!.populated else 0
        for ((i, ch) in channels.withIndex()) {
            if (i >= smoothers.size) break
            val f = if (atTap) rtaFrame(snapshot, tap, ch) else null
            val n = rtaLevelsOf(f, scratch)
            smoothers[i].setTarget(scratch, n, rtaSeriesIdentity(tap, ch, n / 2))
            if (fallTau <= 0) smoothers[i].snap()
        }
        var nb = 0
        binsRate = 0
        if (atTap && wantsBins && channels.size == 1 && snapshot!!.binsCount > 0 && snapshot.binsChannel == channels[0]) {
            nb = min(snapshot.binsCount, DspiConstants.RTA_MAX_BINS)
            val src = snapshot.binsDb
            for (k in 0 until nb) binScratch[k] = src[k]
            binsRate = snapshot.binsSampleRate.toInt()
        }
        bins.setTarget(binScratch, nb, rtaSeriesIdentity(tap, channels.firstOrNull() ?: -1, nb, binsRate / 100))
        if (fallTau <= 0) bins.snap()
    }

    /** One display frame at [nowNanos]; true while anything is still moving. */
    fun step(nowNanos: Long): Boolean {
        if (fallTau <= 0) return false
        var moving = false
        for (i in channels.indices) {
            if (i >= smoothers.size) break
            val s = smoothers[i]
            // Peaks jump up; only their fall is interpolated
            moving = s.step(nowNanos, fallTau * 0.4, fallTau, peakFrom = s.count / 2) or moving
        }
        moving = bins.step(nowNanos, fallTau * 0.4, fallTau) or moving
        return moving
    }

    /** Band indices in slot order for a bars cell: the populated bands of an `nb`-band frame. */
    fun shownBands(nb: Int, out: IntArray): Int {
        var n = 0
        for (b in 0 until min(nb, out.size)) if ((populated shr b) and 1L == 1L) out[n++] = b
        return n
    }
}

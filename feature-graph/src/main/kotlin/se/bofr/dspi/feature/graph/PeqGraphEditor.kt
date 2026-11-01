// Port of DSPi-Console-Windows:DSPiConsole.Core/GraphEditing/PeqGraphEditor.cs
// @ v1.1.6-beta4, itself a port of DSPi-Console-b5:DSPi Console/PeqGraphEditor.swift
// @ b5fdeac: the interaction state machine, kept apart from drawing so it runs
// (and is tested) on the JVM. Member names follow the C# ones in camelCase.
//
// Android additions (no upstream equivalent, all touch-only; see
// docs/UPSTREAM-DEVIATIONS.md GRAPH): [hitRadius]/[curveHitDistance] are
// per-instance so a finger gets a bigger target than a mouse, and
// [abandonPress] drops a press that turned into a two-finger gesture without
// treating it as a click.
package se.bofr.dspi.feature.graph

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS
import se.bofr.dspi.core.DspiConstants.FILTER_ALLPASS1
import se.bofr.dspi.core.DspiConstants.FILTER_FLAT
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHPASS
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_HIGHSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_LOWPASS
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF
import se.bofr.dspi.core.DspiConstants.FILTER_LOWSHELF1
import se.bofr.dspi.core.DspiConstants.FILTER_PEAKING
import se.bofr.dspi.core.FilterParams

/**
 * On-graph PEQ band editing, modelled on FabFilter Pro-Q 4. The view forwards
 * pointer, wheel and key events, draws [picture], and mirrors the chip, card,
 * labels and cursor this class describes.
 *
 * A drag never touches the model: the device follows at up to 30 writes a
 * second through the host, the band list follows through
 * [PeqGraphEditorHost.showLive], and the model is written once, on release.
 *
 * Pointer (see [PeqMods] for the key mapping; the touch mapping is in
 * docs/PORT-MAP.md "Touch gestures"):
 *   hover a band's fill    highlights it; only its dot takes clicks
 *   hover empty graph      a faint dot where a double-click would create a
 *                          bell, with frequency and level readouts
 *   double-click empty     create it, always a bell
 *   Ctrl-click empty       the Add Band card opens there
 *   click empty            deselect
 *   drag the curve         pull a new bell (shelf near either end) out of it
 *   drag empty graph       marquee selection
 *   click a dot            select; Ctrl toggles, Shift selects a range
 *   drag a dot             frequency and gain (Q for second-order cuts;
 *                          frequency only for notches, all-passes and
 *                          first-order cuts)
 *   Ctrl-drag              Q of the selection
 *   Shift-drag fine; Alt-drag lock to one axis; Ctrl pressed mid-drag scales
 *   a selection's gains in proportion
 *   Alt-click              bypass
 *   double-click a dot     type values
 *   wheel over a band      Q; Ctrl-wheel gain; Shift fine (dot or fill)
 *   right-click            band menu on a dot, graph menu elsewhere
 * Keys: Delete removes the selection, Escape deselects, Ctrl-A selects all,
 * arrows move frequency and gain (Alt-arrows Q), Tab steps through bands.
 */
class PeqGraphEditor(private val host: PeqGraphEditorHost, private val clock: PeqScheduler) {
    object Tuning {
        const val NODE_HIT_RADIUS = 10.0
        const val CURVE_HIT_DISTANCE = 6.0
        const val DRAG_THRESHOLD = 2.0
        const val Q_POINTS_PER_OCTAVE = 60.0
        const val FINE = 0.12
        const val BAND_ANIMATION = 0.22
        const val EMPHASIS_TAU = 0.07
        const val COMMIT_DELAY = 0.45
        const val DEVICE_INTERVAL = 1.0 / 30.0

        /** Wheel events closer together than this belong to one gesture. */
        const val WHEEL_SESSION = 0.5

        /** How long the pointer rests on a band before its list row scrolls into view. */
        const val REVEAL_DWELL = 0.25
        const val HUD_HIDE_DELAY = 0.35

        /** Most band rows the graph shows. */
        const val BAND_ROWS = 12
        val CARD_SIZE = PeqSize(110.0, 78.0)

        /** Android: a finger's dot target (dp). */
        const val TOUCH_HIT_RADIUS = 24.0

        /** Android: a finger's distance to the curve (dp). */
        const val TOUCH_CURVE_HIT_DISTANCE = 14.0
    }

    private enum class GestureKind { IDLE, PRESS, BACKGROUND, DRAG, MARQUEE }

    private enum class Axis { HORIZONTAL, VERTICAL }

    private class DragContext(val grabbed: Int, val start: LinkedHashMap<Int, FilterParams>, var last: PeqPoint, val qMode: Boolean) {
        /** Pointer movement applied so far, after fine scaling and any lock. */
        var offsetX = 0.0
        var offsetY = 0.0

        /** The axis Alt has locked the drag to; null when free. */
        var axis: Axis? = null

        /** Alt is held but no axis chosen yet: movement gathers here. */
        var pending: Pair<Double, Double>? = null
    }

    private class Emphasis {
        var hover = 0f
        var select = 0f
        var hoverTarget = 0f
        var selectTarget = 0f
        val settled get() = hover == hoverTarget && select == selectTarget
    }

    /** Android: dot hit radius, [Tuning.NODE_HIT_RADIUS] for a mouse, larger for touch. */
    var hitRadius = Tuning.NODE_HIT_RADIUS

    /** Android: curve hit distance, [Tuning.CURVE_HIT_DISTANCE] for a mouse, larger for touch. */
    var curveHitDistance = Tuning.CURVE_HIT_DISTANCE

    private var config = PeqEditorConfig()
    private var geometryValue = PeqGraphGeometry(0.0, 0.0, 20.0, 20000.0, 25.0, -25.0)
    private var available: Set<Int> = emptySet()

    // Bands: committed mirrors the model, live holds edits not yet committed,
    // shown is what is drawn (committed, live or mid-animation).
    private var committed = mutableListOf<FilterParams>()
    private var shown = mutableListOf<FilterParams>()
    private val live = LinkedHashMap<Int, FilterParams>()
    private var animationFrom: List<FilterParams>? = null
    private var animationStart = 0.0

    private var selectionSet: Set<Int> = emptySet()
    private var anchor: Int? = null
    private var hoveredBand: Int? = null
    private var listHovered: Int? = null
    private var ghost: FilterParams? = null
    private var ghostShown: FilterParams? = null
    private var ghostAmount = 0f
    private var pointer: PeqPoint? = null

    private var gesture = GestureKind.IDLE
    private var pressBand = 0
    private var pressAt = PeqPoint(0.0, 0.0)
    private var pressMods = PeqMods.NONE
    private var backgroundOnCurve = false
    private var drag: DragContext? = null
    private var marqueeFrom = PeqPoint(0.0, 0.0)
    private var marqueeTo = PeqPoint(0.0, 0.0)
    private var marqueeBase: Set<Int> = emptySet()

    private var emphasis = Array(Tuning.BAND_ROWS) { Emphasis() }
    private var lastFrame = 0.0

    private var hudBandValue: Int? = null
    private var hudVisibleValue = false
    private var hudEditingValue = false
    private var showsShapesValue = false
    private var pageSize: PeqSize? = null
    private var pagedOrigin: PeqPoint? = null
    private var hudAdjustStart: FilterParams? = null
    private var hudWheelFieldValue: PeqHudField? = null
    private var hudWheelTime = 0.0

    private var cardPoint: PeqPoint? = null
    private var cardOriginValue = PeqPoint(0.0, 0.0)

    private var hudHideTimer: PeqTimer? = null
    private var revealTimer: PeqTimer? = null
    private var deviceTimer: PeqTimer? = null
    private var commitTimer: PeqTimer? = null
    private val deviceDirty = mutableSetOf<Int>()

    // Bands sent to the device during the live edit in progress.
    private val deviceSent = mutableSetOf<Int>()
    private var wheelBand: Int? = null
    private var wheelTime = 0.0

    private var axisLabelValue: String? = null
    private var axisLabelXValue = 0.0
    private var gainLabelValue: String? = null
    private var gainLabelYValue = 0.0
    private var cursorValue = PeqCursor.ARROW

    private var pictureValue = PeqPicture()

    /** The state moved since [pictureValue] was built; rebuilt when next asked for. */
    private var pictureStale = true

    private val selectionListener: () -> Unit = { onSharedSelectionChanged() }
    private val hoverListener: () -> Unit = { onSharedHoverChanged() }

    init {
        host.selection.addSelectionListener(selectionListener)
        host.selection.addHoverListener(hoverListener)
    }

    fun dispose() {
        commitLive()
        host.selection.removeSelectionListener(selectionListener)
        host.selection.removeHoverListener(hoverListener)
        listOf(hudHideTimer, revealTimer, deviceTimer, commitTimer).forEach { it?.cancel() }
    }

    // ── Outputs for the view ────────────────────────────────────────────────

    /** Raised whenever anything the view shows may have changed. */
    var onChanged: (() -> Unit)? = null

    /** The system beep: nothing to create, or text that didn't parse. */
    var onBeep: (() -> Unit)? = null

    /** The chip should start typing into a field. */
    var onBeginHudEditRequested: ((PeqHudField) -> Unit)? = null

    /** The chip should stop typing (without applying). */
    var onEndHudEditRequested: (() -> Unit)? = null

    val editing get() = config.channel != null
    val channel get() = config.channel
    val geometry get() = geometryValue
    val picture: PeqPicture
        get() {
            if (pictureStale) {
                pictureValue = buildPicture()
                pictureStale = false
            }
            return pictureValue
        }
    val cursor get() = cursorValue
    val selection: Set<Int> get() = selectionSet
    val hovered get() = hoveredBand
    val axisLabel get() = axisLabelValue
    val axisLabelX get() = axisLabelXValue
    val gainLabel get() = gainLabelValue
    val gainLabelY get() = gainLabelYValue
    val availableTypes: Set<Int> get() = available
    val bypassSupported get() = config.bypassSupported

    /** Whether an animation is running and the view should keep calling [frame]. */
    val animating: Boolean
        get() = animationFrom != null || ghostAmount != (if (ghost != null) 1f else 0f) || emphasis.any { !it.settled }

    // Chip
    val hudBand get() = hudBandValue
    val hudVisible get() = hudVisibleValue && hudBandValue != null
    val hudParams: FilterParams? get() = hudBandValue?.takeIf { isBand(it) }?.let { current(it) }
    val showsShapes get() = showsShapesValue
    var hudFrame = PeqRect(0.0, 0.0, 0.0, 0.0)
        private set
    val hudEditing get() = hudEditingValue

    // Add Band card
    val cardOpen get() = cardPoint != null
    val cardOrigin get() = cardOriginValue

    companion object {
        /** The chip's size for [p], fixed per shape so the view and the editor agree on where it goes. */
        fun hudSizeFor(p: FilterParams): PeqSize {
            val rows = if (PeqShape.of(p.filterType) != null) 1 + (if (FilterTypes.hasGain(p.filterType)) 1 else 0) + 1 else 2
            return PeqSize(110.0, 28.0 + rows * 15 + 5)
        }
    }

    // ── Configuration ───────────────────────────────────────────────────────

    fun apply(next: PeqEditorConfig) {
        if (next.sameAs(config) && geometryValue.width > 0) return
        // A pending wheel or key edit belongs to the channel it was made on.
        if (next.channel != config.channel) commitLive()
        val old = config
        config = next
        available = next.availableTypes
        updateGeometry()
        val bands = next.bands.toMutableList()

        if (old.channel != next.channel || !editing) {
            resetInteraction()
            committed = bands
            shown = bands.toMutableList()
            animationFrom = null
        } else if (!bandsSame(bands, committed)) {
            committed = bands
            if (gesture == GestureKind.IDLE && live.isEmpty()) {
                startBandAnimation()
            } else {
                // Mid-edit, only the bands not being edited follow the model.
                if (shown.size != committed.size) {
                    shown = committed.mapIndexed { i, p -> live[i] ?: p }.toMutableList()
                } else {
                    for (i in shown.indices) if (!live.containsKey(i)) shown[i] = committed[i]
                }
            }
        }
        refreshHud()
        invalidate()
    }

    fun resize(width: Double, height: Double) {
        if (width == geometryValue.width && height == geometryValue.height) return
        geometryValue = geometryValue.copy(width = width, height = height)
        layoutHud()
        invalidate()
    }

    private fun updateGeometry() {
        geometryValue =
            PeqGraphGeometry(geometryValue.width, geometryValue.height, config.minFreq, config.maxFreq, config.dbTop, config.dbBottom)
    }

    private fun resetInteraction() {
        closeCard()
        gesture = GestureKind.IDLE
        drag = null
        selectionSet = emptySet()
        anchor = null
        hoveredBand = null
        listHovered = null
        ghost = null
        ghostShown = null
        ghostAmount = 0f
        hideAxisLabels()
        hideHud()
        emphasis = Array(Tuning.BAND_ROWS) { Emphasis() }
    }

    private fun onSharedSelectionChanged() {
        val set = host.selection.selected
        if (set == selectionSet) return
        selectionSet = set.toSet()
        anchor = if (set.size == 1) set.first() else anchor
        pinHudToSelection()
        invalidate()
    }

    private fun onSharedHoverChanged() {
        if (listHovered == host.selection.listHovered) return
        listHovered = host.selection.listHovered
        invalidate()
    }

    // ── Band queries ────────────────────────────────────────────────────────

    private val bandCount get() = min(shown.size, Tuning.BAND_ROWS)

    private fun isBand(i: Int) = i >= 0 && i < bandCount && shown[i].isGraphBand()

    private val graphBands: List<Int> get() = (0 until bandCount).filter { isBand(it) }

    private fun role(p: FilterParams) = PeqNodeRole.of(p)

    /**
     * The graph's axes as a band's own response reads on them: the combined
     * curve includes the channel's level offset, so every band's dot and fill
     * sits [PeqEditorConfig.offsetDb] lower or higher too.
     */
    private val bandGeometry: PeqGraphGeometry
        get() = geometryValue.copy(dbTop = geometryValue.dbTop - config.offsetDb, dbBottom = geometryValue.dbBottom - config.offsetDb)

    private fun nodePoint(p: FilterParams): PeqPoint {
        val y = bandGeometry.y(role(p).dbFor(p))
        return PeqPoint(geometryValue.x(p.freq.toDouble()), min(max(y, 6.0), max(geometryValue.height - 6, 6.0)))
    }

    private fun bandDb(p: FilterParams, freq: Double): Double {
        val probe = p.copy(bypass = false)
        return PeqDspMath.responseAt(freq.toFloat(), listOf(probe)).toDouble()
    }

    private fun combinedDb(freq: Double): Double {
        val db = config.offsetDb.toDouble()
        if (config.flat) return db
        val audible = graphBands.map { shown[it] }.filter { !it.bypass } + config.statics
        return db + PeqDspMath.responseAt(freq.toFloat(), audible)
    }

    /** The dot under [point], preferring selected and hovered dots, which are drawn on top. */
    private fun nodeAt(point: PeqPoint): Int? {
        var best: Int? = null
        var bestScore = Double.MAX_VALUE
        for (b in graphBands) {
            val d = nodePoint(shown[b]).distanceTo(point)
            if (d > hitRadius) continue
            val score = d - (if (b in selectionSet) 3 else 0) - (if (hoveredBand == b) 2 else 0)
            if (best == null || score < bestScore) {
                best = b
                bestScore = score
            }
        }
        return best
    }

    /**
     * The band whose filled area (between its curve and 0 dB) contains [point].
     * A fill highlights its band and takes the wheel, but clicks go through it.
     */
    private fun lobeAt(point: PeqPoint): Int? {
        val g = bandGeometry
        val freq = g.freq(point.x)
        val zero = g.y(0.0)
        var best: Int? = null
        var bestDistance = Double.MAX_VALUE
        for (b in graphBands) {
            if (role(shown[b]).kind == PeqNodeRole.Kind.LOCKED) continue
            val y = g.y(bandDb(shown[b], freq))
            if (abs(y - zero) < 3 || point.y < min(y, zero) || point.y > max(y, zero)) continue
            val d = abs(point.y - y)
            if (best == null || d < bestDistance) {
                best = b
                bestDistance = d
            }
        }
        return best
    }

    /** The band the wheel adjusts at a point: its dot or its fill. */
    private fun bandAt(point: PeqPoint) = nodeAt(point) ?: lobeAt(point)

    private fun isNearCurve(point: PeqPoint) = abs(geometryValue.y(combinedDb(geometryValue.freq(point.x))) - point.y) <= curveHitDistance

    private val freeSlot: Int?
        get() {
            for (i in 0 until min(committed.size, Tuning.BAND_ROWS)) {
                if (committed[i].filterType == FILTER_FLAT && !live.containsKey(i)) return i
            }
            return null
        }

    private val freqRange: Pair<Double, Double>
        get() {
            val lo = max(PeqLimits.FREQ_MIN, config.minFreq)
            val hi = min(PeqLimits.FREQ_MAX, config.maxFreq)
            return lo to max(hi, lo)
        }

    private fun clampToRange(f: Double): Double {
        val (lo, hi) = freqRange
        return PeqLimits.clamp(f, lo, hi)
    }

    /**
     * A band's frequency moved from [start], clamped to the axis, except that a
     * band already off the axis is never pulled onto it: it may move toward the
     * axis but no further out.
     */
    private fun clampMoved(start: Double, moved: Double): Double {
        var (lo, hi) = freqRange
        lo = max(PeqLimits.FREQ_MIN, min(lo, start))
        hi = min(PeqLimits.FREQ_MAX, max(hi, start))
        return PeqLimits.clamp(moved, lo, max(hi, lo))
    }

    private fun frequencyOrder(bands: Iterable<Int>): List<Int> = bands.sortedWith(compareBy<Int>({ shown[it].freq }, { it }))

    private fun current(b: Int): FilterParams = live[b] ?: shown[b]

    // ── Drawing ─────────────────────────────────────────────────────────────

    private fun invalidate() {
        updateEmphasisTargets()
        pictureStale = true
        onChanged?.invoke()
    }

    private fun updateEmphasisTargets() {
        val dragged = if (gesture == GestureKind.DRAG) drag?.start?.keys ?: emptySet() else emptySet()
        for (i in emphasis.indices) {
            val isShown = isBand(i)
            emphasis[i].selectTarget = if (isShown && i in selectionSet) 1f else 0f
            emphasis[i].hoverTarget =
                if (isShown && (hoveredBand == i || listHovered == i || i in dragged || (hudBandValue == i && hudVisibleValue))) 1f else 0f
        }
    }

    /**
     * Advances animations to [now] (seconds; [Double.POSITIVE_INFINITY]
     * finishes everything) and rebuilds the picture. True while running.
     */
    fun frame(now: Double): Boolean {
        val finite = now.isFinite()
        val dt = if (finite) (if (lastFrame == 0.0) 1.0 / 60 else min(now - lastFrame, 0.1)) else 1.0
        lastFrame = if (finite) now else 0.0
        var running = false

        val from = animationFrom
        if (from != null) {
            val t = if (finite) (now - animationStart) / Tuning.BAND_ANIMATION else 1.0
            val target = committed.mapIndexed { i, p -> live[i] ?: p }
            if (t >= 1 || from.size != target.size) {
                shown = target.toMutableList()
                animationFrom = null
                refreshHud()
            } else {
                val eased = 1 - (1 - t).pow(3)
                shown = from.zip(target) { a, b -> a.interpolated(b, eased) }.toMutableList()
                running = true
                layoutHud()
            }
        }

        updateEmphasisTargets()
        val k = (1 - exp(-dt / Tuning.EMPHASIS_TAU)).toFloat()

        fun approach(v: Float, target: Float): Float {
            val n = v + (target - v) * k
            return if (abs(target - n) < 0.004f) target else n
        }
        for (e in emphasis) {
            e.hover = approach(e.hover, e.hoverTarget)
            e.select = approach(e.select, e.selectTarget)
            if (!e.settled) running = true
        }
        val ghostTarget = if (ghost != null) 1f else 0f
        ghostAmount = approach(ghostAmount, ghostTarget)
        if (ghost != null) ghostShown = ghost
        if (ghostAmount == 0f) ghostShown = null
        if (ghostAmount != ghostTarget) running = true

        pictureStale = true
        if (!running) lastFrame = 0.0
        return running
    }

    private fun buildPicture(): PeqPicture {
        val dimAll = if (config.flat) 0.5f else 1f
        val styles = mutableListOf<PeqBandStyle>()
        val nodes = mutableListOf<Pair<Float, PeqNode>>()
        for (b in graphBands) {
            val band = shown[b]
            val e = emphasis[b]
            val lift = max(e.hover, e.select)
            var color = PeqBandPalette.color(b)
            if (band.bypass) {
                val grey = (color.r + color.g + color.b) / 3
                val keep = 0.35f
                color = PeqRgb(grey + (color.r - grey) * keep, grey + (color.g - grey) * keep, grey + (color.b - grey) * keep)
            }
            // Every audible band keeps a faint fill and shows its outline while
            // hovered or selected; a bypassed one stays as a grey ghost of both.
            val (lo, hi) = lobeReach(band)
            styles +=
                PeqBandStyle(
                    b,
                    color,
                    (if (band.bypass) 0.35f + 0.25f * lift else 0.9f * lift) * dimAll,
                    (if (band.bypass) 0.06f + 0.06f * lift else 0.22f + 0.2f * lift) * dimAll,
                    lo,
                    hi
                )
            val c = nodePoint(band)
            // Flat discs; hovering enlarges them, selecting also adds a centre dot.
            nodes +=
                (lift + e.select) to
                PeqNode(c.x, c.y, 5 + 1.5 * lift + 0.5 * e.select, color, (if (band.bypass) 0.55f else 1f) * dimAll, e.select)
        }
        val g = ghostShown
        if (g != null && ghostAmount > 0) {
            val c = nodePoint(g)
            nodes += -1f to PeqNode(c.x, c.y, 5.0, PeqRgb.WHITE, 0.3f * ghostAmount, 0f)
        }

        val marquee =
            if (gesture == GestureKind.MARQUEE) {
                PeqRect(
                    min(marqueeFrom.x, marqueeTo.x),
                    min(marqueeFrom.y, marqueeTo.y),
                    abs(marqueeTo.x - marqueeFrom.x),
                    abs(
                        marqueeTo.y - marqueeFrom.y
                    )
                )
            } else {
                null
            }

        return PeqPicture(
            geometry = geometryValue,
            bands = (0 until Tuning.BAND_ROWS).map { if (isBand(it)) shown[it] else null },
            statics = config.statics,
            offsetDb = config.offsetDb,
            flat = config.flat,
            curveColor = config.curveColor,
            lineWidth = config.lineWidth,
            glow = config.glow,
            bandStyles = styles,
            // Emphasised dots last, so they draw on top.
            nodes = nodes.sortedBy { it.first }.map { it.second },
            marquee = marquee
        )
    }

    /** The dB range a band's lobe spans, to bound its fill. */
    private fun lobeReach(p: FilterParams): Pair<Double, Double> {
        // In the band's own dB, so off the plot whatever the level offset.
        val bottom = config.dbBottom - config.offsetDb - 1
        return when (p.filterType) {
            FILTER_PEAKING, FILTER_LOWSHELF, FILTER_HIGHSHELF, FILTER_LOWSHELF1, FILTER_HIGHSHELF1 ->
                min(p.gain.toDouble(), 0.0) to max(p.gain.toDouble(), 0.0)
            FILTER_LOWPASS, FILTER_HIGHPASS -> bottom to max(20 * kotlin.math.log10(max(p.q.toDouble(), 0.01)), 0.0)
            FILTER_ALLPASS, FILTER_ALLPASS1 -> 0.0 to 0.0
            else -> bottom to config.dbTop - config.offsetDb + 1
        }
    }

    private fun startBandAnimation() {
        val target = committed
        if (shown.size != target.size) {
            shown = target.toMutableList()
            return
        }
        val significant =
            shown.zip(target).any { (a, b) ->
                a.filterType != b.filterType ||
                    a.bypass != b.bypass ||
                    abs(a.gain - b.gain) > 0.02 ||
                    abs(ln(max(a.freq.toDouble(), 1.0) / max(b.freq.toDouble(), 1.0))) > 0.002 ||
                    abs(ln(max(a.q.toDouble(), 0.01) / max(b.q.toDouble(), 0.01))) > 0.002
            }
        if (significant) {
            animationFrom = shown.toList()
            animationStart = clock.now
        } else {
            shown = target.toMutableList()
        }
    }

    // ── Editing pipeline ────────────────────────────────────────────────────

    /** A change in progress: drawn, shown in the chip and the list, sent to the device at most 30 times a second. */
    private fun setLive(changes: Map<Int, FilterParams>) {
        if (changes.isEmpty()) return
        if (animationFrom != null) {
            animationFrom = null
            shown = committed.mapIndexed { i, p -> live[i] ?: p }.toMutableList()
        }
        for ((b, p) in changes) {
            if (b >= shown.size) continue
            live[b] = p
            shown[b] = p
            config.channel?.let { host.showLive(it, b, p) }
        }
        deviceDirty.addAll(changes.keys)
        if (deviceTimer == null) sendToDevice()
        refreshHud()
        invalidate()
    }

    private fun sendToDevice() {
        val ch = config.channel ?: return
        if (deviceDirty.isEmpty()) return
        val changes = deviceDirty.sorted().filter { live.containsKey(it) }.map { it to live.getValue(it) }
        deviceDirty.clear()
        for ((b, _) in changes) deviceSent.add(b)
        host.sendGraphBandsToDevice(ch, changes)
        deviceTimer =
            clock.schedule(Tuning.DEVICE_INTERVAL) {
                deviceTimer = null
                sendToDevice()
            }
    }

    /** Writes live edits to the model, once. */
    private fun commitLive() {
        deviceTimer?.cancel()
        deviceTimer = null
        deviceDirty.clear()
        commitTimer?.cancel()
        commitTimer = null
        val endCh = config.channel
        if (endCh != null && live.isNotEmpty()) host.endLive(endCh)
        // A band that ends where it started is still committed if the device was
        // sent a value on the way: the dropped throttled send may have been the
        // return trip, leaving the device on an intermediate value.
        val changes =
            live.keys.sorted()
                .filter { it < committed.size && (!live.getValue(it).sameAs(committed[it]) || it in deviceSent) }
                .map { it to live.getValue(it) }
        live.clear()
        deviceSent.clear()
        val ch = config.channel
        if (changes.isEmpty() || ch == null) return
        for ((band, p) in changes) committed[band] = p
        host.commitGraphBands(ch, changes)
    }

    /** For wheel and key edits: live now, committed once they pause. */
    private fun setLiveThenCommit(changes: Map<Int, FilterParams>) {
        setLive(changes)
        commitTimer?.cancel()
        commitTimer = clock.schedule(Tuning.COMMIT_DELAY) { commitLive() }
    }

    /** Discrete edits (create, delete, shape, typed values) commit at once. */
    private fun commitNow(changes: Map<Int, FilterParams>) {
        commitLive()
        val ch = config.channel ?: return
        if (changes.isEmpty()) return
        animationFrom = null
        for ((b, p) in changes) {
            if (b >= committed.size) continue
            committed[b] = p
            shown[b] = p
        }
        host.commitGraphBands(ch, changes.keys.sorted().map { it to changes.getValue(it) })
        refreshHud()
        invalidate()
    }

    private fun setSelection(next: Iterable<Int>) {
        val valid = next.filter { isBand(it) }.toSet()
        if (valid == selectionSet) return
        selectionSet = valid
        if (host.selection.selected != valid) host.selection.setSelected(valid)
        pinHudToSelection()
        invalidate()
    }

    private fun setHovered(band: Int?) {
        if (band == hoveredBand) return
        hoveredBand = band
        if (host.selection.graphHovered != band) host.selection.graphHovered = band
        scheduleReveal()
        invalidate()
    }

    /** Once the pointer rests on a band, its list row scrolls into view. */
    private fun scheduleReveal() {
        revealTimer?.cancel()
        revealTimer = null
        val band = hoveredBand ?: return
        revealTimer =
            clock.schedule(Tuning.REVEAL_DWELL) {
                revealTimer = null
                if (hoveredBand != band) return@schedule
                if (gesture != GestureKind.IDLE || graphWheelActive || hudWheelActive) {
                    scheduleReveal()
                    return@schedule
                }
                host.selection.requestReveal(band)
            }
    }

    // ── Band operations ─────────────────────────────────────────────────────

    private fun createBand(p: FilterParams, select: Boolean = true): Int? {
        val slot = freeSlot
        if (slot == null) {
            onBeep?.invoke()
            return null
        }
        commitNow(mapOf(slot to p))
        if (select) {
            setSelection(listOf(slot))
            anchor = slot
        }
        ghost = null
        return slot
    }

    private fun deleteBands(bands: Iterable<Int>) {
        val targets = bands.filter { isBand(it) }.toSet()
        if (targets.isEmpty()) return
        val changes = targets.associateWith { FilterTypes.band(FILTER_FLAT, 1000f, FilterTypes.DEFAULT_Q, 0f) }
        setSelection(selectionSet - targets)
        if (hudBandValue?.let { it in targets } == true) hideHud()
        commitNow(changes)
        pinHudToSelection()
    }

    private fun toggleBypass(bands: Iterable<Int>) {
        if (!config.bypassSupported) return
        val ch = config.channel ?: return
        val targets = bands.filter { isBand(it) }.sorted()
        if (targets.isEmpty()) return
        commitLive()
        val bypass = !shown[targets[0]].bypass
        animationFrom = null
        for (b in targets) {
            committed[b] = committed[b].copy(bypass = bypass)
            shown[b] = shown[b].copy(bypass = bypass)
        }
        host.setGraphBandBypass(ch, targets, bypass)
        refreshHud()
        invalidate()
    }

    /** Changes shape (and order), keeping frequency, and carrying gain and Q over where the new shape uses them. */
    private fun setShape(bands: Iterable<Int>, shape: PeqShape, order: Int) {
        val changes = LinkedHashMap<Int, FilterParams>()
        for (b in bands.filter { isBand(it) }) {
            val type = shape.type(order) ?: shape.type(2) ?: continue
            if (type !in available) continue
            var p = current(b).retyped(type)
            if (!FilterTypes.hasGain(type)) p = p.copy(gain = 0f)
            if (FilterTypes.hasQ(type) && !FilterTypes.hasQ(current(b).filterType)) {
                p = p.copy(q = if (shape == PeqShape.BELL || shape == PeqShape.NOTCH) 1f else 0.707f)
            }
            changes[b] = p
        }
        commitNow(changes)
    }

    /** A band action applies to the whole selection when the band is part of it, and to the band alone otherwise. */
    private fun targetsFor(b: Int): Set<Int> = if (b in selectionSet) selectionSet.toSet() else setOf(b)

    // ── Pointer ─────────────────────────────────────────────────────────────

    fun pointerMoved(p: PeqPoint) {
        if (!editing) return
        pointer = p
        updateHover(p)
    }

    fun pointerExited() {
        pointer = null
        if (gesture != GestureKind.IDLE) return
        setHovered(null)
        ghost = null
        hideAxisLabels()
        scheduleHudHide()
        cursorValue = PeqCursor.ARROW
        invalidate()
    }

    private fun updateHover(p: PeqPoint) {
        if (gesture != GestureKind.IDLE) return
        val bounds = PeqRect(0.0, 0.0, geometryValue.width, geometryValue.height)
        if (!bounds.contains(p)) {
            setHovered(null)
            ghost = null
            hideAxisLabels()
            scheduleHudHide()
            invalidate()
            return
        }
        // While the Add Band card is open, the faint dot marks where its band will go.
        val at = cardPoint
        if (at != null) {
            setHovered(null)
            ghost = PeqCreation.band(at.x, at.y, bandGeometry, available, fromCurve = false)
            hideAxisLabels()
            cursorValue = PeqCursor.ARROW
            invalidate()
            return
        }
        if (hudVisible && hudFrame.inset(-4.0, -4.0).contains(p)) {
            cancelHudHide()
            ghost = null
            hideAxisLabels()
            cursorValue = PeqCursor.ARROW
            invalidate()
            return
        }
        val dot = nodeAt(p)
        val hit = dot ?: lobeAt(p)
        setHovered(hit)
        val pin = selectedChipBand
        if (pin != null) {
            // The chip stays on the selection; hovering another band lights it instead.
            if (!hudVisible || hudBandValue != pin) showHud(pin)
        } else if (dot != null) {
            if (hudBandValue != dot || !hudVisible) showHud(dot) else cancelHudHide()
        } else if (hudVisible && !hudEditingValue) {
            scheduleHudHide()
        }
        if (dot == null) {
            if (freeSlot != null) {
                ghost = PeqCreation.band(p.x, p.y, bandGeometry, available, fromCurve = false)
                if (config.showFrequencyReadout) {
                    showAxisLabel(PeqValueText.frequency(geometryValue.freq(p.x)), p.x)
                } else {
                    axisLabelValue =
                        null
                }
                // The pointer's level on the graph's axis.
                if (config.showLevelReadout) {
                    showGainLabel(PeqValueText.gain(PeqLimits.clampGain(geometryValue.db(p.y))), p.y)
                } else {
                    gainLabelValue =
                        null
                }
            } else {
                ghost = null
                gainLabelValue = null
                showAxisLabel("All ${min(committed.size, Tuning.BAND_ROWS)} bands in use", p.x)
            }
        } else {
            ghost = null
            hideAxisLabels()
        }
        cursorValue = if (dot != null) PeqCursor.OPEN_HAND else PeqCursor.ARROW
        invalidate()
    }

    /** A press with the primary button. [clickCount] is 2 for the second press of a double-click. */
    fun pointerPressed(p: PeqPoint, mods: Int, clickCount: Int) {
        if (!editing) return
        closeShapePage()
        if (hudEditingValue) onEndHudEditRequested?.invoke()

        // The card takes its own clicks; one anywhere else dismisses it.
        if (cardPoint != null) {
            closeCard()
            gesture = GestureKind.IDLE
            updateHover(p)
            return
        }

        if (clickCount == 2) {
            val b = nodeAt(p)
            if (b != null) {
                showHud(b)
                onBeginHudEditRequested?.invoke(PeqHudField.FREQ)
            } else {
                createBand(PeqCreation.band(p.x, p.y, bandGeometry, available, fromCurve = false))
            }
            gesture = GestureKind.IDLE
            invalidate()
            return
        }

        val band = nodeAt(p)
        if (band != null) {
            // Modified presses wait for release (a click) or movement (a drag).
            if ((mods and (PeqMods.CTRL or PeqMods.ALT or PeqMods.SHIFT)) == 0 && band !in selectionSet) {
                setSelection(listOf(band))
                anchor = band
            }
            gesture = GestureKind.PRESS
            pressBand = band
            pressAt = p
            pressMods = mods
            // A modified press on a band outside the selection leaves the chip on the selection.
            showHud(if (selectionSet.isEmpty() || band in selectionSet) band else (selectedChipBand ?: band))
            return
        }

        if ((mods and PeqMods.CTRL) != 0) {
            openCard(p)
            gesture = GestureKind.IDLE
            return
        }
        gesture = GestureKind.BACKGROUND
        pressAt = p
        pressMods = mods
        backgroundOnCurve = freeSlot != null && isNearCurve(p)
    }

    /** Pointer movement with the primary button held. */
    fun pointerDragged(p: PeqPoint, mods: Int) {
        if (!editing) return
        pointer = p
        when (gesture) {
            GestureKind.PRESS -> {
                if (p.distanceTo(pressAt) < Tuning.DRAG_THRESHOLD) return
                val b = pressBand
                val bands = if (b in selectionSet) selectionSet.toMutableSet() else mutableSetOf(b)
                val q = (pressMods and PeqMods.CTRL) != 0
                if (q) bands.add(b)
                if (role(current(b)).kind == PeqNodeRole.Kind.LOCKED) {
                    gesture = GestureKind.IDLE
                    return
                }
                if (q && b !in selectionSet) {
                    setSelection(selectionSet + b)
                } else if (b !in selectionSet) {
                    setSelection(listOf(b))
                    anchor = b
                }
                beginDrag(b, bands, pressAt, q)
                continueDrag(p, mods)
            }
            GestureKind.BACKGROUND -> {
                if (p.distanceTo(pressAt) < Tuning.DRAG_THRESHOLD) return
                ghost = null
                hideAxisLabels()
                val slot = freeSlot
                if (backgroundOnCurve && slot != null) {
                    // Pull a new band out of the curve, starting flat so the curve
                    // does not jump, then follow the pointer.
                    val band = PeqCreation.band(pressAt.x, pressAt.y, bandGeometry, available, fromCurve = true).copy(gain = 0f)
                    live[slot] = band
                    if (slot < shown.size) shown[slot] = band
                    setSelection(listOf(slot))
                    anchor = slot
                    beginDrag(slot, mutableSetOf(slot), pressAt, qMode = false)
                    continueDrag(p, mods)
                } else {
                    // Ctrl opens the shape card, so only Shift reaches here to add.
                    marqueeBase = if ((pressMods and PeqMods.SHIFT) != 0) selectionSet.toSet() else emptySet()
                    marqueeFrom = pressAt
                    marqueeTo = p
                    gesture = GestureKind.MARQUEE
                    updateMarquee()
                }
            }
            GestureKind.DRAG -> continueDrag(p, mods)
            GestureKind.MARQUEE -> {
                marqueeTo = p
                updateMarquee()
            }
            GestureKind.IDLE -> {}
        }
    }

    fun pointerReleased(p: PeqPoint) {
        if (!editing) return
        when (gesture) {
            GestureKind.PRESS -> {
                val b = pressBand
                // Released without moving: a click.
                if ((pressMods and PeqMods.ALT) != 0) {
                    toggleBypass(targetsFor(b))
                } else if ((pressMods and PeqMods.CTRL) != 0) {
                    val next = selectionSet.toMutableSet()
                    if (!next.remove(b)) next.add(b)
                    setSelection(next)
                    anchor = b
                } else if ((pressMods and PeqMods.SHIFT) != 0) {
                    val order = frequencyOrder(graphBands)
                    val i = anchor?.let { order.indexOf(it) } ?: -1
                    val j = order.indexOf(b)
                    if (i >= 0 && j >= 0) {
                        setSelection(order.subList(min(i, j), max(i, j) + 1))
                    } else {
                        setSelection(listOf(b))
                        anchor = b
                    }
                } else {
                    setSelection(listOf(b))
                    anchor = b
                }
            }
            GestureKind.BACKGROUND -> {
                // A single click on empty graph only deselects; creating a band
                // takes a double-click (or Ctrl-click).
                setSelection(emptyList())
                // An explicit deselect dismisses the chip at once.
                hideHud()
            }
            GestureKind.DRAG -> {
                gesture = GestureKind.IDLE
                drag = null
                commitLive()
                cursorValue = PeqCursor.OPEN_HAND
            }
            else -> {}
        }
        gesture = GestureKind.IDLE
        drag = null
        invalidate()
        updateHover(p)
    }

    /** The pointer was taken away mid-gesture: finish as a release where it last was. */
    fun pointerCanceled() {
        if (gesture == GestureKind.IDLE) return
        pointerReleased(pointer ?: pressAt)
    }

    /**
     * Android: a press that has not moved yet became a two-finger gesture. A
     * press or background press is dropped without counting as a click; a drag
     * or marquee in progress ends as a release.
     */
    fun abandonPress() {
        when (gesture) {
            GestureKind.PRESS, GestureKind.BACKGROUND -> {
                gesture = GestureKind.IDLE
                invalidate()
            }
            GestureKind.IDLE -> {}
            else -> pointerCanceled()
        }
    }

    private fun beginDrag(grabbed: Int, bands: Set<Int>, at: PeqPoint, qMode: Boolean) {
        commitTimer?.cancel()
        commitTimer = null
        val start = LinkedHashMap<Int, FilterParams>()
        for (b in bands) if (isBand(b) || live.containsKey(b)) start[b] = current(b)
        start[grabbed] = current(grabbed)
        drag = DragContext(grabbed, start, at, qMode)
        gesture = GestureKind.DRAG
        ghost = null
        hideAxisLabels()
        showHud(grabbed)
        cursorValue = if (qMode) PeqCursor.UP_DOWN else PeqCursor.CLOSED_HAND
    }

    /**
     * Alt locks the drag to one axis whenever it is held, and never makes the
     * band jump: while locked, movement on the other axis is not applied.
     * Engaging mid-drag takes the axis the drag has mostly moved along; with
     * too little movement to tell, the next few points decide.
     */
    private fun continueDrag(p: PeqPoint, mods: Int) {
        val ctx = drag ?: return
        val scale = if ((mods and PeqMods.SHIFT) != 0) Tuning.FINE else 1.0
        val mx = (p.x - ctx.last.x) * scale
        val my = (p.y - ctx.last.y) * scale
        ctx.last = p

        fun dominant(x: Double, y: Double) = if (abs(x) >= abs(y)) Axis.HORIZONTAL else Axis.VERTICAL

        if ((mods and PeqMods.ALT) == 0) {
            ctx.axis = null
            ctx.pending = null
            ctx.offsetX += mx
            ctx.offsetY += my
        } else {
            if (ctx.axis == null && ctx.pending == null) {
                if (sqrt(ctx.offsetX * ctx.offsetX + ctx.offsetY * ctx.offsetY) >= 4) {
                    ctx.axis = dominant(ctx.offsetX, ctx.offsetY)
                } else {
                    ctx.pending = 0.0 to 0.0
                }
            }
            val pend = ctx.pending
            if (pend != null) {
                val px = pend.first + mx
                val py = pend.second + my
                if (sqrt(px * px + py * py) >= 4) {
                    val axis = dominant(px, py)
                    ctx.axis = axis
                    ctx.pending = null
                    if (axis == Axis.HORIZONTAL) ctx.offsetX += px else ctx.offsetY += py
                } else {
                    ctx.pending = px to py
                }
            } else if (ctx.axis == Axis.HORIZONTAL) {
                ctx.offsetX += mx
            } else {
                ctx.offsetY += my
            }
        }
        setLive(dragResult(ctx, ctx.offsetX, ctx.offsetY, scaleGains = (mods and PeqMods.CTRL) != 0))
    }

    /** Modifiers changed without the pointer moving. Mid-drag, Ctrl switches offset/scaled gains at once. */
    fun modifiersChanged(mods: Int) {
        val ctx = drag ?: return
        if (gesture != GestureKind.DRAG || ctx.qMode) return
        setLive(dragResult(ctx, ctx.offsetX, ctx.offsetY, scaleGains = (mods and PeqMods.CTRL) != 0))
    }

    /**
     * Applies a drag offset to the bands that were grabbed: the grabbed dot
     * follows the pointer and the others move by the same frequency ratio.
     * Their gains move by the same number of dB, or with [scaleGains] are
     * scaled in proportion.
     */
    private fun dragResult(ctx: DragContext, dx: Double, dy: Double, scaleGains: Boolean): Map<Int, FilterParams> {
        val output = LinkedHashMap<Int, FilterParams>()
        val g0 = ctx.start[ctx.grabbed] ?: return output
        val qFactor = 2.0.pow(-dy / Tuning.Q_POINTS_PER_OCTAVE)
        if (ctx.qMode) {
            for ((b, s) in ctx.start) {
                if (!FilterTypes.hasQ(s.filterType)) continue
                output[b] = s.copy(q = PeqLimits.clampQ(s.q * qFactor).toFloat())
            }
            return output
        }

        val startX = geometryValue.x(g0.freq.toDouble())
        val newFreq = clampToRange(geometryValue.freq(startX + dx))
        val ratio = newFreq / g0.freq
        val dbDelta = -dy * geometryValue.dbPerPoint
        val r0 = role(g0)
        var g = g0.copy(freq = newFreq.toFloat())
        when (r0.kind) {
            PeqNodeRole.Kind.GAIN -> g = g.copy(gain = PeqLimits.clampGain(g0.gain + dbDelta / r0.scale).toFloat())
            PeqNodeRole.Kind.RESONANCE -> {
                val db = r0.dbFor(g0) + dbDelta
                g = g.copy(q = PeqLimits.clampQ(10.0.pow(db / 20)).toFloat())
            }
            // Fixed: a drag moves frequency only. Q is the wheel's.
            PeqNodeRole.Kind.FIXED -> {}
            PeqNodeRole.Kind.LOCKED -> return output
        }
        output[ctx.grabbed] = g

        val gainFactor: Double? =
            if (scaleGains && r0.kind == PeqNodeRole.Kind.GAIN && abs(g0.gain) >= 0.25) g.gain.toDouble() / g0.gain else null
        // Offset by what the grabbed band's gain actually moved, after its clamp.
        val gainOffset: Double? = if (r0.kind == PeqNodeRole.Kind.GAIN) (g.gain - g0.gain).toDouble() else null
        for ((b, s) in ctx.start) {
            if (b == ctx.grabbed) continue
            val r = role(s)
            if (r.kind == PeqNodeRole.Kind.LOCKED) continue
            var p = s.copy(freq = clampMoved(s.freq.toDouble(), s.freq * ratio).toFloat())
            if (FilterTypes.hasGain(s.filterType)) {
                val gain = if (gainFactor != null) s.gain * gainFactor else s.gain + (gainOffset ?: (dbDelta / max(r.gainScale, 0.05)))
                p = p.copy(gain = PeqLimits.clampGain(gain).toFloat())
            }
            output[b] = p
        }
        return output
    }

    private fun updateMarquee() {
        val rect = PeqRect(
            min(marqueeFrom.x, marqueeTo.x),
            min(marqueeFrom.y, marqueeTo.y),
            abs(marqueeTo.x - marqueeFrom.x),
            abs(
                marqueeTo.y - marqueeFrom.y
            )
        )
        val inside = graphBands.filter { rect.contains(nodePoint(shown[it])) }
        setSelection(marqueeBase + inside)
        frequencyOrder(inside).firstOrNull()?.let { anchor = it }
        invalidate()
    }

    // ── Wheel ───────────────────────────────────────────────────────────────

    /**
     * One wheel step over the graph, in points (a mouse notch is about 8).
     * False when no band takes it, so the view can zoom or scroll instead. A
     * scroll gesture stays with the band it started on. An explicit selection
     * owns the wheel. On Android a pinch arrives here too (PORT-MAP).
     */
    fun wheel(p: PeqPoint, rawDelta: Double, mods: Int, begins: Boolean = false): Boolean {
        if (!editing) return false
        if (begins) hudWheelFieldValue = null
        val now = clock.now
        val owner = selectedChipBand
        val target: Int?
        val wb = wheelBand
        val d0 = drag
        target =
            if (gesture == GestureKind.DRAG && d0 != null) {
                d0.grabbed
            } else if (!begins &&
                wb != null &&
                isBand(wb) &&
                now - wheelTime < Tuning.WHEEL_SESSION &&
                (owner == null || wb in selectionSet)
            ) {
                wb
            } else {
                // The chip is drawn over the graph, so it wins over a band area beneath it.
                val chip = hudBandValue?.takeIf { hudVisible && hudFrame.contains(p) }
                val under = chip ?: bandAt(p)
                if (under == null) null else owner ?: under
            }
        if (target == null) {
            wheelBand = null
            return false
        }
        val b: Int = target
        wheelBand = b
        wheelTime = now
        hudWheelFieldValue = null
        val fine = (mods and PeqMods.SHIFT) != 0
        val delta = rawDelta * (if (fine) Tuning.FINE else 1.0)
        if (delta == 0.0) return true
        val bands = targetsFor(b)

        // Each wheel step is one transformation, applied to the live band and,
        // mid-drag, to the drag's starting snapshot too.
        val transform: (FilterParams) -> FilterParams? =
            if ((mods and PeqMods.CTRL) != 0) {
                { p0 ->
                    if (!FilterTypes.hasGain(p0.filterType)) null else p0.copy(gain = PeqLimits.clampGain(p0.gain + delta * 0.05).toFloat())
                }
            } else {
                // Q for every shape that has one, cuts included.
                val factor = 2.0.pow(delta / 100)
                val t: (FilterParams) -> FilterParams? = { p0 ->
                    if (!FilterTypes.hasQ(p0.filterType)) null else p0.copy(q = PeqLimits.clampQ(p0.q * factor).toFloat())
                }
                t
            }

        val changes = LinkedHashMap<Int, FilterParams>()
        for (i in bands) {
            if (i < shown.size) transform(current(i))?.let { changes[i] = it }
        }
        val ctx = drag
        if (gesture == GestureKind.DRAG && ctx != null) {
            for (i in ctx.start.keys.toList()) {
                if (changes.containsKey(i)) transform(ctx.start.getValue(i))?.let { ctx.start[i] = it }
            }
            setLive(changes)
        } else {
            setLiveThenCommit(changes)
        }
        if (hoveredBand == null) setHovered(b)
        showHud(b)
        return true
    }

    /**
     * The selected band the chip is pinned to and the wheel adjusts: the one
     * the chip already shows if it is selected, else the selection anchor,
     * else the lowest.
     */
    private val selectedChipBand: Int?
        get() {
            hudBandValue?.let { if (it in selectionSet) return it }
            anchor?.let { if (it in selectionSet) return it }
            return frequencyOrder(selectionSet.filter { it < shown.size }).firstOrNull()
        }

    val graphWheelActive get() = wheelBand != null && clock.now - wheelTime < Tuning.WHEEL_SESSION

    private val hudWheelActive get() = hudWheelFieldValue != null && hudBandValue != null && clock.now - hudWheelTime < Tuning.WHEEL_SESSION

    // ── Right-click ─────────────────────────────────────────────────────────

    /** A right press. Returns the menu to show there, or null when it only closed the card. */
    fun rightPressed(p: PeqPoint): List<PeqMenuItem>? {
        if (!editing) return null
        if (cardPoint != null) {
            closeCard()
            gesture = GestureKind.IDLE
            invalidate()
            return null
        }
        closeShapePage()
        val b = nodeAt(p)
        if (b != null) {
            if (b !in selectionSet) {
                setSelection(listOf(b))
                anchor = b
            }
            return bandMenu(b)
        }
        return graphMenu()
    }

    private fun bandMenu(b: Int): List<PeqMenuItem> {
        val bands = targetsFor(b)
        val p = current(b)
        val menu = mutableListOf(
            PeqMenuItem(
                title = if (bands.size >
                    1
                ) {
                    "${bands.size} Bands"
                } else {
                    "Band ${b + 1}"
                },
                isHeader = true,
                enabled = false
            )
        )
        // Shape and typed values are set on the chip; the menu does not repeat them.
        val so = PeqShape.of(p.filterType)
        if (so != null) {
            val (shape, order) = so
            val t1 = shape.type(1)
            val t2 = shape.type(2)
            if (t1 != null && t2 != null && t1 in available && t2 in available) {
                menu +=
                    PeqMenuItem(
                        title = if (shape == PeqShape.ALL_PASS) "Order" else "Slope",
                        submenu = listOf(1, 2).map { o ->
                            PeqMenuItem(title = shape.orderTitle(o), checked = o == order, invoke = { setShape(bands, shape, o) })
                        }
                    )
            }
        }
        menu += PeqMenuItem.SEPARATOR
        if (config.bypassSupported) menu += PeqMenuItem(title = if (p.bypass) "Enable" else "Bypass", invoke = { toggleBypass(bands) })
        if (FilterTypes.hasGain(p.filterType)) {
            menu +=
                PeqMenuItem(title = "Invert Gain", invoke = {
                    val changes = LinkedHashMap<Int, FilterParams>()
                    for (i in bands) {
                        if (!FilterTypes.hasGain(current(i).filterType)) continue
                        changes[i] = current(i).copy(gain = -current(i).gain)
                    }
                    commitNow(changes)
                })
        }
        menu += PeqMenuItem.SEPARATOR
        menu += PeqMenuItem(title = if (bands.size > 1) "Delete ${bands.size} Bands" else "Delete Band", invoke = { deleteBands(bands) })
        return menu
    }

    private fun graphMenu(): List<PeqMenuItem> {
        val menu =
            mutableListOf(
                PeqMenuItem(title = "Select All Bands", enabled = graphBands.isNotEmpty(), invoke = { setSelection(graphBands) }),
                PeqMenuItem(title = "Deselect All", enabled = selectionSet.isNotEmpty(), invoke = { setSelection(emptyList()) })
            )
        if (selectionSet.isNotEmpty()) {
            val n = selectionSet.size
            menu += PeqMenuItem.SEPARATOR
            menu +=
                PeqMenuItem(
                    title = if (n >
                        1
                    ) {
                        "Delete $n Selected Bands"
                    } else {
                        "Delete Selected Band"
                    },
                    invoke = { deleteBands(selectionSet.toList()) }
                )
        }
        return menu
    }

    // ── Keyboard ────────────────────────────────────────────────────────────

    /** A key press while the graph has focus. True when the editor used it. */
    fun keyDown(key: PeqKey, mods: Int): Boolean {
        if (!editing) return false
        when (key) {
            PeqKey.DELETE -> {
                // Pressed with the button still held: end the press or drag first.
                if (gesture == GestureKind.PRESS || gesture == GestureKind.DRAG) {
                    gesture = GestureKind.IDLE
                    drag = null
                }
                deleteBands(selectionSet.toList())
                return true
            }
            PeqKey.ESCAPE -> {
                if (cardPoint != null) {
                    closeCard()
                    invalidate()
                    return true
                }
                if (hudVisible && showsShapesValue) {
                    closeShapePage()
                    invalidate()
                    return true
                }
                setSelection(emptyList())
                hideHud()
                invalidate()
                return true
            }
            PeqKey.TAB -> {
                stepSelection(backwards = (mods and PeqMods.SHIFT) != 0)
                return true
            }
            PeqKey.LEFT, PeqKey.RIGHT, PeqKey.UP, PeqKey.DOWN -> {
                nudge(key, mods)
                return true
            }
            PeqKey.A -> {
                if (mods != PeqMods.CTRL) return false
                setSelection(graphBands)
                return true
            }
            PeqKey.OTHER -> return false
        }
    }

    private fun stepSelection(backwards: Boolean) {
        val order = frequencyOrder(graphBands)
        if (order.isEmpty()) return
        val from = anchor?.let { order.indexOf(it) } ?: -1
        val next =
            if (from >= 0) {
                (((from + (if (backwards) -1 else 1)) % order.size) + order.size) % order.size
            } else if (backwards) {
                order.size - 1
            } else {
                0
            }
        setSelection(listOf(order[next]))
        anchor = order[next]
        showHud(order[next])
    }

    private fun nudge(key: PeqKey, mods: Int) {
        val fine = (mods and PeqMods.SHIFT) != 0
        val changes = LinkedHashMap<Int, FilterParams>()
        for (b in selectionSet.filter { isBand(it) }) {
            var p = current(b)
            if (key == PeqKey.LEFT || key == PeqKey.RIGHT) {
                val octaves = (if (fine) 1.0 / 96 else 1.0 / 12) * (if (key == PeqKey.RIGHT) 1 else -1)
                p = p.copy(freq = clampMoved(p.freq.toDouble(), p.freq * 2.0.pow(octaves)).toFloat())
            } else {
                val up = key == PeqKey.UP
                if ((mods and PeqMods.ALT) != 0 || !FilterTypes.hasGain(p.filterType)) {
                    if (!FilterTypes.hasQ(p.filterType)) continue
                    p = p.copy(q = PeqLimits.clampQ(p.q * 2.0.pow((if (fine) 0.02 else 0.1) * (if (up) 1 else -1))).toFloat())
                } else {
                    p = p.copy(gain = PeqLimits.clampGain(p.gain + (if (fine) 0.1 else 0.5) * (if (up) 1 else -1)).toFloat())
                }
            }
            changes[b] = p
        }
        setLiveThenCommit(changes)
        anchor?.let { if (it in selectionSet) showHud(it) }
    }

    // ── Chip ────────────────────────────────────────────────────────────────

    /** The chip's power button. */
    fun hudBypass() {
        hudBandValue?.let { toggleBypass(targetsFor(it)) }
    }

    /** The chip's shape button: turn to the shape page, or back. */
    fun hudShapeButton() {
        if (hudBandValue == null) return
        if (showsShapesValue) {
            showsShapesValue = false
            pageSize = null
        } else {
            if (hudEditingValue) onEndHudEditRequested?.invoke()
            showsShapesValue = true
            pageSize = if (hudFrame.width > 0) PeqSize(hudFrame.width, hudFrame.height) else null
        }
        layoutHud()
        invalidate()
    }

    /** The shape page's back arrow on its first step. */
    fun hudShapePageBack() {
        closeShapePage()
        invalidate()
    }

    /** A shape and order picked on the chip's shape page. */
    fun hudPick(shape: PeqShape, order: Int) {
        val b = hudBandValue ?: return
        setShape(targetsFor(b), shape, order)
        closeShapePage()
        invalidate()
    }

    fun setHudEditing(editing: Boolean) {
        if (hudEditingValue == editing) return
        hudEditingValue = editing
        if (!editing) scheduleHudHide()
    }

    /** A scroll gesture on a chip field keeps that field. */
    fun hudScroll(field: PeqHudField, delta: Double, fine: Boolean) {
        val b = hudBandValue ?: return
        val now = clock.now
        val locked = if (hudWheelActive) (hudWheelFieldValue ?: field) else field
        hudWheelFieldValue = locked
        hudWheelTime = now
        wheelBand = null
        val d = delta * (if (fine) Tuning.FINE else 1.0)
        adjusted(current(b), locked, d * 0.5)?.let { setLiveThenCommit(mapOf(b to it)) }
    }

    /** The field a running scroll on the chip is locked to, if any. */
    val hudWheelField: PeqHudField? get() = if (hudWheelActive) hudWheelFieldValue else null

    private fun adjusted(p: FilterParams, field: PeqHudField, delta: Double): FilterParams? = when (field) {
        PeqHudField.FREQ -> p.copy(freq = clampMoved(p.freq.toDouble(), p.freq * 2.0.pow(delta / 100)).toFloat())
        PeqHudField.GAIN -> if (!FilterTypes.hasGain(
                p.filterType
            )
        ) {
            null
        } else {
            p.copy(gain = PeqLimits.clampGain(p.gain + delta * 0.1).toFloat())
        }
        PeqHudField.Q -> if (!FilterTypes.hasQ(
                p.filterType
            )
        ) {
            null
        } else {
            p.copy(q = PeqLimits.clampQ(p.q * 2.0.pow(delta / 80)).toFloat())
        }
    }

    /** A vertical drag on a chip value: points moved upward since the press, fine-scaled by the chip. */
    fun hudAdjust(field: PeqHudField, delta: Double, phase: PeqAdjustPhase) {
        val b = hudBandValue ?: return
        when (phase) {
            PeqAdjustPhase.BEGAN -> {
                commitTimer?.cancel()
                commitTimer = null
                hudAdjustStart = current(b)
            }
            PeqAdjustPhase.CHANGED -> {
                val start = hudAdjustStart ?: return
                adjusted(start, field, delta)?.let { setLive(mapOf(b to it)) }
            }
            PeqAdjustPhase.ENDED -> {
                hudAdjustStart = null
                commitLive()
                invalidate()
            }
        }
    }

    /** Typed text for a chip field. False when it did not parse, which keeps the old value (and beeps). */
    fun hudText(field: PeqHudField, text: String): Boolean {
        val b = hudBandValue ?: return false
        var p = current(b)

        fun fail(): Boolean {
            onBeep?.invoke()
            return false
        }
        when (field) {
            PeqHudField.FREQ -> {
                val f = PeqValueText.parseFrequency(text) ?: return fail()
                p = p.copy(freq = PeqLimits.clampFreq(f).toFloat())
            }
            PeqHudField.GAIN -> {
                if (!FilterTypes.hasGain(p.filterType)) return fail()
                val g = PeqValueText.parseNumber(text, "db") ?: return fail()
                p = p.copy(gain = PeqLimits.clampGain(g).toFloat())
            }
            PeqHudField.Q -> {
                if (!FilterTypes.hasQ(p.filterType)) return fail()
                val q = PeqValueText.parseNumber(text, "") ?: return fail()
                p = p.copy(q = PeqLimits.clampQ(q).toFloat())
            }
        }
        if (!p.sameAs(current(b))) commitNow(mapOf(b to p))
        return true
    }

    private fun showHud(b: Int) {
        if (!isBand(b)) return
        cancelHudHide()
        if (hudBandValue != b) closeShapePage()
        hudBandValue = b
        hudVisibleValue = true
        refreshHud()
        invalidate()
    }

    private fun refreshHud() {
        val b = hudBandValue ?: return
        if (!isBand(b)) {
            hideHud()
            return
        }
        layoutHud()
    }

    private fun layoutHud() {
        val b = hudBandValue ?: return
        if (!isBand(b)) return
        val p = current(b)
        val size = if (showsShapesValue) pageSize ?: hudSizeFor(p) else hudSizeFor(p)
        // The shape page stays where it opened.
        val o = pagedOrigin
        if (showsShapesValue && o != null) {
            hudFrame = PeqRect(o.x, o.y, size.width, size.height)
            return
        }
        pagedOrigin = null
        val frame = panelFrame(nodePoint(p), size, boost = role(p).dbFor(p) >= 0)
        hudFrame = frame
        if (showsShapesValue) pagedOrigin = PeqPoint(frame.x, frame.y)
    }

    /**
     * Where a floating panel of [size] sits beside [point]. Away from 0 dB
     * first, then beside the point; the lobe side last. The Add Band card
     * shares the rule so a new band's chip opens on the side the card was on.
     */
    fun panelFrame(point: PeqPoint, size: PeqSize, boost: Boolean): PeqRect {
        val gap = 16.0
        val area = PeqRect(0.0, 0.0, geometryValue.width, geometryValue.height).inset(4.0, 4.0)

        fun clampedX(x: Double) = min(max(x, area.minX), max(area.maxX - size.width, area.minX))

        fun clampedY(y: Double) = min(max(y, area.minY), max(area.maxY - size.height, area.minY))
        val above = PeqRect(clampedX(point.x - size.width / 2), point.y - gap - size.height, size.width, size.height)
        val below = PeqRect(clampedX(point.x - size.width / 2), point.y + gap, size.width, size.height)
        val right = PeqRect(point.x + gap, clampedY(point.y - size.height / 2), size.width, size.height)
        val left = PeqRect(point.x - gap - size.width, clampedY(point.y - size.height / 2), size.width, size.height)
        val candidates = if (boost) listOf(above, right, left, below) else listOf(below, right, left, above)
        val frame = candidates.firstOrNull { area.contains(it) } ?: candidates[0]
        return frame.copy(x = clampedX(frame.x), y = clampedY(frame.y))
    }

    /** While bands are selected the chip stays up on the selection; with none it follows the hovered dot. */
    private fun pinHudToSelection() {
        val pin = selectedChipBand
        if (pin != null) {
            if (gesture == GestureKind.MARQUEE) return
            if (!hudVisible || hudBandValue != pin) showHud(pin)
        } else {
            val h = hudBandValue
            val ptr = pointer
            if (h != null && (ptr == null || nodeAt(ptr) != h)) scheduleHudHide()
        }
    }

    private fun scheduleHudHide() {
        if (!hudVisible || hudEditingValue) return
        hudBandValue?.let { if (it in selectionSet) return }
        if (gesture == GestureKind.DRAG) return
        cancelHudHide()
        hudHideTimer =
            clock.schedule(Tuning.HUD_HIDE_DELAY) {
                hudHideTimer = null
                hideHud()
                invalidate()
            }
    }

    private fun cancelHudHide() {
        hudHideTimer?.cancel()
        hudHideTimer = null
    }

    private fun hideHud() {
        cancelHudHide()
        closeShapePage()
        if (hudEditingValue) onEndHudEditRequested?.invoke()
        hudBandValue = null
        hudVisibleValue = false
    }

    private fun closeShapePage() {
        if (!showsShapesValue) return
        showsShapesValue = false
        pageSize = null
        layoutHud()
    }

    // ── Add Band card ───────────────────────────────────────────────────────

    /** A new band of [shape] at [order] placed at a point: its frequency, and its level as gain where it has one. */
    private fun bandAt(shape: PeqShape, order: Int, p: PeqPoint): FilterParams? {
        val type = shape.type(order) ?: return null
        var band = FilterTypes.band(
            type,
            clampToRange(geometryValue.freq(p.x)).toFloat(),
            if (shape == PeqShape.BELL ||
                shape == PeqShape.NOTCH
            ) {
                1f
            } else {
                0.707f
            },
            0f
        )
        if (FilterTypes.hasGain(type)) band = band.copy(gain = PeqLimits.clampGain(bandGeometry.db(p.y)).toFloat())
        return band
    }

    /** Opens the Add Band card for a band at [p], placed as the band's chip will be. */
    fun openCard(p: PeqPoint) {
        if (freeSlot == null) {
            onBeep?.invoke()
            return
        }
        closeShapePage()
        val frame = panelFrame(p, Tuning.CARD_SIZE, boost = bandGeometry.db(p.y) >= 0)
        cardOriginValue = PeqPoint(frame.x, frame.y)
        cardPoint = p
        updateHover(p)
        invalidate()
    }

    /** A shape and order picked on the card: the band is created where the card was opened, selected, and its chip takes over. */
    fun cardPick(shape: PeqShape, order: Int) {
        val at = cardPoint ?: return
        val band = bandAt(shape, order, at)
        closeCard()
        if (band != null) createBand(band)?.let { showHud(it) }
        invalidate()
    }

    /** The card's back arrow on its first step: cancel. */
    fun cardCancel() {
        closeCard()
        invalidate()
    }

    private fun closeCard() {
        if (cardPoint == null) return
        cardPoint = null
        ghost = null
    }

    // ── Labels ──────────────────────────────────────────────────────────────

    private fun showAxisLabel(text: String, x: Double) {
        axisLabelValue = text
        axisLabelXValue = x
    }

    private fun showGainLabel(text: String, y: Double) {
        gainLabelValue = text
        gainLabelYValue = y
    }

    private fun hideAxisLabels() {
        axisLabelValue = null
        gainLabelValue = null
    }

    // ── Test hooks ──────────────────────────────────────────────────────────

    /** Where band [b]'s dot is drawn now (also used by the touch layer to find dots). */
    fun nodePointForTesting(b: Int): PeqPoint = nodePoint(shown[b])

    /** The band as drawn now (live, animating or committed). */
    fun shownForTesting(b: Int): FilterParams = shown[b]

    /** Settle every animation at once. */
    fun settleForTesting() {
        updateEmphasisTargets()
        frame(Double.POSITIVE_INFINITY)
    }

    /** Android: whether [p] is on a dot (the touch layer decides long-press menus and pinch targets). */
    fun isOnDot(p: PeqPoint): Boolean = editing && nodeAt(p) != null
}

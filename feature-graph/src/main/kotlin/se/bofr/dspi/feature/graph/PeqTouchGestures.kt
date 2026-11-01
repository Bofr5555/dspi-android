// Android only: the touch mapping of README "On-Graph Filter Editing"
// (DSPi-Console-b5 README l.572-792) onto the ported PeqGraphEditor's pointer
// API. The table is in docs/PORT-MAP.md "Touch gestures"; deviations in
// docs/UPSTREAM-DEVIATIONS.md GRAPH. Pure logic over editor calls, so it is
// JVM-tested (PeqTouchGesturesTest).
package se.bofr.dspi.feature.graph

import kotlin.math.abs
import kotlin.math.ln

/** Timings and distances of the platform's gesture detection (ViewConfiguration), in dp and ms. */
data class PeqTouchConfig(
    val touchSlop: Double = 8.0,
    val doubleTapTimeoutMs: Long = 300,
    val doubleTapSlop: Double = 32.0,
    val longPressTimeoutMs: Long = 400
)

/**
 * Turns finger events into the editor's mouse-shaped calls:
 *
 * | Touch | Editor |
 * |---|---|
 * | tap a dot / empty graph | click (select / deselect) |
 * | double-tap empty graph or a fill | double-click: add a Bell |
 * | double-tap a dot | double-click: chip opens on Freq for typing |
 * | drag a dot | drag: frequency and gain (Q for 12 dB cuts) |
 * | drag the curve | pull a new band out of it |
 * | drag empty graph | marquee selection |
 * | long-press a dot | right-click: band menu |
 * | long-press empty graph | right-click: graph menu, plus "Add Band…" (the Cmd-click card) |
 * | pinch or two-finger vertical drag over a band | wheel: Q of the band / selection |
 * | pinch over empty graph | zoom the dB range (macOS: scroll over the dB labels) |
 *
 * A move within [PeqTouchConfig.touchSlop] of the press is not forwarded, so
 * a fingertip's jitter is a tap rather than a 2-point drag.
 */
class PeqTouchGestures(private val editor: PeqGraphEditor, var config: PeqTouchConfig = PeqTouchConfig()) {
    private enum class State { IDLE, PRESSED, DRAGGING, LONG_PRESSED, DOUBLE, PINCH, PINCH_DONE }

    /** A menu to show at a point (long-press). */
    var onMenu: ((List<PeqMenuItem>, PeqPoint) -> Unit)? = null

    /** Zoom the dB range by a factor (> 1 narrows the range, i.e. zooms in). */
    var onZoom: ((Double) -> Unit)? = null

    private var state = State.IDLE
    private var downAt = PeqPoint(0.0, 0.0)
    private var downTimeMs = 0L
    private var pressId = 0
    private var lastTapAt: PeqPoint? = null
    private var lastTapTimeMs = 0L

    private var pinchSpan = 0.0
    private var pinchCentre = PeqPoint(0.0, 0.0)
    private var pinchBegins = false
    private var pinchZooms = false

    /** The current press, for a long-press timer to check it is still the same one. */
    val currentPressId get() = pressId

    /** Whether a gesture is running (the view consumes events while it is). */
    val active get() = state != State.IDLE

    /** First finger down. Returns the press id the long-press timer must pass back. */
    fun down(p: PeqPoint, timeMs: Long, mods: Int = PeqMods.NONE): Int {
        pressId++
        // A finger gets bigger targets than a mouse (the view restores the
        // mouse values on any mouse event).
        editor.hitRadius = PeqGraphEditor.Tuning.TOUCH_HIT_RADIUS
        editor.curveHitDistance = PeqGraphEditor.Tuning.TOUCH_CURVE_HIT_DISTANCE
        val last = lastTapAt
        val isDouble = last != null && timeMs - lastTapTimeMs <= config.doubleTapTimeoutMs && last.distanceTo(p) <= config.doubleTapSlop
        downAt = p
        downTimeMs = timeMs
        if (isDouble) {
            lastTapAt = null
            editor.pointerPressed(p, mods, 2)
            state = State.DOUBLE
        } else {
            editor.pointerPressed(p, mods, 1)
            state = State.PRESSED
        }
        return pressId
    }

    fun move(p: PeqPoint, mods: Int = PeqMods.NONE) {
        when (state) {
            State.PRESSED -> {
                if (p.distanceTo(downAt) < config.touchSlop) return
                state = State.DRAGGING
                lastTapAt = null
                editor.pointerDragged(p, mods)
            }
            State.DRAGGING -> editor.pointerDragged(p, mods)
            else -> {}
        }
    }

    /** Last finger up. */
    fun up(p: PeqPoint, timeMs: Long) {
        when (state) {
            State.PRESSED -> {
                // A tap: released where it was pressed, whatever the jitter.
                editor.pointerReleased(downAt)
                lastTapAt = downAt
                lastTapTimeMs = timeMs
            }
            State.DRAGGING -> editor.pointerReleased(p)
            State.DOUBLE -> editor.pointerReleased(downAt)
            else -> {}
        }
        // A finger does not hover: clear the ghost dot and readouts it leaves.
        if (state != State.IDLE) editor.pointerExited()
        state = State.IDLE
    }

    /** The long-press timer for [id] fired. */
    fun longPress(id: Int) {
        if (id != pressId || state != State.PRESSED) return
        editor.abandonPress()
        state = State.LONG_PRESSED
        lastTapAt = null
        val onDot = editor.isOnDot(downAt)
        val items = editor.rightPressed(downAt) ?: return
        val at = downAt
        val menu =
            if (onDot) {
                items
            } else {
                listOf(PeqMenuItem(title = "Add Band…", invoke = { editor.openCard(at) }), PeqMenuItem.SEPARATOR) + items
            }
        onMenu?.invoke(menu, downAt)
    }

    /** A second finger came down: [centre] and [span] of the two. */
    fun pinchStart(centre: PeqPoint, span: Double) {
        when (state) {
            State.DRAGGING, State.LONG_PRESSED -> return
            else -> {}
        }
        editor.abandonPress()
        lastTapAt = null
        state = State.PINCH
        pinchSpan = span
        pinchCentre = centre
        pinchBegins = true
        pinchZooms = false
    }

    fun pinch(centre: PeqPoint, span: Double) {
        if (state != State.PINCH || span <= 0 || pinchSpan <= 0) return
        val ratio = span / pinchSpan
        val dy = centre.y - pinchCentre.y
        // The band is looked up where the fingers were; the editor's wheel
        // session then keeps it while the fingers move off it.
        val at = pinchCentre
        pinchSpan = span
        pinchCentre = centre
        if (!pinchZooms) {
            // Spreading the fingers narrows (raises Q); a two-finger push upward
            // raises Q as a Cmd-drag does (60 points an octave).
            val delta = 100 * ln(ratio) / ln(2.0) - dy * 100 / PeqGraphEditor.Tuning.Q_POINTS_PER_OCTAVE
            // After a pause longer than a wheel session the band is looked up
            // afresh (as a new trackpad gesture is on macOS) instead of stalling.
            val begins = pinchBegins || !editor.graphWheelActive
            if (editor.wheel(at, delta, PeqMods.NONE, begins = begins)) {
                pinchBegins = false
                return
            }
            // No band under the fingers: the pinch zooms the dB range instead.
            if (!pinchBegins) return
            pinchZooms = true
        }
        pinchBegins = false
        if (abs(ratio - 1) > 1e-9) onZoom?.invoke(ratio)
    }

    /** Fewer than two fingers remain. */
    fun pinchEnd() {
        if (state == State.PINCH) state = State.PINCH_DONE
    }

    /** The system took the gesture away. */
    fun cancel() {
        if (state == State.PRESSED) editor.abandonPress()
        if (state == State.DRAGGING) editor.pointerCanceled()
        if (state != State.IDLE) editor.pointerExited()
        state = State.IDLE
    }
}

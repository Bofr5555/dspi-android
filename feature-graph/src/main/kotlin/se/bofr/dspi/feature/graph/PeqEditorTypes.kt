// Port of DSPi-Console-Windows:DSPiConsole.Core/GraphEditing/PeqEditorTypes.cs
// @ v1.1.6-beta4: the editor's value types, its host and clock interfaces, and
// the picture it hands the renderer.
package se.bofr.dspi.feature.graph

import kotlin.math.sqrt
import se.bofr.dspi.core.FilterParams

/**
 * Modifier keys as the editor reads them. macOS Command → [CTRL], Option →
 * [ALT], Shift → [SHIFT] (Windows mapping). On Android they come from a
 * hardware keyboard; touch gestures never set them (see PORT-MAP gesture table).
 */
object PeqMods {
    const val NONE = 0
    const val CTRL = 1
    const val ALT = 2
    const val SHIFT = 4
}

enum class PeqKey { OTHER, DELETE, ESCAPE, TAB, LEFT, RIGHT, UP, DOWN, A }

enum class PeqCursor { ARROW, OPEN_HAND, CLOSED_HAND, UP_DOWN }

/** macOS `PeqHUDField`. */
enum class PeqHudField { FREQ, GAIN, Q }

enum class PeqAdjustPhase { BEGAN, CHANGED, ENDED }

data class PeqPoint(val x: Double, val y: Double) {
    fun distanceTo(o: PeqPoint): Double = sqrt((x - o.x) * (x - o.x) + (y - o.y) * (y - o.y))
}

data class PeqSize(val width: Double, val height: Double)

data class PeqRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val minX get() = x
    val minY get() = y
    val maxX get() = x + width
    val maxY get() = y + height

    fun contains(p: PeqPoint) = p.x >= x && p.x <= maxX && p.y >= y && p.y <= maxY

    fun contains(r: PeqRect) = r.x >= x && r.maxX <= maxX && r.y >= y && r.maxY <= maxY

    fun inset(dx: Double, dy: Double) = PeqRect(x + dx, y + dy, width - 2 * dx, height - 2 * dy)
}

fun interface PeqTimer {
    fun cancel()
}

/** Time and one-shot timers, injected so tests can run the editor's clock by hand. */
interface PeqScheduler {
    /** Seconds, monotonic. */
    val now: Double

    fun schedule(seconds: Double, action: () -> Unit): PeqTimer
}

/** Everything the editor is told about the graph it sits on (macOS `PeqGraphEditorConfig`). */
data class PeqEditorConfig(
    /** The channel being edited (app id); null leaves the editor inert. */
    val channel: Int? = null,
    val bands: List<FilterParams> = emptyList(),
    /** Crossover bands, fixed during a PEQ edit. */
    val statics: List<FilterParams> = emptyList(),
    /**
     * The channel's level offset (gain or preamp) the graph shows: added to the
     * combined curve, and every band is drawn that much higher or lower so it
     * stays on the curve. Band values exclude it.
     */
    val offsetDb: Float = 0f,
    /** Master EQ bypass: the combined curve is flat. */
    val flat: Boolean = false,
    val curveColor: PeqRgb = PeqRgb.WHITE,
    val lineWidth: Float = 2f,
    val glow: Boolean = true,
    val minFreq: Double = 20.0,
    val maxFreq: Double = 20000.0,
    val dbTop: Double = 25.0,
    val dbBottom: Double = -25.0,
    val availableTypes: Set<Int> = emptySet(),
    val bypassSupported: Boolean = false,
    /** The frequency and level readouts shown while hovering empty graph. */
    val showFrequencyReadout: Boolean = true,
    val showLevelReadout: Boolean = true
) {
    /** Same graph and same bands, field for field (Windows `SameAs`). */
    fun sameAs(o: PeqEditorConfig) = this == o
}

/** One menu entry for the view to show; the editor runs [invoke]. */
data class PeqMenuItem(
    val title: String = "",
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val isSeparator: Boolean = false,
    /** A disabled title line (the band menu's "Band N"). */
    val isHeader: Boolean = false,
    val invoke: (() -> Unit)? = null,
    val submenu: List<PeqMenuItem>? = null
) {
    companion object {
        val SEPARATOR = PeqMenuItem(isSeparator = true)
    }
}

/** How one band's lobe and outline are drawn. The reach is in the band's own dB, before the level offset. */
data class PeqBandStyle(
    val row: Int,
    val color: PeqRgb,
    val lineOpacity: Float,
    val fillOpacity: Float,
    val reachLow: Double,
    val reachHigh: Double
)

/** One dot: a flat disc; [select] (0..1) grows a centre in the graph's background colour. */
data class PeqNode(val x: Double, val y: Double, val radius: Double, val color: PeqRgb, val opacity: Float, val select: Float)

/** What the renderer draws, rebuilt by the editor each frame. */
data class PeqPicture(
    val geometry: PeqGraphGeometry = PeqGraphGeometry(0.0, 0.0, 20.0, 20000.0, 25.0, -25.0),
    /** One entry per band row; null rows are empty slots. */
    val bands: List<FilterParams?> = emptyList(),
    val statics: List<FilterParams> = emptyList(),
    val offsetDb: Float = 0f,
    val flat: Boolean = false,
    val curveColor: PeqRgb = PeqRgb.WHITE,
    val lineWidth: Float = 2f,
    val glow: Boolean = false,
    val bandStyles: List<PeqBandStyle> = emptyList(),
    /** Back to front. */
    val nodes: List<PeqNode> = emptyList(),
    val marquee: PeqRect? = null
)

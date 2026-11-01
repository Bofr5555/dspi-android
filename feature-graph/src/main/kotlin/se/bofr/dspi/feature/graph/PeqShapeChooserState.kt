// Port of DSPi-Console-Windows:DSPiConsole.Core/GraphEditing/PeqShapeChooserState.cs
// @ v1.1.6-beta4 (the logic of macOS PeqBandHUD.swift's PeqShapeChooser).
package se.bofr.dspi.feature.graph

/**
 * The two-step shape choice behind the chip's shape page and the Add Band card:
 * a grid of shapes, then, for a shape with two orders, its two slopes (or
 * all-pass phases). The last click reports shape and order together.
 */
class PeqShapeChooserState {
    private var available: Set<Int> = emptySet()

    /** The shapes offered: those with at least one order the firmware supports. */
    var shapes: List<PeqShape> = emptyList()
        private set

    /** The shape picked in step one, awaiting its order in step two. */
    var pendingShape: PeqShape? = null
        private set

    /** The shape under the pointer, named in the header. */
    var hoveredShape: PeqShape? = null

    /** The band's own shape and order, marked in its colour; null for the card. */
    var own: Pair<PeqShape, Int>? = null

    fun configure(available: Set<Int>) {
        this.available = available
        shapes = PeqShape.ALL.filter { PeqShape.defaultOrder(it, available) != null }
        reset()
    }

    /** Back to the first step. */
    fun reset() {
        pendingShape = null
        hoveredShape = null
    }

    /** The header: the picked shape, else the one under the pointer, else the band's own, else "Add Band". */
    val title: String get() = (pendingShape ?: hoveredShape ?: own?.first)?.title ?: "Add Band"

    /** Step one. A shape with one order is chosen now (returned); one with two goes on to step two (null). */
    fun pickShape(shape: PeqShape): Pair<PeqShape, Int>? {
        val orders = PeqShape.orders(shape, available)
        if (orders.size == 2) {
            pendingShape = shape
            hoveredShape = null
            return null
        }
        return if (orders.size == 1) shape to orders[0] else null
    }

    /** Step two: the order completes the choice. */
    fun pickOrder(order: Int): Pair<PeqShape, Int>? = pendingShape?.let { it to order }

    /** The back arrow. True when it left the chooser (step one), false when it returned to the shapes. */
    fun back(): Boolean {
        if (pendingShape == null) return true
        pendingShape = null
        return false
    }

    /** Whether an order button in step two is marked: only for the band's own shape, on its own order. */
    fun isOrderMarked(order: Int): Boolean {
        val p = pendingShape ?: return false
        val o = own ?: return false
        return o.first == p && o.second == order
    }
}

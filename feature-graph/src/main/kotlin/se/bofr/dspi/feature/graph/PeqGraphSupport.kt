// Port of DSPi-Console-b5:DSPi Console/PeqGraphSupport.swift @ b5fdeac
// (PeqGraphSelection), following DSPi-Console-Windows:DSPiConsole.Core/
// GraphEditing/PeqGraphSelection.cs @ v1.1.6-beta4 for the event plumbing.
// PeqLiveReadouts (AppKit readouts in the band list) has no counterpart: the
// band list is HOME's and follows a drag through [PeqGraphSelection.live].
package se.bofr.dspi.feature.graph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.bofr.dspi.core.FilterParams

/**
 * Graph band selection and hover, shared between the response graph and the
 * band list (HOME). Listeners fire only when the set or the hovered band
 * actually changes, never per pointer movement.
 */
class PeqGraphSelection {
    private var selectedSet: Set<Int> = emptySet()
    private var graphHoveredBand: Int? = null
    private var listHoveredBand: Int? = null

    /** The row a Shift-click in the list extends from. */
    private var listAnchor: Int? = null

    private val selectionListeners = mutableListOf<() -> Unit>()
    private val hoverListeners = mutableListOf<() -> Unit>()
    private val revealListeners = mutableListOf<(Int) -> Unit>()

    private val _state = MutableStateFlow(State())

    /** Compose-friendly mirror of selection and hover. */
    val state: StateFlow<State> = _state.asStateFlow()

    data class State(val selected: Set<Int> = emptySet(), val graphHovered: Int? = null, val listHovered: Int? = null)

    private val _live = MutableStateFlow<Map<Int, FilterParams>>(emptyMap())

    /** Bands mid-drag, for the band list to show live (macOS PeqLiveReadouts). */
    val live: StateFlow<Map<Int, FilterParams>> = _live.asStateFlow()

    val selected: Set<Int> get() = selectedSet

    /** The band under the pointer on the graph; its row lights up. */
    var graphHovered: Int?
        get() = graphHoveredBand
        set(value) {
            if (graphHoveredBand == value) return
            graphHoveredBand = value
            publish()
            hoverListeners.toList().forEach { it() }
        }

    /** The row under the pointer in the list; its dot and lobe light up. */
    var listHovered: Int?
        get() = listHoveredBand
        set(value) {
            if (listHoveredBand == value) return
            listHoveredBand = value
            publish()
            hoverListeners.toList().forEach { it() }
        }

    /** The selection the list itself last made; the list follower leaves it alone. */
    var madeByList: Set<Int>? = null
        private set

    fun addSelectionListener(l: () -> Unit) {
        selectionListeners += l
    }

    fun removeSelectionListener(l: () -> Unit) {
        selectionListeners -= l
    }

    fun addHoverListener(l: () -> Unit) {
        hoverListeners += l
    }

    fun removeHoverListener(l: () -> Unit) {
        hoverListeners -= l
    }

    /** A band the pointer has rested on in the graph; the list scrolls its row into view. */
    fun addRevealListener(l: (Int) -> Unit) {
        revealListeners += l
    }

    fun removeRevealListener(l: (Int) -> Unit) {
        revealListeners -= l
    }

    fun setSelected(bands: Collection<Int>) {
        val next = bands.toSet()
        if (next == selectedSet) return
        selectedSet = next
        madeByList = null
        publish()
        selectionListeners.toList().forEach { it() }
    }

    fun requestReveal(band: Int) = revealListeners.toList().forEach { it(band) }

    fun reset() {
        setSelected(emptySet())
        graphHovered = null
        listHovered = null
        listAnchor = null
    }

    internal fun showLive(band: Int, p: FilterParams) {
        _live.value = _live.value + (band to p)
    }

    internal fun endLive() {
        if (_live.value.isNotEmpty()) _live.value = emptyMap()
    }

    /**
     * A click on a band's number in the list, with the modifiers of a click on
     * its dot: [command] toggles the band, [shift] takes the run of rows from
     * the last one clicked (row order), and a plain click selects it alone.
     * [isActive] says which rows hold a band; empty rows are skipped.
     */
    fun listClick(band: Int, command: Boolean, shift: Boolean, isActive: (Int) -> Boolean) {
        val next: Set<Int>
        val from = listAnchor?.takeIf { it in selectedSet } ?: if (selectedSet.size == 1) selectedSet.first() else null
        if (command) {
            next = if (band in selectedSet) selectedSet - band else selectedSet + band
            listAnchor = band
        } else if (shift && from != null) {
            // The anchor stays put, so a second Shift-click reshapes the run.
            next = (minOf(from, band)..maxOf(from, band)).filter(isActive).toSet()
            listAnchor = from
        } else {
            next = setOf(band)
            listAnchor = band
        }
        val changed = next != selectedSet
        selectedSet = next
        madeByList = next
        publish()
        if (changed) selectionListeners.toList().forEach { it() }
    }

    private fun publish() {
        _state.value = State(selectedSet, graphHoveredBand, listHoveredBand)
    }
}

/**
 * What the editor needs from the app (macOS `PeqGraphEditorHost`): the shared
 * selection, and somewhere to send and commit bands. [BridgePeqEditorHost] is
 * the real host; tests supply one that records.
 */
interface PeqGraphEditorHost {
    val selection: PeqGraphSelection

    /** Writes bands to the model and the device, once, mirrored onto a linked partner. */
    fun commitGraphBands(channel: Int, changes: List<Pair<Int, FilterParams>>)

    /** Drag-time device writes, without touching the model. */
    fun sendGraphBandsToDevice(channel: Int, changes: List<Pair<Int, FilterParams>>)

    fun setGraphBandBypass(channel: Int, bands: List<Int>, bypass: Boolean)

    /** A band's live value, for the band list to follow a drag. */
    fun showLive(channel: Int, band: Int, p: FilterParams)

    /** The live display is over; rows go back to the model. */
    fun endLive(channel: Int)
}

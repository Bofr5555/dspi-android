// The graph's share of macOS AppSettings (DSPi-Console-b5:DSPi Console/
// DSPi_ConsoleApp.swift l.84-200 @ b5fdeac): the Graphing and Spectrum
// preferences the response graph reads, with the macOS keys and defaults, and
// RtaChannelSelection (SpectrumAnalyserView.swift l.611).
//
// CONSOLIDATE: AppSettings (DataStore) belongs to :app / SETTINGS
// (PORT-MAP "AppState, AppSettings"). Until it exists the graph reads its
// preferences through [LocalGraphSettings], whose default keeps them in memory;
// the app (or SETTINGS) provides a persistent [GraphSettingsStore] there.
package se.bofr.dspi.feature.graph

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.bofr.dspi.core.DspiConstants

/**
 * The graph preferences. Every property name is the exact macOS `@AppStorage`
 * key (DSPi_ConsoleApp.swift l.84-167), so the F3 consolidation can map them
 * 1:1 onto the shared AppSettings store. (macOS names two of its Swift
 * properties differently from their keys: `rtaDashboardSelectionKey` is
 * stored as "rtaDashboardSource", `rtaDashboardOtherSideKey` as
 * "rtaDashboardOtherSide"; the key names are used here.)
 * "popoutGraphFollowsSelection" is not carried: GraphScreen does not follow
 * a main-window selection (UPSTREAM-DEVIATIONS GRAPH §6).
 */
data class GraphSettings(
    val showGraphGlow: Boolean = true,
    val graphLineWidth: Double = 2.0,
    val showPhase: Boolean = false,
    val phaseUnwrapped: Boolean = false,
    val showFrequencyLabels: Boolean = true,
    val showDBLabels: Boolean = true,
    val showFrequencyReadout: Boolean = true,
    val showLevelReadout: Boolean = true,
    val showFrequencyGrid: Boolean = true,
    val showDBGrid: Boolean = true,
    /** Scales every grid line; 1 is the original look, 0 hides them, 2 doubles them. */
    val graphGridOpacity: Double = 0.5,
    val graphDBRange: Double = 50.0,
    val graphDBCenter: Double = 0.0,
    /** Points (dp), 200..350 (GraphResizeHandle). */
    val graphHeight: Double = 250.0,
    val graphMinFreq: Double = 15.0,
    val graphMaxFreq: Double = 20000.0,
    /** Seconds; how long curve changes take to animate (Settings > Graphing "Animation Speed"). */
    val graphAnimationSpeed: Double = 0.2,
    // Spectrum (RTA) display
    val rtaDashboardShowGraph: Boolean = true,
    val rtaChannelShowGraph: Boolean = true,
    /** The dashboard's channels as [RtaChannelSelection.storageKey], "" for the default. */
    val rtaDashboardSource: String = "",
    /** The dashboard's other side's channels, "" when never used. */
    val rtaDashboardOtherSide: String = "",
    val rtaChannelPagesShowSpectrum: Boolean = true,
    val rtaGraphOpacity: Double = 1.0,
    val rtaFloorDB: Double = -90.0,
    val rtaCeilingDB: Double = 6.0,
    val rtaShowPeakHold: Boolean = true,
    val rtaSmoothingOn: Boolean = true
) {
    /** The smoothing amount the spectrum views use (macOS `rtaSmoothing`): 0.35 when on, 0 = off. */
    val rtaSmoothing get() = if (rtaSmoothingOn) RTA_SMOOTHING_AMOUNT else 0.0

    val dbTop get() = graphDBCenter + graphDBRange / 2.0
    val dbBottom get() = graphDBCenter - graphDBRange / 2.0

    /** Phase axis scales with the vertical zoom: ±180° at the default 50 dB range. */
    val phaseTop get() = 180.0 * graphDBRange / 50.0

    /** Whether a page draws its spectrum in the graph (macOS `rtaShows(.graph, onDashboard:)`). */
    fun rtaShowsGraph(onDashboard: Boolean) = if (onDashboard) rtaDashboardShowGraph else rtaChannelShowGraph

    fun withRtaShowsGraph(onDashboard: Boolean, on: Boolean) =
        if (onDashboard) copy(rtaDashboardShowGraph = on) else copy(rtaChannelShowGraph = on)

    /** The four SCALE settings restored by Graph Setup's Reset. */
    fun resetScale() = copy(graphMinFreq = 15.0, graphMaxFreq = 20000.0, graphDBRange = 50.0, graphDBCenter = 0.0)

    companion object {
        const val DB_RANGE_MIN = 10.0
        const val DB_RANGE_MAX = 100.0
        const val DB_CENTER_MIN = -40.0
        const val DB_CENTER_MAX = 20.0
        const val GRAPH_HEIGHT_MIN = 200.0
        const val GRAPH_HEIGHT_MAX = 350.0
        val MIN_FREQS = listOf(10.0, 15.0, 20.0, 50.0, 100.0)
        val MAX_FREQS = listOf(5000.0, 10000.0, 20000.0)
    }
}

/** Where the graph reads and writes its preferences. */
interface GraphSettingsStore {
    val settings: StateFlow<GraphSettings>

    fun update(change: (GraphSettings) -> GraphSettings)
}

/** Preferences kept for the process only (the default until AppSettings exists). */
class InMemoryGraphSettingsStore(initial: GraphSettings = GraphSettings()) : GraphSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<GraphSettings> = state.asStateFlow()

    override fun update(change: (GraphSettings) -> GraphSettings) {
        state.value = change(state.value)
    }
}

private val processGraphSettings = InMemoryGraphSettingsStore()

/** The app provides a persistent store here; the default is shared, in memory. */
val LocalGraphSettings = staticCompositionLocalOf<GraphSettingsStore> { processGraphSettings }

/**
 * The band selection shared between the graph and HOME's band list. HOME
 * provides one per channel page; without it each GraphView keeps its own.
 */
val LocalPeqGraphSelection = staticCompositionLocalOf<PeqGraphSelection?> { null }

/** Channels at one analyser tap (macOS `RtaChannelSelection`). */
class RtaChannelSelection(val tap: Int, channels: List<Int>) {
    /** Channels at [tap] (input row, or matrix output index), ascending. */
    val channels: List<Int> = channels.toSortedSet().toList()

    override fun equals(other: Any?) = other is RtaChannelSelection && other.tap == tap && other.channels == channels

    override fun hashCode() = tap * 31 + channels.hashCode()

    override fun toString() = storageKey

    val isEmpty get() = channels.isEmpty()

    val mask: Int get() = channels.fold(0) { m, c -> m or (1 shl c) }

    /** "in:0,2" or "out:1"; "out:" is a deliberate choice of nothing. */
    val storageKey: String get() = (if (tap == DspiConstants.RTA_TAP_INPUT) "in:" else "out:") + channels.joinToString(",")

    fun restricted(to: List<Int>) = RtaChannelSelection(tap, channels.filter { it in to })

    fun toggled(channel: Int) = RtaChannelSelection(tap, if (channel in channels) channels - channel else channels + channel)

    companion object {
        val NONE = RtaChannelSelection(DspiConstants.RTA_TAP_OUTPUT, emptyList())

        fun fromStorageKey(key: String): RtaChannelSelection? {
            val colon = key.indexOf(':')
            if (colon < 0) return null
            val tap =
                when (key.substring(0, colon)) {
                    "in" -> DspiConstants.RTA_TAP_INPUT
                    "out" -> DspiConstants.RTA_TAP_OUTPUT
                    else -> return null
                }
            val rest = key.substring(colon + 1)
            if (rest.isEmpty()) return RtaChannelSelection(tap, emptyList())
            val chans = rest.split(',').map { it.trim().toIntOrNull() ?: return null }
            if (chans.any { it < 0 || it >= DspiConstants.RTA_MAX_CHANNELS }) return null
            return RtaChannelSelection(tap, chans)
        }
    }
}

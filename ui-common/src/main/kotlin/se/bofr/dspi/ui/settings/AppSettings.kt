// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `AppSettings`
// (@AppStorage preferences, l.84-203) and `RtaSpectrumView`, plus the one
// view-level @AppStorage key (TubeModellerView.swift "tubeModellerAdvanced").
// Keys are the macOS UserDefaults keys, so the names stay parallel.
// The app's single preference store (decision F3): :app provides one
// persistent instance through [LocalAppSettings]; every feature reads and
// writes it there.
package se.bofr.dspi.ui.settings

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a page draws its live spectrum (macOS `RtaSpectrumView`). */
enum class RtaSpectrumView { GRAPH, BARS }

/**
 * The Console's own preferences (General/Graphing/Spectrum/Advanced, plus the
 * dashboard/spectrum placement state other pages keep here). Property names
 * are the macOS Swift property names; defaults are the macOS ones. Two Swift
 * properties are stored under other keys: [rtaDashboardSelectionKey] as
 * "rtaDashboardSource", [rtaDashboardOtherSideKey] as "rtaDashboardOtherSide".
 */
data class AppSettings(
    // Appearance
    val showGraphGlow: Boolean = true,
    // Graphing
    val graphLineWidth: Double = 2.0,
    /** Seconds; how long curve changes take to animate. */
    val graphAnimationSpeed: Double = 0.2,
    val showPhase: Boolean = false,
    val phaseUnwrapped: Boolean = false,
    // Scale & labels
    val showFrequencyLabels: Boolean = true,
    val showDBLabels: Boolean = true,
    val showFrequencyReadout: Boolean = true,
    val showLevelReadout: Boolean = true,
    val showFrequencyGrid: Boolean = true,
    val showDBGrid: Boolean = true,
    /** Scales every grid line, the 0 dB line included; 0 hides them, 2 doubles them. */
    val graphGridOpacity: Double = 0.5,
    val graphDBRange: Double = 50.0,
    val graphDBCenter: Double = 0.0,
    /** Points (dp), [GRAPH_HEIGHT_MIN]..[GRAPH_HEIGHT_MAX] (GraphResizeHandle). */
    val graphHeight: Double = 250.0,
    val graphMinFreq: Double = 15.0,
    val graphMaxFreq: Double = 20000.0,
    // Dashboard: 0 fits as many filter-list cards per row as fit, 1..3 fixes it
    val dashboardCardsPerRow: Int = 0,
    // Pop-out graph
    val popoutGraphFollowsSelection: Boolean = true,
    // Advanced
    val showDebugInfo: Boolean = false,
    // Spectrum analyser
    val rtaDashboardShowGraph: Boolean = true,
    val rtaDashboardShowBars: Boolean = false,
    val rtaChannelShowGraph: Boolean = true,
    val rtaChannelShowBars: Boolean = false,
    /** macOS key "rtaDashboardSource": the dashboard's channels ("in:0,2", "out:1"), "" for the default. */
    val rtaDashboardSelectionKey: String = "",
    /** macOS key "rtaDashboardOtherSide": the dashboard's other side's channels, "" when never used. */
    val rtaDashboardOtherSideKey: String = "",
    val rtaBarColumns: Int = 2,
    val rtaBarHeight: Double = 96.0,
    val rtaChannelPagesShowSpectrum: Boolean = true,
    val rtaGraphOpacity: Double = 1.0,
    val rtaFloorDB: Double = -90.0,
    val rtaCeilingDB: Double = 6.0,
    val rtaShowPeakHold: Boolean = true,
    val rtaSmoothingOn: Boolean = true,
    val rtaFftOrder: Int = 10,
    val rtaAvgMs: Int = 300,
    val rtaPeakDecayDBs: Int = 12,
    /** Sidebar volume slider: "auto" (host on USB / user on S/PDIF/I2S) or "master". */
    val sidebarVolumeMode: String = "auto",
    /** Settings page shown last (macOS SettingsView "settingsSelectedTab"). */
    val settingsSelectedTab: String = "general",
    /** Tube Modeller Basic/Advanced (macOS TubeModellerView "tubeModellerAdvanced"). */
    val tubeModellerAdvanced: Boolean = false
) {
    /** The smoothing amount the views use (the old Light setting when on). */
    val rtaSmoothing: Double get() = if (rtaSmoothingOn) RTA_SMOOTHING_AMOUNT else 0.0

    fun rtaShows(view: RtaSpectrumView, onDashboard: Boolean): Boolean = when (view) {
        RtaSpectrumView.GRAPH -> if (onDashboard) rtaDashboardShowGraph else rtaChannelShowGraph
        RtaSpectrumView.BARS -> if (onDashboard) rtaDashboardShowBars else rtaChannelShowBars
    }

    fun withRtaShows(view: RtaSpectrumView, onDashboard: Boolean, on: Boolean): AppSettings = when (view) {
        RtaSpectrumView.GRAPH -> if (onDashboard) copy(rtaDashboardShowGraph = on) else copy(rtaChannelShowGraph = on)
        RtaSpectrumView.BARS -> if (onDashboard) copy(rtaDashboardShowBars = on) else copy(rtaChannelShowBars = on)
    }

    /**
     * The device-side analyser options, clamped so a preference from another
     * platform/build can never become a STALLed configuration
     * (macOS `rtaOptions`). Order range = RTA_ORDER_MIN..RTA_ORDER_MAX.
     */
    fun rtaOptions(orderMin: Int = RTA_ORDER_MIN, orderMax: Int = RTA_ORDER_MAX): RtaOptions = RtaOptions(
        fftOrder = rtaFftOrder.coerceIn(orderMin, orderMax),
        avgMs = rtaAvgMs.coerceIn(0, 0xFFFF),
        peakDecayDBs = rtaPeakDecayDBs.coerceIn(0, 0xFF)
    )

    companion object {
        const val RTA_ORDER_MIN = 8
        const val RTA_ORDER_MAX = 10

        /** macOS `rtaSmoothing` when on (the old Light setting). */
        const val RTA_SMOOTHING_AMOUNT = 0.35

        // Graph Setup / GraphResizeHandle ranges (GraphView.swift, SpectrumAnalyserView.swift GraphSetupPage)
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

/** The analyser options the engine pushes to the device (macOS `RtaOptions`). */
data class RtaOptions(val fftOrder: Int, val avgMs: Int, val peakDecayDBs: Int)

/**
 * Read/write access to [AppSettings]. [settings] always holds the latest
 * value (defaults until the store has loaded).
 *
 * The app's instance is `DataStoreAppSettingsStore.of(context)`, provided to
 * every screen through [LocalAppSettings]; tests and previews use
 * [InMemoryAppSettingsStore].
 */
interface AppSettingsStore {
    val settings: StateFlow<AppSettings>

    /** Apply [transform] and persist the fields it changed. */
    fun update(transform: (AppSettings) -> AppSettings)

    companion object
}

/** Non-persistent store (previews, tests). */
class InMemoryAppSettingsStore(initial: AppSettings = AppSettings()) : AppSettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<AppSettings> = state.asStateFlow()

    override fun update(transform: (AppSettings) -> AppSettings) {
        state.value = transform(state.value)
    }
}

private val processAppSettings = InMemoryAppSettingsStore()

/**
 * The app's one [AppSettingsStore]. :app provides the persistent DataStore
 * instance; without a provider (previews, tests) a shared in-memory store.
 */
val LocalAppSettings = staticCompositionLocalOf<AppSettingsStore> { processAppSettings }

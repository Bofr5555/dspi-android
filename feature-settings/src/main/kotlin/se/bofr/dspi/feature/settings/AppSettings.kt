// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `AppSettings`
// (@AppStorage preferences) and `RtaSpectrumView`. Keys are the macOS
// UserDefaults keys, so the names stay parallel.
package se.bofr.dspi.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where a page draws its live spectrum (macOS `RtaSpectrumView`). */
enum class RtaSpectrumView { GRAPH, BARS }

/**
 * The Console's own preferences (General/Graphing/Spectrum/Advanced, plus the
 * dashboard/spectrum placement state other pages keep here). Defaults are the
 * macOS ones.
 */
data class AppSettings(
    // Appearance
    val showGraphGlow: Boolean = true,
    // Graphing
    val graphLineWidth: Double = 2.0,
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
    /** macOS key "rtaDashboardSource". */
    val rtaDashboardSelectionKey: String = "",
    /** macOS key "rtaDashboardOtherSide". */
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
    /** Settings page shown last (macOS "settingsSelectedTab"). */
    val settingsSelectedTab: String = SettingsCategory.GENERAL.id
) {
    /** The smoothing amount the views use (the old Light setting when on). */
    val rtaSmoothing: Double get() = if (rtaSmoothingOn) 0.35 else 0.0

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
    }
}

/** The analyser options the engine pushes to the device (macOS `RtaOptions`). */
data class RtaOptions(val fftOrder: Int, val avgMs: Int, val peakDecayDBs: Int)

/**
 * Read/write access to [AppSettings] — the small interface other features read
 * (the graph, dashboard and spectrum views). [settings] always holds the
 * latest value (defaults until the store has loaded).
 *
 * Get the app's instance with `AppSettingsStore.of(context)`
 * ([DataStoreAppSettingsStore]); tests and previews use
 * [InMemoryAppSettingsStore]. Consolidation note: this interface belongs in
 * :ui-common so other features can depend on it (CR to HOME/coordinator).
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

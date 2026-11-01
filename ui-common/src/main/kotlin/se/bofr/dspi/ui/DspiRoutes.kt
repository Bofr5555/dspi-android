package se.bofr.dspi.ui

/**
 * Top-level navigation destinations. The app's NavHost (:app AppScaffold)
 * maps each route to the entry composable of exactly one feature module; a
 * feature navigates elsewhere only through the `onNavigate(route)` lambda it
 * receives. Feature-internal sub-routes stay inside the feature.
 */
object DspiRoutes {
    /** :feature-home — HomeScreen (macOS ContentView sidebar), ChannelScreen, Dashboard. */
    const val HOME = "home"

    /** :feature-home — one channel's page; argument = app channel id. */
    const val CHANNEL = "channel/{ch}"

    fun channel(ch: Int) = "channel/$ch"

    /** :feature-graph — response graph / on-graph PEQ editor (full screen). */
    const val GRAPH = "graph"

    /** :feature-matrix — MatrixMixerView. */
    const val MATRIX = "matrix"

    /** :feature-tools — tool list; argument-less entry, tools are sub-routes. */
    const val TOOLS = "tools"

    /** :feature-tools — OutputLimiterView for one output; argument = output index. */
    const val LIMITER = "tools/limiter/{out}"

    fun limiter(out: Int) = "tools/limiter/$out"

    /** :feature-presets — presets, unsaved changes, import/export, AutoEQ. */
    const val PRESETS = "presets"

    /** :feature-settings — SettingsView and its tabs. */
    const val SETTINGS = "settings"

    /** :feature-analysis — signal generator, spectrum analyser, stats, monitor. */
    const val ANALYSIS = "analysis"

    /** :feature-firmware — FirmwareUpdateView. */
    const val FIRMWARE = "firmware"
}

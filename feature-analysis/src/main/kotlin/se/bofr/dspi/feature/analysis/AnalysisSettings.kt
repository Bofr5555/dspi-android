// The analyser's view of the shared AppSettings (decision F3; :ui-common
// `AppSettings`, macOS DSPi_ConsoleApp.swift @AppStorage keys and defaults).
package se.bofr.dspi.feature.analysis

import se.bofr.dspi.ui.settings.AppSettings

/** The analyser preferences of [this] (macOS `AppSettings` fields the window reads). */
fun AppSettings.rtaPreferences(): RtaPreferences = RtaPreferences(
    floorDb = rtaFloorDB,
    ceilingDb = rtaCeilingDB,
    showPeakHold = rtaShowPeakHold,
    smoothing = rtaSmoothing,
    barColumns = rtaBarColumns,
    graphMinFreq = graphMinFreq,
    graphMaxFreq = graphMaxFreq,
    fftOrder = rtaFftOrder,
    avgMs = rtaAvgMs,
    peakDecayDbs = rtaPeakDecayDBs,
    dashboardShowGraph = rtaDashboardShowGraph,
    dashboardShowBars = rtaDashboardShowBars
)

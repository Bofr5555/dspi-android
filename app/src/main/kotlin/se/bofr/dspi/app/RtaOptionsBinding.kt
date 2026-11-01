// macOS pushes `AppSettings.rtaOptions` to the analyser engine from the
// Spectrum settings tab and whenever a view starts watching
// (DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift SpectrumSettingsTab
// push, SpectrumAnalyserView.swift RtaWatch). Here it happens once, app-wide:
// at start and on every change of the settings, the analyser caps or the
// bridge (demo enter/exit). `bridge.rta` keeps the values and sends them with
// every request, so nothing is lost when a view starts watching later.
package se.bofr.dspi.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.bridge.RtaController
import se.bofr.dspi.core.RtaCapsInfo
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.RtaOptions

/** The options for [caps]: the device's order range when it reports a usable one, else the protocol's. */
fun rtaOptionsFor(s: AppSettings, caps: RtaCapsInfo?): RtaOptions =
    if (caps != null && caps.supported && caps.orderMax >= caps.orderMin) s.rtaOptions(caps.orderMin, caps.orderMax) else s.rtaOptions()

fun RtaController.apply(o: RtaOptions) {
    fftOrder = o.fftOrder
    avgMs = o.avgMs
    peakDecay = o.peakDecayDBs
}

/** Keep the current bridge's analyser options equal to the preferences. Main thread (the bridge's). */
@OptIn(ExperimentalCoroutinesApi::class)
fun bindRtaOptions(scope: CoroutineScope, settings: StateFlow<AppSettings>, bridges: StateFlow<DspiBridge>): Job = scope.launch {
    bridges
        .flatMapLatest { bridge ->
            combine(settings, bridge.rta.caps) { s, caps -> rtaOptionsFor(s, caps) }
                .distinctUntilChanged()
                .map { bridge to it }
        }.collect { (bridge, o) -> bridge.rta.apply(o) }
}

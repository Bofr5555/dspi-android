// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `SpectrumSettingsTab`.
package se.bofr.dspi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlin.math.roundToInt
import se.bofr.dspi.bridge.DspiBridge
import se.bofr.dspi.ui.settings.AppSettings
import se.bofr.dspi.ui.settings.AppSettingsStore

/**
 * The analyser preferences. Placement and drawing are the Console's own; the
 * transform size, averaging and peak decay belong to the device but are
 * forgotten at every power cycle, so the Console remembers them and pushes
 * them: :app pushes them into `bridge.rta` (which sends them with every
 * request) at start and on every change, once, app-wide.
 */
@Composable
fun SpectrumSettingsTab(bridge: DspiBridge, appSettings: AppSettingsStore) {
    val s by appSettings.settings.collectAsState()
    val caps by bridge.rta.caps.collectAsState()
    val connected = bridge.status.collectAsState().value.connected
    val supported = caps?.supported == true

    fun set(f: (AppSettings) -> AppSettings) = appSettings.update(f)

    val orders = spectrumOrders(caps?.orderMin, caps?.orderMax, supported)
    val selectedOrder = s.rtaFftOrder.coerceIn(orders.first(), orders.last())

    SettingsForm {
        item {
            SettingsSection(
                "Display",
                footer =
                "Choose which channels to show, and whether to draw them on the response graph, as bars, or both, from the gear button " +
                    "at the top right of the response graph. The analyser only runs while something is watching it, so with the spectrum " +
                    "hidden " +
                    "and the analyser window closed the device stops it and spends nothing."
            ) {
                SliderRow(
                    "Spectrum Strength: ${(s.rtaGraphOpacity * 100).toInt()}%",
                    s.rtaGraphOpacity.toFloat(),
                    0.3f..1f,
                    steps = 13,
                    detail = "How far the fill comes forward behind the curves when the spectrum is drawn on the response graph."
                ) { v -> set { it.copy(rtaGraphOpacity = (v * 20).roundToInt() / 20.0) } }
                SwitchRow("Peak Hold", "A cap above each band marking its recent maximum", s.rtaShowPeakHold) { v ->
                    set { it.copy(rtaShowPeakHold = v) }
                }
                SwitchRow(
                    "Smoothing",
                    "Glides the spectrum between device frames the way the peak meters glide between polls. It matters most with several " +
                        "channels selected, where one channel refreshes only every few hundred milliseconds and the picture would " +
                        "otherwise step.",
                    s.rtaSmoothingOn
                ) { v -> set { it.copy(rtaSmoothingOn = v) } }
            }
        }
        item {
            SettingsSection("Vertical Scale") {
                ChoiceRow("Floor", null, listOf(-60.0 to "-60 dB", -90.0 to "-90 dB", -120.0 to "-120 dB"), s.rtaFloorDB) { v ->
                    set { it.copy(rtaFloorDB = v) }
                }
                ChoiceRow("Ceiling", null, listOf(0.0 to "0 dBFS", 6.0 to "+6 dBFS", 12.0 to "+12 dBFS"), s.rtaCeilingDB) { v ->
                    set { it.copy(rtaCeilingDB = v) }
                }
                Caption(
                    "Six decibels of headroom above full scale is the default because upmix-derived rows and hot EQ can legitimately " +
                        "exceed 0 dBFS."
                )
            }
        }
        item {
            val footer = when {
                connected && caps != null && !supported ->
                    "The connected firmware has no spectrum analyser, so these settings have nothing to apply to until it is updated."
                supported ->
                    "This device reports ${caps?.dynamicRangeDb} dB of usable range and transforms up " +
                        "to ${1 shl (caps?.orderMax ?: 10)} points."
                else -> null
            }
            SettingsSection(
                "Engine",
                footer = footer,
                footerColor = if (connected &&
                    caps != null &&
                    !supported
                ) {
                    WarningOrange
                } else {
                    androidx.compose.ui.graphics.Color.Unspecified
                }
            ) {
                SegmentedRow(
                    "Transform Size",
                    "More points resolve lower frequencies, but a frame takes longer to fill, so each channel refreshes less often.",
                    orders.map { it to "${1 shl it} points" },
                    selectedOrder
                ) { v -> set { it.copy(rtaFftOrder = v) } }
                ChoiceRow(
                    "Averaging",
                    null,
                    listOf(0 to "Off", 50 to "50 ms", 125 to "125 ms", 300 to "300 ms", 1000 to "1 s", 3000 to "3 s"),
                    s.rtaAvgMs
                ) { v -> set { it.copy(rtaAvgMs = v) } }
                ChoiceRow(
                    "Peak Decay",
                    null,
                    listOf(0 to "Off", 4 to "4 dB/s", 12 to "12 dB/s", 30 to "30 dB/s"),
                    s.rtaPeakDecayDBs,
                    enabled = s.rtaShowPeakHold
                ) { v -> set { it.copy(rtaPeakDecayDBs = v) } }
            }
        }
    }
}

/**
 * Sizes the device offers: from the caps once read, else the protocol's own
 * range, so the picker never offers a size the device would STALL.
 */
internal fun spectrumOrders(orderMin: Int?, orderMax: Int?, supported: Boolean): List<Int> {
    val lo = orderMin ?: 0
    val hi = orderMax ?: -1
    return if (!supported || hi < lo) (AppSettings.RTA_ORDER_MIN..AppSettings.RTA_ORDER_MAX).toList() else (lo..hi).toList()
}

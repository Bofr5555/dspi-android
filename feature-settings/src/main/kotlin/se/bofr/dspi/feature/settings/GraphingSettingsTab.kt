// Port of DSPi-Console-b5:DSPi Console/DSPi_ConsoleApp.swift `GraphingSettingsTab`.
package se.bofr.dspi.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun GraphingSettingsTab(appSettings: AppSettingsStore) {
    val s by appSettings.settings.collectAsState()
    fun set(f: (AppSettings) -> AppSettings) = appSettings.update(f)
    SettingsForm {
        item {
            SettingsSection("Graph Appearance") {
                SwitchRow("Graph Line Glow", "Add a neon glow effect to frequency response curves", s.showGraphGlow) { v ->
                    set { it.copy(showGraphGlow = v) }
                }
                SwitchRow("Show Phase Response", "Overlay the selected channel's phase (degrees) as a dotted line", s.showPhase) { v ->
                    set { it.copy(showPhase = v) }
                }
                SwitchRow(
                    "Unwrap Phase",
                    "Show continuous phase instead of wrapping at ±180°",
                    s.phaseUnwrapped,
                    enabled = s.showPhase
                ) { v -> set { it.copy(phaseUnwrapped = v) } }
            }
        }
        item {
            SettingsSection("Response Curve") {
                SliderRow(
                    "Line Width: ${String.format(Locale.ROOT, "%.1f", s.graphLineWidth)}pt",
                    s.graphLineWidth.toFloat(),
                    1f..4f,
                    steps = 5
                ) { v -> set { it.copy(graphLineWidth = (v * 2).roundToInt() / 2.0) } }
                SliderRow(
                    "Animation Speed: ${String.format(Locale.ROOT, "%.2f", s.graphAnimationSpeed)}s",
                    s.graphAnimationSpeed.toFloat(),
                    0.1f..0.5f,
                    steps = 7
                ) { v -> set { it.copy(graphAnimationSpeed = (v * 20).roundToInt() / 20.0) } }
            }
        }
        item {
            SettingsSection("Scale & Grid") {
                SwitchRow("Show Frequency Grid", null, s.showFrequencyGrid) { v -> set { it.copy(showFrequencyGrid = v) } }
                SwitchRow("Show Frequency Labels", null, s.showFrequencyLabels) { v -> set { it.copy(showFrequencyLabels = v) } }
                SwitchRow("Show dB Grid", null, s.showDBGrid) { v -> set { it.copy(showDBGrid = v) } }
                SwitchRow("Show dB Labels", null, s.showDBLabels) { v -> set { it.copy(showDBLabels = v) } }
                SwitchRow("Show Frequency Readout", null, s.showFrequencyReadout) { v -> set { it.copy(showFrequencyReadout = v) } }
                SwitchRow("Show Gain Readout", null, s.showLevelReadout) { v -> set { it.copy(showLevelReadout = v) } }
                SliderRow(
                    "Grid Opacity: ${(s.graphGridOpacity * 100).roundToInt()}%",
                    s.graphGridOpacity.toFloat(),
                    0f..2f,
                    enabled = s.showFrequencyGrid || s.showDBGrid
                ) { v -> set { it.copy(graphGridOpacity = v.toDouble()) } }
                RowDivider()
                SliderRow("Vertical Range: ${s.graphDBRange.toInt()} dB", s.graphDBRange.toFloat(), 10f..100f) { v ->
                    set { it.copy(graphDBRange = v.roundToInt().toDouble()) }
                }
                SliderRow(centerLabel(s), s.graphDBCenter.toFloat(), -40f..20f) { v ->
                    set { it.copy(graphDBCenter = v.roundToInt().toDouble()) }
                }
                RowDivider()
                ChoiceRow(
                    "Min Frequency",
                    null,
                    listOf(10.0 to "10 Hz", 15.0 to "15 Hz", 20.0 to "20 Hz", 50.0 to "50 Hz", 100.0 to "100 Hz"),
                    s.graphMinFreq
                ) { v -> set { it.copy(graphMinFreq = v) } }
                ChoiceRow(
                    "Max Frequency",
                    null,
                    listOf(5000.0 to "5 kHz", 10000.0 to "10 kHz", 20000.0 to "20 kHz"),
                    s.graphMaxFreq
                ) { v -> set { it.copy(graphMaxFreq = v) } }
            }
        }
        item {
            // macOS "Pop-out Window": the pop-out graph is GraphScreen's full-screen view here
            SettingsSection("Pop-out Window") {
                SwitchRow("Pop-out graph follows channel selection", null, s.popoutGraphFollowsSelection) { v ->
                    set { it.copy(popoutGraphFollowsSelection = v) }
                }
            }
        }
    }
}

/** "Center: 0 dB → +25 to -25" (macOS). */
internal fun centerLabel(s: AppSettings): String {
    val top = s.graphDBCenter + s.graphDBRange / 2
    val bottom = s.graphDBCenter - s.graphDBRange / 2
    return "Center: ${s.graphDBCenter.toInt()} dB → ${String.format(
        Locale.ROOT,
        "%+.0f",
        top
    )} to ${String.format(Locale.ROOT, "%+.0f", bottom)}"
}
